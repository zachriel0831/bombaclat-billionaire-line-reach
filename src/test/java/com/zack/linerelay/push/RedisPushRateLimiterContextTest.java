package com.zack.linerelay.push;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Verifies that enabling the Redis limiter creates the production limiter bean.
 */
@SpringBootTest(properties = {
        "line.channel-secret=test-secret",
        "line.channel-access-token=test-token",
        "line.mysql.enabled=false",
        "line.schedule.enabled=false",
        "line.push.rate-limit.enabled=true"
})
class RedisPushRateLimiterContextTest {

    @MockBean
    private StringRedisTemplate redis;

    @Autowired
    private PushRateLimiter limiter;

    @Test
    void contextCreatesRedisLimiterWhenEnabled() {
        assertInstanceOf(RedisPushRateLimiter.class, limiter);
    }
}
