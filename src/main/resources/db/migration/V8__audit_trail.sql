-- =====================================================================================
-- V8 - Audit trail (R7: "we must be able to tell who changed what and when", ISO 27001).
--
-- One row per change, written by the application in the SAME transaction as the change
-- itself: a change cannot be committed without its audit row, and a rolled-back change
-- leaves none.
--
-- Append-only: the application role may INSERT and SELECT, never UPDATE or DELETE, so the
-- history cannot be rewritten through the application, even by a bug or a SQL injection.
-- Retention and archiving run as a separate, dedicated role in production.
-- =====================================================================================

CREATE TABLE audit_event
(
    id             UUID PRIMARY KEY,
    marketplace_id UUID        NOT NULL REFERENCES marketplace (id),
    occurred_at    TIMESTAMPTZ NOT NULL,
    actor_role     VARCHAR(32) NOT NULL,              -- who: SUPPLIER, RETAILER, MARKETPLACE_ADMIN
    actor_id       UUID        NOT NULL,              -- who: the supplier, retailer or administrator id
    action         VARCHAR(64) NOT NULL,              -- what: e.g. OFFER_PUBLISHED
    entity_type    VARCHAR(32) NOT NULL,              -- on what: OFFER, PARTNERSHIP...
    entity_id      UUID        NOT NULL,
    details        JSONB,                             -- e.g. the values before and after an edit
    correlation_id VARCHAR(64)                        -- links the change to the request's log lines
);

-- "Show me the history of this offer", newest first.
CREATE INDEX ix_audit_event_by_entity ON audit_event (entity_id, occurred_at DESC);

ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_event
    USING (marketplace_id = current_marketplace_id())
    WITH CHECK (marketplace_id = current_marketplace_id());

GRANT SELECT, INSERT ON audit_event TO portal_app;
