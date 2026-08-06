package com.zack.linerelay.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zack.linerelay.config.LineProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Mock HTTP tests for LINE request shape, push toggles, rate limiting, and
 * multicast batching.
 */
class LinePushClientTest {

    private MockRestServiceServer server;
    private RestClient.Builder builder;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        builder = RestClient.builder()
                .baseUrl("https://api.line.me")
                .defaultHeader("Authorization", "Bearer test-token")
                .defaultHeader("Content-Type", "application/json");
        MockRestServiceServer.MockRestServiceServerBuilder serverBuilder =
                MockRestServiceServer.bindTo(builder);
        server = serverBuilder.build();
    }

    private LinePushClient enabledClient() {
        LineProperties props = new LineProperties(
                "s", "t", null, new LineProperties.Push(true, true), null);
        return new LinePushClient(builder.build(), new PushModeService(props), new NoopPushRateLimiter());
    }

    private LinePushClient enabledClient(PushRateLimiter limiter) {
        LineProperties props = new LineProperties(
                "s", "t", null, new LineProperties.Push(true, true), null);
        return new LinePushClient(builder.build(), new PushModeService(props), limiter);
    }

    private LinePushClient disabledClient() {
        LineProperties props = new LineProperties(
                "s", "t", null, new LineProperties.Push(false, true), null);
        return new LinePushClient(builder.build(), new PushModeService(props), new NoopPushRateLimiter());
    }

    @Test
    void pushSendsCorrectPayload() throws Exception {
        server.expect(requestTo("https://api.line.me/v2/bot/message/push"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(request -> {
                    JsonNode body = mapper.readTree(request.getBody().toString());
                    assertEquals("U123", body.get("to").asText());
                    assertEquals("text", body.get("messages").get(0).get("type").asText());
                    assertEquals("hello", body.get("messages").get(0).get("text").asText());
                    return withSuccess().createResponse(request);
                });

        enabledClient().push("U123", "hello");
        server.verify();
    }

    @Test
    void pushTruncatesLongText() throws Exception {
        String longText = "a".repeat(6000);
        server.expect(requestTo("https://api.line.me/v2/bot/message/push"))
                .andRespond(request -> {
                    JsonNode body = mapper.readTree(request.getBody().toString());
                    assertEquals(5000, body.get("messages").get(0).get("text").asText().length());
                    return withSuccess().createResponse(request);
                });

        enabledClient().push("U123", longText);
        server.verify();
    }

    @Test
    void pushFailsForBlankTarget() {
        LinePushClient client = enabledClient();
        assertThrows(IllegalArgumentException.class, () -> client.push("", "hi"));
        assertThrows(IllegalArgumentException.class, () -> client.push(null, "hi"));
    }

    @Test
    void multicastSendsAllTargetsInOneBatchWhenUnderLimit() throws Exception {
        server.expect(requestTo("https://api.line.me/v2/bot/message/multicast"))
                .andRespond(request -> {
                    JsonNode body = mapper.readTree(request.getBody().toString());
                    assertEquals(3, body.get("to").size());
                    return withSuccess().createResponse(request);
                });

        enabledClient().multicast(List.of("U1", "U2", "U3"), "hi");
        server.verify();
    }

    @Test
    void multicastBatchesOver500Targets() {
        List<String> targets = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) {
            targets.add("U" + i);
        }
        server.expect(requestTo("https://api.line.me/v2/bot/message/multicast"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://api.line.me/v2/bot/message/multicast"))
                .andRespond(withSuccess());

        enabledClient().multicast(targets, "hi");
        server.verify();
    }

    @Test
    void pushPropagatesHttpError() {
        server.expect(requestTo("https://api.line.me/v2/bot/message/push"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"message\":\"Invalid token\"}"));

        LinePushClient client = enabledClient();
        assertThrows(org.springframework.web.client.RestClientResponseException.class,
                () -> client.push("U123", "hi"));
        server.verify();
    }

    @Test
    void multicastFailsForEmptyTargets() {
        LinePushClient client = enabledClient();
        assertThrows(IllegalArgumentException.class,
                () -> client.multicast(List.of(), "hi"));
        assertThrows(IllegalArgumentException.class,
                () -> client.multicast(null, "hi"));
    }

    @Test
    void pushSkippedWhenDisabled() {
        disabledClient().push("U123", "hello");
        server.verify();
    }

    @Test
    void pushIgnoringToggleSendsWhenDisabled() throws Exception {
        server.expect(requestTo("https://api.line.me/v2/bot/message/push"))
                .andRespond(request -> {
                    JsonNode body = mapper.readTree(request.getBody().toString());
                    assertEquals("U123", body.get("to").asText());
                    assertEquals("hello", body.get("messages").get(0).get("text").asText());
                    return withSuccess().createResponse(request);
                });

        disabledClient().pushIgnoringToggle("U123", "hello");
        server.verify();
    }

    @Test
    void multicastSkippedWhenDisabled() {
        disabledClient().multicast(List.of("U1", "U2"), "hello");
        server.verify();
    }

    @Test
    void pushSkippedWhenRateLimitExceeded() {
        PushRateLimiter limiter = mock(PushRateLimiter.class);
        org.mockito.Mockito.when(limiter.acquire(PushMessageType.PUBLIC_ANALYSIS, "U123"))
                .thenReturn(PushRateLimiter.Lease.denied(
                        PushMessageType.PUBLIC_ANALYSIS, "U123", "k", "2026-04-27", 2, 2));

        LinePushClient.PushAttempt result = enabledClient(limiter).push("U123", "hello");

        assertEquals(false, result.delivered());
        assertEquals(true, result.skippedByRateLimit());
        server.verify();
    }

    @Test
    void pushRollsBackQuotaWhenHttpFails() {
        PushRateLimiter limiter = mock(PushRateLimiter.class);
        PushRateLimiter.Lease lease = PushRateLimiter.Lease.allowed(
                PushMessageType.PUBLIC_ANALYSIS, "U123", "k", "2026-04-27", 1, 2);
        org.mockito.Mockito.when(limiter.acquire(PushMessageType.PUBLIC_ANALYSIS, "U123")).thenReturn(lease);
        server.expect(requestTo("https://api.line.me/v2/bot/message/push"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"message\":\"Invalid token\"}"));

        LinePushClient client = enabledClient(limiter);
        assertThrows(org.springframework.web.client.RestClientResponseException.class,
                () -> client.push("U123", "hello"));

        verify(limiter).rollback(lease);
        server.verify();
    }

    @Test
    void multicastFiltersRateLimitedTargets() throws Exception {
        PushRateLimiter limiter = mock(PushRateLimiter.class);
        org.mockito.Mockito.when(limiter.acquire(PushMessageType.PUBLIC_ANALYSIS, "U1"))
                .thenReturn(PushRateLimiter.Lease.allowed(
                        PushMessageType.PUBLIC_ANALYSIS, "U1", "k1", "2026-04-27", 1, 2));
        org.mockito.Mockito.when(limiter.acquire(PushMessageType.PUBLIC_ANALYSIS, "U2"))
                .thenReturn(PushRateLimiter.Lease.denied(
                        PushMessageType.PUBLIC_ANALYSIS, "U2", "k2", "2026-04-27", 2, 2));
        org.mockito.Mockito.when(limiter.acquire(PushMessageType.PUBLIC_ANALYSIS, "U3"))
                .thenReturn(PushRateLimiter.Lease.allowed(
                        PushMessageType.PUBLIC_ANALYSIS, "U3", "k3", "2026-04-27", 1, 2));
        server.expect(requestTo("https://api.line.me/v2/bot/message/multicast"))
                .andRespond(request -> {
                    JsonNode body = mapper.readTree(request.getBody().toString());
                    assertEquals(2, body.get("to").size());
                    assertEquals("U1", body.get("to").get(0).asText());
                    assertEquals("U3", body.get("to").get(1).asText());
                    return withSuccess().createResponse(request);
                });

        LinePushClient.MulticastAttempt result = enabledClient(limiter).multicast(List.of("U1", "U2", "U3"), "hi");

        assertEquals(2, result.delivered());
        assertEquals(1, result.skippedByRateLimit());
        server.verify();
    }

    @Test
    void isPushEnabledReflectsConfig() {
        assertEquals(true, enabledClient().isPushEnabled());
        assertEquals(false, disabledClient().isPushEnabled());
    }
}
