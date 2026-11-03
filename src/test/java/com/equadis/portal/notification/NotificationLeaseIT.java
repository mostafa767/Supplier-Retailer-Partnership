package com.equadis.portal.notification;

import com.equadis.portal.shared.work.ClaimedWork;
import com.equadis.portal.shared.work.WorkClaimRepository;
import com.equadis.portal.support.AbstractIntegrationTest;
import com.equadis.portal.support.TestData;
import com.equadis.portal.tenant.TenantContext;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A worker must never still be delivering when its lease expires: another worker would claim
 * the same notifications and retailers would receive them twice. The lease is shortened here,
 * so that one slow webhook is enough to use up the time available for a batch.
 */
@TestPropertySource(properties = {
        "portal.notifications.lease=PT3S",
        "portal.notifications.lease-safety-margin=PT2S"
})
class NotificationLeaseIT extends AbstractIntegrationTest {

    private static final String WEBHOOK_PATH = "/hooks/offers";
    private static final int SLOWER_THAN_TIME_AVAILABLE_MILLIS = 1500;

    @RegisterExtension
    static final WireMockExtension retailerSystem = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @Autowired
    private WorkClaimRepository workClaimRepository;

    private UUID marketplace;
    private UUID supplier;

    @BeforeEach
    void supplierAndSlowRetailerSystem() {
        marketplace = testData.marketplace();
        supplier = testData.supplier(marketplace, "Nivea");
        retailerSystem.stubFor(WireMock.post(urlEqualTo(WEBHOOK_PATH))
                .willReturn(ok().withFixedDelay(SLOWER_THAN_TIME_AVAILABLE_MILLIS)));
    }

    @Test
    @DisplayName("Slow deliveries: the rest of the batch is released without using an attempt, then sent exactly once")
    void slowDelivery_releasesTheRest_andNothingIsSentTwice() throws Exception {
        UUID first = webhookPartner("First");
        UUID second = webhookPartner("Second");
        publish("SLOW-1");
        drainOutbox();

        notificationSender.sendDueBatch();

        assertThat(deliveries()).hasSize(1);
        assertThat(testData.count("""
                SELECT count(*) FROM retailer_notification
                WHERE retailer_id IN (?, ?) AND status = 'PENDING' AND attempts = 0 AND locked_until IS NULL
                """, first, second)).isEqualTo(1);

        notificationSender.sendDueBatch();

        assertThat(deliveries()).hasSize(2);
        assertThat(deliveries().stream().map(call -> call.getHeader("X-Portal-Delivery-Id")).distinct()).hasSize(2);
        assertThat(testData.count("SELECT count(*) FROM retailer_notification WHERE retailer_id IN (?, ?) AND status = 'SENT'", first, second))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Releasing a notification when a newer one is pending supersedes it: the newer one carries the offers")
    void releaseWithNewerPendingNotification_supersedes() throws Exception {
        UUID retailer = webhookPartner("Busy");
        publish("BUSY-1");
        drainOutbox();
        List<ClaimedWork> claimed = workClaimRepository.claimRetailerNotifications(Instant.now(), Duration.ofMinutes(2), 20);

        publish("BUSY-2");
        drainOutbox(); // the claimed row is SENDING, so this creates a new PENDING one

        claimed.forEach(work -> TenantContext.runAs(work.marketplaceId(), () -> notificationSender.release(work)));

        assertThat(testData.owner().queryForList(
                "SELECT status FROM retailer_notification WHERE retailer_id = ? ORDER BY status", String.class, retailer))
                .containsExactly("PENDING", "SUPERSEDED");
    }

    private UUID webhookPartner(String name) {
        UUID retailer = testData.retailer(marketplace, name, null, retailerSystem.baseUrl() + WEBHOOK_PATH, "retailer-shared-secret");
        testData.activePartnership(marketplace, supplier, retailer);
        return retailer;
    }

    private List<LoggedRequest> deliveries() {
        return retailerSystem.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH)));
    }

    private void drainOutbox() {
        while (outboxDispatcher.dispatchBatch() > 0) {
            // drain
        }
    }

    private void publish(String reference) throws Exception {
        String body = mockMvc.perform(post("/api/offers")
                        .with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Offer %s", "discountType": "PERCENTAGE", "discountValue": 15,
                                 "validFrom": "%s", "validTo": "%s"}
                                """.formatted(reference, reference, TestData.today(), TestData.today().plusDays(30))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID offer = UUID.fromString(JsonPath.read(body, "$.id"));
        mockMvc.perform(post("/api/offers/{id}/publication", offer).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isOk());
    }
}
