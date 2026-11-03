package com.equadis.portal.importing;

import com.equadis.portal.shared.jdbc.JdbcTime;
import com.equadis.portal.shared.validation.Violation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The line-by-line error report of an import (R3: "suppliers need to know exactly which lines
 * failed and why").
 *
 * <p>Rejected lines are by nature bad data, so everything they contribute is made safe here,
 * in the one place that writes them: a line that cannot be stored must never fail the import.
 */
@Repository
class ImportErrorRepository {

    /** Enough to recognise the value. Without a limit, a 50 MB cell would be stored per problem. */
    static final int MAX_STORED_VALUE_LENGTH = 500;
    static final int MAX_MESSAGE_LENGTH = 1000;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    ImportErrorRepository(NamedParameterJdbcTemplate namedJdbc, JdbcClient jdbc, ObjectMapper objectMapper) {
        this.namedJdbc = namedJdbc;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * One batch insert for the whole chunk. {@code ON CONFLICT DO NOTHING}: when a chunk is
     * re-processed after a crash, its errors are not duplicated.
     */
    void insertAll(ImportJob job, List<ParsedLine> rejectedLines, Instant now) {
        List<MapSqlParameterSource> rows = new ArrayList<>();
        for (ParsedLine line : rejectedLines) {
            String rawRecord = toJson(storableValues(line.rawValues()));
            for (Violation violation : line.violations()) {
                rows.add(new MapSqlParameterSource()
                        .addValue("jobId", job.id())
                        .addValue("marketplaceId", job.marketplaceId())
                        .addValue("lineNumber", line.lineNumber())
                        .addValue("field", violation.field())
                        .addValue("code", violation.code())
                        .addValue("message", storableText(violation.message(), MAX_MESSAGE_LENGTH))
                        .addValue("rawRecord", rawRecord, Types.VARCHAR)
                        .addValue("now", JdbcTime.toDb(now), Types.TIMESTAMP_WITH_TIMEZONE));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        namedJdbc.batchUpdate("""
                INSERT INTO import_error (import_job_id, marketplace_id, line_number, field, error_code, message, raw_record, created_at)
                VALUES (:jobId, :marketplaceId, :lineNumber, :field, :code, :message, CAST(:rawRecord AS jsonb), :now)
                ON CONFLICT DO NOTHING
                """, rows.toArray(MapSqlParameterSource[]::new));
    }

    /** @return the import's problems grouped by code, most frequent first */
    List<ErrorCount> countByCode(UUID jobId) {
        return jdbc.sql("""
                        SELECT error_code, count(*) AS occurrences
                        FROM import_error
                        WHERE import_job_id = :jobId
                        GROUP BY error_code
                        ORDER BY occurrences DESC, error_code
                        """)
                .param("jobId", jobId)
                .query((row, rowNumber) -> new ErrorCount(row.getString("error_code"), row.getLong("occurrences")))
                .list();
    }

    /** Keyset pagination on the error id: page 500 is as fast as page 1. */
    List<StoredImportError> findPage(UUID jobId, long afterId, int size) {
        return jdbc.sql("""
                        SELECT id, line_number, field, error_code, message, raw_record::text AS raw_record
                        FROM import_error
                        WHERE import_job_id = :jobId AND id > :afterId
                        ORDER BY id
                        LIMIT :size
                        """)
                .param("jobId", jobId)
                .param("afterId", afterId)
                .param("size", size)
                .query((row, rowNumber) -> new StoredImportError(
                        row.getLong("id"),
                        row.getInt("line_number"),
                        row.getString("field"),
                        row.getString("error_code"),
                        row.getString("message"),
                        fromJson(row.getString("raw_record"))))
                .list();
    }

    private static Map<String, String> storableValues(Map<String, String> rawValues) {
        Map<String, String> storable = new LinkedHashMap<>();
        rawValues.forEach((column, value) -> storable.put(
                storableText(column, MAX_STORED_VALUE_LENGTH),
                value == null ? null : storableText(value, MAX_STORED_VALUE_LENGTH)));
        return storable;
    }

    /** Removes NUL, which PostgreSQL refuses in text and JSONB, and shortens the value. */
    private static String storableText(String text, int maxLength) {
        String withoutNul = text.replace("\u0000", "");
        return withoutNul.length() <= maxLength ? withoutNul : withoutNul.substring(0, maxLength) + "...";
    }

    private String toJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise raw CSV values", e);
        }
    }

    /** @param position a line of the file, or an index in the JSON array (see {@link ImportErrorView}) */
    record StoredImportError(long id, int position, String field, String code, String message, Map<String, String> rawValues) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fromJson(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored raw record is not valid JSON", e);
        }
    }
}
