package com.equadis.portal.importing;

import com.equadis.portal.offer.OfferReference;
import com.equadis.portal.offer.OfferTerms;
import com.equadis.portal.shared.validation.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One data line of an import file after parsing and validation: either a valid
 * {@link Candidate}, or the list of everything wrong with it.
 *
 * @param lineNumber line of the file on which this offer ends (header = 1). Blank lines are
 *                   counted, so it matches what the supplier sees in a text editor or a
 *                   spreadsheet. An offer with a line break inside a quoted value spans several
 *                   lines: the last one is reported.
 * @param rawValues  the values as received, stored with errors to help fixing the line
 */
record ParsedLine(int lineNumber, String reference, Map<String, String> rawValues,
                  Candidate candidate, List<Violation> violations) {

    ParsedLine {
        violations = List.copyOf(violations);
        if ((candidate == null) == violations.isEmpty()) {
            throw new IllegalArgumentException("A line is either valid (candidate) or invalid (violations)");
        }
    }

    boolean isValid() {
        return candidate != null;
    }

    /** The case-insensitive comparison key of the reference (see {@link OfferReference#key}). */
    String referenceKey() {
        return OfferReference.key(reference);
    }

    /** Returns a copy of this line, now invalid because of {@code violation}. */
    ParsedLine rejectedWith(Violation violation) {
        List<Violation> all = new ArrayList<>(violations);
        all.add(violation);
        return new ParsedLine(lineNumber, reference, rawValues, null, all);
    }

    /** A valid line: what the supplier wants the offer to be. */
    record Candidate(String reference, OfferTerms terms, boolean publish) {
    }
}
