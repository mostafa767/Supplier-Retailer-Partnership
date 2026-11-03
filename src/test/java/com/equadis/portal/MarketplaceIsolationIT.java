package com.equadis.portal;

import com.equadis.portal.support.AbstractIntegrationTest;
import com.equadis.portal.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1 - "A marketplace, its suppliers and its retailers must never see another marketplace's
 * data". Proven at two levels: through the API, and directly in the database (Row-Level
 * Security protects us even from a query that forgets its WHERE clause).
 */
class MarketplaceIsolationIT extends AbstractIntegrationTest {

    @Autowired
    private DataSource applicationDataSource; // the real app pool: portal_app + TenantAwareDataSource

    private UUID marketplaceA;
    private UUID marketplaceB;
    private UUID supplierA;
    private UUID retailerA;
    private UUID supplierB;
    private UUID retailerB;
    private UUID offerA;
    private UUID offerB;

    @BeforeEach
    void twoMarketplacesWithTheSameShape() {
        marketplaceA = testData.marketplace();
        supplierA = testData.supplier(marketplaceA, "Nivea");
        retailerA = testData.retailer(marketplaceA, "Pharmacie du Lac", "lac@example.com");
        testData.activePartnership(marketplaceA, supplierA, retailerA);
        offerA = testData.publishedOffer(marketplaceA, supplierA, "A-OFFER");

        marketplaceB = testData.marketplace();
        supplierB = testData.supplier(marketplaceB, "Nivea"); // same name, different marketplace: allowed (BR-01 is per marketplace)
        retailerB = testData.retailer(marketplaceB, "Epicerie Martin", "martin@example.com");
        testData.activePartnership(marketplaceB, supplierB, retailerB);
        offerB = testData.publishedOffer(marketplaceB, supplierB, "B-OFFER");
    }

    @Test
    @DisplayName("A retailer of marketplace A sees A's offers and never B's")
    void retailerOfMarketplaceA_cannotSeeOffersOfMarketplaceB() throws Exception {
        mockMvc.perform(get("/api/catalogue").with(asRetailer(marketplaceA, retailerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].offerId").value(offerA.toString()));

        mockMvc.perform(get("/api/catalogue").with(asRetailer(marketplaceB, retailerB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].offerId").value(offerB.toString()));
    }

    @Test
    @DisplayName("Claiming to be in marketplace A does not give a retailer of B access to anything")
    void retailerUsingAnotherMarketplaceId_seesNothing() throws Exception {
        mockMvc.perform(get("/api/catalogue").with(asRetailer(marketplaceA, retailerB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("Row-Level Security filters even a query without any WHERE clause")
    void databaseHidesOtherMarketplaces_evenWithoutWhereClause() {
        JdbcTemplate app = new JdbcTemplate(applicationDataSource);

        long offersSeenByA = TenantContext.callAs(marketplaceA, () -> app.queryForObject("SELECT count(*) FROM offer", Long.class));
        long bOffersSeenByA = TenantContext.callAs(marketplaceA, () -> app.queryForObject(
                "SELECT count(*) FROM offer WHERE marketplace_id = ?", Long.class, marketplaceB));

        assertThat(offersSeenByA).isEqualTo(testData.count("SELECT count(*) FROM offer WHERE marketplace_id = ?", marketplaceA));
        assertThat(bOffersSeenByA).isZero();
    }

    @Test
    @DisplayName("Without a marketplace, the database returns nothing at all (fail closed)")
    void withoutMarketplaceContext_databaseReturnsNothing() {
        JdbcTemplate app = new JdbcTemplate(applicationDataSource);

        assertThat(testData.count("SELECT count(*) FROM offer")).isPositive();
        assertThat(app.queryForObject("SELECT count(*) FROM offer", Long.class)).isZero();
        assertThat(app.queryForObject("SELECT count(*) FROM supplier", Long.class)).isZero();
    }

    @Test
    @DisplayName("A row of marketplace B cannot be written while working for marketplace A")
    void writingIntoAnotherMarketplace_isRejectedByTheDatabase() {
        JdbcTemplate app = new JdbcTemplate(applicationDataSource);

        assertThatThrownBy(() -> TenantContext.runAs(marketplaceA, () -> app.update(
                "INSERT INTO supplier (id, marketplace_id, company_name) VALUES (?, ?, 'Intruder')", UUID.randomUUID(), marketplaceB)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("violates row-level security policy");
    }

    @Test
    @DisplayName("A supplier cannot read an import of another marketplace, even with its id")
    void supplierOfB_cannotReadImportOfA() throws Exception {
        String location = mockMvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", """
                                reference,title,discount_type,discount_value,valid_from,valid_to
                                A-1,Deal,PERCENTAGE,10,2030-01-01,2030-01-31
                                """.getBytes(StandardCharsets.UTF_8)))
                        .with(asSupplier(marketplaceA, supplierA)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location).with(asSupplier(marketplaceB, supplierB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("BR-01: supplier names are unique per marketplace, case-insensitively")
    void supplierNameUniquePerMarketplace_caseInsensitive() {
        assertThatThrownBy(() -> testData.supplier(marketplaceA, "NIVEA"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_supplier_name_per_marketplace");
    }
}
