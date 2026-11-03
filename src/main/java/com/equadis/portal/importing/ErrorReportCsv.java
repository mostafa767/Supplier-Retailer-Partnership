package com.equadis.portal.importing;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Writes the error report as CSV, so suppliers can open it in a spreadsheet next to their file.
 *
 * <p>The reference column echoes what the supplier sent, so the supplier can find the line.
 *
 * <p><b>CSV injection</b>: a cell starting with {@code = + - @} (or tab / carriage return) is
 * executed as a formula by spreadsheet software. The report echoes values sent by the supplier
 * (and potentially by an attacker who controls a supplier system), so such cells are prefixed
 * with an apostrophe, which spreadsheets display as plain text.
 */
final class ErrorReportCsv {

    /** "line" is filled for CSV imports, "item" (index in the JSON array) for JSON imports. */
    static final List<String> HEADER = List.of("line", "item", "reference", "field", "code", "message");

    private ErrorReportCsv() {
    }

    static CSVPrinter open(Writer writer) throws IOException {
        CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
        printer.printRecord(HEADER);
        return printer;
    }

    static void write(CSVPrinter printer, ImportErrorView error) throws IOException {
        String reference = error.rawValues().getOrDefault("reference", "");
        printer.printRecord(error.lineNumber(), error.itemIndex(), neutralizeFormula(reference), neutralizeFormula(error.field()),
                neutralizeFormula(error.code()), neutralizeFormula(error.message()));
    }

    /** @return the value, made harmless if a spreadsheet would execute it as a formula */
    static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        boolean formulaTrigger = first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
        return formulaTrigger ? "'" + value : value;
    }
}
