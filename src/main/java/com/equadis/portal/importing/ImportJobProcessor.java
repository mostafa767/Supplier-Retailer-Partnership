package com.equadis.portal.importing;

import com.equadis.portal.audit.AuditAction;
import com.equadis.portal.audit.AuditService;
import com.equadis.portal.offer.OffersPublished;
import com.equadis.portal.outbox.OutboxWriter;
import com.equadis.portal.tenant.Actor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Processes one import job: reads its offers ({@link OfferLineReader}) and writes them chunk by
 * chunk.
 *
 * <p>Each chunk is ONE transaction containing: the offers, the line errors, the updated
 * counters/checkpoint, the audit row and - if offers were published - one outbox event.
 * Consequences:
 * <ul>
 *     <li>a crash loses at most the chunk in progress; the next attempt resumes after the
 *         last committed line (checkpoint) - no reprocessing of 99,000 lines;</li>
 *     <li>counters always match what is really in the database;</li>
 *     <li>notifications are emitted only for offers that were really committed.</li>
 * </ul>
 */
@Component
class ImportJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImportJobProcessor.class);

    private final ImportJobRepository jobRepository;
    private final ImportFileStorage fileStorage;
    private final OfferLineReader lineReader;
    private final OfferImportWriter writer;
    private final OutboxWriter outboxWriter;
    private final AuditService auditService;
    private final TransactionTemplate transactions;
    private final ImportProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    ImportJobProcessor(ImportJobRepository jobRepository, ImportFileStorage fileStorage, OfferLineReader lineReader,
                       OfferImportWriter writer, OutboxWriter outboxWriter, AuditService auditService, TransactionTemplate transactions,
                       ImportProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.jobRepository = jobRepository;
        this.fileStorage = fileStorage;
        this.lineReader = lineReader;
        this.writer = writer;
        this.outboxWriter = outboxWriter;
        this.auditService = auditService;
        this.transactions = transactions;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    void process(UUID jobId) {
        ImportJob job = jobRepository.findById(jobId).orElseThrow(() -> new IllegalStateException("Import job " + jobId + " not found"));
        ChunkedWrite chunkedWrite = new ChunkedWrite(job);
        try {
            lineReader.read(fileStorage.resolve(job.storageKey()), job.delimiter(), job.format(), LocalDate.now(clock), chunkedWrite::accept);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read import file of job " + jobId, e);
        }
        chunkedWrite.commitRemaining();

        jobRepository.markCompleted(jobId, clock.instant());
        log.info("Import {} completed: {} line(s) processed", jobId, chunkedWrite.committedRow);
    }

    /** Collects offers into chunks and commits each chunk, resuming after the last checkpoint. */
    private final class ChunkedWrite {

        private final ImportJob job;
        private final List<ParsedLine> chunk = new ArrayList<>(properties.chunkSize());
        private int committedRow;
        private int lastRow;

        ChunkedWrite(ImportJob job) {
            this.job = job;
            this.committedRow = job.lastCommittedRow();
        }

        void accept(int row, ParsedLine line) {
            if (row <= committedRow) {
                return; // committed by a previous attempt; read only to rebuild duplicate detection
            }
            chunk.add(line);
            lastRow = row;
            if (chunk.size() == properties.chunkSize()) {
                commitRemaining();
            }
        }

        void commitRemaining() {
            if (chunk.isEmpty()) {
                return;
            }
            commitChunk(job, chunk, committedRow, lastRow);
            committedRow = lastRow;
            chunk.clear();
        }
    }

    private void commitChunk(ImportJob job, List<ParsedLine> chunk, int committedRow, int lastRowOfChunk) {
        Instant now = clock.instant();
        ChunkResult result = transactions.execute(status -> {
            ChunkResult written = writer.write(job, chunk, now);
            if (!jobRepository.recordChunk(job.id(), committedRow, lastRowOfChunk, written, now, properties.lease())) {
                // Another worker took over this job (our lease expired). Roll back, let it continue.
                throw new IllegalStateException("Lost ownership of import job " + job.id() + "; chunk rolled back");
            }
            if (written.published() > 0) {
                outboxWriter.append(job.marketplaceId(), OffersPublished.EVENT_TYPE, job.supplierId(),
                        new OffersPublished(job.supplierId(), written.published(), "IMPORT:" + job.id()), now);
            }
            // One audit row per chunk, not per offer. Each offer keeps source_import_id, which leads here.
            auditService.record(new Actor(job.marketplaceId(), Actor.Role.SUPPLIER, job.supplierId()),
                    AuditAction.OFFERS_IMPORTED, job.id(), Map.of("firstRow", committedRow + 1, "lastRow", lastRowOfChunk, "result", written));
            return written;
        });
        recordMetrics(result);
    }

    private void recordMetrics(ChunkResult result) {
        meterRegistry.counter("portal.imports.lines", "result", "created").increment(result.created());
        meterRegistry.counter("portal.imports.lines", "result", "updated").increment(result.updated());
        meterRegistry.counter("portal.imports.lines", "result", "unchanged").increment(result.unchanged());
        meterRegistry.counter("portal.imports.lines", "result", "failed").increment(result.failed());
    }
}
