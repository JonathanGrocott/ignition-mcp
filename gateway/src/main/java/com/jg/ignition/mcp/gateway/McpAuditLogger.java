package com.jg.ignition.mcp.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McpAuditLogger {

    private final Logger logger = LoggerFactory.getLogger(McpAuditLogger.class);

    public void logToolCall(String actor, String tool, long durationMs, boolean success, String detail) {
        logger.info(
            "mcp_tool_call actor={} tool={} durationMs={} success={} detail={}",
            sanitize(actor),
            tool,
            durationMs,
            success,
            sanitize(detail)
        );
    }

    public void logWriteAttempt(String actor, String tool, boolean committed, boolean allowed, String target) {
        logger.info(
            "mcp_write_attempt actor={} tool={} committed={} allowed={} target={}",
            sanitize(actor),
            tool,
            committed,
            allowed,
            sanitize(target)
        );
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
