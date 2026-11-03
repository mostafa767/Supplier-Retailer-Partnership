package com.equadis.portal.outbox;

import com.equadis.portal.shared.jdbc.JdbcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores outbox events. Claiming them across marketplaces is done by
 * {@link com.equadis.portal.shared.work.WorkClaimRepository}.
 */
@Repository
class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID eventId, UUID marketplaceId, String eventType, UUID aggregateId, String payloadJson, Instant occurredAt) {
        jdbc.sql("""
                        INSERT INTO outbox_event (id, marketplace_id, event_type, aggregate_id, payload, occurred_at, available_at)
                        VALUES (:id, :marketplaceId, :eventType, :aggregateId, CAST(:payload AS jsonb), :occurredAt, :occurredAt)
                        """)
                .param("id", eventId)
                .param("marketplaceId", marketplaceId)
                .param("eventType", eventType)
                .param("aggregateId", aggregateId)
                .param("payload", payloadJson)
                .param("occurredAt", JdbcTime.toDb(occurredAt))
                .update();
    }

    /** @return the event, or empty when it was already processed or given up on */
    Optional<OutboxEvent> findUnprocessed(UUID eventId) {
        return jdbc.sql("""
                        SELECT id, marketplace_id, event_type, aggregate_id, payload::text AS payload, occurred_at
                        FROM outbox_event WHERE id = :id AND processed_at IS NULL AND failed_at IS NULL
                        """)
                .param("id", eventId)
                .query((row, rowNumber) -> new OutboxEvent(
                        row.getObject("id", UUID.class),
                        row.getObject("marketplace_id", UUID.class),
                        row.getString("event_type"),
                        row.getObject("aggregate_id", UUID.class),
                        row.getString("payload"),
                        JdbcTime.instant(row, "occurred_at")))
                .optional();
    }

    void markProcessed(UUID eventId, Instant now) {
        jdbc.sql("UPDATE outbox_event SET processed_at = :now, locked_until = NULL, last_error = NULL WHERE id = :id")
                .param("now", JdbcTime.toDb(now))
                .param("id", eventId)
                .update();
    }

    /**
     * Records a failed attempt.
     *
     * @param giveUp  true when no attempt is left: the event will not be retried
     * @param retryAt when the next attempt may start
     */
    void markAttemptFailed(UUID eventId, boolean giveUp, String error, Instant retryAt, Instant now) {
        jdbc.sql("""
                        UPDATE outbox_event
                        SET locked_until = NULL,
                            last_error = :error,
                            available_at = :retryAt,
                            failed_at = CASE WHEN :giveUp THEN CAST(:now AS timestamptz) ELSE NULL END
                        WHERE id = :id
                        """)
                .param("error", error)
                .param("retryAt", JdbcTime.toDb(retryAt))
                .param("giveUp", giveUp)
                .param("now", JdbcTime.toDb(now))
                .param("id", eventId)
                .update();
    }
}
