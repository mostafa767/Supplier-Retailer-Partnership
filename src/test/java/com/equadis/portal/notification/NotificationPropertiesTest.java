package com.equadis.portal.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationPropertiesTest {

    @Test
    @DisplayName("A safety margin as long as the lease is refused at startup: nothing would ever be sent")
    void safetyMarginMustBeShorterThanLease() {
        assertThatThrownBy(() -> withLease(Duration.ofMinutes(2), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-safety-margin");
        assertThatCode(() -> withLease(Duration.ofMinutes(2), Duration.ofSeconds(30))).doesNotThrowAnyException();
    }

    private static NotificationProperties withLease(Duration lease, Duration safetyMargin) {
        return new NotificationProperties(Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofSeconds(10),
                lease, safetyMargin, 20, 5, Duration.ofSeconds(30), Duration.ofMinutes(30), 100, 5,
                "no-reply@partnership-portal.local", "http://localhost:8080/api/catalogue");
    }
}
