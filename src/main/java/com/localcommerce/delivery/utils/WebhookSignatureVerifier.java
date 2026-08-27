package com.localcommerce.delivery.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Validates the {@code X-Hub-Signature-256} header on incoming Meta/WhatsApp webhook POST
 * requests.
 *
 * <p>Meta computes the header value as {@code sha256=HMAC-SHA256(rawBody, appSecret)}.
 * Verifying this header on every inbound POST proves the request originated from Meta's
 * infrastructure, preventing forged payloads from triggering business logic.
 *
 * <p>Uses constant-time string comparison to prevent timing-based side-channel attacks.
 */
public final class WebhookSignatureVerifier {

    private WebhookSignatureVerifier() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Validates an {@code X-Hub-Signature-256} header value against a raw request body.
     *
     * @param rawBody         the raw UTF-8 request body string (must be the unmodified bytes
     *                        received from Meta, before any Jackson deserialization)
     * @param signatureHeader the full value of the {@code X-Hub-Signature-256} header,
     *                        expected in the format {@code sha256=<hex-digest>}
     * @param appSecret       the Meta App Secret (from Meta Developer Console → App → Settings
     *                        → Basic → App Secret)
     * @return {@code true} if the signature is valid and the request can be trusted;
     *         {@code false} if the header is absent, malformed, or the HMAC does not match
     */
    public static boolean isValid(String rawBody, String signatureHeader, String appSecret) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computed = "sha256=" + bytesToHex(hash);
            return constantTimeEquals(computed, signatureHeader);
        } catch (Exception e) {
            // Any crypto failure is treated as an invalid signature
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Compares two strings in constant time to prevent timing attacks.
     * Returns {@code true} only if both strings are identical.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
