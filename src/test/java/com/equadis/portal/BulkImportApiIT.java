package com.equadis.portal;

import com.equadis.portal.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R3 through an API: "send offers from their own systems, as a file (CSV) or through an API,
 * with up to 100,000 offers at a time". Same guarantees as a CSV file.
 */
class BulkImportApiIT extends AbstractIntegrationTest {

    private static final String THREE_OFFERS = """
            {"offers": [
              {"reference": "API-1", "title": "Sun cream", "discountType": "PERCENTAGE", "discountValue": 15,
               "validFrom": "2030-01-01", "validTo": "2030-01-31", "publish": false},
              {"reference": "API-2", "title": "Too generous", "description": "Two\\nlines",
               "discountType": "PERCENTAGE", "discountValue": 150, "validFrom": "2030-01-01", "validTo": "2030-01-31"},
              {"reference": "API-3", "title": "Lotion", "discountType": "FIXED_AMOUNT", "discountValue": 5.50, "currency": "CHF",
               "validFrom": "2030-01-01", "validTo": "2030-01-31"}
            ]}
            """;

    private UUID marketplace;
    private UUID supplier;

    @BeforeEach
    void supplierInItsMarketplace() {
        marketplace = testData.marketplace();
        supplier = testData.supplier(marketplace, "Nivea");
    }

    @Test
    @DisplayName("Valid offers are imported; a wrong one is reported by its index in the array, with JSON field names")
    void partialErrors_reportedByItemIndex() throws Exception {
        UUID importId = submit(THREE_OFFERS);
        runImports();

        mockMvc.perform(get("/api/imports/{id}", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.format").value("JSON"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed").value(1));
        mockMvc.perform(get("/api/imports/{id}/errors", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].itemIndex").value(1))
                .andExpect(jsonPath("$.items[0].lineNumber").doesNotExist())
                .andExpect(jsonPath("$.items[0].field").value("discountValue"))
                .andExpect(jsonPath("$.items[0].code").value("DISCOUNT_OUT_OF_RANGE"));
        assertThat(testData.owner().queryForList("SELECT reference FROM offer WHERE supplier_id = ?", String.class, supplier))
                .containsExactlyInAnyOrder("API-1", "API-3");
    }

    @Test
    @DisplayName("The same request sent twice returns the first import and creates no duplicate")
    void sameRequestTwice_noDuplicates() throws Exception {
        UUID firstImport = submit(THREE_OFFERS);
        runImports();

        send(THREE_OFFERS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstImport.toString()))
                .andExpect(jsonPath("$.alreadySubmitted").value(true));
        assertThat(testData.count("SELECT count(*) FROM import_job WHERE supplier_id = ?", supplier)).isEqualTo(1);
    }

    @Test
    @DisplayName("A request that is not the expected JSON is refused at once, with a reason")
    void wrongShape_isRefused() throws Exception {
        send("{\"items\": []}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_UNREADABLE"));
        send("{\"offers\": [1, 2]}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_UNREADABLE"));
        send("{\"offers\": [ {\"reference\": ").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_UNREADABLE"));
        send("{\"offers\": []}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_EMPTY"));
    }

    @Test
    @DisplayName("Duplicate references inside one request: the first wins, the second points to it")
    void duplicateReferences_secondReported() throws Exception {
        UUID importId = submit("""
                {"offers": [
                  {"reference": "DUP", "title": "One", "discountType": "PERCENTAGE", "discountValue": 10,
                   "validFrom": "2030-01-01", "validTo": "2030-01-31"},
                  {"reference": "dup", "title": "Two", "discountType": "PERCENTAGE", "discountValue": 10,
                   "validFrom": "2030-01-01", "validTo": "2030-01-31"}
                ]}
                """);
        runImports();

        mockMvc.perform(get("/api/imports/{id}/errors", importId).with(asSupplier(marketplace, supplier)))
                .andExpect(jsonPath("$.items[0].itemIndex").value(1))
                .andExpect(jsonPath("$.items[0].code").value("DUPLICATE_REFERENCE_IN_FILE"))
                .andExpect(jsonPath("$.items[0].message").value(org.hamcrest.Matchers.containsString("item 0")));
    }

    @Test
    @DisplayName("A dry run of a JSON request reports by item index and stores nothing")
    void dryRun_reportsByItemIndex_andStoresNothing() throws Exception {
        mockMvc.perform(post("/api/imports/dry-run")
                        .with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THREE_OFFERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("JSON"))
                .andExpect(jsonPath("$.wouldCreate").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.problems[0].itemIndex").value(1))
                .andExpect(jsonPath("$.problems[0].field").value("discountValue"));

        assertThat(testData.count("SELECT count(*) FROM offer WHERE supplier_id = ?", supplier)).isZero();
        assertThat(testData.count("SELECT count(*) FROM import_job WHERE supplier_id = ?", supplier)).isZero();
    }

    private UUID submit(String json) throws Exception {
        String body = send(json).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions send(String json) throws Exception {
        return mockMvc.perform(post("/api/imports")
                .with(asSupplier(marketplace, supplier))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
