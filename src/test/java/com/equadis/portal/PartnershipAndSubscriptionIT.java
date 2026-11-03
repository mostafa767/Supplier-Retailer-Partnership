package com.equadis.portal;

import com.equadis.portal.partnership.PartnershipService;
import com.equadis.portal.support.AbstractIntegrationTest;
import com.equadis.portal.support.TestData;
import com.equadis.portal.tenant.Actor;
import com.equadis.portal.tenant.TenantContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R6 (end of a partnership), BR-04 (no overlap, even under concurrency), BR-06 (renewal),
 * BR-07 (termination cancels subscriptions), BR-10 (visibility) and BR-11 (one active
 * subscription).
 */
class PartnershipAndSubscriptionIT extends AbstractIntegrationTest {

    @Autowired
    private PartnershipService partnershipService;

    private UUID marketplace;
    private UUID supplier;
    private UUID retailer;
    private UUID partnership;
    private UUID offer;

    @BeforeEach
    void partnersWithOnePublishedOffer() {
        marketplace = testData.marketplace();
        supplier = testData.supplier(marketplace, "Danone");
        retailer = testData.retailer(marketplace, "Epicerie Martin", "martin@example.com");
        partnership = testData.activePartnership(marketplace, supplier, retailer);
        offer = testData.publishedOffer(marketplace, supplier, "YOG-1");
    }

    @Test
    @DisplayName("R6: terminating cancels the subscriptions and removes catalogue access immediately")
    void termination_cancelsSubscriptions_andRemovesAccess() throws Exception {
        mockMvc.perform(post("/api/offers/{id}/subscriptions", offer).with(asRetailer(marketplace, retailer)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/partnerships/{id}/termination", partnership).with(asAdmin(marketplace)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        assertThat(testData.owner().queryForObject(
                "SELECT status || ':' || cancellation_reason FROM subscription WHERE retailer_id = ?", String.class, retailer))
                .isEqualTo("CANCELLED:PARTNERSHIP_TERMINATED");
        mockMvc.perform(get("/api/catalogue").with(asRetailer(marketplace, retailer)))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(post("/api/offers/{id}/subscriptions", offer).with(asRetailer(marketplace, retailer)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("BR-11: subscribing twice (double click, retry) keeps exactly one active subscription")
    void subscribingTwice_isIdempotent() throws Exception {
        String first = mockMvc.perform(post("/api/offers/{id}/subscriptions", offer).with(asRetailer(marketplace, retailer)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/offers/{id}/subscriptions", offer).with(asRetailer(marketplace, retailer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(testData.count("SELECT count(*) FROM subscription WHERE retailer_id = ? AND status = 'ACTIVE'", retailer)).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-10: an offer of a supplier the retailer is not partnered with is 'not found'")
    void subscribingToInvisibleOffer_isNotFound() throws Exception {
        UUID stranger = testData.supplier(marketplace, "Stranger Foods");
        UUID strangerOffer = testData.publishedOffer(marketplace, stranger, "STR-1");

        mockMvc.perform(post("/api/offers/{id}/subscriptions", strangerOffer).with(asRetailer(marketplace, retailer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OFFER_NOT_FOUND"));
    }

    @Test
    @DisplayName("BR-04: an overlapping partnership is refused with a business error")
    void overlappingPartnership_isRefused() throws Exception {
        mockMvc.perform(post("/api/partnerships").with(asAdmin(marketplace))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(partnershipJson(TestData.today().plusMonths(2), TestData.today().plusMonths(20))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTNERSHIP_OVERLAP"))
                .andExpect(jsonPath("$.businessRule").value("BR-04"));
    }

    @Test
    @DisplayName("BR-04 under concurrency: two simultaneous creations, exactly one succeeds")
    void concurrentOverlappingCreations_onlyOneSucceeds() throws Exception {
        UUID newRetailer = testData.retailer(marketplace, "Race Shop", "race@example.com");
        Actor admin = new Actor(marketplace, Actor.Role.MARKETPLACE_ADMIN, UUID.randomUUID());
        LocalDate start = TestData.today().plusMonths(1);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Runnable attempt = () -> TenantContext.runAs(marketplace, () -> {
                try {
                    startTogether.await(5, TimeUnit.SECONDS);
                    partnershipService.create(admin, supplier, newRetailer, start, start.plusMonths(6));
                    successes.incrementAndGet();
                } catch (Exception refused) {
                    // expected for the loser: business pre-check or database exclusion constraint
                }
            });
            CompletableFuture.allOf(CompletableFuture.runAsync(attempt, threads), CompletableFuture.runAsync(attempt, threads))
                    .get(30, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(testData.count("SELECT count(*) FROM partnership WHERE retailer_id = ?", newRetailer)).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-03: a partnership longer than 36 months is refused")
    void partnershipLongerThan36Months_isRefused() throws Exception {
        UUID newRetailer = testData.retailer(marketplace, "Long Shop", "long@example.com");
        mockMvc.perform(post("/api/partnerships").with(asAdmin(marketplace))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId": "%s", "retailerId": "%s", "startDate": "2030-01-31", "endDate": "2033-02-01"}
                                """.formatted(supplier, newRetailer)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARTNERSHIP_INVALID_PERIOD"));
    }

    @Test
    @DisplayName("BR-06: an ACTIVE partnership is renewed without gap or overlap")
    void activePartnership_isRenewed() throws Exception {
        LocalDate currentEnd = TestData.today().plusMonths(11);

        mockMvc.perform(post("/api/partnerships/{id}/renewal", partnership).with(asAdmin(marketplace))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"%s\"}".formatted(currentEnd.plusMonths(12))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").value(currentEnd.plusDays(1).toString()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.renewedFromId").value(partnership.toString()));
    }

    @Test
    @DisplayName("R6: terminating a renewed partnership also terminates the renewal, so access never comes back")
    void termination_alsoTerminatesTheScheduledRenewal() throws Exception {
        LocalDate currentEnd = TestData.today().plusMonths(11);
        String renewal = mockMvc.perform(post("/api/partnerships/{id}/renewal", partnership).with(asAdmin(marketplace))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"%s\"}".formatted(currentEnd.plusMonths(12))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String renewalId = JsonPath.read(renewal, "$.id");

        mockMvc.perform(post("/api/partnerships/{id}/termination", partnership).with(asAdmin(marketplace)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        mockMvc.perform(get("/api/partnerships/{id}", renewalId).with(asAdmin(marketplace)))
                .andExpect(jsonPath("$.status").value("TERMINATED"));
        assertThat(testData.owner().queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'PARTNERSHIP_TERMINATED' AND entity_id = ?::uuid",
                Integer.class, renewalId))
                .isEqualTo(1);
    }

    private String partnershipJson(LocalDate start, LocalDate end) {
        return """
                {"supplierId": "%s", "retailerId": "%s", "startDate": "%s", "endDate": "%s"}
                """.formatted(supplier, retailer, start, end);
    }
}
