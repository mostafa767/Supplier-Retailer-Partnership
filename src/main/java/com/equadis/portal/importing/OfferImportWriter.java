package com.equadis.portal.importing;

import com.equadis.portal.offer.OfferStatus;
import com.equadis.portal.shared.validation.Violation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides what happens to each line of a chunk - the heart of R3.
 *
 * <p>For each valid line, compared with the stored offer of the same reference:
 * <table>
 *     <tr><th>Stored offer</th><th>Line</th><th>Result</th></tr>
 *     <tr><td>none</td><td>-</td><td>CREATED (as DRAFT, or PUBLISHED if publish=true)</td></tr>
 *     <tr><td>any status</td><td>same content</td><td>UNCHANGED - nothing written (re-sending a catalogue is normal)</td></tr>
 *     <tr><td>DRAFT</td><td>same content, publish=true</td><td>UPDATED (published)</td></tr>
 *     <tr><td>DRAFT</td><td>different content</td><td>UPDATED</td></tr>
 *     <tr><td>PUBLISHED / CLOSED</td><td>different content</td><td>line error OFFER_NOT_EDITABLE (BR-08)</td></tr>
 * </table>
 *
 * <p>Deciding ({@link #plan}) and writing ({@link #write}) are separate, so that a dry run gives
 * exactly the answers a real import would. The SQL is in {@link OfferImportRepository}.
 */
@Service
class OfferImportWriter {

    private final OfferImportRepository offerImportRepository;
    private final ImportErrorRepository errorRepository;

    OfferImportWriter(OfferImportRepository offerImportRepository, ImportErrorRepository errorRepository) {
        this.offerImportRepository = offerImportRepository;
        this.errorRepository = errorRepository;
    }

    /** Decides what each line would do, without writing anything. */
    ChunkPlan plan(UUID supplierId, List<ParsedLine> lines) {
        Map<String, StoredOffer> storedOffers = offerImportRepository.findByReferences(supplierId, validReferences(lines));

        List<ParsedLine.Candidate> newOffers = new ArrayList<>();
        List<OfferImportRepository.DraftUpdate> draftUpdates = new ArrayList<>();
        List<ParsedLine> rejectedLines = new ArrayList<>();
        int unchanged = 0;
        int published = 0;

        for (ParsedLine line : lines) {
            if (!line.isValid()) {
                rejectedLines.add(line);
                continue;
            }
            ParsedLine.Candidate candidate = line.candidate();
            StoredOffer storedOffer = storedOffers.get(line.referenceKey());

            if (storedOffer == null) {
                newOffers.add(candidate);
                published += candidate.publish() ? 1 : 0;
            } else if (isUnchanged(storedOffer, candidate)) {
                unchanged++;
            } else if (storedOffer.status() == OfferStatus.DRAFT) {
                draftUpdates.add(new OfferImportRepository.DraftUpdate(storedOffer, candidate));
                published += candidate.publish() ? 1 : 0;
            } else {
                rejectedLines.add(line.rejectedWith(notEditable(candidate, storedOffer)));
            }
        }
        return new ChunkPlan(newOffers, draftUpdates, rejectedLines, unchanged, published);
    }

    /** Writes one chunk inside the caller's transaction. @return what happened to its lines */
    @Transactional(propagation = Propagation.MANDATORY)
    ChunkResult write(ImportJob job, List<ParsedLine> lines, Instant now) {
        ChunkPlan plan = plan(job.supplierId(), lines);
        offerImportRepository.insertAll(job, plan.newOffers(), now);
        offerImportRepository.updateAll(job, plan.draftUpdates(), now);
        errorRepository.insertAll(job, plan.rejectedLines(), now);
        return plan.result();
    }

    private static List<String> validReferences(List<ParsedLine> lines) {
        return lines.stream().filter(ParsedLine::isValid).map(ParsedLine::reference).distinct().toList();
    }

    /** A draft with publish=true is not unchanged, even with the same content: it gets published. */
    private static boolean isUnchanged(StoredOffer storedOffer, ParsedLine.Candidate candidate) {
        boolean publishesDraft = candidate.publish() && storedOffer.status() == OfferStatus.DRAFT;
        return storedOffer.terms().sameContentAs(candidate.terms()) && !publishesDraft;
    }

    private static Violation notEditable(ParsedLine.Candidate candidate, StoredOffer storedOffer) {
        return new Violation(Violation.WHOLE_RECORD, "OFFER_NOT_EDITABLE",
                "Offer %s is %s and its conditions differ from this line; only DRAFT offers can be changed (BR-08). "
                        .formatted(candidate.reference(), storedOffer.status())
                        + "Use a new reference for new conditions.");
    }

    /** What a chunk will do: which offers to create or update, which lines are rejected. */
    record ChunkPlan(List<ParsedLine.Candidate> newOffers, List<OfferImportRepository.DraftUpdate> draftUpdates,
                     List<ParsedLine> rejectedLines, int unchanged, int published) {

        ChunkResult result() {
            return new ChunkResult(newOffers.size(), draftUpdates.size(), unchanged, rejectedLines.size(), published);
        }
    }
}
