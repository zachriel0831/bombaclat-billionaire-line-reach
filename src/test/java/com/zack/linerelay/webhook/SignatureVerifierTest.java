package com.zack.linerelay.webhook;

import com.zack.linerelay.config.LineProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureVerifierTest {

    private static final String SECRET = "test-channel-secret";

    private final SignatureVerifier verifier = new SignatureVerifier(
            new LineProperties(SECRET, "dummy-token", null)
    );

    @Test
    void verify_returnsTrueForMatchingSignature() throws Exception {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(SECRET, body);

        assertTrue(verifier.verify(body, signature));
    }

    @Test
    void verify_returnsFalseForTamperedBody() throws Exception {
        byte[] original = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(SECRET, original);
        byte[] tampered = "{\"events\":[1]}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier.verify(tampered, signature));
    }

    @Test
    void verify_returnsFalseForWrongSecret() throws Exception {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        String signature = sign("different-secret", body);

        assertFalse(verifier.verify(body, signature));
    }

    @Test
    void verify_returnsFalseForNullSignature() {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier.verify(body, null));
    }

    @Test
    void verify_returnsFalseForBlankSignature() {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier.verify(body, "   "));
    }

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}
