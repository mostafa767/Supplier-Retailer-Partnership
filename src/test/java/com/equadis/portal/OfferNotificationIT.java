package com.equadis.portal;

import com.equadis.portal.support.AbstractIntegrationTest;
import com.equadis.portal.support.TestData;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R5 - when a supplier publishes offers, the retailers concerned - and only them - are
 * informed: by an e-mail digest, and by a signed webhook for retailers that have one.
 */
class OfferNotificationIT extends AbstractIntegrationTest {

    private static final String WEBHOOK_PATH = "/hooks/offers";
    private static final String WEBHOOK_SECRET = "retailer-shared-secret";

    @RegisterExtension
    static final GreenMailExtension mailbox = new GreenMailExtension(ServerSetupTest.SMTP).withPerMethodLifecycle(true);

    @RegisterExtension
    static final WireMockExtension retailerSystem = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private UUID marketplace;
    private UUID supplier;
    private UUID activePartner;
    private String partnerEmail;

    @BeforeEach
    void supplierWithAnActivePartner() {
        marketplace = testData.marketplace();
        supplier = testData.supplier(marketplace, "Nivea");
        partnerEmail = uniqueEmail("lac"); // unique per test: leftovers of other tests never match
        activePartner = testData.retailer(marketplace, "Pharmacie du Lac", partnerEmail);
        testData.activePartnership(marketplace, supplier, activePartner);
    }

    @Test
    @DisplayName("Publishing records the outbox event in the same transaction; a refused publication records none")
    void publication_andOutboxEvent_areAtomic() throws Exception {
        UUID offer = createDraftViaApi("ATOM-1");
        mockMvc.perform(post("/api/offers/{id}/publication", offer).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        assertThat(outboxEventsOfSupplier()).isEqualTo(1);

        UUID expired = createDraftViaApi("ATOM-2");
        testData.owner().update("UPDATE offer SET valid_from = ?, valid_to = ? WHERE id = ?",
                TestData.today().minusDays(10), TestData.today().minusDays(1), expired);
        mockMvc.perform(post("/api/offers/{id}/publication", expired).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        assertThat(outboxEventsOfSupplier()).isEqualTo(1); // still one: nothing was published, nothing announced
    }

    @Test
    @DisplayName("Only retailers with an ACTIVE partnership are e-mailed - not terminated, scheduled or other marketplaces")
    void onlyActivePartnersAreNotified() throws Exception {
        String formerEmail = uniqueEmail("former");
        String futureEmail = uniqueEmail("future");
        String elsewhereEmail = uniqueEmail("elsewhere");
        UUID terminatedPartner = testData.retailer(marketplace, "Former partner", formerEmail);
        UUID partnership = testData.activePartnership(marketplace, supplier, terminatedPartner);
        testData.owner().update("UPDATE partnership SET terminated_at = now() WHERE id = ?", partnership);

        UUID futurePartner = testData.retailer(marketplace, "Future partner", futureEmail);
        testData.partnership(marketplace, supplier, futurePartner, TestData.today().plusDays(5), TestData.today().plusMonths(6));

        UUID otherMarketplace = testData.marketplace();
        UUID otherSupplier = testData.supplier(otherMarketplace, "Nivea");
        UUID otherRetailer = testData.retailer(otherMarketplace, "Elsewhere", elsewhereEmail);
        testData.activePartnership(otherMarketplace, otherSupplier, otherRetailer);

        publishViaApi("SUN-01");
        runNotificationPipeline();

        List<MimeMessage> received = messagesTo(partnerEmail, formerEmail, futureEmail, elsewhereEmail);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getAllRecipients()[0].toString()).isEqualTo(partnerEmail);
        assertThat(received.get(0).getSubject()).isEqualTo("You have 1 new offer(s) from 1 supplier(s)");
        assertThat(GreenMailUtil.getBody(received.get(0))).contains("Offer SUN-01").contains("publishedSince=");
    }

    @Test
    @DisplayName("Many publications (import chunks + API) are coalesced into ONE digest per retailer")
    void manyPublications_coalesceIntoOneDigest() throws Exception {
        String csv = "reference,title,discount_type,discount_value,valid_from,valid_to,publish\n"
                + String.join("", List.of(1, 2, 3, 4, 5).stream()
                .map(i -> "BULK-%d,Bulk deal %d,PERCENTAGE,10,%s,%s,true\n".formatted(i, i, TestData.today(), TestData.today().plusDays(30)))
                .toList());
        mockMvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("file", "bulk.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .with(asSupplier(marketplace, supplier)))
                .andExpect(status().isAccepted());
        runImports();                 // 5 offers, chunk size 3 -> 2 outbox events
        publishViaApi("SINGLE-1");    // 1 more event

        while (outboxDispatcher.dispatchBatch() > 0) {
            // drain
        }
        assertThat(testData.count("SELECT count(*) FROM retailer_notification WHERE retailer_id = ? AND status = 'PENDING'", activePartner))
                .isEqualTo(1);

        notificationSender.sendDueBatch();
        List<MimeMessage> received = messagesTo(partnerEmail);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getSubject()).isEqualTo("You have 6 new offer(s) from 1 supplier(s)");
    }

    @Test
    @DisplayName("A partnership terminated between publication and sending: nothing leaks")
    void partnershipTerminatedBeforeSending_nothingSent() throws Exception {
        publishViaApi("LATE-1");
        while (outboxDispatcher.dispatchBatch() > 0) {
            // drain
        }
        testData.owner().update("UPDATE partnership SET terminated_at = now() WHERE retailer_id = ?", activePartner);

        notificationSender.sendDueBatch();

        assertThat(messagesTo(partnerEmail)).isEmpty();
        assertThat(testData.owner().queryForObject(
                "SELECT status FROM retailer_notification WHERE retailer_id = ?", String.class, activePartner))
                .isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("Large retailers receive a signed webhook they can verify with their secret")
    void webhook_isDeliveredAndSigned() throws Exception {
        retailerSystem.stubFor(WireMock.post(urlEqualTo(WEBHOOK_PATH)).willReturn(ok()));
        UUID bigRetailer = testData.retailer(marketplace, "BigRetail", null, retailerSystem.baseUrl() + WEBHOOK_PATH, WEBHOOK_SECRET);
        testData.activePartnership(marketplace, supplier, bigRetailer);

        publishViaApi("HOOK-1");
        runNotificationPipeline();

        List<LoggedRequest> calls = retailerSystem.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH)));
        assertThat(calls).hasSize(1);
        LoggedRequest call = calls.get(0);
        String body = call.getBodyAsString();

        // Verify exactly as the retailer would: HMAC-SHA256(secret, timestamp + "." + body).
        String[] signature = call.getHeader("X-Portal-Signature").split(",");
        String timestamp = signature[0].substring("t=".length());
        assertThat(signature[1]).isEqualTo("v1=" + hmacSha256(WEBHOOK_SECRET, timestamp + "." + body));

        assertThat(call.getHeader("X-Portal-Delivery-Id")).isNotBlank();
        assertThat((Integer) JsonPath.read(body, "$.totalNewOffers")).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.offers[0].reference")).isEqualTo("HOOK-1");
        assertThat(testData.count("SELECT count(*) FROM retailer_notification_cursor WHERE retailer_id = ?", bigRetailer)).isEqualTo(1);
    }

    @Test
    @DisplayName("A failing webhook is retried, then marked FAILED; the cursor does not move (offers not lost)")
    void failingWebhook_retriedThenFailed() throws Exception {
        retailerSystem.stubFor(WireMock.post(urlEqualTo(WEBHOOK_PATH)).willReturn(aResponse().withStatus(503)));
        UUID bigRetailer = testData.retailer(marketplace, "Down", null, retailerSystem.baseUrl() + WEBHOOK_PATH, WEBHOOK_SECRET);
        testData.activePartnership(marketplace, supplier, bigRetailer);

        publishViaApi("DOWN-1");
        runNotificationPipeline();                // attempt 1 -> 503 -> back to PENDING
        notificationSender.sendDueBatch();        // attempt 2 -> 503 -> max attempts (2 in tests) -> FAILED

        assertThat(retailerSystem.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH)))).hasSize(2);
        assertThat(testData.owner().queryForObject(
                "SELECT status FROM retailer_notification WHERE retailer_id = ?", String.class, bigRetailer)).isEqualTo("FAILED");
        assertThat(testData.count("SELECT count(*) FROM retailer_notification_cursor WHERE retailer_id = ?", bigRetailer)).isZero();
    }

    @Test
    @DisplayName("A webhook answering 4xx is not retried: the request itself is refused")
    void rejectedWebhook_failsWithoutRetry() throws Exception {
        retailerSystem.stubFor(WireMock.post(urlEqualTo(WEBHOOK_PATH)).willReturn(aResponse().withStatus(401)));
        UUID bigRetailer = testData.retailer(marketplace, "Misconfigured", null, retailerSystem.baseUrl() + WEBHOOK_PATH, WEBHOOK_SECRET);
        testData.activePartnership(marketplace, supplier, bigRetailer);

        publishViaApi("AUTH-1");
        runNotificationPipeline();

        assertThat(retailerSystem.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH)))).hasSize(1);
        assertThat(testData.owner().queryForObject(
                "SELECT status FROM retailer_notification WHERE retailer_id = ?", String.class, bigRetailer)).isEqualTo("FAILED");
    }

    // --- helpers ---------------------------------------------------------------------------

    private UUID createDraftViaApi(String reference) throws Exception {
        String body = mockMvc.perform(post("/api/offers")
                        .with(asSupplier(marketplace, supplier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "title": "Offer %s", "discountType": "PERCENTAGE", "discountValue": 15,
                                 "validFrom": "%s", "validTo": "%s"}
                                """.formatted(reference, reference, TestData.today(), TestData.today().plusDays(30))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void publishViaApi(String reference) throws Exception {
        UUID offer = createDraftViaApi(reference);
        mockMvc.perform(post("/api/offers/{id}/publication", offer).with(asSupplier(marketplace, supplier)))
                .andExpect(status().isOk());
    }

    private long outboxEventsOfSupplier() {
        return testData.count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'OFFERS_PUBLISHED'", supplier);
    }

    /** Messages received by any of these addresses (other tests' leftovers are ignored). */
    private static List<MimeMessage> messagesTo(String... addresses) {
        List<String> wanted = Arrays.asList(addresses);
        return Arrays.stream(mailbox.getReceivedMessages())
                .filter(message -> {
                    try {
                        return Arrays.stream(message.getRecipients(Message.RecipientType.TO))
                                .anyMatch(recipient -> wanted.contains(recipient.toString()));
                    } catch (jakarta.mail.MessagingException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String hmacSha256(String secret, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
