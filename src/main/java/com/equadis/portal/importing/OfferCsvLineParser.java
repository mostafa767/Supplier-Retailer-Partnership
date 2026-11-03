package com.equadis.portal.importing;

import com.equadis.portal.offer.DiscountType;
import com.equadis.portal.offer.OfferField;
import com.equadis.portal.offer.OfferField.Naming;
import com.equadis.portal.offer.OfferReference;
import com.equadis.portal.offer.OfferTerms;
import com.equadis.portal.shared.validation.Violation;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns one CSV record into a {@link ParsedLine}, collecting <b>every</b> problem of the line.
 *
 * <p>Two stages:
 * <ol>
 *     <li><b>parsing</b> text into types (number, date, enum) - reported here, with the
 *         exact value received;</li>
 *     <li><b>business rules</b> - delegated to {@link OfferTerms}, the same code the JSON API
 *         uses, so an offer is valid or not whatever channel it comes through.</li>
 * </ol>
 * A field that failed parsing is not reported a second time as "required" by stage 2.
 *
 * <p>Columns are always read by their CSV name; problems are reported with the field names
 * the supplier used ({@code discount_value} in a CSV file, {@code discountValue} in JSON).
 */
@Component
class OfferCsvLineParser {

    /**
     * @param position        where the offer is in what the supplier sent, used in reports
     * @param expectedColumns number of columns in the header
     * @param today           the business date, for the publication rule
     * @param naming          how field names are reported
     */
    ParsedLine parse(CSVRecord record, int position, int expectedColumns, LocalDate today, Naming naming) {
        Map<String, String> rawValues = rawValues(record);
        List<Violation> violations = new ArrayList<>();
        Set<String> unparseableFields = new HashSet<>();

        if (record.size() != expectedColumns) {
            violations.add(new Violation(Violation.WHOLE_RECORD, "COLUMN_COUNT_MISMATCH",
                    "The line has %d value(s) but the header has %d column(s)".formatted(record.size(), expectedColumns)));
        }

        String reference = OfferReference.normalize(cellValue(record, OfferField.REFERENCE));
        OfferReference.validate(reference, naming).ifPresent(violations::add);

        DiscountType discountType = parseDiscountType(record, naming, violations, unparseableFields);
        BigDecimal discountValue = parseDiscountValue(record, naming, violations, unparseableFields);
        LocalDate validFrom = parseDate(record, OfferField.VALID_FROM, naming, violations, unparseableFields);
        LocalDate validTo = parseDate(record, OfferField.VALID_TO, naming, violations, unparseableFields);
        boolean publish = parsePublish(record, naming, violations);

        OfferTerms terms = new OfferTerms(cellValue(record, OfferField.TITLE), cellValue(record, OfferField.DESCRIPTION),
                discountType, discountValue, cellValue(record, OfferField.CURRENCY), validFrom, validTo);
        terms.violations(naming).stream()
                .filter(violation -> !unparseableFields.contains(violation.field()))
                .forEach(violations::add);
        if (publish && validTo != null) {
            violations.addAll(terms.publicationViolations(today, naming));
        }

        ParsedLine.Candidate candidate = violations.isEmpty() ? new ParsedLine.Candidate(reference, terms, publish) : null;
        return new ParsedLine(position, reference, rawValues, candidate, violations);
    }

    private static DiscountType parseDiscountType(CSVRecord record, Naming naming, List<Violation> violations, Set<String> unparseableFields) {
        String text = cellValue(record, OfferField.DISCOUNT_TYPE);
        if (text == null) {
            return null; // "required" is reported by OfferTerms
        }
        try {
            return DiscountType.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            String field = OfferField.DISCOUNT_TYPE.nameIn(naming);
            unparseableFields.add(field);
            violations.add(new Violation(field, "DISCOUNT_TYPE_INVALID",
                    "'%s' is not a discount type; use PERCENTAGE or FIXED_AMOUNT".formatted(text)));
            return null;
        }
    }

    private static BigDecimal parseDiscountValue(CSVRecord record, Naming naming, List<Violation> violations, Set<String> unparseableFields) {
        String text = cellValue(record, OfferField.DISCOUNT_VALUE);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notANumber) {
            String field = OfferField.DISCOUNT_VALUE.nameIn(naming);
            unparseableFields.add(field);
            violations.add(new Violation(field, "DISCOUNT_VALUE_INVALID",
                    "'%s' is not a number; use a dot as decimal separator (e.g. 12.50)".formatted(text)));
            return null;
        }
    }

    private static LocalDate parseDate(CSVRecord record, OfferField dateField, Naming naming, List<Violation> violations,
                                       Set<String> unparseableFields) {
        String text = cellValue(record, dateField);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text); // ISO yyyy-MM-dd, strict
        } catch (DateTimeParseException notADate) {
            String field = dateField.nameIn(naming);
            unparseableFields.add(field);
            violations.add(new Violation(field, "DATE_INVALID",
                    "'%s' is not a valid date; use yyyy-MM-dd (e.g. 2026-11-27)".formatted(text)));
            return null;
        }
    }

    private static boolean parsePublish(CSVRecord record, Naming naming, List<Violation> violations) {
        String text = cellValue(record, OfferField.PUBLISH);
        if (text == null || text.equalsIgnoreCase("false")) {
            return false;
        }
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        violations.add(new Violation(OfferField.PUBLISH.nameIn(naming), "PUBLISH_INVALID",
                "'%s' is not valid for publish; use true or false".formatted(text)));
        return false;
    }

    /** The trimmed value of a column, or null when the column is absent or the cell is empty. */
    private static String cellValue(CSVRecord record, OfferField field) {
        String column = field.csvName();
        if (!record.isMapped(column) || !record.isSet(column)) {
            return null;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Map<String, String> rawValues(CSVRecord record) {
        Map<String, String> rawValues = new LinkedHashMap<>();
        record.toMap().forEach(rawValues::put);
        return rawValues;
    }
}
