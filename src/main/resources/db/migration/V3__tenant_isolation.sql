-- =====================================================================================
-- V3 - Tenant isolation (R1) with PostgreSQL Row-Level Security.
--
-- Two database roles:
--   * the MIGRATION role (owner of the tables, runs Flyway). Table owners bypass RLS.
--   * portal_app - the role the application connects with. NOT the owner, so every query
--     it runs is filtered by the policies below, even a query that forgets its WHERE clause.
--
-- The application tells PostgreSQL which marketplace a connection works for by setting
-- app.marketplace_id each time a connection is taken from the pool (TenantAwareDataSource).
-- No marketplace set => current_marketplace_id() is NULL => no row matches (fail closed).
-- =====================================================================================

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'portal_app') THEN
            EXECUTE format('CREATE ROLE portal_app LOGIN PASSWORD %L', '${app_db_password}');
        ELSE
            EXECUTE format('ALTER ROLE portal_app LOGIN PASSWORD %L', '${app_db_password}');
        END IF;
    END
$$;

-- The marketplace the current connection works for, or NULL when none was set.
-- NULLIF: a pooled connection that was reset holds '' (empty string), which must also mean "none".
CREATE FUNCTION current_marketplace_id() RETURNS UUID
    LANGUAGE sql
    STABLE
AS
$$
SELECT NULLIF(current_setting('app.marketplace_id', true), '')::uuid
$$;

-- -------------------------------------------------------------------------------------
-- Policies. USING filters what is read/updated; WITH CHECK rejects writing a row that
-- belongs to another marketplace.
-- -------------------------------------------------------------------------------------
ALTER TABLE marketplace ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON marketplace
    USING (id = current_marketplace_id())
    WITH CHECK (id = current_marketplace_id());

DO
$$
    DECLARE
        tenant_table TEXT;
    BEGIN
        FOREACH tenant_table IN ARRAY ARRAY [
            'supplier', 'retailer', 'partnership', 'offer', 'subscription',
            'import_job', 'import_error', 'outbox_event',
            'retailer_notification', 'retailer_notification_cursor'
            ]
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tenant_table);
                EXECUTE format(
                        'CREATE POLICY tenant_isolation ON %I '
                            || 'USING (marketplace_id = current_marketplace_id()) '
                            || 'WITH CHECK (marketplace_id = current_marketplace_id())',
                        tenant_table);
            END LOOP;
    END
$$;

-- -------------------------------------------------------------------------------------
-- Least privilege: the application can read and write business data, but cannot change
-- the schema, and cannot DELETE anything (nothing in the PoC needs physical deletion;
-- retention purges run as a separate, dedicated job/role in production).
-- -------------------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO portal_app;
GRANT SELECT ON marketplace TO portal_app;
GRANT SELECT, INSERT, UPDATE ON
    supplier, retailer, partnership, offer, subscription,
    import_job, import_error, outbox_event,
    retailer_notification, retailer_notification_cursor
    TO portal_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO portal_app;
