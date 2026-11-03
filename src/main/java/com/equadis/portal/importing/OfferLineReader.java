package com.equadis.portal.importing;

import com.equadis.portal.offer.OfferField;
import com.equadis.portal.shared.validation.Violation;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a stored canonical file and hands over its offers one by one, parsed and validated.
 * Used by the import itself and by the dry run, so both see exactly the same problems.
 *
 * <p>Memory stays flat: the file is streamed; only the references already seen are kept, to
 * detect duplicates.
 */
@Component
class OfferLineReader {

    private final OfferCsvLineParser lineParser;

    OfferLineReader(OfferCsvLineParser lineParser) {
        this.lineParser = lineParser;
    }

    /** @param handler receives each offer with its row number (1 = first offer) */
    void read(Path file, char delimiter, ImportFormat format, LocalDate today, LineHandler handler) throws IOException {
        Map<String, Integer> firstPositionByReference = new HashMap<>();
        try (Reader reader = OfferCsvFormat.openUtf8(file);
             CSVParser parser = OfferCsvFormat.format(delimiter).parse(reader)) {
            int expectedColumns = parser.getHeaderNames().size();
            int row = 0;
            for (CSVRecord record : parser) {
                row++;
                ParsedLine parsed = lineParser.parse(record, positionOf(format, parser, row), expectedColumns, today, format.fieldNaming());
                handler.accept(row, rejectDuplicateReference(parsed, firstPositionByReference, format));
            }
        }
    }

    /**
     * Where the supplier will look for this offer.
     * <ul>
     *     <li>CSV: the real line of the file - counting rows would be wrong after a blank line;</li>
     *     <li>JSON: the index in the "offers" array (first = 0), as JSON tools count.</li>
     * </ul>
     */
    private static int positionOf(ImportFormat format, CSVParser parser, int row) {
        return format == ImportFormat.JSON ? row - 1 : (int) parser.getCurrentLineNumber();
    }

    /**
     * Assumption (documented): the FIRST occurrence of a reference wins; later occurrences are
     * reported as errors - deterministic, and the supplier sees both positions. First
     * occurrences are tracked even when invalid, so the rule does not depend on whether line
     * 12 had a typo.
     */
    private static ParsedLine rejectDuplicateReference(ParsedLine line, Map<String, Integer> firstPositionByReference, ImportFormat format) {
        if (line.reference().isEmpty()) {
            return line;
        }
        Integer firstPosition = firstPositionByReference.putIfAbsent(line.referenceKey(), line.lineNumber());
        if (firstPosition == null) {
            return line;
        }
        return line.rejectedWith(new Violation(OfferField.REFERENCE.nameIn(format.fieldNaming()), "DUPLICATE_REFERENCE_IN_FILE",
                "Reference %s already appears at %s; only the first occurrence is imported"
                        .formatted(line.reference(), format.describePosition(firstPosition))));
    }

    @FunctionalInterface
    interface LineHandler {
        void accept(int row, ParsedLine line);
    }
}
