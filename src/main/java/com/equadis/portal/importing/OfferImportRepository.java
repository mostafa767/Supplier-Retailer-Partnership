package com.equadis.portal.importing;

import com.equadis.portal.offer.DiscountType;
import com.equadis.portal.offer.OfferReference;
import com.equadis.portal.offer.OfferStatus;
import com.equadis.portal.offer.OfferTerms;
import com.equadis.portal.shared.jdbc.JdbcTime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk reads and writes of offers for the import.
 *
 * <p>Performance: one query loads the chunk's existing offers, then one batch INSERT and one
 * batch UPDATE (JDBC batching with {@code reWriteBatchedInserts}) - about 4 round-trips per
 * 1,000 lines instead of 2,000+. JPA is deliberately not used here: it handles entities one
 * by one, which is the right tool for single edits but not for 100,000-line files.
 *
 * <p>Safety: a concurrent change (e.g. the supplier editing a draft in the UI during the
 * import) makes a guarded UPDATE touch 0 rows; the whole chunk then rolls back and is retried
 * by the job, which re-classifies the lines against the new state. A concurrent creation of
 * the same reference makes the INSERT hit the unique index, with the same outcome.
 */
@Repository
class OfferImportRepository {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcClient jdbc;

    OfferImportRepository(NamedParameterJdbcTemplate namedJdbc, JdbcClient jdbc) {
        this.namedJdbc = namedJdbc;
        this.jdbc = jdbc;
    }

    /**
     * One indexed query for the whole chunk (uses uq_offer_reference_per_supplier).
     *
     * <p>{@code lower()} runs on both sides here, in the database, so the comparison is exactly
     * the one the unique index makes. Java and PostgreSQL do not lower-case every letter the
     * same way, and a difference would make a known offer look new.
     *
     * @return the stored offers, by {@link OfferReference#key}
     */
    Map<String, StoredOffer> findByReferences(UUID supplierId, List<String> references) {
        if (references.isEmpty()) {
            return Map.of();
        }
        List<StoredOffer> offers = jdbc.sql("""
                        SELECT id, reference, status, version, title, description,
                               discount_type, discount_value, currency, valid_from, valid_to
                        FROM offer
                        WHERE supplier_id = :supplierId
                          AND lower(reference) IN (SELECT lower(candidate)
                                                   FROM unnest(CAST(:references AS text[])) AS candidate)
                        """)
                .param("supplierId", supplierId)
                .param("references", references.toArray(String[]::new))
                .query((row, rowNumber) -> new StoredOffer(
                        row.getObject("id", UUID.class),
                        row.getString("reference"),
                        OfferStatus.valueOf(row.getString("status")),
                        row.getLong("version"),
                        new OfferTerms(row.getString("title"), row.getString("description"),
                                DiscountType.valueOf(row.getString("discount_type")), row.getBigDecimal("discount_value"),
                                row.getString("currency"), row.getObject("valid_from", LocalDate.class),
                                row.getObject("valid_to", LocalDate.class))))
                .list();

        Map<String, StoredOffer> offersByKey = new HashMap<>();
        offers.forEach(offer -> offersByKey.put(OfferReference.key(offer.reference()), offer));
        return offersByKey;
    }

    void insertAll(ImportJob job, List<ParsedLine.Candidate> newOffers, Instant now) {
        if (newOffers.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] rows = newOffers.stream()
                .map(candidate -> insertParameters(job, candidate, now))
                .toArray(MapSqlParameterSource[]::new);
        namedJdbc.batchUpdate("""
                INSERT INTO offer (id, marketplace_id, supplier_id, reference, title, description, discount_type,
                                   discount_value, currency, valid_from, valid_to, status, published_at,
                                   source_import_id, version, created_at, updated_at)
                VALUES (:id, :marketplaceId, :supplierId, :reference, :title, :description, :discountType,
                        :discountValue, :currency, :validFrom, :validTo, :status, :publishedAt,
                        :importId, 0, :now, :now)
                """, rows);
    }

    /** @throws ConcurrentOfferChangeException when a draft changed since it was read */
    void updateAll(ImportJob job, List<DraftUpdate> draftUpdates, Instant now) {
        if (draftUpdates.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] rows = draftUpdates.stream()
                .map(update -> updateParameters(job, update, now))
                .toArray(MapSqlParameterSource[]::new);
        int[] updatedRowCounts = namedJdbc.batchUpdate("""
                UPDATE offer
                SET title = :title, description = :description, discount_type = :discountType,
                    discount_value = :discountValue, currency = :currency, valid_from = :validFrom, valid_to = :validTo,
                    status = :status, published_at = :publishedAt, source_import_id = :importId,
                    version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expectedVersion AND status = 'DRAFT'
                """, rows);
        for (int updatedRowCount : updatedRowCounts) {
            if (updatedRowCount == 0) {
                throw new ConcurrentOfferChangeException();
            }
        }
    }

    private static MapSqlParameterSource insertParameters(ImportJob job, ParsedLine.Candidate candidate, Instant now) {
        return termsParameters(candidate, now)
                .addValue("id", UUID.randomUUID())
                .addValue("marketplaceId", job.marketplaceId())
                .addValue("supplierId", job.supplierId())
                .addValue("reference", candidate.reference())
                .addValue("importId", job.id());
    }

    private static MapSqlParameterSource updateParameters(ImportJob job, DraftUpdate update, Instant now) {
        return termsParameters(update.candidate(), now)
                .addValue("id", update.storedOffer().id())
                .addValue("expectedVersion", update.storedOffer().version())
                .addValue("importId", job.id());
    }

    /** Explicit SQL types for nullable values, so the driver never has to guess. */
    private static MapSqlParameterSource termsParameters(ParsedLine.Candidate candidate, Instant now) {
        OfferTerms terms = candidate.terms();
        OfferStatus status = candidate.publish() ? OfferStatus.PUBLISHED : OfferStatus.DRAFT;
        return new MapSqlParameterSource()
                .addValue("title", terms.title())
                .addValue("description", terms.description(), Types.VARCHAR)
                .addValue("discountType", terms.discountType().name())
                .addValue("discountValue", terms.discountValue())
                .addValue("currency", terms.currency(), Types.VARCHAR)
                .addValue("validFrom", terms.validFrom())
                .addValue("validTo", terms.validTo())
                .addValue("status", status.name())
                .addValue("publishedAt", candidate.publish() ? JdbcTime.toDb(now) : null, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("now", JdbcTime.toDb(now), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /** A stored draft, and what the line wants it to become. */
    record DraftUpdate(StoredOffer storedOffer, ParsedLine.Candidate candidate) {
    }

    /** Signals that the chunk must be rolled back and retried against fresh data. */
    static class ConcurrentOfferChangeException extends RuntimeException {
        ConcurrentOfferChangeException() {
            super("An offer of this chunk was changed concurrently; the chunk will be retried");
        }
    }
}
