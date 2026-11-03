package com.equadis.portal.shared.work;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(30), Duration.ofMinutes(5));

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.backoffAfter(5)).isEqualTo(Duration.ofMinutes(5));    // 8 min capped to 5
        assertThat(policy.backoffAfter(1_000)).isEqualTo(Duration.ofMinutes(5)); // no overflow
    }

    @Test
    void exhaustedAfterMaxAttempts() {
        assertThat(policy.isExhausted(4)).isFalse();
        assertThat(policy.isExhausted(5)).isTrue();
    }
}
