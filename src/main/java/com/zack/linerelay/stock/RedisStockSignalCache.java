package com.zack.linerelay.stock;

import com.zack.linerelay.config.StockSignalCacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "line.stock-signal-cache", name = "enabled", havingValue = "true", matchIfMissing = true)
class RedisStockSignalCache implements StockSignalCache {

    private static final Logger log = LoggerFactory.getLogger(RedisStockSignalCache.class);

    private final StringRedisTemplate redis;
    private final StockSignalCacheProperties props;

    RedisStockSignalCache(StringRedisTemplate redis, StockSignalCacheProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public Optional<String> get(String ticker) {
        String key = key(ticker);
        if (key.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(key);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (Exception ex) {
            log.warn("stock_signal_cache read_failed ticker={} reason={}", ticker, ex.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String ticker, String reply) {
        String key = key(ticker);
        if (key.isBlank() || reply == null || reply.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(key, reply, props.ttl());
            log.info("stock_signal_cache stored ticker={} ttl_seconds={}", ticker, props.ttl().toSeconds());
        } catch (Exception ex) {
            log.warn("stock_signal_cache write_failed ticker={} reason={}", ticker, ex.toString());
        }
    }

    private String key(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return "";
        }
        String normalized = ticker.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "");
        return normalized.isBlank() ? "" : props.keyPrefix() + ":" + normalized;
    }
}
