package com.equadis.portal.importing;

import com.equadis.portal.audit.AuditAction;
import com.equadis.portal.audit.AuditService;
import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import com.equadis.portal.tenant.Actor;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Import use cases for suppliers: send offers (CSV file or JSON request), follow the import,
 * read its error report.
 */
@Service
class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    static final int MAX_ERROR_PAGE_SIZE = 500;

    private final ImportIntake intake;
    private final ImportJobRepository jobRepository;
    private final ImportErrorRepository errorRepository;
    private final AuditService auditService;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ImportService(ImportIntake intake, ImportJobRepository jobRepository, ImportErrorRepository errorRepository,
                  AuditService auditService, TransactionTemplate transactions, Clock clock) {
        this.intake = intake;
        this.jobRepository = jobRepository;
        this.errorRepository = errorRepository;
        this.auditService = auditService;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Accepts offers for asynchronous processing.
     *
     * <ol>
     *     <li>receive them through the format's inbound connector and check the structure
     *         (header, encoding, size) - rejected at once if broken ({@link ImportIntake});</li>
     *     <li>create the job, unless this supplier already sent exactly the same offers: then
     *         the existing job is returned and nothing is imported twice (R3). If that earlier
     *         import failed for technical reasons, it is re-queued.</li>
     * </ol>
     *
     * @param name the file name the supplier gave, shown back to it; never used to build a path
     */
    ImportJobView submit(Actor actor, ImportFormat format, InputStream source, String name) {
        UUID supplierId = actor.requireSupplier();
        UUID jobId = UUID.randomUUID();
        ImportIntake.ReceivedFile file = intake.receive(actor.marketplaceId(), jobId, format, source);
        try {
            ImportJobView job = transactions.execute(status -> {
                boolean created = jobRepository.insertIfNew(jobId, actor.marketplaceId(), supplierId, format, name,
                        file.storedFile().sha256(), file.storedFile().key(), file.inspectedFile().delimiter(),
                        file.inspectedFile().rowCount(), clock.instant());
                if (created) {
                    auditService.record(actor, AuditAction.IMPORT_SUBMITTED, jobId, Map.of("format", format, "fileName", name,
                            "rows", file.inspectedFile().rowCount(), "sha256", file.storedFile().sha256()));
                    return ImportJobView.of(requireJob(jobId), false);
                }
                return existingJobOfSameContent(actor, supplierId, file.storedFile().sha256());
            });
            if (job.alreadySubmitted()) {
                intake.discard(file); // we keep the copy of the first upload
            }
            log.info("Import {} accepted: format={} name={} rows={} alreadySubmitted={}", job.id(), format, name,
                    job.totalRows(), job.alreadySubmitted());
            return job;
        } catch (RuntimeException rejected) {
            intake.discard(file);
            throw rejected;
        }
    }

    /** @return the calling supplier's import, with its problems grouped by code */
    ImportJobView get(Actor actor, UUID jobId) {
        ImportJob job = requireOwnedJob(actor, jobId);
        return ImportJobView.of(job, errorRepository.countByCode(jobId), false);
    }

    /** @return one page of the error report, after the error {@code afterId} */
    ImportErrorPage errorPage(Actor actor, UUID jobId, long afterId, int size) {
        ImportJob job = requireOwnedJob(actor, jobId);
        if (size < 1 || size > MAX_ERROR_PAGE_SIZE) {
            throw new PortalException(ErrorCode.REQUEST_VALIDATION_FAILED,
                    "size must be between 1 and %d".formatted(MAX_ERROR_PAGE_SIZE));
        }
        List<ImportErrorView> items = errorRepository.findPage(jobId, afterId, size).stream()
                .map(error -> ImportErrorView.of(error, job.format()))
                .toList();
        Long nextAfterId = items.size() == size ? items.get(items.size() - 1).id() : null;
        return new ImportErrorPage(items, nextAfterId);
    }

    /** Writes the whole report as CSV, page by page: memory stays flat whatever its size. */
    void writeErrorReport(Actor actor, UUID jobId, Writer writer) throws IOException {
        ImportJob job = requireOwnedJob(actor, jobId);
        try (CSVPrinter printer = ErrorReportCsv.open(writer)) {
            long afterId = 0;
            List<ImportErrorRepository.StoredImportError> page;
            do {
                page = errorRepository.findPage(jobId, afterId, MAX_ERROR_PAGE_SIZE);
                for (ImportErrorRepository.StoredImportError error : page) {
                    ErrorReportCsv.write(printer, ImportErrorView.of(error, job.format()));
                    afterId = error.id();
                }
            } while (page.size() == MAX_ERROR_PAGE_SIZE);
        }
    }

    private ImportJobView existingJobOfSameContent(Actor actor, UUID supplierId, String sha256) {
        ImportJob existing = jobRepository.findBySupplierAndHash(supplierId, sha256).orElseThrow();
        if (jobRepository.requeueFailed(existing.id())) {
            auditService.record(actor, AuditAction.IMPORT_REQUEUED, existing.id(), null);
            log.info("Same offers already imported by job {} which had failed; re-queued", existing.id());
            existing = requireJob(existing.id());
        }
        return ImportJobView.of(existing, true);
    }

    /** 404 for jobs of other suppliers (and, through Row-Level Security, of other marketplaces). */
    private ImportJob requireOwnedJob(Actor actor, UUID jobId) {
        UUID supplierId = actor.requireSupplier();
        return jobRepository.findById(jobId)
                .filter(job -> job.supplierId().equals(supplierId))
                .orElseThrow(() -> PortalException.notFound(ErrorCode.IMPORT_NOT_FOUND, "Import", jobId));
    }

    private ImportJob requireJob(UUID jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> new IllegalStateException("Import job " + jobId + " vanished"));
    }
}
