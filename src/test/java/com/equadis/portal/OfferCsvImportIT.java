package com.equadis.portal;

import com.equadis.portal.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R3 - suppliers send offers as a CSV file: errors on some lines do not block the others,
 * every failed line is reported with its number and reason, and sending the same file twice
 * never creates duplicates. (Chunk size is 3 in tests, so every file spans several
 * transactions.)
 */
class OfferCsvImportIT extends AbstractIntegrationTest {

    private static final String HEADER = "reference,title,description,discount_type,discount_value,currency,valid_from,valid_to,publish\n";

    private UUID marketplace;
    private UUID supplier;

    @BeforeEach
    void supplierInItsMarketplace() {
        marketplace = testData.marketplace();
        supplier = testData.supplier(marketplace, "Nivea");
    }

    @Test
    @DisplayName("Valid lines are imported, invalid lines are reported by line number with all their problems")
    void partialErrors_validLinesImported_invalidLinesReported() throws Exception {
        String csv = HEADER + """
                SUN-01,Sun cream,,PERCENTAGE,15,,2030-06-01,2030-08-31,false
                SUN-02,Too generous,,PERCENTAGE,150,,2030-06-01,2030-08-31,false
                SUN-03,Lotion,"Bundle, 2 bottles",FIXED_AMOUNT,5.50,CHF,2030-06-01,2030-08-31,false
                SUN-04,Broken,,FIXED_AMOUNT,5,,31/12/2030,2030-08-31,false
                SUN-05,After-sun,,PERCENTAGE,100,,2030-06-01,2030-06-01,false
                """;

        UUID importId = submit(csv);
        runImports();

        mockMvc.perform(get("/api/imports/{id}", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.errorSummary.length()").value(3))           // problems grouped by code
                .andExpect(jsonPath("$.errorSummary[0].code").value("CURRENCY_REQUIRED"))
                .andExpect(jsonPath("$.errorSummary[0].count").value(1));

        // Line 3 = SUN-02 (header is line 1). Line 5 = SUN-04 with TWO problems, both reported.
        mockMvc.perform(get("/api/imports/{id}/errors", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].lineNumber").value(3))
                .andExpect(jsonPath("$.items[0].field").value("discount_value"))
                .andExpect(jsonPath("$.items[0].code").value("DISCOUNT_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.items[1].lineNumber").value(5))
                .andExpect(jsonPath("$.items[2].lineNumber").value(5))
                .andExpect(jsonPath("$.items[*].code", org.hamcrest.Matchers.containsInAnyOrder(
                        "DISCOUNT_OUT_OF_RANGE", "DATE_INVALID", "CURRENCY_REQUIRED")));

        assertThat(storedReferences()).containsExactlyInAnyOrder("SUN-01", "SUN-03", "SUN-05");
    }

    @Test
    @DisplayName("Sending the same file twice returns the first import and creates no duplicate")
    void sameFileTwice_noDuplicates() throws Exception {
        String csv = HEADER + """
                DUP-01,Deal one,,PERCENTAGE,10,,2030-01-01,2030-01-31,false
                DUP-02,Deal two,,PERCENTAGE,20,,2030-01-01,2030-01-31,false
                """;
        UUID firstImport = submit(csv);
        runImports();

        upload(csv)
                .andExpect(status().isOk())                                   // not 202: nothing new accepted
                .andExpect(jsonPath("$.id").value(firstImport.toString()))
                .andExpect(jsonPath("$.alreadySubmitted").value(true))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        runImports();

        assertThat(storedReferences()).containsExactlyInAnyOrder("DUP-01", "DUP-02");
        assertThat(testData.count("SELECT count(*) FROM import_job WHERE supplier_id = ?", supplier)).isEqualTo(1);
    }

    @Test
    @DisplayName("A new file with known references updates drafts, skips identical lines, never duplicates")
    void knownReferencesInNewFile_updateDrafts_unchangedLinesSkipped() throws Exception {
        submit(HEADER + """
                REF-A,Original title,,PERCENTAGE,10,,2030-01-01,2030-01-31,false
                REF-B,Same content,,PERCENTAGE,20.00,,2030-01-01,2030-01-31,false
                """);
        runImports();

        // REF-A changed; ref-b identical (different case, 20 vs 20.00); REF-C new.
        UUID secondImport = submit(HEADER + """
                REF-A,New title,,PERCENTAGE,10,,2030-01-01,2030-01-31,false
                ref-b,Same content,,PERCENTAGE,20,,2030-01-01,2030-01-31,false
                REF-C,Brand new,,PERCENTAGE,30,,2030-01-01,2030-01-31,false
                """);
        runImports();

        mockMvc.perform(get("/api/imports/{id}", secondImport).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unchanged").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        assertThat(storedReferences()).containsExactlyInAnyOrder("REF-A", "REF-B", "REF-C");
        assertThat(testData.owner().queryForObject(
                "SELECT title FROM offer WHERE supplier_id = ? AND reference = 'REF-A'", String.class, supplier))
                .isEqualTo("New title");
    }

    @Test
    @DisplayName("BR-08: a line trying to change a PUBLISHED offer fails alone; identical lines are fine")
    void changingPublishedOffer_failsForThatLineOnly() throws Exception {
        submit(HEADER + """
                PUB-1,Live deal,,PERCENTAGE,10,,2020-01-01,2099-12-31,true
                PUB-2,Live deal 2,,PERCENTAGE,10,,2020-01-01,2099-12-31,true
                """);
        runImports();

        UUID secondImport = submit(HEADER + """
                PUB-1,Live deal,,PERCENTAGE,25,,2020-01-01,2099-12-31,true
                PUB-2,Live deal 2,,PERCENTAGE,10,,2020-01-01,2099-12-31,true
                """);
        runImports();

        mockMvc.perform(get("/api/imports/{id}", secondImport).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.unchanged").value(1));
        mockMvc.perform(get("/api/imports/{id}/errors", secondImport).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.items[0].lineNumber").value(2))
                .andExpect(jsonPath("$.items[0].code").value("OFFER_NOT_EDITABLE"));
    }

    @Test
    @DisplayName("The same reference twice in one file: the first occurrence wins, the second is reported")
    void duplicateReferenceInFile_firstOccurrenceWins() throws Exception {
        UUID importId = submit(HEADER + """
                TWICE,First version,,PERCENTAGE,10,,2030-01-01,2030-01-31,false
                OTHER,Other,,PERCENTAGE,10,,2030-01-01,2030-01-31,false
                twice,Second version,,PERCENTAGE,20,,2030-01-01,2030-01-31,false
                """);
        runImports();

        mockMvc.perform(get("/api/imports/{id}/errors", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].lineNumber").value(4))
                .andExpect(jsonPath("$.items[0].code").value("DUPLICATE_REFERENCE_IN_FILE"))
                .andExpect(jsonPath("$.items[0].message", containsString("line 2")));
        assertThat(testData.owner().queryForObject(
                "SELECT title FROM offer WHERE supplier_id = ? AND lower(reference) = 'twice'", String.class, supplier))
                .isEqualTo("First version");
    }

    @Test
    @DisplayName("A file with a broken header is rejected at once, without creating a job")
    void brokenHeader_rejectedImmediately() throws Exception {
        upload("ref,title,discount\nX,Y,10\n")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_INVALID_HEADER"))
                .andExpect(jsonPath("$.detail", containsString("reference")));

        assertThat(testData.count("SELECT count(*) FROM import_job WHERE supplier_id = ?", supplier)).isZero();
    }

    @Test
    @DisplayName("Excel-style files are accepted: UTF-8 BOM, semicolons, columns in another order, accents")
    void excelStyleFile_isAccepted() throws Exception {
        String byteOrderMark = String.valueOf((char) 0xFEFF);
        String csv = byteOrderMark + "valid_to;valid_from;discount_value;discount_type;title;reference\n"
                + "2030-01-31;2030-01-01;12.5;percentage;\"Crème brûlée \"\"L'Oréal\"\"\";FR-01\n";

        submit(csv);
        runImports();

        assertThat(testData.owner().queryForObject(
                "SELECT title FROM offer WHERE supplier_id = ? AND reference = 'FR-01'", String.class, supplier))
                .isEqualTo("Crème brûlée \"L'Oréal\"");
    }

    @Test
    @DisplayName("A file whose import failed technically can be sent again: the job is re-queued, not duplicated")
    void resendingAfterTechnicalFailure_requeuesTheSameJob() throws Exception {
        String csv = HEADER + "RETRY-1,Deal,,PERCENTAGE,10,,2030-01-01,2030-01-31,false\n";
        UUID importId = submit(csv);
        testData.owner().update("UPDATE import_job SET status = 'FAILED', attempts = 3 WHERE id = ?", importId);

        upload(csv)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(importId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
        runImports();

        assertThat(storedReferences()).containsExactly("RETRY-1");
    }

    @Test
    @DisplayName("The downloadable error report neutralises spreadsheet formulas (CSV injection)")
    void errorReportCsv_neutralisesFormulas() throws Exception {
        // A supplier system sends a reference that a spreadsheet would execute, on an invalid line.
        UUID importId = submit(HEADER + "=HYPERLINK(1),Deal,,PERCENTAGE,500,,2030-01-01,2030-01-31,false\n");
        runImports();

        mockMvc.perform(get("/api/imports/{id}/errors.csv", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("'=HYPERLINK(1)")));
    }

    @Test
    @DisplayName("A dry run tells what the import would do - including BR-08 against stored offers - and changes nothing")
    void dryRun_reportsWithoutChangingAnything() throws Exception {
        testData.publishedOffer(marketplace, supplier, "PUB-1");
        String csv = HEADER + """
                NEW-1,New deal,,PERCENTAGE,10,,2030-01-01,2030-01-31,false
                PUB-1,Other conditions,,PERCENTAGE,25,,2030-01-01,2030-01-31,false
                BAD-1,Too generous,,PERCENTAGE,150,,2030-01-01,2030-01-31,false
                """;

        mockMvc.perform(multipart("/api/imports/dry-run")
                        .file(new MockMultipartFile("file", "offers.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .with(asSupplier(marketplace, supplier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.wouldCreate").value(1))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.errorSummary.length()").value(2))
                .andExpect(jsonPath("$.problems[0].lineNumber").value(3))
                .andExpect(jsonPath("$.problems[0].code").value("OFFER_NOT_EDITABLE"))
                .andExpect(jsonPath("$.problems[1].code").value("DISCOUNT_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.problemsTruncated").value(false));

        assertThat(storedReferences()).containsExactly("PUB-1");
        assertThat(testData.count("SELECT count(*) FROM import_job WHERE supplier_id = ?", supplier)).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------

    private UUID submit(String csv) throws Exception {
        String body = upload(csv).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions upload(String csv) throws Exception {
        return mockMvc.perform(multipart("/api/imports")
                .file(new MockMultipartFile("file", "offers.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .with(asSupplier(marketplace, supplier)));
    }

    private List<String> storedReferences() {
        return testData.owner().queryForList("SELECT reference FROM offer WHERE supplier_id = ?", String.class, supplier);
    }
}
