-- =====================================================================================
-- V4 - Work-claiming functions for background workers.
--
-- Workers are not acting for one marketplace: they must find the next piece of work across
-- ALL marketplaces. Instead of giving the application a role that bypasses Row-Level
-- Security, the only cross-tenant operation is this small, reviewed set of functions:
--   * SECURITY DEFINER: they run with the owner's rights (which bypass RLS),
--   * they return only (id, marketplace_id, attempts) - no business data,
--   * the worker then switches to that marketplace and does the real work under RLS.
--
-- Concurrency: FOR UPDATE SKIP LOCKED lets several application instances poll the same
-- table; each row is handed to exactly one of them. A lease (locked_until) makes the work
-- re-claimable if the worker that took it crashes.
--
-- "now" is passed by the application (p_now) so that the application clock is the single
-- source of time - and tests can control it.
-- =====================================================================================

CREATE FUNCTION claim_import_job(p_now TIMESTAMPTZ, p_lease INTERVAL)
    RETURNS TABLE
            (
                job_id             UUID,
                job_marketplace_id UUID,
                job_attempts       INTEGER
            )
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS
$$
UPDATE import_job j
SET status       = 'PROCESSING',
    locked_until = p_now + p_lease,
    attempts     = j.attempts + 1,
    started_at   = coalesce(j.started_at, p_now)
WHERE j.id = (SELECT c.id
              FROM import_job c
              WHERE (c.status = 'PENDING' OR (c.status = 'PROCESSING' AND c.locked_until < p_now))
                -- one running import per supplier (also guaranteed by a unique index)
                AND NOT EXISTS (SELECT 1
                                FROM import_job r
                                WHERE r.supplier_id = c.supplier_id
                                  AND r.status = 'PROCESSING'
                                  AND r.id <> c.id)
              ORDER BY c.created_at
              LIMIT 1 FOR UPDATE SKIP LOCKED)
RETURNING j.id, j.marketplace_id, j.attempts;
$$;

CREATE FUNCTION claim_outbox_events(p_now TIMESTAMPTZ, p_lease INTERVAL, p_limit INTEGER)
    RETURNS TABLE
            (
                event_id             UUID,
                event_marketplace_id UUID,
                event_attempts       INTEGER
            )
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS
$$
UPDATE outbox_event e
SET locked_until = p_now + p_lease,
    attempts     = e.attempts + 1
WHERE e.id IN (SELECT c.id
               FROM outbox_event c
               WHERE c.processed_at IS NULL
                 AND c.failed_at IS NULL
                 AND c.available_at <= p_now
                 AND (c.locked_until IS NULL OR c.locked_until < p_now)
               ORDER BY c.available_at
               LIMIT p_limit FOR UPDATE SKIP LOCKED)
RETURNING e.id, e.marketplace_id, e.attempts;
$$;

CREATE FUNCTION claim_retailer_notifications(p_now TIMESTAMPTZ, p_lease INTERVAL, p_limit INTEGER)
    RETURNS TABLE
            (
                notification_id             UUID,
                notification_marketplace_id UUID,
                notification_attempts       INTEGER
            )
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS
$$
UPDATE retailer_notification n
SET status       = 'SENDING',
    locked_until = p_now + p_lease,
    attempts     = n.attempts + 1
WHERE n.id IN (SELECT c.id
               FROM retailer_notification c
               WHERE (c.status = 'PENDING' AND c.available_at <= p_now)
                  OR (c.status = 'SENDING' AND c.locked_until < p_now)
               ORDER BY c.available_at
               LIMIT p_limit FOR UPDATE SKIP LOCKED)
RETURNING n.id, n.marketplace_id, n.attempts;
$$;

REVOKE ALL ON FUNCTION claim_import_job(TIMESTAMPTZ, INTERVAL) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_outbox_events(TIMESTAMPTZ, INTERVAL, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_retailer_notifications(TIMESTAMPTZ, INTERVAL, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_import_job(TIMESTAMPTZ, INTERVAL) TO portal_app;
GRANT EXECUTE ON FUNCTION claim_outbox_events(TIMESTAMPTZ, INTERVAL, INTEGER) TO portal_app;
GRANT EXECUTE ON FUNCTION claim_retailer_notifications(TIMESTAMPTZ, INTERVAL, INTEGER) TO portal_app;
