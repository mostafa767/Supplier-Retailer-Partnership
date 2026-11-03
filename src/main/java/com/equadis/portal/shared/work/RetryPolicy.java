package com.equadis.portal.shared.work;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded retries with exponential back-off: 30s, 1m, 2m, 4m... capped at {@code maxBackoff}.
 *
 * <p>Bounded, because a "poison" piece of work (a broken file, a webhook URL that will never
 * answer) must end up FAILED and visible - not retried forever, hiding other work.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
    }

    /** @param attemptsMade attempts already made, including the one that just failed */
    public boolean isExhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /** Delay before the next attempt, after {@code attemptsMade} failed attempts. */
    public Duration backoffAfter(int attemptsMade) {
        int exponent = Math.max(0, Math.min(attemptsMade - 1, 20)); // 2^20 is already far beyond any cap
        Duration delay = initialBackoff.multipliedBy(1L << exponent);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
