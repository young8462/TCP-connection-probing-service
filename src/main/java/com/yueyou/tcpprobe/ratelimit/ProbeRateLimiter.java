package com.yueyou.tcpprobe.ratelimit;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
public class ProbeRateLimiter {

    private static final long TOKEN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long MAX_CREDIT_NANOS = 10 * TOKEN_INTERVAL_NANOS;

    private final LongSupplier clock;
    private long lastRefill;
    private long creditNanos = MAX_CREDIT_NANOS;

    public ProbeRateLimiter() {
        this(System::nanoTime);
    }

    ProbeRateLimiter(LongSupplier clock) {
        this.clock = clock;
        this.lastRefill = clock.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        long elapsed = now - lastRefill;
        creditNanos += Math.min(elapsed, MAX_CREDIT_NANOS - creditNanos);
        lastRefill = now;

        if (creditNanos < TOKEN_INTERVAL_NANOS) {
            return false;
        }
        creditNanos -= TOKEN_INTERVAL_NANOS;
        return true;
    }
}
