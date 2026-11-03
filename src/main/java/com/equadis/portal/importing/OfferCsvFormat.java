package com.equadis.portal.importing;

import com.equadis.portal.offer.OfferField;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The CSV contract for offer files (documented in the README):
 * <pre>
 * reference,title,description,discount_type,discount_value,currency,valid_from,valid_to,publish
 * SUN-01,Sun cream summer deal,,PERCENTAGE,15,,2026-06-01,2026-08-31,true
 * </pre>
 * <ul>
 *     <li>UTF-8 (with or without BOM); comma <b>or semicolon</b> separated (French Excel uses
 *         semicolons) - detected from the header line;</li>
 *     <li>columns are matched by <b>name</b>, in any order, case-insensitive;</li>
 *     <li>dates are ISO {@code yyyy-MM-dd}; decimals use a dot;</li>
 *     <li>{@code publish} is optional ({@code true}/{@code false}, default {@code false}).</li>
 * </ul>
 * A real CSV parser (Apache Commons CSV) handles quoted values containing separators, quotes
 * or line breaks.
 */
final class OfferCsvFormat {

    static final List<String> REQUIRED_COLUMNS = List.of(
            OfferField.REFERENCE.csvName(), OfferField.TITLE.csvName(), OfferField.DISCOUNT_TYPE.csvName(),
            OfferField.DISCOUNT_VALUE.csvName(), OfferField.VALID_FROM.csvName(), OfferField.VALID_TO.csvName());

    static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private OfferCsvFormat() {
    }

    static CSVFormat format(char delimiter) {
        return CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()                       // first line is the header
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
    }

    /**
     * Opens the file as <b>strict</b> UTF-8: invalid bytes raise an error instead of being
     * silently replaced by '?', which would corrupt offer titles without anyone noticing.
     * A leading byte-order mark is skipped.
     */
    static Reader openUtf8(Path file) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)));
        reader.mark(1);
        if (reader.read() != BYTE_ORDER_MARK) {
            reader.reset();
        }
        return reader;
    }

    /** Semicolon if the header has more semicolons than commas, comma otherwise. */
    static char detectDelimiter(String headerLine) {
        long semicolons = headerLine.chars().filter(c -> c == ';').count();
        long commas = headerLine.chars().filter(c -> c == ',').count();
        return semicolons > commas ? ';' : ',';
    }

    static List<String> missingRequiredColumns(Collection<String> headerNames) {
        Set<String> present = headerNames.stream()
                .map(name -> name.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return REQUIRED_COLUMNS.stream().filter(column -> !present.contains(column)).toList();
    }
}
