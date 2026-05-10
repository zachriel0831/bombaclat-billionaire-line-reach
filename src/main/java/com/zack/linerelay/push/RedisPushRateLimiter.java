package com.zack.linerelay.push;

import com.zack.linerelay.config.PushRateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Redis-backed daily push cap shared across all application instances.
 */
@Service
@ConditionalOnProperty(prefix = "line.push.rate-limit", name = "enabled", havingValue = "true")
class RedisPushRateLimiter implements PushRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisPushRateLimiter.class);
    private static final Duration KEY_RETENTION = Duration.ofDays(3);

    private final StringRedisTemplate redis;
    private final PushRateLimitProperties props;
    private final Clock clock;

    @Autowired
    RedisPushRateLimiter(StringRedisTemplate redis, PushRateLimitProperties props) {
        this(redis, props, Clock.system(ZoneId.of(props.zone())));
    }

    RedisPushRateLimiter(StringRedisTemplate redis, PushRateLimitProperties props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Lease acquire(PushMessageType type, String targetId) {
        PushMessageType messageType = type == null ? PushMessageType.PUBLIC_ANALYSIS : type;
        String businessDate = LocalDate.now(clock).toString();
        int dailyLimit = props.dailyLimitFor(messageType);
        String key = props.keyPrefix() + ":" + businessDate + ":" + messageType.name() + ":" + targetId;
        migrateLegacyPublicAnalysisCounterIfNeeded(messageType, businessDate, targetId, key);
        Long nextCount = redis.opsForValue().increment(key);
        if (nextCount == null) {
            throw new IllegalStateException("Redis increment returned null for key=" + key);
        }
        if (nextCount == 1L) {
            redis.expire(key, KEY_RETENTION);
        }
        if (nextCount > dailyLimit) {
            // Undo the overflow increment immediately so the stored counter
            // stays capped at the configured limit.
            redis.opsForValue().decrement(key);
            log.warn("push_rate_limit exceeded type={} target={} business_date={} limit={}",
                    messageType, targetId, businessDate, dailyLimit);
            return Lease.denied(messageType, targetId, key, businessDate, dailyLimit, dailyLimit);
        }
        return Lease.allowed(messageType, targetId, key, businessDate, nextCount, dailyLimit);
    }

    private void migrateLegacyPublicAnalysisCounterIfNeeded(
            PushMessageType type,
            String businessDate,
            String targetId,
            String typedKey
    ) {
        if (type != PushMessageType.PUBLIC_ANALYSIS) {
            return;
        }
        Boolean typedExists = redis.hasKey(typedKey);
        if (Boolean.TRUE.equals(typedExists)) {
            return;
        }
        String legacyKey = props.keyPrefix() + ":" + businessDate + ":" + targetId;
        Boolean legacyExists = redis.hasKey(legacyKey);
        if (!Boolean.TRUE.equals(legacyExists)) {
            return;
        }
        String legacyValue = redis.opsForValue().get(legacyKey);
        if (legacyValue == null || legacyValue.isBlank()) {
            return;
        }
        redis.opsForValue().setIfAbsent(typedKey, legacyValue, KEY_RETENTION);
        log.info("push_rate_limit migrated_legacy_counter type={} target={} business_date={}",
                type, targetId, businessDate);
    }

    @Override
    public void rollback(Lease lease) {
        if (lease == null || !lease.allowed() || lease.redisKey() == null || lease.redisKey().isBlank()) {
            return;
        }
        Long remaining = redis.opsForValue().decrement(lease.redisKey());
        log.warn("push_rate_limit rolled_back type={} target={} business_date={} remaining={}",
                lease.type(), lease.targetId(), lease.businessDate(), remaining);
    }
}
