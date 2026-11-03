package com.equadis.portal.importing;

import java.time.Instant;
import java.util.UUID;

/** An import job as stored. Counters always reflect the committed chunks. */
record ImportJob(UUID id, UUID marketplaceId, UUID supplierId, ImportFormat format, String fileName, String fileSha256,
                 String storageKey, char delimiter, Status status, int totalRows, int lastCommittedRow,
                 int createdCount, int updatedCount, int unchangedCount, int failedCount, int publishedCount,
                 int attempts, String failureReason, Instant createdAt, Instant startedAt, Instant finishedAt) {

    enum Status {
        /** Accepted, waiting for a worker. */
        PENDING,
        /** A worker is processing it (holds a lease). */
        PROCESSING,
        /** Every line was handled; some lines may have failed (see the error report). */
        COMPLETED,
        /** Technical failure after all retries - not a line problem. */
        FAILED
    }
}
