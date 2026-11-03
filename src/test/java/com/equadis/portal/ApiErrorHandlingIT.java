package com.equadis.portal;

import com.equadis.portal.support.AbstractIntegrationTest;
import com.equadis.portal.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The middleware and the error contract: every failure is a Problem Details JSON with a stable
 * code, the business rule when relevant, and the correlation id that links it to the logs.
 */
class ApiErrorHandlingIT extends AbstractIntegrationTest {

    private UUID marketplace;
    private UUID supplier;

    @BeforeEach
    void aSupplier() {
        marketplace = testData.marketplace();
        supplier = testData.supplier(marketplace, "Nivea");
    }

    @Test
    @DisplayName("No identity -> 401 MISSING_IDENTITY, with the correlation id in the header AND the body")
    void missingIdentity_isRejected_withCorrelationId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/catalogue"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_IDENTITY"))
                .andExpect(jsonPath("$.type").value("urn:equadis:portal:error:MISSING_IDENTITY"))
                .andExpect(jsonPath("$.instance").value("/api/catalogue"))
                .andReturn();

        String correlationId = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains(correlationId);
    }

    @Test
    @DisplayName("A caller-provided correlation id is reused, so a supplier can trace its own call")
    void providedCorrelationId_isEchoed() throws Exception {
        mockMvc.perform(get("/api/catalogue").header("X-Correlation-Id", "supplier-batch-42"))
                .andExpect(header().string("X-Correlation-Id", "supplier-batch-42"))
                .andExpect(jsonPath("$.correlationId").value("supplier-batch-42"));
    }

    @Test
    @DisplayName("Unreadable JSON -> 400 MALFORMED_REQUEST (Spring's built-in errors get the same shape)")
    void malformedJson_isRejected() throws Exception {
        mockMvc.perform(post("/api/offers").with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("Business rule broken -> 422 with the rule (BR-09) and EVERY violated field")
    void invalidOffer_reportsAllViolations() throws Exception {
        mockMvc.perform(post("/api/offers").with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "BAD-1", "title": "", "discountType": "FIXED_AMOUNT", "discountValue": -5,
                                 "validFrom": "2030-02-01", "validTo": "2030-01-01"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OFFER_INVALID_TERMS"))
                .andExpect(jsonPath("$.businessRule").value("BR-09"))
                .andExpect(jsonPath("$.violations[*].field", hasItem("title")))
                .andExpect(jsonPath("$.violations[*].field", hasItem("discountValue")))
                .andExpect(jsonPath("$.violations[*].field", hasItem("currency")))
                .andExpect(jsonPath("$.violations[*].code", hasItem("VALIDITY_WINDOW_INVALID")));
    }

    @Test
    @DisplayName("Wrong role -> 403 ROLE_NOT_ALLOWED")
    void wrongRole_isForbidden() throws Exception {
        mockMvc.perform(get("/api/catalogue").with(asSupplier(marketplace, supplier)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("Another supplier's offer -> 404, never 403: existence is not revealed")
    void otherSuppliersOffer_isNotFound() throws Exception {
        UUID competitor = testData.supplier(marketplace, "Competitor");
        UUID competitorOffer = testData.draftOffer(marketplace, competitor, "SECRET");

        mockMvc.perform(get("/api/offers/{id}", competitorOffer).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OFFER_NOT_FOUND"));
    }

    @Test
    @DisplayName("BR-08: editing a published offer -> 422 OFFER_NOT_EDITABLE")
    void editingPublishedOffer_isRefused() throws Exception {
        UUID published = testData.publishedOffer(marketplace, supplier, "LIVE-1");

        mockMvc.perform(put("/api/offers/{id}", published).with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(offerUpdateJson(0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OFFER_NOT_EDITABLE"))
                .andExpect(jsonPath("$.businessRule").value("BR-08"));
    }

    @Test
    @DisplayName("Stale version (someone edited in between) -> 409 CONCURRENT_MODIFICATION")
    void staleVersion_isRefused() throws Exception {
        UUID draft = testData.draftOffer(marketplace, supplier, "DRAFT-1");
        testData.owner().update("UPDATE offer SET version = 3 WHERE id = ?", draft);

        mockMvc.perform(put("/api/offers/{id}", draft).with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(offerUpdateJson(2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    private static String offerUpdateJson(long version) {
        return """
                {"version": %d, "title": "Changed", "discountType": "PERCENTAGE", "discountValue": 20,
                 "validFrom": "%s", "validTo": "%s"}
                """.formatted(version, TestData.today(), TestData.today().plusDays(10));
    }
}
