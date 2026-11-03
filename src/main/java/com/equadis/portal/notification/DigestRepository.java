package com.equadis.portal.notification;

import com.equadis.portal.offer.DiscountType;
import com.equadis.portal.shared.jdbc.JdbcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads what goes into a retailer's digest.
 *
 * <p>The conditions are those of BR-10, the same as the catalogue: PUBLISHED, within the
 * validity window today, ACTIVE partnership today. So a digest can never show what the
 * retailer cannot see, even when a partnership ended between publication and sending (R6).
 */
@Repository
class DigestRepository {

    private static final String VISIBLE_NEW_OFFERS = """
            FROM offer o
            JOIN partnership p ON p.supplier_id = o.supplier_id
                              AND p.retailer_id = :retailerId
                              AND p.terminated_at IS NULL
                              AND :today BETWEEN p.start_date AND p.end_date
            JOIN supplier s ON s.id = o.supplier_id
            WHERE o.status = 'PUBLISHED'
              AND :today BETWEEN o.valid_from AND o.valid_to
              AND o.published_at > :windowFrom
              AND o.published_at <= :windowTo
            """;

    private final JdbcClient jdbc;

    DigestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return how many new offers each supplier has, largest count first */
    List<Digest.SupplierSummary> countNewOffersBySupplier(UUID retailerId, Instant windowFrom, Instant windowTo, LocalDate today) {
        return jdbc.sql("""
                        SELECT s.id AS supplier_id, s.company_name, count(*) AS new_offers
                        """ + VISIBLE_NEW_OFFERS + """
                        GROUP BY s.id, s.company_name
                        ORDER BY new_offers DESC, s.company_name
                        """)
                .params(windowParameters(retailerId, windowFrom, windowTo, today))
                .query((row, rowNumber) -> new Digest.SupplierSummary(
                        row.getObject("supplier_id", UUID.class), row.getString("company_name"), row.getInt("new_offers")))
                .list();
    }

    /** @return the most recent new offers, at most {@code maxOffers} */
    List<Digest.OfferSummary> findNewOffers(UUID retailerId, Instant windowFrom, Instant windowTo, LocalDate today, int maxOffers) {
        return jdbc.sql("""
                        SELECT o.id, o.supplier_id, s.company_name, o.reference, o.title, o.discount_type,
                               o.discount_value, o.currency, o.valid_from, o.valid_to, o.published_at
                        """ + VISIBLE_NEW_OFFERS + """
                        ORDER BY o.published_at DESC, o.id DESC
                        LIMIT :maxOffers
                        """)
                .params(windowParameters(retailerId, windowFrom, windowTo, today))
                .param("maxOffers", maxOffers)
                .query(DigestRepository::toOfferSummary)
                .list();
    }

    private static Map<String, Object> windowParameters(UUID retailerId, Instant windowFrom, Instant windowTo, LocalDate today) {
        return Map.of(
                "retailerId", retailerId,
                "today", today,
                "windowFrom", JdbcTime.toDb(windowFrom),
                "windowTo", JdbcTime.toDb(windowTo));
    }

    private static Digest.OfferSummary toOfferSummary(ResultSet row, int rowNumber) throws SQLException {
        return new Digest.OfferSummary(
                row.getObject("id", UUID.class),
                row.getObject("supplier_id", UUID.class),
                row.getString("company_name"),
                row.getString("reference"),
                row.getString("title"),
                DiscountType.valueOf(row.getString("discount_type")),
                row.getBigDecimal("discount_value"),
                row.getString("currency"),
                row.getObject("valid_from", LocalDate.class),
                row.getObject("valid_to", LocalDate.class),
                JdbcTime.instant(row, "published_at"));
    }
}
