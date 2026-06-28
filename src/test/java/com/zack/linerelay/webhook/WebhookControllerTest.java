package com.zack.linerelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.zack.linerelay.config.InMemoryRateLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web MVC slice tests for the HTTP boundary: signature failures should stop
 * before event processing, while valid requests delegate raw events.
 */
@WebMvcTest(controllers = WebhookController.class)
@Import(WebhookControllerTest.TestBeans.class)
class WebhookControllerTest {

    private static final String SECRET = "unit-test-secret";
    private static final String WEBHOOK_PATH = "/webhook";
    private static final String SIGNATURE_HEADER = "X-Line-Signature";
    private static final String EMPTY_EVENTS_BODY = "{\"events\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WebhookEventProcessor processor;

    @TestConfiguration
    static class TestBeans {
        @Bean
        SignatureVerifier signatureVerifier() {
            return new SignatureVerifier(new com.zack.linerelay.config.LineProperties(SECRET, "token", null, null, null));
        }

        @Bean
        InMemoryRateLimiter inMemoryRateLimiter() {
            return new InMemoryRateLimiter();
        }
    }

    @Test
    void rejectsMissingSignature() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_EVENTS_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_signature"));

        verify(processor, never()).process(any());
    }

    @Test
    void rejectsBadSignature() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, "not-a-real-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_EVENTS_BODY))
                .andExpect(status().isUnauthorized());

        verify(processor, never()).process(any());
    }

    @Test
    void acceptsValidSignatureAndDelegatesToProcessor() throws Exception {
        when(processor.process(any()))
                .thenReturn(new WebhookEventProcessor.Summary(1, 1, 0, 1, 0, 0, 0, 0));

        String body = "{\"events\":[{\"type\":\"message\",\"source\":{\"type\":\"user\",\"userId\":\"U123\"},"
                + "\"message\":{\"type\":\"text\",\"text\":\"hi\"}}]}";
        String signature = sign(SECRET, body.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.events").value(1))
                .andExpect(jsonPath("$.users").value(1));

        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(processor).process(captor.capture());
        JsonNode captured = captor.getValue();
        assertTrue(captured.isArray());
        assertEquals(1, captured.size());
    }

    @Test
    void acceptsEmptyEventList() throws Exception {
        when(processor.process(any()))
                .thenReturn(new WebhookEventProcessor.Summary(0, 0, 0, 0, 0, 0, 0, 0));

        String signature = sign(SECRET, EMPTY_EVENTS_BODY.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_EVENTS_BODY))
                .andExpect(status().isOk());
    }

    private static String sign(String secret, byte[] body) throws GeneralSecurityException {
        // Keep signatures realistic so controller tests catch raw-body mistakes.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}
