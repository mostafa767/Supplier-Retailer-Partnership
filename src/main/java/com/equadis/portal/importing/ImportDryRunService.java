package com.equadis.portal.importing;

import com.equadis.portal.shared.validation.Violation;
import com.equadis.portal.tenant.Actor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates offers before sending them for real: "will this file go through, and what will it
 * change?". Suppliers check a new export once, fix it, then import it; nothing is stored, no
 * job is created and no retailer is informed.
 *
 * <p>It runs the real pipeline - the same inbound connector, structure checks, line rules and
 * decisions against the stored offers (e.g. BR-08: a published offer cannot change) - and
 * stops before writing. It runs during the request, so it is meant for one file at a time:
 * 100,000 lines take a few seconds.
 */
@Service
class ImportDryRunService {

    static final int MAX_LISTED_PROBLEMS = 100;

    private final ImportIntake intake;
    private final OfferLineReader lineReader;
    private final OfferImportWriter writer;
    private final ImportProperties properties;
    private final Clock clock;

    ImportDryRunService(ImportIntake intake, OfferLineReader lineReader, OfferImportWriter writer,
                        ImportProperties properties, Clock clock) {
        this.intake = intake;
        this.lineReader = lineReader;
        this.writer = writer;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return what importing these offers would do; nothing is changed */
    ImportDryRunReport check(Actor actor, ImportFormat format, InputStream source) {
        UUID supplierId = actor.requireSupplier();
        ImportIntake.ReceivedFile file = intake.receive(actor.marketplaceId(), UUID.randomUUID(), format, source);
        try {
            Tally tally = new Tally(format);
            List<ParsedLine> chunk = new ArrayList<>(properties.chunkSize());
            lineReader.read(file.storedFile().path(), file.inspectedFile().delimiter(), format, LocalDate.now(clock), (row, line) -> {
                chunk.add(line);
                if (chunk.size() == properties.chunkSize()) {
                    tally.add(writer.plan(supplierId, chunk));
                    chunk.clear();
                }
            });
            if (!chunk.isEmpty()) {
                tally.add(writer.plan(supplierId, chunk));
            }
            return tally.report(file.inspectedFile().rowCount());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the offers to check", e);
        } finally {
            intake.discard(file);
        }
    }

    /** Adds up the plans of all chunks. */
    private static final class Tally {

        private final ImportFormat format;
        private final Map<String, Long> problemsByCode = new LinkedHashMap<>();
        private final List<ImportErrorView> problems = new ArrayList<>();
        private int wouldCreate;
        private int wouldUpdate;
        private int unchanged;
        private int failed;
        private int wouldPublish;

        Tally(ImportFormat format) {
            this.format = format;
        }

        void add(OfferImportWriter.ChunkPlan plan) {
            wouldCreate += plan.newOffers().size();
            wouldUpdate += plan.draftUpdates().size();
            unchanged += plan.unchanged();
            failed += plan.rejectedLines().size();
            wouldPublish += plan.published();
            for (ParsedLine line : plan.rejectedLines()) {
                for (Violation violation : line.violations()) {
                    problemsByCode.merge(violation.code(), 1L, Long::sum);
                    if (problems.size() < MAX_LISTED_PROBLEMS) {
                        problems.add(ImportErrorView.of(line, violation, format));
                    }
                }
            }
        }

        ImportDryRunReport report(int totalRows) {
            List<ErrorCount> errorSummary = problemsByCode.entrySet().stream()
                    .map(entry -> new ErrorCount(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparingLong(ErrorCount::count).reversed().thenComparing(ErrorCount::code))
                    .toList();
            long totalProblems = problemsByCode.values().stream().mapToLong(Long::longValue).sum();
            return new ImportDryRunReport(format, totalRows, wouldCreate, wouldUpdate, unchanged, failed, wouldPublish,
                    errorSummary, List.copyOf(problems), totalProblems > problems.size());
        }
    }
}
