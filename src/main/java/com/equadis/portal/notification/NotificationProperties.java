package com.equadis.portal.notification;

import com.equadis.portal.shared.work.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Notification tuning. The defaults favour "few, useful messages" over "instant but noisy".
 *
 * @param emailDigestDelay   how long news is collected before an e-mail digest is sent
 * @param webhookBatchDelay  same for webhooks - shorter: machines want data quickly, in batches
 * @param consistencyLag     a digest covers offers published up to (now - lag), so that offers
 *                           whose transaction is still committing are not skipped
 * @param lease              how long a claimed notification is reserved for this worker
 * @param leaseSafetyMargin  no delivery starts with less than this left on the lease; it must be
 *                           longer than the slowest delivery (see the SMTP and webhook timeouts)
 * @param batchSize          notifications claimed per claim; a worker claims again while there is work
 * @param maxOffersPerDigest offers listed in a webhook payload (the rest via the catalogue API)
 * @param emailHighlights    offers highlighted in the e-mail body
 * @param fromAddress        sender of digest e-mails
 * @param catalogueUrl       link to the retailer catalogue, put in e-mails and webhooks
 */
@ConfigurationProperties("portal.notifications")
public record NotificationProperties(@DefaultValue("PT1H") Duration emailDigestDelay,
                                     @DefaultValue("PT1M") Duration webhookBatchDelay,
                                     @DefaultValue("PT10S") Duration consistencyLag,
                                     @DefaultValue("PT2M") Duration lease,
                                     @DefaultValue("PT30S") Duration leaseSafetyMargin,
                                     @DefaultValue("20") int batchSize,
                                     @DefaultValue("5") int maxAttempts,
                                     @DefaultValue("PT30S") Duration initialBackoff,
                                     @DefaultValue("PT30M") Duration maxBackoff,
                                     @DefaultValue("100") int maxOffersPerDigest,
                                     @DefaultValue("5") int emailHighlights,
                                     @DefaultValue("no-reply@partnership-portal.local") String fromAddress,
                                     @DefaultValue("http://localhost:8080/api/catalogue") String catalogueUrl) {

    /** Without this check, every notification would be released and none ever sent. */
    public NotificationProperties {
        if (leaseSafetyMargin.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("lease-safety-margin (%s) must be shorter than lease (%s)"
                    .formatted(leaseSafetyMargin, lease));
        }
    }

    public RetryPolicy retryPolicy() {
        return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff);
    }

    /** A notification must never be due before the consistency lag has passed. */
    Duration dueDelay(Channel channel) {
        Duration channelDelay = channel == Channel.EMAIL ? emailDigestDelay : webhookBatchDelay;
        return channelDelay.compareTo(consistencyLag) >= 0 ? channelDelay : consistencyLag;
    }
}
