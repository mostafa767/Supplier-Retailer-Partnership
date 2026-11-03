package com.equadis.portal.directory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Encrypts retailers' webhook secrets before they reach the database (AES-256-GCM).
 *
 * <p>A webhook secret cannot be hashed like a password: signing a webhook needs its original
 * value. Encrypting it means that a stolen backup, or a SQL injection, reveals nothing without
 * the key, which is held outside the database (a secrets manager in production).
 *
 * <p>Details:
 * <ul>
 *     <li>each encryption uses a new random IV, so equal secrets give different values;</li>
 *     <li>GCM also detects tampering: a modified value fails to decrypt;</li>
 *     <li>the retailer id is authenticated with the value, so a value copied onto another
 *         retailer's row fails to decrypt;</li>
 *     <li>values start with a format version, so a key rotation can tell old from new.</li>
 * </ul>
 *
 * <p>The application fails at startup when the key is missing or invalid, instead of failing
 * later on the first delivery.
 */
@Component
public class WebhookSecretCipher {

    private static final String FORMAT_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    /** @param base64Key 32 random bytes in Base64, e.g. from {@code openssl rand -base64 32} */
    public WebhookSecretCipher(@Value("${portal.webhooks.secret-encryption-key}") String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("portal.webhooks.secret-encryption-key must be Base64");
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("portal.webhooks.secret-encryption-key must be %d bytes, got %d"
                    .formatted(KEY_LENGTH_BYTES, keyBytes.length));
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** Used by retailer onboarding, which is outside this proof of concept, and by the tests. */
    public String encrypt(UUID retailerId, String secret) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, iv, retailerId).doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] ivAndEncrypted = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return FORMAT_PREFIX + Base64.getEncoder().encodeToString(ivAndEncrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt the webhook secret of retailer " + retailerId, e);
        }
    }

    /** @throws IllegalStateException when the value is corrupt, or was encrypted with another key */
    public String decrypt(UUID retailerId, String storedValue) {
        if (!storedValue.startsWith(FORMAT_PREFIX)) {
            throw new IllegalStateException("The webhook secret of retailer " + retailerId + " is not in a known format");
        }
        try {
            byte[] ivAndEncrypted = Base64.getDecoder().decode(storedValue.substring(FORMAT_PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(ivAndEncrypted);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, iv, retailerId).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException undecryptable) {
            // The cause is left out on purpose: it must not carry any part of the secret.
            throw new IllegalStateException("Cannot decrypt the webhook secret of retailer " + retailerId);
        }
    }

    private Cipher cipher(int mode, byte[] iv, UUID retailerId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        cipher.updateAAD(retailerId.toString().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
