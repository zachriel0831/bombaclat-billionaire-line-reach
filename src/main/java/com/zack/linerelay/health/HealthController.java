package com.zack.linerelay.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Minimal health endpoint that does not depend on LINE or MySQL.
 */
@RestController
public class HealthController {

    /**
     * Lightweight liveness check used by local smoke tests and external monitors.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "line-relay-service",
                "timestamp", Instant.now().toString()
        );
    }
}
