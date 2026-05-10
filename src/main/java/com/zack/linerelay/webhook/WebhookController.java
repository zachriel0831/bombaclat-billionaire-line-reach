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

/**
 * HTTP boundary for LINE webhook callbacks.
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier verifier;
    private final ObjectMapper objectMapper;
    private final WebhookEventProcessor processor;

    public WebhookController(SignatureVerifier verifier, ObjectMapper objectMapper, WebhookEventProcessor processor) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.processor = processor;
    }

    /**
     * Receives LINE webhook callbacks. Signature verification must happen on the
     * exact raw request body, so parsing is intentionally delayed until after
     * the HMAC check succeeds.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody byte[] rawBody
    ) {
        if (!verifier.verify(rawBody, signature)) {
            log.warn("Webhook signature verification failed (signature={}, body_bytes={})",
                    signature, rawBody == null ? 0 : rawBody.length);
            return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
        }

        WebhookEventProcessor.Summary summary;
        try {
            // The processor owns event semantics; the controller stays thin and
            // only handles HTTP/signature/JSON boundaries.
            JsonNode root = objectMapper.readTree(rawBody);
            summary = processor.process(root.path("events"));
        } catch (Exception e) {
            log.error("Failed to parse webhook body: {}", e.getMessage(), e);
            return ResponseEntity.status(400).body(Map.of("error", "invalid_body"));
        }

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "events", summary.events(),
                "users", summary.users(),
                "groups", summary.groups(),
                "commands", summary.commands()
        ));
    }
}
