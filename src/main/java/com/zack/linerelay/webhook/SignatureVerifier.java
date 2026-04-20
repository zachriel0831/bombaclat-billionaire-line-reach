package com.zack.linerelay.webhook;

import com.zack.linerelay.config.LineProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
public class SignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final byte[] channelSecretBytes;

    public SignatureVerifier(LineProperties props) {
        this.channelSecretBytes = props.channelSecret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(byte[] rawBody, String providedSignature) {
        if (providedSignature == null || providedSignature.isBlank() || rawBody == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(channelSecretBytes, HMAC_ALGO));
            byte[] digest = mac.doFinal(rawBody);
            String expected = Base64.getEncoder().encodeToString(digest);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    providedSignature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HMAC_ALGO + " not available", e);
        } catch (Exception e) {
            return false;
        }
    }
}
