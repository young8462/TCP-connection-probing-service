package com.yueyou.tcpprobe.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeRateLimiterTest {

    private final AtomicLong clock = new AtomicLong();
    private final ProbeRateLimiter limiter = new ProbeRateLimiter(clock::get);

    @Test
    void permitsTenRequestsBeforeRejecting() {
        exhaustBucket();
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void replenishesOneTokenEveryTwoHundredMilliseconds() {
        exhaustBucket();
        advance(199);
        assertFalse(limiter.tryAcquire());
        advance(1);
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        advance(1000);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void capsCreditAfterIdlePeriods() {
        exhaustBucket();
        advance(60_000);
        exhaustBucket();
        assertFalse(limiter.tryAcquire());
    }

    private void exhaustBucket() {
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire());
        }
    }

    private void advance(long milliseconds) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(milliseconds));
    }
}
