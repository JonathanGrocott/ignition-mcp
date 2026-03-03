package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.jg.ignition.mcp.common.GlobMatcher;

import java.util.List;
import java.util.function.Supplier;

public class SafetyPolicyEngine {

    private final Supplier<McpServerConfigResource> configSupplier;
    private final SlidingWindowRateLimiter requestRateLimiter;
    private final SlidingWindowRateLimiter writeRateLimiter;

    public SafetyPolicyEngine(Supplier<McpServerConfigResource> configSupplier) {
        this.configSupplier = configSupplier;
        this.requestRateLimiter = new SlidingWindowRateLimiter(60_000L);
        this.writeRateLimiter = new SlidingWindowRateLimiter(60_000L);
    }

    public boolean allowRequest(String tokenKey) {
        McpServerConfigResource config = configSupplier.get();
        return requestRateLimiter.tryAcquire(tokenKey, config.maxRequestsPerMinutePerToken());
    }

    public boolean allowWrite(String tokenKey) {
        McpServerConfigResource config = configSupplier.get();
        return writeRateLimiter.tryAcquire(tokenKey, config.maxWriteOpsPerMinutePerToken());
    }

    public boolean isTagReadAllowed(String tagPath) {
        return matchesAny(configSupplier.get().allowedTagReadPatterns(), tagPath);
    }

    public boolean isTagWriteAllowed(String tagPath) {
        return matchesAny(configSupplier.get().allowedTagWritePatterns(), tagPath);
    }

    public boolean isAlarmAckAllowed(String source) {
        return matchesAny(configSupplier.get().allowedAlarmAckSources(), source);
    }

    public boolean isDryRun(JsonNode arguments) {
        McpServerConfigResource config = configSupplier.get();
        if (arguments == null || !arguments.has("commit")) {
            return config.defaultDryRun();
        }
        return !arguments.path("commit").asBoolean(false);
    }

    public int maxBatchWriteSize() {
        return configSupplier.get().maxBatchWriteSize();
    }

    public int historianMaxRows() {
        return configSupplier.get().historianMaxRows();
    }

    public String historianDefaultProvider() {
        return configSupplier.get().historianDefaultProvider();
    }

    private boolean matchesAny(List<String> patterns, String value) {
        if (value == null) {
            return false;
        }
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (GlobMatcher.matches(pattern, value)) {
                return true;
            }
        }
        return false;
    }
}
