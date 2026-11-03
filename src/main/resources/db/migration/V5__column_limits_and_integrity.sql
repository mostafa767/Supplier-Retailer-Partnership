-- =====================================================================================
-- V5 - Length limits on free-text columns, and stronger integrity.
--
-- Why limits, when TEXT and VARCHAR(n) perform the same in PostgreSQL: a limit protects the
-- database from a single oversized value, keeps values small enough for B-tree indexes, and
-- documents the column. A limit that has to grow later is a cheap change: increasing a
-- VARCHAR(n) does not rewrite the table.
--
-- Not changed here:
--   * status-like columns: their CHECK (... IN (...)) already bounds them;
--   * offer.reference, title and description: V1 already limits them with CHECK constraints.
--
-- CHAR(n) becomes VARCHAR(n): CHAR pads values with spaces, which is never what we want.
-- =====================================================================================

ALTER TABLE marketplace
    ALTER COLUMN code TYPE VARCHAR(64),
    ALTER COLUMN name TYPE VARCHAR(200);

ALTER TABLE supplier
    ALTER COLUMN company_name TYPE VARCHAR(200),
    ALTER COLUMN contact_email TYPE VARCHAR(254);  -- longest address allowed by RFC 5321

ALTER TABLE retailer
    ALTER COLUMN name TYPE VARCHAR(200),
    ALTER COLUMN website TYPE VARCHAR(2048),
    ALTER COLUMN address TYPE VARCHAR(500),
    ALTER COLUMN city TYPE VARCHAR(100),
    ALTER COLUMN contact_email TYPE VARCHAR(254),
    ALTER COLUMN webhook_url TYPE VARCHAR(2048);

ALTER TABLE offer
    ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE subscription
    ALTER COLUMN cancellation_reason TYPE VARCHAR(64);

ALTER TABLE import_job
    ALTER COLUMN file_name TYPE VARCHAR(255),
    ALTER COLUMN file_sha256 TYPE VARCHAR(64),
    ALTER COLUMN storage_key TYPE VARCHAR(255),
    ALTER COLUMN delimiter TYPE VARCHAR(1),
    ALTER COLUMN failure_reason TYPE VARCHAR(1000);

ALTER TABLE import_error
    ALTER COLUMN field TYPE VARCHAR(64),
    ALTER COLUMN error_code TYPE VARCHAR(64),
    ALTER COLUMN message TYPE VARCHAR(2000);

ALTER TABLE outbox_event
    ALTER COLUMN event_type TYPE VARCHAR(64),
    ALTER COLUMN last_error TYPE VARCHAR(2000);

ALTER TABLE retailer_notification
    ALTER COLUMN last_error TYPE VARCHAR(2000);

-- -------------------------------------------------------------------------------------
-- These columns had no foreign key, so a row could point at another marketplace.
-- -------------------------------------------------------------------------------------
ALTER TABLE subscription
    ADD CONSTRAINT fk_subscription_supplier
        FOREIGN KEY (marketplace_id, supplier_id) REFERENCES supplier (marketplace_id, id);

ALTER TABLE import_job
    ADD CONSTRAINT uq_import_job_marketplace_id UNIQUE (marketplace_id, id);

ALTER TABLE import_error
    DROP CONSTRAINT import_error_import_job_id_fkey,
    ADD CONSTRAINT fk_import_error_job
        FOREIGN KEY (marketplace_id, import_job_id) REFERENCES import_job (marketplace_id, id) ON DELETE CASCADE;

ALTER TABLE outbox_event
    ADD CONSTRAINT fk_outbox_event_marketplace FOREIGN KEY (marketplace_id) REFERENCES marketplace (id);

-- -------------------------------------------------------------------------------------
-- The error report is paged with "id > :afterId ORDER BY id". The old index on
-- (import_job_id, line_number) could not serve that order, so every page sorted all the
-- errors of the job. This index makes page 500 as fast as page 1.
-- -------------------------------------------------------------------------------------
DROP INDEX ix_import_error_by_job;
CREATE INDEX ix_import_error_by_job ON import_error (import_job_id, id);
