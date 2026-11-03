package com.equadis.portal.catalogue;

import com.equadis.portal.offer.DiscountType;
import com.equadis.portal.shared.jdbc.JdbcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The retailer catalogue query - the most read query of the platform.
 *
 * <p>BR-10 is the WHERE clause: an offer is visible only if PUBLISHED, within its validity
 * window today, and the retailer has an ACTIVE partnership with the supplier today. Because
 * visibility is computed on every read, terminating a partnership removes access instantly
 * (R6) - there is no cache or copy to invalidate.
 *
 * <p>Performance (target: p95 &lt; 200 ms):
 * <ul>
 *     <li>starts from the retailer's live partnerships (~15 rows, index
 *         {@code ix_partnership_retailer_live});</li>
 *     <li>for each partner, a {@code LATERAL} sub-query reads its published offers newest-first
 *         from the partial index {@code ix_offer_published_by_supplier} and <b>stops after one
 *         page</b>; the outer query merges these short lists and keeps one page. The work is
 *         bounded by (number of partners x page size), whatever the size of the suppliers'
 *         catalogues. A plain join would read every published offer of every partner, then
 *         sort them all;</li>
 *     <li>keyset pagination ({@link CatalogueCursor}), bounded page size;</li>
 *     <li>only the columns the screen needs (no {@code SELECT *}, no entity loading);</li>
 *     <li>SQL is built from <b>fixed</b> fragments; user input only ever travels as bind
 *         parameters - never concatenated (SQL injection).</li>
 * </ul>
 */
@Repository
class CatalogueRepository {

    /** One partner's visible offers. Offer filters are appended to it, then {@link #NEWEST_FIRST}. */
    private static final String PARTNER_OFFERS = """
            SELECT o.id, o.supplier_id, o.reference, o.title, o.description, o.discount_type,
                   o.discount_value, o.currency, o.valid_from, o.valid_to, o.published_at
            FROM offer o
            WHERE o.supplier_id = p.supplier_id
              AND o.status = 'PUBLISHED'
              AND :today BETWEEN o.valid_from AND o.valid_to
            """;

    /** The retailer's partnerships that are ACTIVE today. The supplier filter is appended to it. */
    private static final String LIVE_PARTNERSHIPS = """
            WHERE p.retailer_id = :retailerId
              AND p.terminated_at IS NULL
              AND :today BETWEEN p.start_date AND p.end_date
            """;

    /** Same order as the index, inside each partner and for the merged page. */
    private static final String NEWEST_FIRST = "\nORDER BY o.published_at DESC, o.id DESC LIMIT :limit";

    /**
     * BR-04 guarantees at most one live partnership per supplier and retailer, so an offer
     * never appears twice.
     */
    private static final String VISIBLE_OFFERS = """
            SELECT o.id, o.supplier_id, s.company_name, o.reference, o.title, o.description, o.discount_type,
                   o.discount_value, o.currency, o.valid_from, o.valid_to, o.published_at
            FROM partnership p
            CROSS JOIN LATERAL (%s) o
            JOIN supplier s ON s.id = o.supplier_id
            %s
            """;

    private final JdbcClient jdbc;

    CatalogueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param after where the previous page stopped, or null for the first page
     * @param limit maximum number of rows returned
     * @return the matching offers, newest first
     */
    List<CatalogueOffer> findVisibleOffers(UUID retailerId, LocalDate today, CatalogueFilter filter, CatalogueCursor after, int limit) {
        StringBuilder partnerOffers = new StringBuilder(PARTNER_OFFERS);
        StringBuilder partnerships = new StringBuilder(LIVE_PARTNERSHIPS);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("retailerId", retailerId);
        parameters.put("today", today);

        if (filter.supplierId() != null) {
            partnerships.append(" AND p.supplier_id = :supplierId");
            parameters.put("supplierId", filter.supplierId());
        }
        if (filter.publishedSince() != null) {
            partnerOffers.append(" AND o.published_at > CAST(:publishedSince AS timestamptz)");
            parameters.put("publishedSince", JdbcTime.toDb(filter.publishedSince()));
        }
        if (filter.hasSearchText()) {
            partnerOffers.append(" AND (o.title ILIKE :pattern ESCAPE '\\' OR o.reference ILIKE :pattern ESCAPE '\\')");
            parameters.put("pattern", "%" + escapeLikeWildcards(filter.searchText().strip()) + "%");
        }
        if (after != null) {
            partnerOffers.append(" AND (o.published_at, o.id) < (CAST(:afterPublishedAt AS timestamptz), CAST(:afterOfferId AS uuid))");
            parameters.put("afterPublishedAt", JdbcTime.toDb(after.publishedAt()));
            parameters.put("afterOfferId", after.offerId());
        }
        // Each partner contributes at most one page; the merged result is cut to one page again.
        partnerOffers.append(NEWEST_FIRST);
        partnerships.append(NEWEST_FIRST);
        parameters.put("limit", limit);

        String sql = VISIBLE_OFFERS.formatted(partnerOffers, partnerships);
        return jdbc.sql(sql)
                .params(parameters)
                .query(CatalogueRepository::toCatalogueOffer)
                .list();
    }

    /** "50%" typed by a user must search for "50%", not "50 followed by anything". */
    static String escapeLikeWildcards(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static CatalogueOffer toCatalogueOffer(ResultSet row, int rowNumber) throws SQLException {
        return new CatalogueOffer(
                row.getObject("id", UUID.class),
                row.getObject("supplier_id", UUID.class),
                row.getString("company_name"),
                row.getString("reference"),
                row.getString("title"),
                row.getString("description"),
                DiscountType.valueOf(row.getString("discount_type")),
                row.getBigDecimal("discount_value"),
                row.getString("currency"),
                row.getObject("valid_from", LocalDate.class),
                row.getObject("valid_to", LocalDate.class),
                JdbcTime.instant(row, "published_at"));
    }
}
