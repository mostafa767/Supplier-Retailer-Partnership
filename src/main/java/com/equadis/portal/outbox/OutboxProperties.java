package com.equadis.portal.outbox;

import com.equadis.portal.shared.work.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param batchSize      events claimed per polling round
 * @param lease          how long a claimed event is reserved for this worker
 * @param maxAttempts    after that many failures the event is marked failed (and alerted on)
 * @param initialBackoff first retry delay, doubled at each failure
 * @param maxBackoff     cap for the retry delay
 */
@ConfigurationProperties("portal.outbox")
public record OutboxProperties(@DefaultValue("50") int batchSize,
                               @DefaultValue("PT1M") Duration lease,
                               @DefaultValue("5") int maxAttempts,
                               @DefaultValue("PT10S") Duration initialBackoff,
                               @DefaultValue("PT10M") Duration maxBackoff) {

    public RetryPolicy retryPolicy() {
        return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff);
    }
}
