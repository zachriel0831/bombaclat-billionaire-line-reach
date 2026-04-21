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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
                "s", "t", null, new LineProperties.Push(true), null);
        return new LinePushClient(builder.build(), props);
    }

    private LinePushClient disabledClient() {
        LineProperties props = new LineProperties(
                "s", "t", null, new LineProperties.Push(false), null);
        return new LinePushClient(builder.build(), props);
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
        // no server expectation set — if push attempts HTTP, MockRestServiceServer.verify() will fail
        disabledClient().push("U123", "hello");
        server.verify();
    }

    @Test
    void multicastSkippedWhenDisabled() {
        disabledClient().multicast(List.of("U1", "U2"), "hello");
        server.verify();
    }

    @Test
    void isPushEnabledReflectsConfig() {
        assertEquals(true, enabledClient().isPushEnabled());
        assertEquals(false, disabledClient().isPushEnabled());
    }
}
