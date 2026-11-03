package com.equadis.portal;

import com.equadis.portal.support.AbstractIntegrationTest;
import com.equadis.portal.support.TestData;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R7 - "we must be able to tell who changed what and when".
 */
class AuditTrailIT extends AbstractIntegrationTest {

    @Autowired
    private DataSource applicationDataSource;

    private UUID marketplace;
    private UUID supplier;

    @BeforeEach
    void supplierInItsMarketplace() {
        marketplace = testData.marketplace();
        supplier = testData.supplier(marketplace, "Nivea");
    }

    @Test
    @DisplayName("Every change of an offer is recorded: who, what (with before and after), when, and the request's correlation id")
    void offerChanges_areRecorded() throws Exception {
        UUID offer = createDraft("AUD-1", "First title");
        mockMvc.perform(put("/api/offers/{id}", offer)
                        .with(asSupplier(marketplace, supplier))
                        .header("X-Correlation-Id", "edit-request-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(offerJson("First title edited", 0)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/offers/{id}/publication", offer).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/audit-events").param("entityId", offer.toString()).with(asAdmin(marketplace)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", contains("OFFER_PUBLISHED", "OFFER_UPDATED", "OFFER_CREATED")))
                .andExpect(jsonPath("$[1].actorRole").value("SUPPLIER"))
                .andExpect(jsonPath("$[1].actorId").value(supplier.toString()))
                .andExpect(jsonPath("$[1].details.before.title").value("First title"))
                .andExpect(jsonPath("$[1].details.after.title").value("First title edited"))
                .andExpect(jsonPath("$[1].correlationId").value("edit-request-42"));
    }

    @Test
    @DisplayName("A refused change leaves no audit row: the change and its audit commit together, or not at all")
    void refusedChange_isNotRecorded() throws Exception {
        UUID offer = createDraft("AUD-2", "Title");
        mockMvc.perform(put("/api/offers/{id}", offer)
                        .with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(offerJson("Title", 99)))      // wrong version -> 409
                .andExpect(status().isConflict());

        assertThat(testData.count("SELECT count(*) FROM audit_event WHERE entity_id = ?", offer)).isEqualTo(1); // only OFFER_CREATED
    }

    @Test
    @DisplayName("Imports are recorded: the submission, then each chunk written, on behalf of the supplier")
    void imports_areRecorded() throws Exception {
        String csv = "reference,title,discount_type,discount_value,valid_from,valid_to\n"
                + "IMP-1,One,PERCENTAGE,10,2030-01-01,2030-01-31\n";
        String body = mockMvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("file", "offers.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .with(asSupplier(marketplace, supplier)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID importId = UUID.fromString(JsonPath.read(body, "$.id"));
        runImports();

        mockMvc.perform(get("/api/audit-events").param("entityId", importId.toString()).with(asAdmin(marketplace)))
                .andExpect(jsonPath("$[*].action", contains("OFFERS_IMPORTED", "IMPORT_SUBMITTED")))
                .andExpect(jsonPath("$[0].actorId").value(supplier.toString()))
                .andExpect(jsonPath("$[0].details.result.created").value(1));
    }

    @Test
    @DisplayName("Only administrators read the trail, and only their own marketplace's")
    void trail_isVisibleToAdministratorsOfTheMarketplaceOnly() throws Exception {
        UUID offer = createDraft("AUD-3", "Title");

        mockMvc.perform(get("/api/audit-events").param("entityId", offer.toString()).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-events").param("entityId", offer.toString()).with(asAdmin(testData.marketplace())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("The application cannot rewrite history: UPDATE and DELETE on the trail are refused by the database")
    void trail_isAppendOnly() {
        JdbcTemplate asApplication = new JdbcTemplate(applicationDataSource);

        // Spring wraps the driver error; PostgreSQL's own message is in the root cause.
        assertThatThrownBy(() -> asApplication.update("UPDATE audit_event SET action = 'OFFER_CLOSED'"))
                .rootCause().hasMessageContaining("permission denied for table audit_event");
        assertThatThrownBy(() -> asApplication.update("DELETE FROM audit_event"))
                .rootCause().hasMessageContaining("permission denied for table audit_event");
    }

    private UUID createDraft(String reference, String title) throws Exception {
        String body = mockMvc.perform(post("/api/offers")
                        .with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "%s", "discountType": "PERCENTAGE", "discountValue": 15,
                                 "validFrom": "%s", "validTo": "%s"}
                                """.formatted(reference, title, TestData.today(), TestData.today().plusDays(30))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static String offerJson(String title, long version) {
        return """
                {"version": %d, "title": "%s", "discountType": "PERCENTAGE", "discountValue": 15,
                 "validFrom": "%s", "validTo": "%s"}
                """.formatted(version, title, TestData.today(), TestData.today().plusDays(30));
    }
}
