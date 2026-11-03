-- =====================================================================================
-- V7 - Claiming never takes more rows than the batch size.
--
-- "WHERE id IN (SELECT ... LIMIT n FOR UPDATE SKIP LOCKED)" (V4) can claim MORE than n rows:
-- PostgreSQL is free to run that subquery several times, and SKIP LOCKED then finds different
-- rows each time. A MATERIALIZED common table expression runs exactly once.
--
-- claim_import_job is unchanged: it compares with "=" on a LIMIT 1, which cannot do this.
-- CREATE OR REPLACE keeps the permissions granted in V4.
-- =====================================================================================

CREATE OR REPLACE FUNCTION claim_outbox_events(p_now TIMESTAMPTZ, p_lease INTERVAL, p_limit INTEGER)
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
WITH claimable AS MATERIALIZED (SELECT c.id
                                FROM outbox_event c
                                WHERE c.processed_at IS NULL
                                  AND c.failed_at IS NULL
                                  AND c.available_at <= p_now
                                  AND (c.locked_until IS NULL OR c.locked_until < p_now)
                                ORDER BY c.available_at
                                LIMIT p_limit FOR UPDATE SKIP LOCKED)
UPDATE outbox_event e
SET locked_until = p_now + p_lease,
    attempts     = e.attempts + 1
FROM claimable
WHERE e.id = claimable.id
RETURNING e.id, e.marketplace_id, e.attempts;
$$;

CREATE OR REPLACE FUNCTION claim_retailer_notifications(p_now TIMESTAMPTZ, p_lease INTERVAL, p_limit INTEGER)
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
WITH claimable AS MATERIALIZED (SELECT c.id
                                FROM retailer_notification c
                                WHERE (c.status = 'PENDING' AND c.available_at <= p_now)
                                   OR (c.status = 'SENDING' AND c.locked_until < p_now)
                                ORDER BY c.available_at
                                LIMIT p_limit FOR UPDATE SKIP LOCKED)
UPDATE retailer_notification n
SET status       = 'SENDING',
    locked_until = p_now + p_lease,
    attempts     = n.attempts + 1
FROM claimable
WHERE n.id = claimable.id
RETURNING n.id, n.marketplace_id, n.attempts;
$$;
