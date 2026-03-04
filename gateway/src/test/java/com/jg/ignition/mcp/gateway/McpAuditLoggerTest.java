package com.jg.ignition.mcp.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAuditLoggerTest {

    @Test
    void snapshotTracksToolAndWriteMetrics() {
        McpAuditLogger logger = new McpAuditLogger();

        logger.logToolCall("tok-a", "ignition.tags.read", 20, true, "ok");
        logger.logToolCall("tok-a", "ignition.tags.read", 40, false, "failed");
        logger.logToolCall("tok-b", "ignition.projects.list", 10, true, "ok");
        logger.logWriteAttempt("tok-a", "ignition.tags.write", true, true, "[default]MCP/Setpoint");
        logger.logWriteAttempt("tok-a", "ignition.tags.write", true, false, "[default]BLOCKED/Setpoint");

        McpAuditLogger.ObservabilitySnapshot snapshot = logger.snapshot();
        assertEquals(3, snapshot.totalToolCalls());
        assertEquals(2, snapshot.successfulToolCalls());
        assertEquals(1, snapshot.failedToolCalls());
        assertEquals(2, snapshot.totalWriteAttempts());
        assertEquals(1, snapshot.allowedWriteAttempts());
        assertEquals(1, snapshot.blockedWriteAttempts());
        assertTrue(snapshot.averageToolDurationMs() > 0.0);
        assertFalse(snapshot.topTools().isEmpty());
        assertEquals("ignition.tags.read", snapshot.topTools().get(0).tool());
        assertFalse(snapshot.recentEvents().isEmpty());
    }
}
