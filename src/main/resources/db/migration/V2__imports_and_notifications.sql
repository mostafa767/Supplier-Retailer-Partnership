-- =====================================================================================
-- V2 - Asynchronous work: CSV import jobs (R3), transactional outbox and retailer
--      notifications (R5).
--
-- All three tables are "work queues" processed by background workers. Workers claim rows
-- with a LEASE (locked_until): if a worker dies, the lease expires and another worker takes
-- over. Claiming is done by the functions in V4 (FOR UPDATE SKIP LOCKED).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Import job: one uploaded CSV file.
-- Idempotency (R3): the same file (same SHA-256) sent twice by the same supplier maps to
-- the SAME job - enforced by uq_import_job_file.
-- -------------------------------------------------------------------------------------
CREATE TABLE import_job
(
    id                UUID PRIMARY KEY,
    marketplace_id    UUID        NOT NULL,
    supplier_id       UUID        NOT NULL,
    file_name         TEXT        NOT NULL,
    file_sha256       CHAR(64)    NOT NULL,
    storage_key       TEXT        NOT NULL,
    delimiter         CHAR(1)     NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    total_rows        INTEGER     NOT NULL CHECK (total_rows >= 0),
    last_committed_row INTEGER    NOT NULL DEFAULT 0,   -- checkpoint: resume point after a crash
    created_count     INTEGER     NOT NULL DEFAULT 0,
    updated_count     INTEGER     NOT NULL DEFAULT 0,
    unchanged_count   INTEGER     NOT NULL DEFAULT 0,
    failed_count      INTEGER     NOT NULL DEFAULT 0,
    published_count   INTEGER     NOT NULL DEFAULT 0,
    attempts          INTEGER     NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ,
    failure_reason    TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,

    CONSTRAINT fk_import_job_supplier FOREIGN KEY (marketplace_id, supplier_id) REFERENCES supplier (marketplace_id, id),
    CONSTRAINT uq_import_job_file UNIQUE (supplier_id, file_sha256)
);

-- Pending work, oldest first.
CREATE INDEX ix_import_job_claimable ON import_job (created_at) WHERE status IN ('PENDING', 'PROCESSING');
-- At most one running import per supplier: two imports of the same supplier touching the
-- same references concurrently would only produce confusing results.
CREATE UNIQUE INDEX uq_import_job_one_running_per_supplier ON import_job (supplier_id) WHERE status = 'PROCESSING';

-- -------------------------------------------------------------------------------------
-- Import error: one row per (line, problem). Kept so that suppliers can download the
-- report later, and so support can investigate. Purged with the file after retention.
-- -------------------------------------------------------------------------------------
CREATE TABLE import_error
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_job_id  UUID        NOT NULL REFERENCES import_job (id) ON DELETE CASCADE,
    marketplace_id UUID        NOT NULL,
    line_number    INTEGER     NOT NULL,
    field          TEXT        NOT NULL,              -- '*' when the problem concerns the whole line
    error_code     TEXT        NOT NULL,              -- stable, machine-readable (for supplier systems)
    message        TEXT        NOT NULL,              -- human-readable
    raw_record     JSONB,                             -- the values as received, to help fixing the line
    created_at     TIMESTAMPTZ NOT NULL,
    -- Re-processing a chunk after a crash must not duplicate its errors.
    CONSTRAINT uq_import_error_once UNIQUE (import_job_id, line_number, field, error_code)
);

CREATE INDEX ix_import_error_by_job ON import_error (import_job_id, line_number);

-- -------------------------------------------------------------------------------------
-- Transactional outbox. A domain event is written in the SAME transaction as the business
-- change, so "offer published but nobody informed" can never happen.
-- -------------------------------------------------------------------------------------
CREATE TABLE outbox_event
(
    id             UUID PRIMARY KEY,
    marketplace_id UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    aggregate_id   UUID        NOT NULL,              -- e.g. the supplier that published offers
    payload        JSONB       NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    available_at   TIMESTAMPTZ NOT NULL,              -- retry back-off
    attempts       INTEGER     NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    processed_at   TIMESTAMPTZ,
    failed_at      TIMESTAMPTZ,
    last_error     TEXT
);

CREATE INDEX ix_outbox_event_pending ON outbox_event (available_at) WHERE processed_at IS NULL AND failed_at IS NULL;

-- -------------------------------------------------------------------------------------
-- Retailer notification: "this retailer has news on this channel".
--
-- Coalescing (R5, Black Friday): at most ONE pending notification per (retailer, channel),
-- enforced by uq_retailer_notification_pending. 500,000 published offers therefore create
-- at most (#retailers x #channels) rows - never (#offers x #retailers).
-- The content (which offers) is computed at SEND time from the retailer's cursor.
-- -------------------------------------------------------------------------------------
CREATE TABLE retailer_notification
(
    id             UUID PRIMARY KEY,
    marketplace_id UUID        NOT NULL,
    retailer_id    UUID        NOT NULL,
    channel        TEXT        NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK')),
    status         TEXT        NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'SKIPPED', 'SUPERSEDED', 'FAILED')),
    available_at   TIMESTAMPTZ NOT NULL,              -- digest window / retry back-off
    attempts       INTEGER     NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    window_from    TIMESTAMPTZ,                       -- filled when sent: offers published in ]from, to]
    window_to      TIMESTAMPTZ,
    offer_count    INTEGER,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    sent_at        TIMESTAMPTZ,

    CONSTRAINT fk_retailer_notification_retailer FOREIGN KEY (marketplace_id, retailer_id) REFERENCES retailer (marketplace_id, id)
);

CREATE UNIQUE INDEX uq_retailer_notification_pending ON retailer_notification (retailer_id, channel) WHERE status = 'PENDING';
CREATE INDEX ix_retailer_notification_due ON retailer_notification (available_at) WHERE status IN ('PENDING', 'SENDING');

-- -------------------------------------------------------------------------------------
-- Notification cursor: up to which publication moment a retailer has been informed, per
-- channel. Advanced only after a successful delivery, so a failed delivery never loses
-- offers: the next notification starts from the same cursor.
-- -------------------------------------------------------------------------------------
CREATE TABLE retailer_notification_cursor
(
    retailer_id    UUID        NOT NULL,
    channel        TEXT        NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK')),
    marketplace_id UUID        NOT NULL,
    notified_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (retailer_id, channel),
    CONSTRAINT fk_notification_cursor_retailer FOREIGN KEY (marketplace_id, retailer_id) REFERENCES retailer (marketplace_id, id)
);
