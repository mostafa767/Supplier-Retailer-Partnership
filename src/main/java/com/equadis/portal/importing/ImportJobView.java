package com.equadis.portal.importing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Import status as returned to the supplier.
 *
 * @param alreadySubmitted true when exactly the same offers had already been sent: the existing
 *                         job is returned and nothing is imported twice (R3)
 * @param errorSummary     problems grouped by code, most frequent first
 */
record ImportJobView(UUID id, ImportFormat format, String fileName, ImportJob.Status status, int totalRows, int processedRows,
                     int created, int updated, int unchanged, int failed, int published, List<ErrorCount> errorSummary,
                     String failureReason, Instant createdAt, Instant finishedAt, boolean alreadySubmitted) {

    static ImportJobView of(ImportJob job, boolean alreadySubmitted) {
        return of(job, List.of(), alreadySubmitted);
    }

    static ImportJobView of(ImportJob job, List<ErrorCount> errorSummary, boolean alreadySubmitted) {
        return new ImportJobView(job.id(), job.format(), job.fileName(), job.status(), job.totalRows(), job.lastCommittedRow(),
                job.createdCount(), job.updatedCount(), job.unchangedCount(), job.failedCount(), job.publishedCount(),
                List.copyOf(errorSummary), job.failureReason(), job.createdAt(), job.finishedAt(), alreadySubmitted);
    }
}
