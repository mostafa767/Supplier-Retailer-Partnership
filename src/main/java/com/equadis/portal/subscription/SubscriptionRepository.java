package com.equadis.portal.subscription;

import com.equadis.portal.shared.jdbc.JdbcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores subscriptions (plain SQL: {@code ON CONFLICT} makes subscribing idempotent).
 */
@Repository
class SubscriptionRepository {

    private static final String PARTNERSHIP_TERMINATED = "PARTNERSHIP_TERMINATED";

    private static final String COLUMNS = "id, offer_id, supplier_id, status, created_at, cancelled_at, cancellation_reason";

    private final JdbcClient jdbc;

    SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the new subscription's id, or empty when an active one already exists (BR-11) */
    Optional<UUID> insertIfNotActive(UUID marketplaceId, UUID retailerId, UUID supplierId, UUID offerId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO subscription (id, marketplace_id, retailer_id, supplier_id, offer_id, status, created_at)
                        VALUES (:id, :marketplaceId, :retailerId, :supplierId, :offerId, 'ACTIVE', :now)
                        ON CONFLICT (retailer_id, offer_id) WHERE status = 'ACTIVE' DO NOTHING
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("marketplaceId", marketplaceId)
                .param("retailerId", retailerId)
                .param("supplierId", supplierId)
                .param("offerId", offerId)
                .param("now", JdbcTime.toDb(now))
                .query(UUID.class)
                .optional();
    }

    Optional<SubscriptionView> findActive(UUID retailerId, UUID offerId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM subscription WHERE retailer_id = :retailerId AND offer_id = :offerId AND status = 'ACTIVE'")
                .param("retailerId", retailerId)
                .param("offerId", offerId)
                .query(SubscriptionRepository::toView)
                .optional();
    }

    List<SubscriptionView> findAllActive(UUID retailerId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM subscription WHERE retailer_id = :retailerId AND status = 'ACTIVE' ORDER BY created_at DESC")
                .param("retailerId", retailerId)
                .query(SubscriptionRepository::toView)
                .list();
    }

    /** @return how many subscriptions were cancelled */
    int cancelActive(UUID retailerId, UUID supplierId, Instant cancelledAt) {
        return jdbc.sql("""
                        UPDATE subscription
                        SET status = 'CANCELLED', cancelled_at = :cancelledAt, cancellation_reason = :reason
                        WHERE retailer_id = :retailerId AND supplier_id = :supplierId AND status = 'ACTIVE'
                        """)
                .param("cancelledAt", JdbcTime.toDb(cancelledAt))
                .param("reason", PARTNERSHIP_TERMINATED)
                .param("retailerId", retailerId)
                .param("supplierId", supplierId)
                .update();
    }

    private static SubscriptionView toView(ResultSet row, int rowNumber) throws SQLException {
        return new SubscriptionView(
                row.getObject("id", UUID.class),
                row.getObject("offer_id", UUID.class),
                row.getObject("supplier_id", UUID.class),
                SubscriptionStatus.valueOf(row.getString("status")),
                JdbcTime.instant(row, "created_at"),
                JdbcTime.instant(row, "cancelled_at"),
                row.getString("cancellation_reason"));
    }
}
