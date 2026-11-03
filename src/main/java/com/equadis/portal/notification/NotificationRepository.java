package com.equadis.portal.notification;

import com.equadis.portal.shared.jdbc.JdbcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores retailer notifications and their cursors. Claiming them across marketplaces is done
 * by {@link com.equadis.portal.shared.work.WorkClaimRepository}.
 */
@Repository
class NotificationRepository {

    /**
     * The status of a notification that goes back in the queue. The partial unique index allows
     * one PENDING row per retailer and channel, so when a newer one exists, this one is
     * SUPERSEDED: the newer one starts from the same cursor, so it carries these offers too.
     */
    private static final String PENDING_UNLESS_SUPERSEDED = """
            CASE
                WHEN EXISTS (SELECT 1 FROM retailer_notification other
                             WHERE other.retailer_id = n.retailer_id AND other.channel = n.channel
                               AND other.status = 'PENDING' AND other.id <> n.id) THEN 'SUPERSEDED'
                ELSE 'PENDING'
            END
            """;

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One set-based SQL statement, no Java loop: ~900 partner retailers are handled in a single
     * round-trip. {@code ON CONFLICT ... DO NOTHING} on the partial unique index
     * {@code uq_retailer_notification_pending} is the <b>coalescing</b>: if the retailer already
     * has a pending notification on that channel, nothing new is created. A supplier publishing
     * 100 offers one second apart therefore produces one notification per retailer, not 100.
     *
     * <p>Idempotent: replaying the same event (at-least-once delivery) creates nothing new.
     *
     * @return number of rows created
     */
    int insertPendingForActivePartners(UUID supplierId, LocalDate today, Instant now, Instant emailDueAt, Instant webhookDueAt) {
        return jdbc.sql("""
                        INSERT INTO retailer_notification (id, marketplace_id, retailer_id, channel, status, available_at, created_at)
                        SELECT gen_random_uuid(), r.marketplace_id, r.id, channel.name, 'PENDING',
                               CASE channel.name WHEN 'EMAIL' THEN CAST(:emailDueAt AS timestamptz) ELSE CAST(:webhookDueAt AS timestamptz) END,
                               CAST(:now AS timestamptz)
                        FROM partnership p
                        JOIN retailer r ON r.id = p.retailer_id
                        CROSS JOIN LATERAL (VALUES ('EMAIL', r.contact_email IS NOT NULL),
                                                   ('WEBHOOK', r.webhook_url IS NOT NULL)) AS channel(name, enabled)
                        WHERE p.supplier_id = :supplierId
                          AND p.terminated_at IS NULL
                          AND :today BETWEEN p.start_date AND p.end_date
                          AND channel.enabled
                        ON CONFLICT (retailer_id, channel) WHERE status = 'PENDING' DO NOTHING
                        """)
                .param("supplierId", supplierId)
                .param("today", today)
                .param("now", JdbcTime.toDb(now))
                .param("emailDueAt", JdbcTime.toDb(emailDueAt))
                .param("webhookDueAt", JdbcTime.toDb(webhookDueAt))
                .update();
    }

    /** @return the notification if it is still being sent, or empty if another worker finished it */
    Optional<NotificationInProgress> findInProgress(UUID notificationId) {
        return jdbc.sql("""
                        SELECT n.id, n.retailer_id, n.channel,
                               coalesce(c.notified_until, r.created_at) AS window_from
                        FROM retailer_notification n
                        JOIN retailer r ON r.id = n.retailer_id
                        LEFT JOIN retailer_notification_cursor c ON c.retailer_id = n.retailer_id AND c.channel = n.channel
                        WHERE n.id = :id AND n.status = 'SENDING'
                        """)
                .param("id", notificationId)
                .query((row, rowNumber) -> new NotificationInProgress(
                        row.getObject("id", UUID.class),
                        row.getObject("retailer_id", UUID.class),
                        Channel.valueOf(row.getString("channel")),
                        JdbcTime.instant(row, "window_from")))
                .optional();
    }

    void markFinished(UUID notificationId, DeliveryOutcome outcome, Digest digest, Instant now) {
        jdbc.sql("""
                        UPDATE retailer_notification
                        SET status = :outcome, locked_until = NULL, last_error = NULL, sent_at = :now,
                            window_from = :windowFrom, window_to = :windowTo, offer_count = :offerCount
                        WHERE id = :id AND status = 'SENDING'
                        """)
                .param("outcome", outcome.name())
                .param("now", JdbcTime.toDb(now))
                .param("windowFrom", JdbcTime.toDb(digest.windowFrom()))
                .param("windowTo", JdbcTime.toDb(digest.windowTo()))
                .param("offerCount", digest.totalOffers())
                .param("id", notificationId)
                .update();
    }

    /** Moves the retailer's cursor forward. {@code GREATEST}: a cursor never moves back. */
    void advanceCursor(UUID retailerId, Channel channel, Instant notifiedUntil) {
        jdbc.sql("""
                        INSERT INTO retailer_notification_cursor (retailer_id, channel, marketplace_id, notified_until)
                        SELECT r.id, CAST(:channel AS text), r.marketplace_id, CAST(:notifiedUntil AS timestamptz)
                        FROM retailer r WHERE r.id = :retailerId
                        ON CONFLICT (retailer_id, channel)
                        DO UPDATE SET notified_until = GREATEST(retailer_notification_cursor.notified_until, EXCLUDED.notified_until)
                        """)
                .param("channel", channel.name())
                .param("notifiedUntil", JdbcTime.toDb(notifiedUntil))
                .param("retailerId", retailerId)
                .update();
    }

    /**
     * Records a failed attempt.
     *
     * @param giveUp  true when no attempt is left: the notification becomes FAILED
     * @param retryAt when the next attempt may start
     */
    void markAttemptFailed(UUID notificationId, boolean giveUp, String error, Instant retryAt) {
        jdbc.sql("""
                        UPDATE retailer_notification n
                        SET locked_until = NULL,
                            last_error = :error,
                            available_at = :retryAt,
                            status = CASE WHEN :giveUp THEN 'FAILED' ELSE %s END
                        WHERE n.id = :id AND n.status = 'SENDING'
                        """.formatted(PENDING_UNLESS_SUPERSEDED))
                .param("error", error)
                .param("retryAt", JdbcTime.toDb(retryAt))
                .param("giveUp", giveUp)
                .param("id", notificationId)
                .update();
    }

    /** Puts back a notification that was claimed but not attempted, and takes back its attempt. */
    void release(UUID notificationId) {
        jdbc.sql("""
                        UPDATE retailer_notification n
                        SET locked_until = NULL,
                            attempts = n.attempts - 1,
                            status = %s
                        WHERE n.id = :id AND n.status = 'SENDING'
                        """.formatted(PENDING_UNLESS_SUPERSEDED))
                .param("id", notificationId)
                .update();
    }

    /** @param windowFrom the digest starts after this moment: the cursor, or the retailer's creation */
    record NotificationInProgress(UUID id, UUID retailerId, Channel channel, Instant windowFrom) {
    }
}
