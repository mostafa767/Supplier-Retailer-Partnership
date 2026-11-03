package com.equadis.portal.importing;

import com.equadis.portal.offer.OfferField.Naming;

import com.equadis.portal.shared.validation.Violation;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Line-level parsing and validation of the CSV import (R3). */
class OfferCsvLineParserTest {

    private static final String HEADER = "reference,title,description,discount_type,discount_value,currency,valid_from,valid_to,publish";
    private static final LocalDate TODAY = LocalDate.of(2030, 1, 10);

    private final OfferCsvLineParser parser = new OfferCsvLineParser();

    private static ParsedLine parse(String dataLine) throws IOException {
        try (CSVParser csv = OfferCsvFormat.format(',').parse(new StringReader(HEADER + "\n" + dataLine))) {
            CSVRecord record = csv.iterator().next();
            return new OfferCsvLineParser().parse(record, 2, csv.getHeaderNames().size(), TODAY, Naming.CSV);
        }
    }

    @Test
    void validLine_becomesACandidate() throws IOException {
        ParsedLine line = parse("SUN-01,Sun cream,\"Bundle, 2 bottles\",FIXED_AMOUNT,5.50,chf,2030-01-01,2030-01-31,TRUE");

        assertThat(line.isValid()).isTrue();
        assertThat(line.candidate().publish()).isTrue();
        assertThat(line.candidate().terms().currency()).isEqualTo("CHF");
        assertThat(line.candidate().terms().description()).isEqualTo("Bundle, 2 bottles");
    }

    @Test
    void parsingErrorsAreReportedWithTheReceivedValue_andNotDuplicatedAsRequired() throws IOException {
        ParsedLine line = parse("SUN-02,Deal,,PERCENT,12;5,,31/01/2030,2030-01-31,maybe");

        assertThat(line.isValid()).isFalse();
        assertThat(line.violations()).extracting(Violation::code)
                .containsExactlyInAnyOrder("DISCOUNT_TYPE_INVALID", "DISCOUNT_VALUE_INVALID", "DATE_INVALID", "PUBLISH_INVALID");
        assertThat(line.violations()).extracting(Violation::message)
                .anyMatch(message -> message.contains("'31/01/2030'"));
    }

    @Test
    void decimalComma_isRefusedWithAHelpfulMessage() throws IOException {
        ParsedLine line = parse("SUN-03,Deal,,PERCENTAGE,\"12,5\",,2030-01-01,2030-01-31,");

        assertThat(line.violations()).singleElement()
                .satisfies(v -> assertThat(v.message()).contains("use a dot as decimal separator"));
    }

    @Test
    void publishingAnExpiredOffer_isRefused() throws IOException {
        ParsedLine line = parse("OLD-01,Old deal,,PERCENTAGE,10,,2029-01-01,2029-12-31,true");

        assertThat(line.violations()).extracting(Violation::code).containsExactly("OFFER_EXPIRED");
    }

    @Test
    void missingValues_areReportedAsRequired() throws IOException {
        ParsedLine line = parse(",,,,,,,,");

        assertThat(line.violations()).extracting(Violation::code).contains(
                "REFERENCE_REQUIRED", "TITLE_REQUIRED", "DISCOUNT_TYPE_REQUIRED", "DISCOUNT_VALUE_REQUIRED",
                "VALID_FROM_REQUIRED", "VALID_TO_REQUIRED");
    }

    @Test
    void lineWithTooFewValues_isReported() throws IOException {
        ParsedLine line = parse("SHORT-01,Deal,,PERCENTAGE,10");

        assertThat(line.violations()).extracting(Violation::code).contains("COLUMN_COUNT_MISMATCH");
    }

    @Test
    void delimiterIsDetectedFromTheHeader() {
        assertThat(OfferCsvFormat.detectDelimiter("reference;title;discount_type")).isEqualTo(';');
        assertThat(OfferCsvFormat.detectDelimiter("reference,title,discount_type")).isEqualTo(',');
    }

    @Test
    void parserInstanceIsStateless() throws IOException {
        // The same parser instance is shared by all imports; parsing must not leak state between lines.
        try (CSVParser csv = OfferCsvFormat.format(',').parse(new StringReader(HEADER + "\nA,T,,PERCENTAGE,10,,2030-01-01,2030-01-31,\nB,,,PERCENTAGE,10,,2030-01-01,2030-01-31,"))) {
            var records = csv.getRecords();
            assertThat(parser.parse(records.get(0), 2, 9, TODAY, Naming.CSV).isValid()).isTrue();
            assertThat(parser.parse(records.get(1), 3, 9, TODAY, Naming.CSV).isValid()).isFalse();
        }
    }
}
