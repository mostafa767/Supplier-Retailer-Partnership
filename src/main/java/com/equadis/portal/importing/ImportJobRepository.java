package com.equadis.portal.importing;

import com.equadis.portal.shared.jdbc.JdbcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of import jobs (plain SQL: the job is a work-queue row with counters, updated
 * atomically by statements such as "counter = counter + n").
 */
@Repository
class ImportJobRepository {

    private static final String COLUMNS = """
            id, marketplace_id, supplier_id, source_format, file_name, file_sha256, storage_key, delimiter, status, total_rows,
            last_committed_row, created_count, updated_count, unchanged_count, failed_count, published_count,
            attempts, failure_reason, created_at, started_at, finished_at
            """;

    private final JdbcClient jdbc;

    ImportJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the job unless this supplier already sent the same file (same SHA-256).
     * The unique constraint makes this safe even when the same file is uploaded twice at
     * the same moment.
     *
     * @return true if inserted, false if the file was already known
     */
    boolean insertIfNew(UUID id, UUID marketplaceId, UUID supplierId, ImportFormat format, String fileName, String sha256,
                        String storageKey, char delimiter, int totalRows, Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO import_job (id, marketplace_id, supplier_id, source_format, file_name, file_sha256, storage_key,
                                                delimiter, status, total_rows, created_at)
                        VALUES (:id, :marketplaceId, :supplierId, :format, :fileName, :sha256, :storageKey,
                                :delimiter, 'PENDING', :totalRows, :now)
                        ON CONFLICT (supplier_id, file_sha256) DO NOTHING
                        """)
                .param("id", id)
                .param("marketplaceId", marketplaceId)
                .param("supplierId", supplierId)
                .param("format", format.name())
                .param("fileName", fileName)
                .param("sha256", sha256)
                .param("storageKey", storageKey)
                .param("delimiter", String.valueOf(delimiter))
                .param("totalRows", totalRows)
                .param("now", JdbcTime.toDb(now))
                .update();
        return inserted == 1;
    }

    Optional<ImportJob> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM import_job WHERE id = :id")
                .param("id", id)
                .query(ImportJobRepository::toJob)
                .optional();
    }

    Optional<ImportJob> findBySupplierAndHash(UUID supplierId, String sha256) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM import_job WHERE supplier_id = :supplierId AND file_sha256 = :sha256")
                .param("supplierId", supplierId)
                .param("sha256", sha256)
                .query(ImportJobRepository::toJob)
                .optional();
    }

    /**
     * A file whose previous import failed for TECHNICAL reasons may be sent again: the job is
     * re-queued (already committed chunks are kept; re-processing them is harmless because
     * writes are idempotent).
     */
    boolean requeueFailed(UUID id) {
        return jdbc.sql("""
                        UPDATE import_job SET status = 'PENDING', attempts = 0, failure_reason = NULL, finished_at = NULL
                        WHERE id = :id AND status = 'FAILED'
                        """)
                .param("id", id)
                .update() == 1;
    }

    /**
     * Commits a chunk's counters and checkpoint, and extends the lease (heartbeat).
     *
     * <p>Guarded by {@code last_committed_row = :expectedLastRow}: if this worker lost its
     * lease and another worker already moved the checkpoint, nothing is updated, the caller
     * rolls back the chunk, and the two workers never double-count.
     */
    boolean recordChunk(UUID id, int expectedLastRow, int newLastRow, ChunkResult result, Instant now, Duration lease) {
        return jdbc.sql("""
                        UPDATE import_job
                        SET last_committed_row = :newLastRow,
                            created_count = created_count + :created,
                            updated_count = updated_count + :updated,
                            unchanged_count = unchanged_count + :unchanged,
                            failed_count = failed_count + :failed,
                            published_count = published_count + :published,
                            locked_until = :leaseUntil
                        WHERE id = :id AND status = 'PROCESSING' AND last_committed_row = :expectedLastRow
                        """)
                .param("newLastRow", newLastRow)
                .param("created", result.created())
                .param("updated", result.updated())
                .param("unchanged", result.unchanged())
                .param("failed", result.failed())
                .param("published", result.published())
                .param("leaseUntil", JdbcTime.toDb(now.plus(lease)))
                .param("id", id)
                .param("expectedLastRow", expectedLastRow)
                .update() == 1;
    }

    void markCompleted(UUID id, Instant now) {
        jdbc.sql("""
                        UPDATE import_job SET status = 'COMPLETED', finished_at = :now, locked_until = NULL
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("now", JdbcTime.toDb(now))
                .param("id", id)
                .update();
    }

    /** Technical failure: back to PENDING for another attempt, or FAILED for good. */
    void markAttemptFailed(UUID id, boolean giveUp, String reason, Instant now) {
        jdbc.sql("""
                        UPDATE import_job
                        SET status = CASE WHEN :giveUp THEN 'FAILED' ELSE 'PENDING' END,
                            failure_reason = :reason,
                            finished_at = CASE WHEN :giveUp THEN CAST(:now AS timestamptz) ELSE NULL END,
                            locked_until = NULL
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("giveUp", giveUp)
                .param("reason", reason)
                .param("now", JdbcTime.toDb(now))
                .param("id", id)
                .update();
    }

    private static ImportJob toJob(ResultSet row, int rowNumber) throws SQLException {
        return new ImportJob(
                row.getObject("id", UUID.class),
                row.getObject("marketplace_id", UUID.class),
                row.getObject("supplier_id", UUID.class),
                ImportFormat.valueOf(row.getString("source_format")),
                row.getString("file_name"),
                row.getString("file_sha256"),
                row.getString("storage_key"),
                row.getString("delimiter").charAt(0),
                ImportJob.Status.valueOf(row.getString("status")),
                row.getInt("total_rows"),
                row.getInt("last_committed_row"),
                row.getInt("created_count"),
                row.getInt("updated_count"),
                row.getInt("unchanged_count"),
                row.getInt("failed_count"),
                row.getInt("published_count"),
                row.getInt("attempts"),
                row.getString("failure_reason"),
                JdbcTime.instant(row, "created_at"),
                JdbcTime.instant(row, "started_at"),
                JdbcTime.instant(row, "finished_at"));
    }
}
