package com.jg.ignition.mcp.gateway;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowRateLimiterTest {

    @Test
    void enforcesLimitWithinWindowAndResetsAfterWindow() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1_000L, clock);

        assertTrue(limiter.tryAcquire("token-a", 2));
        assertTrue(limiter.tryAcquire("token-a", 2));
        assertFalse(limiter.tryAcquire("token-a", 2));

        clock.advanceMillis(1_001L);
        assertTrue(limiter.tryAcquire("token-a", 2));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }
    }
}
