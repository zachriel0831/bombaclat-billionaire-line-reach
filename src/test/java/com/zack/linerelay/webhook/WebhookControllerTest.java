package com.zack.linerelay.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WebhookController.class)
@Import(WebhookControllerTest.TestBeans.class)
class WebhookControllerTest {

    static final String SECRET = "unit-test-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestBeans {
        @Bean
        SignatureVerifier signatureVerifier() {
            return new SignatureVerifier(new com.zack.linerelay.config.LineProperties(SECRET, "token", null, null, null));
        }
    }

    @Test
    void rejectsMissingSignature() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_signature"));
    }

    @Test
    void rejectsBadSignature() throws Exception {
        mockMvc.perform(post("/webhook")
                        .header("X-Line-Signature", "not-a-real-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsValidSignature() throws Exception {
        String body = "{\"events\":[{\"type\":\"message\",\"source\":{\"type\":\"user\",\"userId\":\"U123\"},"
                + "\"message\":{\"type\":\"text\",\"text\":\"hi\"}}]}";
        String signature = sign(SECRET, body.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/webhook")
                        .header("X-Line-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void acceptsEmptyEventList() throws Exception {
        String body = "{\"events\":[]}";
        String signature = sign(SECRET, body.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/webhook")
                        .header("X-Line-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}
