package com.zack.linerelay.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private LinePushClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.line.me")
                .defaultHeader("Authorization", "Bearer test-token")
                .defaultHeader("Content-Type", "application/json");
        MockRestServiceServer.MockRestServiceServerBuilder serverBuilder =
                MockRestServiceServer.bindTo(builder);
        server = serverBuilder.build();
        client = new LinePushClient(builder.build());
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

        client.push("U123", "hello");
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

        client.push("U123", longText);
        server.verify();
    }

    @Test
    void pushFailsForBlankTarget() {
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

        client.multicast(List.of("U1", "U2", "U3"), "hi");
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

        client.multicast(targets, "hi");
        server.verify();
    }

    @Test
    void pushPropagatesHttpError() {
        server.expect(requestTo("https://api.line.me/v2/bot/message/push"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"message\":\"Invalid token\"}"));

        assertThrows(org.springframework.web.client.RestClientResponseException.class,
                () -> client.push("U123", "hi"));
        server.verify();
    }

    @Test
    void multicastFailsForEmptyTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> client.multicast(List.of(), "hi"));
        assertThrows(IllegalArgumentException.class,
                () -> client.multicast(null, "hi"));
    }
}
