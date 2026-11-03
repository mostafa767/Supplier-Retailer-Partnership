package com.equadis.portal.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Webhook signing (authenticity) and URL policy (SSRF protection). */
class WebhookSecurityTest {

    private static final WebhookUrlPolicy PRODUCTION_POLICY =
            new WebhookUrlPolicy(new WebhookProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), false, false));

    @Test
    void signatureIsDeterministic_andBoundToSecretTimestampAndBody() {
        String signature = WebhookSigner.sign("secret", 1_700_000_000L, "{\"a\":1}");

        assertThat(signature).hasSize(64).isEqualTo(WebhookSigner.sign("secret", 1_700_000_000L, "{\"a\":1}"));
        assertThat(WebhookSigner.sign("other-secret", 1_700_000_000L, "{\"a\":1}")).isNotEqualTo(signature);
        assertThat(WebhookSigner.sign("secret", 1_700_000_001L, "{\"a\":1}")).isNotEqualTo(signature);
        assertThat(WebhookSigner.sign("secret", 1_700_000_000L, "{\"a\":2}")).isNotEqualTo(signature);
        assertThat(WebhookSigner.signatureHeader("secret", 1_700_000_000L, "{\"a\":1}")).isEqualTo("t=1700000000,v1=" + signature);
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {
            "http://example.com/hook",            // not HTTPS
            "https://127.0.0.1/hook",             // loopback
            "https://localhost:5432/",            // our own database port
            "https://10.0.0.12/hook",             // private network
            "https://192.168.1.1/hook",           // private network
            "https://169.254.169.254/latest/meta-data/", // cloud metadata endpoint
            "ftp://example.com/hook",
            "https://[fd00::1]/hook",             // IPv6 private network
            "https://100.64.0.1/hook",            // carrier-grade NAT, used inside some clouds
            "https://198.18.0.1/hook",            // benchmarking network
            "https://user:password@example.com/"  // credentials must not travel in a URL
    })
    void dangerousTargets_areRefused(String url) {
        assertThatThrownBy(() -> PRODUCTION_POLICY.validate(url)).isInstanceOf(PermanentDeliveryFailure.class);
    }
}
