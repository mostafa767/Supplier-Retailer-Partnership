package com.equadis.portal.directory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSecretCipherTest {

    private static final WebhookSecretCipher CIPHER = new WebhookSecretCipher(keyOf('a'));
    private static final UUID RETAILER = UUID.randomUUID();

    @Test
    @DisplayName("What is stored never shows the secret, and decrypts back to it")
    void encryptThenDecrypt_returnsTheSecret() {
        String stored = CIPHER.encrypt(RETAILER, "retailer-shared-secret");

        assertThat(stored).startsWith("v1:").doesNotContain("retailer-shared-secret");
        assertThat(CIPHER.decrypt(RETAILER, stored)).isEqualTo("retailer-shared-secret");
    }

    @Test
    @DisplayName("The same secret encrypted twice looks different: the database reveals no equal secrets")
    void sameSecret_givesDifferentStoredValues() {
        assertThat(CIPHER.encrypt(RETAILER, "same")).isNotEqualTo(CIPHER.encrypt(RETAILER, "same"));
    }

    @Test
    @DisplayName("A value copied onto another retailer's row does not decrypt")
    void valueOfAnotherRetailer_isRefused() {
        String stored = CIPHER.encrypt(RETAILER, "secret");

        assertThatThrownBy(() -> CIPHER.decrypt(UUID.randomUUID(), stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A value encrypted with another key, or modified, does not decrypt")
    void wrongKeyOrTampering_isRefused() {
        String stored = CIPHER.encrypt(RETAILER, "secret");
        String tampered = stored.substring(0, stored.length() - 3) + "AAA";

        assertThatThrownBy(() -> new WebhookSecretCipher(keyOf('b')).decrypt(RETAILER, stored)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CIPHER.decrypt(RETAILER, tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CIPHER.decrypt(RETAILER, "plain-text-secret")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A key of the wrong size stops the application at startup")
    void invalidKey_isRefused() {
        assertThatThrownBy(() -> new WebhookSecretCipher("c2hvcnQ=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSecretCipher("not base64 !")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String keyOf(char filler) {
        return Base64.getEncoder().encodeToString(String.valueOf(filler).repeat(32).getBytes());
    }
}
