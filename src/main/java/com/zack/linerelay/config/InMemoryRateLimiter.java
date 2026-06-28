package com.zack.linerelay.config;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryRateLimiter {

    private final Clock clock;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public InMemoryRateLimiter() {
        this(Clock.systemUTC());
    }

    InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean tryAcquire(String bucket, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long currentMinute = Instant.now(clock).getEpochSecond() / 60;
        Counter counter = counters.computeIfAbsent(bucket, ignored -> new Counter(currentMinute));
        synchronized (counter) {
            if (counter.minute != currentMinute) {
                counter.minute = currentMinute;
                counter.count = 0;
            }
            if (counter.count >= limitPerMinute) {
                return false;
            }
            counter.count++;
            return true;
        }
    }

    private static final class Counter {
        private long minute;
        private int count;

        private Counter(long minute) {
            this.minute = minute;
        }
    }
}

