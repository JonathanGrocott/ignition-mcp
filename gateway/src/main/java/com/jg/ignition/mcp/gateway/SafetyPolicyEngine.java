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
        return isTagReadAllowed(null, tagPath);
    }

    public boolean isTagReadAllowed(String tokenName, String tagPath) {
        McpServerConfigResource config = configSupplier.get();
        return matchesAny(config.allowedTagReadPatterns(), tagPath)
            || matchesAny(profilePatterns(config, tokenName, ProfilePatternKind.TAG_READ), tagPath);
    }

    public boolean isTagWriteAllowed(String tagPath) {
        return isTagWriteAllowed(null, tagPath);
    }

    public boolean isTagWriteAllowed(String tokenName, String tagPath) {
        McpServerConfigResource config = configSupplier.get();
        return matchesAny(config.allowedTagWritePatterns(), tagPath)
            || matchesAny(profilePatterns(config, tokenName, ProfilePatternKind.TAG_WRITE), tagPath);
    }

    public boolean isToolAllowed(String tokenName, String toolName, boolean mutating) {
        McpServerConfigResource config = configSupplier.get();
        List<String> patterns = mutating ? config.allowedWriteToolPatterns() : config.allowedReadToolPatterns();
        List<String> profilePatterns = profilePatterns(
            config,
            tokenName,
            mutating ? ProfilePatternKind.WRITE_TOOL : ProfilePatternKind.READ_TOOL
        );
        return matchesAny(patterns, toolName)
            || matchesAny(patterns, actorToolValue(tokenName, toolName))
            || matchesAny(profilePatterns, toolName);
    }

    public boolean isAlarmAckAllowed(String source) {
        return isAlarmAckAllowed(null, source);
    }

    public boolean isAlarmAckAllowed(String tokenName, String source) {
        McpServerConfigResource config = configSupplier.get();
        return matchesAny(config.allowedAlarmAckSources(), source)
            || matchesAny(profilePatterns(config, tokenName, ProfilePatternKind.ALARM_ACK), source);
    }

    public boolean isNamedQueryExecuteAllowed(String projectName, String queryPath) {
        return isNamedQueryExecuteAllowed(null, projectName, queryPath);
    }

    public boolean isNamedQueryExecuteAllowed(String tokenName, String projectName, String queryPath) {
        McpServerConfigResource config = configSupplier.get();
        String fullPath = projectScopedPath(projectName, queryPath);
        List<String> profilePatterns = profilePatterns(config, tokenName, ProfilePatternKind.NAMED_QUERY_EXECUTE);
        return matchesAny(config.allowedNamedQueryExecutePatterns(), fullPath)
            || matchesAny(config.allowedNamedQueryExecutePatterns(), queryPath)
            || matchesAny(profilePatterns, fullPath)
            || matchesAny(profilePatterns, queryPath);
    }

    public boolean isNamedQueryWriteAllowed(String projectName, String queryPath) {
        return isNamedQueryWriteAllowed(null, projectName, queryPath);
    }

    public boolean isNamedQueryWriteAllowed(String tokenName, String projectName, String queryPath) {
        McpServerConfigResource config = configSupplier.get();
        String fullPath = projectScopedPath(projectName, queryPath);
        List<String> profilePatterns = profilePatterns(config, tokenName, ProfilePatternKind.NAMED_QUERY_WRITE);
        return matchesAny(config.allowedNamedQueryWritePatterns(), fullPath)
            || matchesAny(config.allowedNamedQueryWritePatterns(), queryPath)
            || matchesAny(profilePatterns, fullPath)
            || matchesAny(profilePatterns, queryPath);
    }

    public boolean isProjectResourceReadAllowed(String projectName, String moduleId, String typeId, String resourcePath) {
        return isProjectResourceReadAllowed(null, projectName, moduleId, typeId, resourcePath);
    }

    public boolean isProjectResourceReadAllowed(
        String tokenName,
        String projectName,
        String moduleId,
        String typeId,
        String resourcePath
    ) {
        McpServerConfigResource config = configSupplier.get();
        String fullPath = projectResourcePath(projectName, moduleId, typeId, resourcePath);
        return matchesAny(config.allowedProjectResourceReadPatterns(), fullPath)
            || matchesAny(profilePatterns(config, tokenName, ProfilePatternKind.PROJECT_RESOURCE_READ), fullPath);
    }

    public boolean isProjectScriptWriteAllowed(String projectName, String scriptPath) {
        return isProjectScriptWriteAllowed(null, projectName, scriptPath);
    }

    public boolean isProjectScriptWriteAllowed(String tokenName, String projectName, String scriptPath) {
        McpServerConfigResource config = configSupplier.get();
        String fullPath = projectScopedPath(projectName, scriptPath);
        List<String> profilePatterns = profilePatterns(config, tokenName, ProfilePatternKind.PROJECT_SCRIPT_WRITE);
        return matchesAny(config.allowedProjectScriptWritePatterns(), fullPath)
            || matchesAny(config.allowedProjectScriptWritePatterns(), scriptPath)
            || matchesAny(profilePatterns, fullPath)
            || matchesAny(profilePatterns, scriptPath);
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

    public int namedQueryMaxRows() {
        return configSupplier.get().namedQueryMaxRows();
    }

    public static String projectScopedPath(String projectName, String path) {
        return (projectName == null ? "" : projectName.trim())
            + "/"
            + (path == null ? "" : path.trim());
    }

    public static String projectResourcePath(String projectName, String moduleId, String typeId, String resourcePath) {
        return (projectName == null ? "" : projectName.trim())
            + "/"
            + (moduleId == null ? "" : moduleId.trim())
            + "/"
            + (typeId == null ? "" : typeId.trim())
            + "/"
            + (resourcePath == null ? "" : resourcePath.trim());
    }

    private static String actorToolValue(String tokenName, String toolName) {
        return (tokenName == null ? "" : tokenName.trim()) + "/" + (toolName == null ? "" : toolName.trim());
    }

    private static List<String> profilePatterns(
        McpServerConfigResource config,
        String tokenName,
        ProfilePatternKind kind
    ) {
        if (config.authorizationProfiles() == null || config.authorizationProfiles().isEmpty()) {
            return List.of();
        }
        List<String> patterns = new java.util.ArrayList<>();
        for (McpServerConfigResource.AuthorizationProfile profile : config.authorizationProfiles()) {
            if (profile == null || !matchesAnyStatic(profile.tokenPatterns(), tokenName)) {
                continue;
            }
            switch (kind) {
                case READ_TOOL -> patterns.addAll(profile.allowedReadToolPatterns());
                case WRITE_TOOL -> patterns.addAll(profile.allowedWriteToolPatterns());
                case TAG_READ -> patterns.addAll(profile.allowedTagReadPatterns());
                case TAG_WRITE -> patterns.addAll(profile.allowedTagWritePatterns());
                case ALARM_ACK -> patterns.addAll(profile.allowedAlarmAckSources());
                case NAMED_QUERY_EXECUTE -> patterns.addAll(profile.allowedNamedQueryExecutePatterns());
                case PROJECT_RESOURCE_READ -> patterns.addAll(profile.allowedProjectResourceReadPatterns());
                case PROJECT_SCRIPT_WRITE -> patterns.addAll(profile.allowedProjectScriptWritePatterns());
                case NAMED_QUERY_WRITE -> patterns.addAll(profile.allowedNamedQueryWritePatterns());
            }
        }
        return patterns;
    }

    private static boolean matchesAnyStatic(List<String> patterns, String value) {
        if (value == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (GlobMatcher.matches(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAny(List<String> patterns, String value) {
        return matchesAnyStatic(patterns, value);
    }

    private enum ProfilePatternKind {
        READ_TOOL,
        WRITE_TOOL,
        TAG_READ,
        TAG_WRITE,
        ALARM_ACK,
        NAMED_QUERY_EXECUTE,
        PROJECT_RESOURCE_READ,
        PROJECT_SCRIPT_WRITE,
        NAMED_QUERY_WRITE
    }
}
