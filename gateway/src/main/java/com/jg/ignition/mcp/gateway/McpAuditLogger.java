package com.jg.ignition.mcp.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public class McpAuditLogger {

    private static final int RECENT_EVENT_LIMIT = 60;
    private static final int TOP_TOOL_LIMIT = 12;

    private final Logger logger = LoggerFactory.getLogger(McpAuditLogger.class);
    private final AtomicLong startedAtMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong totalToolCalls = new AtomicLong();
    private final AtomicLong successfulToolCalls = new AtomicLong();
    private final AtomicLong failedToolCalls = new AtomicLong();
    private final AtomicLong totalWriteAttempts = new AtomicLong();
    private final AtomicLong allowedWriteAttempts = new AtomicLong();
    private final AtomicLong blockedWriteAttempts = new AtomicLong();
    private final AtomicLong totalToolDurationMs = new AtomicLong();
    private final ConcurrentHashMap<String, ToolCounter> toolCounters = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<AuditEventSnapshot> recentEvents = new ConcurrentLinkedDeque<>();

    public void logToolCall(String actor, String tool, long durationMs, boolean success, String detail) {
        long boundedDurationMs = Math.max(0L, durationMs);
        totalToolCalls.incrementAndGet();
        totalToolDurationMs.addAndGet(boundedDurationMs);
        if (success) {
            successfulToolCalls.incrementAndGet();
        } else {
            failedToolCalls.incrementAndGet();
        }
        toolCounters.computeIfAbsent(tool == null ? "" : tool, key -> new ToolCounter())
            .recordCall(boundedDurationMs, success);
        appendEvent(new AuditEventSnapshot(
            System.currentTimeMillis(),
            "tool-call",
            sanitize(actor),
            tool,
            success,
            null,
            null,
            boundedDurationMs,
            sanitize(detail),
            null
        ));

        logger.info(
            "mcp_tool_call actor={} tool={} durationMs={} success={} detail={}",
            sanitize(actor),
            tool,
            boundedDurationMs,
            success,
            sanitize(detail)
        );
    }

    public void logWriteAttempt(String actor, String tool, boolean committed, boolean allowed, String target) {
        totalWriteAttempts.incrementAndGet();
        if (allowed) {
            allowedWriteAttempts.incrementAndGet();
        } else {
            blockedWriteAttempts.incrementAndGet();
        }
        appendEvent(new AuditEventSnapshot(
            System.currentTimeMillis(),
            "write-attempt",
            sanitize(actor),
            tool,
            allowed,
            committed,
            allowed,
            null,
            null,
            sanitize(target)
        ));

        logger.info(
            "mcp_write_attempt actor={} tool={} committed={} allowed={} target={}",
            sanitize(actor),
            tool,
            committed,
            allowed,
            sanitize(target)
        );
    }

    public ObservabilitySnapshot snapshot() {
        long sampledAtMs = System.currentTimeMillis();
        long calls = totalToolCalls.get();
        double averageDurationMs = calls == 0 ? 0.0 : ((double) totalToolDurationMs.get()) / calls;
        long successes = successfulToolCalls.get();
        long failures = failedToolCalls.get();
        long writes = totalWriteAttempts.get();
        long writesAllowed = allowedWriteAttempts.get();
        long writesBlocked = blockedWriteAttempts.get();

        List<ToolUsageSnapshot> topTools = toolCounters.entrySet().stream()
            .map(entry -> entry.getValue().snapshot(entry.getKey()))
            .sorted(
                Comparator.comparingLong(ToolUsageSnapshot::calls)
                    .reversed()
                    .thenComparing(ToolUsageSnapshot::tool, String.CASE_INSENSITIVE_ORDER)
            )
            .limit(TOP_TOOL_LIMIT)
            .toList();

        List<AuditEventSnapshot> events = recentEvents.stream()
            .limit(RECENT_EVENT_LIMIT)
            .toList();

        return new ObservabilitySnapshot(
            startedAtMs.get(),
            sampledAtMs,
            calls,
            successes,
            failures,
            writes,
            writesAllowed,
            writesBlocked,
            averageDurationMs,
            topTools,
            events
        );
    }

    private void appendEvent(AuditEventSnapshot event) {
        recentEvents.addFirst(event);
        while (recentEvents.size() > RECENT_EVENT_LIMIT) {
            recentEvents.pollLast();
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }

    public record ObservabilitySnapshot(
        long startedAtMs,
        long sampledAtMs,
        long totalToolCalls,
        long successfulToolCalls,
        long failedToolCalls,
        long totalWriteAttempts,
        long allowedWriteAttempts,
        long blockedWriteAttempts,
        double averageToolDurationMs,
        List<ToolUsageSnapshot> topTools,
        List<AuditEventSnapshot> recentEvents
    ) {
    }

    public record ToolUsageSnapshot(
        String tool,
        long calls,
        long successfulCalls,
        long failedCalls,
        double averageDurationMs,
        long maxDurationMs
    ) {
    }

    public record AuditEventSnapshot(
        long timestampMs,
        String eventType,
        String actor,
        String tool,
        Boolean success,
        Boolean committed,
        Boolean allowed,
        Long durationMs,
        String detail,
        String target
    ) {
    }

    private static final class ToolCounter {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong successfulCalls = new AtomicLong();
        private final AtomicLong failedCalls = new AtomicLong();
        private final AtomicLong totalDurationMs = new AtomicLong();
        private final AtomicLong maxDurationMs = new AtomicLong();

        private void recordCall(long durationMs, boolean success) {
            calls.incrementAndGet();
            totalDurationMs.addAndGet(durationMs);
            maxDurationMs.accumulateAndGet(durationMs, Math::max);
            if (success) {
                successfulCalls.incrementAndGet();
            } else {
                failedCalls.incrementAndGet();
            }
        }

        private ToolUsageSnapshot snapshot(String tool) {
            long callCount = calls.get();
            double averageMs = callCount == 0 ? 0.0 : ((double) totalDurationMs.get()) / callCount;
            return new ToolUsageSnapshot(
                tool,
                callCount,
                successfulCalls.get(),
                failedCalls.get(),
                averageMs,
                maxDurationMs.get()
            );
        }
    }
}
