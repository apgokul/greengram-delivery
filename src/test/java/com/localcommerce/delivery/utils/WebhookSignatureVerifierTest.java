package com.localcommerce.delivery.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureVerifierTest {

    private static final String DUMMY_SECRET = "test_secret_123";
    private static final String PAYLOAD = "{\"test\":\"payload\"}";

    private String generateSignature(String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(DUMMY_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return "sha256=" + sb.toString();
    }

    @Test
    void testValidSignature() throws Exception {
        assertTrue(WebhookSignatureVerifier.isValid(PAYLOAD, generateSignature(PAYLOAD), DUMMY_SECRET),
                "Valid signature should be accepted");
    }

    @Test
    void testInvalidSignature() {
        String invalidSignature = "sha256=b0e4395df3d0a6230bcf943c224fc8b9f1d07cda7e31b6982efc6f3938501258";
        assertFalse(WebhookSignatureVerifier.isValid(PAYLOAD, invalidSignature, DUMMY_SECRET),
                "Modified signature should be rejected");
    }

    @Test
    void testMissingPrefix() throws Exception {
        String noPrefix = generateSignature(PAYLOAD).replace("sha256=", "");
        assertFalse(WebhookSignatureVerifier.isValid(PAYLOAD, noPrefix, DUMMY_SECRET),
                "Signature without sha256= prefix should be rejected");
    }

    @Test
    void testModifiedPayload() throws Exception {
        String modifiedPayload = "{\"test\":\"payload_modified\"}";
        assertFalse(WebhookSignatureVerifier.isValid(modifiedPayload, generateSignature(PAYLOAD), DUMMY_SECRET),
                "Valid signature applied to modified payload should be rejected");
    }

    @Test
    void testWrongSecret() throws Exception {
        assertFalse(WebhookSignatureVerifier.isValid(PAYLOAD, generateSignature(PAYLOAD), "wrong_secret"),
                "Signature verified with wrong secret should be rejected");
    }

    @Test
    void testNullInputs() throws Exception {
        assertFalse(WebhookSignatureVerifier.isValid(null, generateSignature(PAYLOAD), DUMMY_SECRET));
        assertFalse(WebhookSignatureVerifier.isValid(PAYLOAD, null, DUMMY_SECRET));
        assertFalse(WebhookSignatureVerifier.isValid(PAYLOAD, generateSignature(PAYLOAD), null));
    }
}
