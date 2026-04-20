package com.zack.linerelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier verifier;
    private final ObjectMapper objectMapper;

    public WebhookController(SignatureVerifier verifier, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody byte[] rawBody
    ) {
        if (!verifier.verify(rawBody, signature)) {
            log.warn("Webhook signature verification failed (signature={}, body_bytes={})",
                    signature, rawBody == null ? 0 : rawBody.length);
            return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
        }

        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode events = root.path("events");
            int count = events.isArray() ? events.size() : 0;
            log.info("Received {} LINE event(s)", count);
            for (JsonNode event : events) {
                logEvent(event);
            }
        } catch (Exception e) {
            log.error("Failed to parse webhook body: {}", e.getMessage(), e);
            return ResponseEntity.status(400).body(Map.of("error", "invalid_body"));
        }

        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    private void logEvent(JsonNode event) {
        String type = event.path("type").asText("unknown");
        JsonNode source = event.path("source");
        String sourceType = source.path("type").asText("");
        String sourceId = switch (sourceType) {
            case "user" -> source.path("userId").asText("");
            case "group" -> source.path("groupId").asText("");
            case "room" -> source.path("roomId").asText("");
            default -> "";
        };

        switch (type) {
            case "message" -> {
                String messageType = event.path("message").path("type").asText("");
                String text = event.path("message").path("text").asText("");
                log.info("event=message source={}:{} msg_type={} text_preview={}",
                        sourceType, sourceId, messageType, preview(text));
            }
            case "follow" -> log.info("event=follow user={}", sourceId);
            case "unfollow" -> log.info("event=unfollow user={}", sourceId);
            case "join" -> log.info("event=join {}={}", sourceType, sourceId);
            case "leave" -> log.info("event=leave {}={}", sourceType, sourceId);
            case "memberJoined" -> log.info("event=memberJoined {}={} members={}",
                    sourceType, sourceId, event.path("joined").path("members"));
            case "memberLeft" -> log.info("event=memberLeft {}={} members={}",
                    sourceType, sourceId, event.path("left").path("members"));
            default -> log.info("event={} source={}:{} raw={}", type, sourceType, sourceId, event);
        }
    }

    private String preview(String text) {
        if (text == null) return "";
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
