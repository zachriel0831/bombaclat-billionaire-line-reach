package com.zack.linerelay.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Fallback limiter for environments where the Redis-backed limit is disabled.
 */
@Service
@ConditionalOnProperty(prefix = "line.push.rate-limit", name = "enabled", havingValue = "false", matchIfMissing = true)
class NoopPushRateLimiter implements PushRateLimiter {

    @Override
    public Lease acquire(PushMessageType type, String targetId) {
        return Lease.noop(type, targetId);
    }

    @Override
    public void rollback(Lease lease) {
        // No shared quota state to undo.
    }
}
