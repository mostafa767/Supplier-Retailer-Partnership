package com.equadis.portal.notification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Signs webhook payloads so that retailers can verify that a call really comes from us and
 * was not altered: {@code HMAC-SHA256(secret, timestamp + "." + body)}.
 *
 * <p>The timestamp is part of the signed content, so a captured request cannot be replayed
 * later: receivers reject signatures older than a few minutes.
 *
 * <p>Header sent: {@code X-Portal-Signature: t=<unix seconds>,v1=<hex signature>}.
 */
final class WebhookSigner {

    static final String SIGNATURE_HEADER = "X-Portal-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    static String signatureHeader(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + sign(secret, timestampSeconds, body);
    }

    static String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] signature = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
