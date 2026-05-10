package com.zack.linerelay.push;

/**
 * Shared quota guard for outbound LINE targets.
 */
public interface PushRateLimiter {

    Lease acquire(PushMessageType type, String targetId);

    void rollback(Lease lease);

    record Lease(
            boolean allowed,
            PushMessageType type,
            String targetId,
            String redisKey,
            String businessDate,
            long usedCount,
            int dailyLimit
    ) {
        static Lease allowed(PushMessageType type, String targetId, String redisKey, String businessDate, long usedCount, int dailyLimit) {
            return new Lease(true, type, targetId, redisKey, businessDate, usedCount, dailyLimit);
        }

        static Lease denied(PushMessageType type, String targetId, String redisKey, String businessDate, long usedCount, int dailyLimit) {
            return new Lease(false, type, targetId, redisKey, businessDate, usedCount, dailyLimit);
        }

        static Lease noop(PushMessageType type, String targetId) {
            return new Lease(true, type, targetId, null, null, 0, 0);
        }
    }
}
