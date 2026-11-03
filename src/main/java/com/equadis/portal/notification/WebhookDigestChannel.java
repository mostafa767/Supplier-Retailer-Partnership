package com.equadis.portal.notification;

import com.equadis.portal.directory.RetailerContact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sends the digest to the retailer's own system: one signed, batched HTTP POST.
 *
 * <p>Failure handling:
 * <ul>
 *     <li>timeouts, connection errors, HTTP 5xx, 408, 429 -> retried with back-off;</li>
 *     <li>other HTTP 4xx, forbidden URL -> {@link PermanentDeliveryFailure} (retrying the same
 *         request cannot succeed);</li>
 *     <li>redirects are not followed for POST requests (no SSRF through a redirect), so an
 *         HTTP 3xx means "not delivered" -> {@link PermanentDeliveryFailure}: only a 2xx
 *         answer counts as a success.</li>
 * </ul>
 */
@Component
class WebhookDigestChannel implements NotificationChannel {

    static final String DELIVERY_ID_HEADER = "X-Portal-Delivery-Id";
    static final String EVENT_HEADER = "X-Portal-Event";
    static final String EVENT_NAME = "offers.published.digest";

    private final RestClient restClient;
    private final WebhookUrlPolicy urlPolicy;
    private final NotificationProperties notificationProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    WebhookDigestChannel(RestClient.Builder restClientBuilder, WebhookProperties webhookProperties,
                         NotificationProperties notificationProperties, ObjectMapper objectMapper, Clock clock) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(webhookProperties.connectTimeout());
        requestFactory.setReadTimeout(webhookProperties.readTimeout());
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.urlPolicy = new WebhookUrlPolicy(webhookProperties);
        this.notificationProperties = notificationProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public boolean isConfiguredFor(RetailerContact retailer) {
        return retailer.webhookUrl() != null && retailer.webhookSecret() != null;
    }

    @Override
    public void deliver(RetailerContact retailer, Digest digest, UUID deliveryId) {
        URI target = urlPolicy.validate(retailer.webhookUrl());
        String body = toJson(WebhookPayload.of(deliveryId, digest, notificationProperties.catalogueUrl()));
        long timestamp = clock.instant().getEpochSecond();
        try {
            HttpStatusCode status = restClient.post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.signatureHeader(retailer.webhookSecret(), timestamp, body))
                    .header(DELIVERY_ID_HEADER, deliveryId.toString())
                    .header(EVENT_HEADER, EVENT_NAME)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            if (!status.is2xxSuccessful()) {
                throw new PermanentDeliveryFailure("Webhook receiver answered HTTP " + status.value() + " instead of a 2xx");
            }
        } catch (RestClientResponseException rejected) {
            int status = rejected.getStatusCode().value();
            boolean retryable = rejected.getStatusCode().is5xxServerError() || status == 408 || status == 429;
            if (!retryable) {
                throw new PermanentDeliveryFailure("Webhook receiver answered HTTP " + status, rejected);
            }
            throw rejected;
        }
    }

    private String toJson(WebhookPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise webhook payload", e);
        }
    }

    /**
     * The JSON contract sent to retailers. {@code truncated} tells the receiver to fetch the
     * rest through the catalogue API ({@code catalogueUrl?publishedSince=windowFrom}).
     */
    record WebhookPayload(String event, UUID deliveryId, UUID retailerId, Instant windowFrom, Instant windowTo,
                          int totalNewOffers, boolean truncated, String catalogueUrl,
                          List<Digest.SupplierSummary> suppliers, List<Digest.OfferSummary> offers) {

        static WebhookPayload of(UUID deliveryId, Digest digest, String catalogueUrl) {
            return new WebhookPayload(EVENT_NAME, deliveryId, digest.retailerId(), digest.windowFrom(), digest.windowTo(),
                    digest.totalOffers(), digest.offers().size() < digest.totalOffers(), catalogueUrl,
                    digest.suppliers(), digest.offers());
        }
    }
}
