package com.zack.linerelay.push;

import com.zack.linerelay.config.PushRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Redis-backed quota service without requiring a live Redis.
 */
class RedisPushRateLimiterTest {

    private PushRateLimitProperties props() {
        return new PushRateLimitProperties(true, 2, 2, 3, "Asia/Taipei", "line:test:push:rate-limit");
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-27T10:00:00Z"), ZoneId.of("Asia/Taipei"));
    }

    @Test
    void acquireAllowsFirstPushAndSetsExpiry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1")).thenReturn(1L);

        RedisPushRateLimiter limiter = new RedisPushRateLimiter(redis, props(), fixedClock());
        PushRateLimiter.Lease lease = limiter.acquire(PushMessageType.PUBLIC_ANALYSIS, "U1");

        assertTrue(lease.allowed());
        assertEquals(PushMessageType.PUBLIC_ANALYSIS, lease.type());
        assertEquals("2026-04-27", lease.businessDate());
        assertEquals(1L, lease.usedCount());
        verify(redis).expire(eq("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1"), any());
    }

    @Test
    void acquireDeniesThirdPushAndRollsBackOverflowIncrement() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1")).thenReturn(3L);

        RedisPushRateLimiter limiter = new RedisPushRateLimiter(redis, props(), fixedClock());
        PushRateLimiter.Lease lease = limiter.acquire(PushMessageType.PUBLIC_ANALYSIS, "U1");

        assertFalse(lease.allowed());
        assertEquals(2L, lease.usedCount());
        verify(ops).decrement("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1");
    }

    @Test
    void acquireAllowsMacroCalendarWithSeparateLimitAndKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("line:test:push:rate-limit:2026-04-27:MACRO_CALENDAR:U1")).thenReturn(1L);

        RedisPushRateLimiter limiter = new RedisPushRateLimiter(redis, props(), fixedClock());
        PushRateLimiter.Lease lease = limiter.acquire(PushMessageType.MACRO_CALENDAR, "U1");

        assertTrue(lease.allowed());
        assertEquals(PushMessageType.MACRO_CALENDAR, lease.type());
        assertEquals("line:test:push:rate-limit:2026-04-27:MACRO_CALENDAR:U1", lease.redisKey());
        assertEquals(3, lease.dailyLimit());
    }

    @Test
    void acquireMigratesLegacyPublicAnalysisCounterBeforeIncrementing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.hasKey("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1")).thenReturn(false);
        when(redis.hasKey("line:test:push:rate-limit:2026-04-27:U1")).thenReturn(true);
        when(ops.get("line:test:push:rate-limit:2026-04-27:U1")).thenReturn("1");
        when(ops.setIfAbsent(eq("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1"), eq("1"), any()))
                .thenReturn(true);
        when(ops.increment("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1")).thenReturn(2L);

        RedisPushRateLimiter limiter = new RedisPushRateLimiter(redis, props(), fixedClock());
        PushRateLimiter.Lease lease = limiter.acquire(PushMessageType.PUBLIC_ANALYSIS, "U1");

        assertTrue(lease.allowed());
        assertEquals(2L, lease.usedCount());
        verify(ops).setIfAbsent(eq("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1"), eq("1"), any());
    }

    @Test
    void rollbackDecrementsReservedQuota() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.decrement("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1")).thenReturn(0L);

        RedisPushRateLimiter limiter = new RedisPushRateLimiter(redis, props(), fixedClock());
        limiter.rollback(PushRateLimiter.Lease.allowed(PushMessageType.PUBLIC_ANALYSIS, "U1",
                "line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1", "2026-04-27", 1, 2));

        verify(ops).decrement("line:test:push:rate-limit:2026-04-27:PUBLIC_ANALYSIS:U1");
    }
}
