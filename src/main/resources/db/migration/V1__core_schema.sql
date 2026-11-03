-- =====================================================================================
-- V1 - Core business schema: marketplaces, suppliers, retailers, partnerships, offers,
--      subscriptions.
--
-- Design principles applied in this file:
--   * Every tenant-owned table carries marketplace_id (R1). Row-Level Security is added in V3.
--   * Business rules that can be broken by concurrent requests are enforced HERE, in the
--     database, not only in Java (unique / exclusion / check / composite foreign keys).
--   * Business calendar dates use DATE; exact moments use TIMESTAMPTZ (stored in UTC).
--   * Constraint names are explicit: the API translates them into business error codes
--     (see DatabaseConstraintTranslator), so renaming one is a breaking change.
-- =====================================================================================

-- Needed to mix "=" (uuid) and "&&" (date range overlap) in one exclusion constraint (BR-04).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- -------------------------------------------------------------------------------------
-- Marketplace: one customer of Equadis (the tenant).
-- -------------------------------------------------------------------------------------
CREATE TABLE marketplace
(
    id         UUID PRIMARY KEY,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketplace_code UNIQUE (code)
);

-- -------------------------------------------------------------------------------------
-- Supplier (brand). BR-01: company name unique per marketplace, case-insensitive.
-- -------------------------------------------------------------------------------------
CREATE TABLE supplier
(
    id             UUID PRIMARY KEY,
    marketplace_id UUID        NOT NULL REFERENCES marketplace (id),
    company_name   TEXT        NOT NULL CHECK (length(trim(company_name)) > 0),
    contact_email  TEXT,                                   -- personal data (GDPR)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Target of composite foreign keys: guarantees a child row lives in the SAME marketplace.
    CONSTRAINT uq_supplier_marketplace_id UNIQUE (marketplace_id, id)
);

CREATE UNIQUE INDEX uq_supplier_name_per_marketplace ON supplier (marketplace_id, lower(company_name));

-- -------------------------------------------------------------------------------------
-- Retailer (shop). BR-02: required location data depends on the store type.
-- -------------------------------------------------------------------------------------
CREATE TABLE retailer
(
    id             UUID PRIMARY KEY,
    marketplace_id UUID        NOT NULL REFERENCES marketplace (id),
    name           TEXT        NOT NULL CHECK (length(trim(name)) > 0),
    store_type     TEXT        NOT NULL CHECK (store_type IN ('ONLINE', 'PHYSICAL', 'HYBRID')),
    website        TEXT,
    address        TEXT,
    city           TEXT,
    contact_email  TEXT,                                   -- personal data (GDPR); email digest channel
    webhook_url    TEXT,                                   -- machine-to-machine channel (large retailers)
    webhook_secret TEXT,                                   -- HMAC key; encrypted at rest in production
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_retailer_marketplace_id UNIQUE (marketplace_id, id),
    CONSTRAINT ck_retailer_location_matches_store_type CHECK (
        (store_type = 'ONLINE' AND website IS NOT NULL)
            OR (store_type = 'PHYSICAL' AND address IS NOT NULL AND city IS NOT NULL)
            OR (store_type = 'HYBRID' AND website IS NOT NULL AND address IS NOT NULL AND city IS NOT NULL)
        ),
    CONSTRAINT ck_retailer_webhook_has_secret CHECK (webhook_url IS NULL OR webhook_secret IS NOT NULL)
);

-- -------------------------------------------------------------------------------------
-- Partnership between one supplier and one retailer, for a period of calendar dates.
--
-- Status (SCHEDULED / ACTIVE / EXPIRED / TERMINATED) is NOT stored: it is derived from the
-- dates and terminated_at for "today" (BR-05). A stored status would need a nightly job and
-- would be wrong whenever that job is late or fails.
-- Both start_date and end_date are inclusive.
-- -------------------------------------------------------------------------------------
CREATE TABLE partnership
(
    id              UUID PRIMARY KEY,
    marketplace_id  UUID        NOT NULL,
    supplier_id     UUID        NOT NULL,
    retailer_id     UUID        NOT NULL,
    start_date      DATE        NOT NULL,
    end_date        DATE        NOT NULL,
    terminated_at   TIMESTAMPTZ,
    renewed_from_id UUID REFERENCES partnership (id),
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Supplier and retailer must belong to the partnership's marketplace (R1).
    CONSTRAINT fk_partnership_supplier FOREIGN KEY (marketplace_id, supplier_id) REFERENCES supplier (marketplace_id, id),
    CONSTRAINT fk_partnership_retailer FOREIGN KEY (marketplace_id, retailer_id) REFERENCES retailer (marketplace_id, id),

    -- BR-03: end after start, at most 36 months. Same month arithmetic as Java's plusMonths(36).
    CONSTRAINT ck_partnership_end_after_start CHECK (end_date > start_date),
    CONSTRAINT ck_partnership_max_36_months CHECK (end_date <= (start_date + INTERVAL '36 months')::date),

    -- BR-04: no two overlapping (non-terminated) partnerships for the same pair.
    -- Enforced by the database so that two concurrent requests cannot both succeed.
    CONSTRAINT ex_partnership_no_overlap EXCLUDE USING gist (
        supplier_id WITH =,
        retailer_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&
        ) WHERE (terminated_at IS NULL)
);

-- Catalogue / fan-out access path: "active partners of this retailer" and "partners of this supplier".
CREATE INDEX ix_partnership_retailer_live ON partnership (retailer_id, supplier_id, start_date, end_date) WHERE terminated_at IS NULL;
CREATE INDEX ix_partnership_supplier_live ON partnership (supplier_id, start_date, end_date) WHERE terminated_at IS NULL;

-- -------------------------------------------------------------------------------------
-- Offer published by a supplier.
-- BR-08: DRAFT -> PUBLISHED -> CLOSED; reference unique per supplier (case-insensitive).
-- BR-09: percentage in ]0, 100]; fixed amount > 0 with a currency; valid_to >= valid_from.
-- -------------------------------------------------------------------------------------
CREATE TABLE offer
(
    id               UUID PRIMARY KEY,
    marketplace_id   UUID          NOT NULL,
    supplier_id      UUID          NOT NULL,
    reference        TEXT          NOT NULL CHECK (length(reference) BETWEEN 1 AND 64),
    title            TEXT          NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    description      TEXT CHECK (length(description) <= 2000),
    discount_type    TEXT          NOT NULL CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    discount_value   NUMERIC(12, 2) NOT NULL,
    currency         CHAR(3),
    valid_from       DATE          NOT NULL,
    valid_to         DATE          NOT NULL,
    status           TEXT          NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    published_at     TIMESTAMPTZ,
    closed_at        TIMESTAMPTZ,
    source_import_id UUID,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_offer_supplier FOREIGN KEY (marketplace_id, supplier_id) REFERENCES supplier (marketplace_id, id),
    CONSTRAINT uq_offer_marketplace_id UNIQUE (marketplace_id, id),
    CONSTRAINT ck_offer_validity_window CHECK (valid_to >= valid_from),
    CONSTRAINT ck_offer_discount CHECK (
        (discount_type = 'PERCENTAGE' AND discount_value > 0 AND discount_value <= 100 AND currency IS NULL)
            OR (discount_type = 'FIXED_AMOUNT' AND discount_value > 0 AND currency IS NOT NULL)
        ),
    CONSTRAINT ck_offer_status_timestamps CHECK (
        (status = 'DRAFT' AND published_at IS NULL AND closed_at IS NULL)
            OR (status = 'PUBLISHED' AND published_at IS NOT NULL AND closed_at IS NULL)
            OR (status = 'CLOSED' AND closed_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_offer_reference_per_supplier ON offer (supplier_id, lower(reference));

-- Catalogue (R4) and digest (R5) access path. Partial index: drafts and closed offers are
-- never read by retailers, so they are left out and the index stays small.
CREATE INDEX ix_offer_published_by_supplier ON offer (supplier_id, published_at DESC, id DESC) WHERE status = 'PUBLISHED';

-- -------------------------------------------------------------------------------------
-- Subscription of a retailer to an offer.
-- BR-11: at most one ACTIVE subscription per (retailer, offer) - partial unique index.
-- supplier_id is denormalised so BR-07 (cancel on termination) is one indexed UPDATE.
-- -------------------------------------------------------------------------------------
CREATE TABLE subscription
(
    id                  UUID PRIMARY KEY,
    marketplace_id      UUID        NOT NULL,
    retailer_id         UUID        NOT NULL,
    supplier_id         UUID        NOT NULL,
    offer_id            UUID        NOT NULL,
    status              TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    cancellation_reason TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    cancelled_at        TIMESTAMPTZ,

    CONSTRAINT fk_subscription_retailer FOREIGN KEY (marketplace_id, retailer_id) REFERENCES retailer (marketplace_id, id),
    CONSTRAINT fk_subscription_offer FOREIGN KEY (marketplace_id, offer_id) REFERENCES offer (marketplace_id, id),
    CONSTRAINT ck_subscription_cancellation CHECK ((status = 'ACTIVE') = (cancelled_at IS NULL))
);

CREATE UNIQUE INDEX uq_subscription_active ON subscription (retailer_id, offer_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_subscription_active_by_pair ON subscription (retailer_id, supplier_id) WHERE status = 'ACTIVE';
