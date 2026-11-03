package com.equadis.portal.importing;

import com.equadis.portal.importing.ImportErrorRepository.StoredImportError;
import com.equadis.portal.shared.validation.Violation;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * One problem on one offer, as returned to the supplier. Exactly one of {@code lineNumber} and
 * {@code itemIndex} is set, depending on how the offers were sent.
 *
 * @param id         position in the stored report, for paging; absent in a dry run
 * @param lineNumber CSV file: the line of the file (header = 1)
 * @param itemIndex  JSON request: the index in the "offers" array (first = 0)
 * @param rawValues  the offer as received, shortened when a value was very long; absent in a dry run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ImportErrorView(Long id, Integer lineNumber, Integer itemIndex, String field, String code, String message,
                       Map<String, String> rawValues) {

    static ImportErrorView of(StoredImportError error, ImportFormat format) {
        return new ImportErrorView(error.id(), lineNumberOf(error.position(), format), itemIndexOf(error.position(), format),
                error.field(), error.code(), error.message(), error.rawValues());
    }

    /** A problem found by a dry run: nothing is stored, so there is no id and no copy of the line. */
    static ImportErrorView of(ParsedLine line, Violation violation, ImportFormat format) {
        return new ImportErrorView(null, lineNumberOf(line.lineNumber(), format), itemIndexOf(line.lineNumber(), format),
                violation.field(), violation.code(), violation.message(), null);
    }

    private static Integer lineNumberOf(int position, ImportFormat format) {
        return format == ImportFormat.CSV ? position : null;
    }

    private static Integer itemIndexOf(int position, ImportFormat format) {
        return format == ImportFormat.JSON ? position : null;
    }
}
