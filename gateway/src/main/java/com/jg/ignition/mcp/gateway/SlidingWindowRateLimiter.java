package com.jg.ignition.mcp.gateway;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SlidingWindowRateLimiter {

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long windowMillis;

    public SlidingWindowRateLimiter(long windowMillis) {
        this(windowMillis, Clock.systemUTC());
    }

    SlidingWindowRateLimiter(long windowMillis, Clock clock) {
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    public boolean tryAcquire(String key, int maxOperations) {
        if (maxOperations <= 0) {
            return false;
        }
        long now = clock.millis();
        Deque<Long> deque = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMillis) {
                deque.removeFirst();
            }
            if (deque.size() >= maxOperations) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }
}
