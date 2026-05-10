package com.zack.linerelay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis cache settings for successful real-time stock model replies.
 */
@ConfigurationProperties(prefix = "line.stock-signal-cache")
public record StockSignalCacheProperties(
        boolean enabled,
        String keyPrefix,
        Duration ttl
) {
    public StockSignalCacheProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "line:stock-signal:cache";
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofHours(6);
        }
    }
}
