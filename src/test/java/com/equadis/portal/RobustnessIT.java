package com.equadis.portal;

import com.equadis.portal.support.AbstractIntegrationTest;
import com.equadis.portal.support.TestData;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hostile or unusual input must stay a line error or a 4xx: it must never fail a whole import,
 * crash a worker, lose a notification or show a retailer an offer it may not see.
 */
class RobustnessIT extends AbstractIntegrationTest {

    private static final String HEADER = "reference,title,description,discount_type,discount_value,currency,valid_from,valid_to,publish\n";
    private static final String VALID_LINE = "GOOD-01,Good offer,,PERCENTAGE,10,,2030-01-01,2030-01-31,false\n";
    private static final String WEBHOOK_PATH = "/hooks/offers";

    @RegisterExtension
    static final WireMockExtension retailerSystem = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private UUID marketplace;
    private UUID supplier;

    @BeforeEach
    void supplierInItsMarketplace() {
        marketplace = testData.marketplace();
        supplier = testData.supplier(marketplace, "Nivea");
    }

    @Test
    @DisplayName("A discount written with a huge exponent is a line error, not a crash")
    void hugeExponentInDiscount_isALineError() throws Exception {
        UUID importId = submit(HEADER + "HUGE-01,Huge,,PERCENTAGE,1E+999999999,,2030-01-01,2030-01-31,false\n" + VALID_LINE);

        runImports();

        assertCompletedWithOneFailedLine(importId, "DISCOUNT_OUT_OF_RANGE");
    }

    @Test
    @DisplayName("A NUL character in a cell is a line error: PostgreSQL cannot store it, the other lines must not suffer")
    void nulCharacterInCell_isALineError() throws Exception {
        UUID importId = submit(HEADER + "NUL-01,Bad\u0000title,,PERCENTAGE,10,,2030-01-01,2030-01-31,false\n" + VALID_LINE);

        runImports();

        assertCompletedWithOneFailedLine(importId, "TITLE_INVALID_CHARACTERS");
    }

    @Test
    @DisplayName("A date PostgreSQL cannot store is a line error")
    void dateOutOfRange_isALineError() throws Exception {
        UUID importId = submit(HEADER + "OLD-01,Old,,PERCENTAGE,10,,-5000-01-01,2030-01-31,false\n" + VALID_LINE);

        runImports();

        assertCompletedWithOneFailedLine(importId, "DATE_OUT_OF_RANGE");
    }

    @Test
    @DisplayName("References are case-insensitive for accented letters too: re-sending one updates it")
    void accentedReferenceInOtherCase_isTheSameOffer() throws Exception {
        submit(HEADER + "CAFÉ-01,First title,,PERCENTAGE,10,,2030-01-01,2030-01-31,false\n");
        runImports();

        UUID secondImport = submit(HEADER + "café-01,Second title,,PERCENTAGE,10,,2030-01-01,2030-01-31,false\n");
        runImports();

        mockMvc.perform(get("/api/imports/{id}", secondImport).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.updated").value(1));
        assertThat(testData.count("SELECT count(*) FROM offer WHERE supplier_id = ?", supplier)).isEqualTo(1);
    }

    @Test
    @DisplayName("Java and PostgreSQL lower-case İ differently: re-sending such a reference must still find the offer")
    void referenceWithDottedCapitalI_isFoundWhenSentAgain() throws Exception {
        submit(HEADER + "İSTANBUL-01,First title,,PERCENTAGE,10,,2030-01-01,2030-01-31,false\n");
        runImports();

        UUID secondImport = submit(HEADER + "İSTANBUL-01,Second title,,PERCENTAGE,10,,2030-01-01,2030-01-31,false\n");
        runImports();

        mockMvc.perform(get("/api/imports/{id}", secondImport).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.updated").value(1));
    }

    @Test
    @DisplayName("A very long cell is reported, but only a short copy of it is stored")
    void veryLongCell_isShortenedInTheReport() throws Exception {
        String hugeTitle = "x".repeat(100_000);
        UUID importId = submit(HEADER + "LONG-01," + hugeTitle + ",,PERCENTAGE,10,,2030-01-01,2030-01-31,false\n" + VALID_LINE);

        runImports();

        assertCompletedWithOneFailedLine(importId, "TITLE_TOO_LONG");
        String storedTitle = testData.owner().queryForObject(
                "SELECT raw_record ->> 'title' FROM import_error WHERE import_job_id = ?", String.class, importId);
        assertThat(storedTitle).hasSizeLessThan(1000);
    }

    @Test
    @DisplayName("The last line is numbered correctly when the file has no final line break")
    void lastLineWithoutLineBreak_isNumberedCorrectly() throws Exception {
        UUID importId = submit(HEADER + VALID_LINE + "BAD-01,Bad,,PERCENTAGE,150,,2030-01-01,2030-01-31,false");

        runImports();

        mockMvc.perform(get("/api/imports/{id}/errors", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.items[0].lineNumber").value(3));
    }

    @Test
    @DisplayName("Reported line numbers match the file, even after blank lines")
    void blankLines_doNotShiftReportedLineNumbers() throws Exception {
        UUID importId = submit(HEADER + VALID_LINE + "\n\n" + "BAD-01,Bad,,PERCENTAGE,150,,2030-01-01,2030-01-31,false\n");

        runImports();

        mockMvc.perform(get("/api/imports/{id}/errors", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.items[0].lineNumber").value(5));
    }

    @Test
    @DisplayName("The API refuses a NUL character with a 422, not a 500")
    void nulCharacterThroughTheApi_isRefused() throws Exception {
        mockMvc.perform(post("/api/offers")
                        .with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(offerJson("NUL-API", "Bad\\u0000title", TestData.today())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("A request body over 1 MB is refused with 413 before it fills the memory")
    void oversizedRequestBody_isRefused() throws Exception {
        String hugeDescription = "x".repeat(2 * 1024 * 1024);

        mockMvc.perform(post("/api/offers")
                        .with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(offerJson("BIG-1", "Big", TestData.today()).replace("\"validFrom\"",
                                "\"description\": \"" + hugeDescription + "\", \"validFrom\"")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
    }

    @Test
    @DisplayName("An oversized search text is refused")
    void oversizedSearchText_isRefused() throws Exception {
        UUID retailer = testData.retailer(marketplace, "Shop", "shop@example.com");

        mockMvc.perform(get("/api/catalogue").param("q", "x".repeat(101)).with(asRetailer(marketplace, retailer)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A webhook answering with a redirect was not delivered: the offers stay due")
    void webhookRedirect_isNotASuccess() throws Exception {
        retailerSystem.stubFor(WireMock.post(urlEqualTo(WEBHOOK_PATH)).willReturn(aResponse().withStatus(301).withHeader("Location", "/moved")));
        UUID retailer = webhookPartner();

        publishViaApi("MOVED-1", TestData.today());
        runNotificationPipeline();

        assertThat(testData.owner().queryForObject(
                "SELECT status FROM retailer_notification WHERE retailer_id = ?", String.class, retailer)).isNotEqualTo("SENT");
        assertThat(testData.count("SELECT count(*) FROM retailer_notification_cursor WHERE retailer_id = ?", retailer)).isZero();
    }

    @Test
    @DisplayName("BR-10: a digest never reveals an offer whose validity has not started")
    void offerNotYetValid_isNotInTheDigest() throws Exception {
        retailerSystem.stubFor(WireMock.post(urlEqualTo(WEBHOOK_PATH)).willReturn(ok()));
        webhookPartner();

        publishViaApi("LATER-1", TestData.today().plusDays(10));
        runNotificationPipeline();

        assertThat(retailerSystem.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH)))).isEmpty();
    }

    private void assertCompletedWithOneFailedLine(UUID importId, String expectedCode) throws Exception {
        mockMvc.perform(get("/api/imports/{id}", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed").value(1));
        mockMvc.perform(get("/api/imports/{id}/errors", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.items[0].code").value(expectedCode));
    }

    private UUID webhookPartner() {
        UUID retailer = testData.retailer(marketplace, "BigRetail", null, retailerSystem.baseUrl() + WEBHOOK_PATH, "retailer-shared-secret");
        testData.activePartnership(marketplace, supplier, retailer);
        return retailer;
    }

    private UUID submit(String csv) throws Exception {
        String body = mockMvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("file", "offers.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .with(asSupplier(marketplace, supplier)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void publishViaApi(String reference, LocalDate validFrom) throws Exception {
        String body = mockMvc.perform(post("/api/offers")
                        .with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(offerJson(reference, "Offer " + reference, validFrom)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID offer = UUID.fromString(JsonPath.read(body, "$.id"));
        mockMvc.perform(post("/api/offers/{id}/publication", offer).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isOk());
    }

    private static String offerJson(String reference, String title, LocalDate validFrom) {
        return """
                {"reference": "%s", "title": "%s", "discountType": "PERCENTAGE", "discountValue": 15,
                 "validFrom": "%s", "validTo": "%s"}
                """.formatted(reference, title, validFrom, validFrom.plusDays(30));
    }
}
