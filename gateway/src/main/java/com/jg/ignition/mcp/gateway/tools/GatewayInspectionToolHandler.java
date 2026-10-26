package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.logging.Level;
import com.inductiveautomation.ignition.common.logging.LogQueryConfig;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Read-only Gateway SDK access. Does not query databases, browse OPC devices or expose connection configuration. */
public final class GatewayInspectionToolHandler implements ToolHandler {
    private final String name;
    public GatewayInspectionToolHandler(String name) { this.name = name; }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name, switch (name) {
            case "ignition.gateway.status" -> "Read Gateway lifecycle, JVM memory/uptime and redundancy status";
            case "ignition.gateway.modules" -> "List installed module identities, versions, lifecycle states and trial flags";
            case "ignition.gateway.connections" -> "Read database and OPC connection status without credentials or connection strings";
            case "ignition.gateway.logs" -> "Read a bounded time window of Gateway logs; operational messages may contain sensitive application data";
            default -> "Combine authorized Gateway status, modules and connections checks; reports coverage and partial failures, not a complete plant health assessment";
        }, PermissionRequirement.READ, schema(), false);
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        JsonNode args = arguments == null ? context.objectMapper().createObjectNode() : arguments;
        try {
            if (!args.isObject()) { throw new IllegalArgumentException("arguments must be an object"); }
            return switch (name) {
                case "ignition.gateway.status" -> status(context);
                case "ignition.gateway.modules" -> modules(args, context);
                case "ignition.gateway.connections" -> connections(args, context);
                case "ignition.gateway.logs" -> logs(args, context);
                case "ignition.gateway.diagnose" -> diagnose(args, context);
                default -> ToolExecutionResult.error("Unknown Gateway inspection tool");
            };
        } catch (Exception e) { return InspectionSupport.failure(e); }
    }

    private ObjectNode body(ToolCallContext context) {
        return context.objectMapper().createObjectNode().put("sampledAt", System.currentTimeMillis());
    }

    private ToolExecutionResult status(ToolCallContext context) {
        ObjectNode out = body(context);
        var state = context.gatewayContext().getState();
        out.put("state", state == null ? "UNKNOWN" : state.name());
        out.put("jvmUptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        Runtime runtime = Runtime.getRuntime();
        out.putObject("memory").put("usedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("committedBytes", runtime.totalMemory()).put("maxBytes", runtime.maxMemory());
        var redundancy = context.gatewayContext().getRedundancyManager();
        if (redundancy != null) {
            out.putObject("redundancy").put("available", true).put("enabled", redundancy.isRedundancyEnabled())
                .put("active", redundancy.isActive()).put("master", redundancy.isMaster());
        } else { out.putObject("redundancy").put("available", false); }
        out.put("complete", state != null && redundancy != null);
        return InspectionSupport.result("Gateway status sampled", out);
    }

    private ToolExecutionResult modules(JsonNode args, ToolCallContext context) {
        int maximum = InspectionSupport.limit(args, "maxResults", 200, 500);
        var manager = context.gatewayContext().getModuleManager();
        if (manager == null) { return ToolExecutionResult.error("SDK_UNAVAILABLE", "Module manager is unavailable"); }
        ObjectNode out = body(context);
        ArrayNode rows = out.putArray("modules");
        var modules = manager.getModules();
        for (var module : modules) {
            if (rows.size() >= maximum) { break; }
            var info = module.getInfo();
            ObjectNode row = rows.addObject();
            row.put("id", info.getId()).put("name", info.getName());
            row.put("version", info.getVersion() == null ? "" : info.getVersion().toString());
            var state = module.getState();
            row.put("state", state == null ? "UNKNOWN" : state.name());
            row.put("trial", info.isTrialMode());
            // Fault metadata can be populated even on an ACTIVE/INACTIVE module in 8.3.
            // The lifecycle state is authoritative; presence of a reason is not a fault predicate.
            row.put("faulted", state == com.inductiveautomation.ignition.gateway.model.ModuleState.FAULTED);
        }
        out.put("count", rows.size());
        out.put("truncated", modules.size() > rows.size());
        out.put("complete", modules.size() <= rows.size());
        return InspectionSupport.result("Gateway modules sampled", out);
    }

    private ToolExecutionResult connections(JsonNode args, ToolCallContext context) {
        int maximum = InspectionSupport.limit(args, "maxResults", 200, 500);
        ObjectNode out = body(context);
        ArrayNode databases = out.putArray("databases");
        ArrayNode opc = out.putArray("opc");
        boolean truncated = false;
        var datasources = context.gatewayContext().getDatasourceManager();
        out.put("databaseStatusAvailable", datasources != null);
        if (datasources != null) {
            var sources = datasources.getDatasources();
            for (var source : sources) {
                if (databases.size() >= maximum) { break; }
                ObjectNode row = databases.addObject().put("name", source.getName());
                row.put("status", source.getStatus() == null ? "UNKNOWN" : source.getStatus().name());
                row.put("activeConnections", source.getActiveConnections()).put("maxConnections", source.getMaxConnections());
            }
            truncated = sources.size() > databases.size();
        }
        var opcManager = context.gatewayContext().getOpcManager();
        out.put("opcStatusAvailable", opcManager != null);
        if (opcManager != null) {
            var configured = opcManager.getConnections();
            for (var item : configured) {
                if (opc.size() >= maximum) { break; }
                // The decoded resource is used only for its resource name, never configuration values.
                ObjectNode row = opc.addObject();
                row.put("name", item.resource().name());
                var connection = item.connection();
                row.put("status", connection == null || connection.getState() == null ? "UNKNOWN" : connection.getState().name());
            }
            truncated |= configured.size() > opc.size();
        }
        out.put("truncated", truncated);
        out.put("complete", datasources != null && opcManager != null && !truncated);
        return InspectionSupport.result("Gateway connection status sampled", out);
    }

    private ToolExecutionResult logs(JsonNode args, ToolCallContext context) {
        int limit = InspectionSupport.limit(args, "maxResults", 100, 500);
        int minutes = InspectionSupport.limit(args, "lastMinutes", 15, 1440);
        var manager = context.gatewayContext().getLoggingManager();
        if (manager == null) { return ToolExecutionResult.error("SDK_UNAVAILABLE", "Logging manager is unavailable"); }
        LogQueryConfig query = new LogQueryConfig();
        query.setLimit(limit + 1);
        long end = System.currentTimeMillis();
        query.setTimeRange(end - TimeUnit.MINUTES.toMillis(minutes), end);
        query.setMinLevel(Level.valueOf(args.path("minLevel").asText("INFO").toUpperCase(Locale.ROOT)));
        String logger = args.path("logger").asText("");
        String search = args.path("search").asText("");
        if (logger.length() > 256 || search.length() > 512) { throw new IllegalArgumentException("Log filter exceeds maximum length"); }
        if (!logger.isBlank()) { query.addAllowedLogger(logger); }
        if (!search.isBlank()) { query.addSearchTerm(search); }
        var result = manager.queryLogEvents(query);
        ObjectNode out = body(context);
        out.put("start", query.getStartTime()).put("end", query.getEndTime());
        ArrayNode rows = out.putArray("events");
        for (var event : result.getEvents()) {
            if (rows.size() == limit) { break; }
            ObjectNode row = rows.addObject().put("timestamp", event.getTimestamp()).put("logger", event.getLoggerName());
            row.put("level", event.getLevel() == null ? "UNKNOWN" : event.getLevel().name());
            String message = event.getMessage() == null ? "" : event.getMessage();
            row.put("message", message.substring(0, Math.min(message.length(), 4000)));
            row.put("messageTruncated", message.length() > 4000);
        }
        out.put("count", rows.size());
        out.put("truncated", result.getEvents().size() > limit);
        out.put("note", "Log messages can contain application data; stack traces and diagnostic properties are omitted");
        return InspectionSupport.result("Gateway log window queried", out);
    }

    private ToolExecutionResult diagnose(JsonNode args, ToolCallContext context) {
        ObjectNode out = body(context);
        ArrayNode steps = out.putArray("steps");
        ObjectNode results = out.putObject("checks");
        boolean complete = true;
        boolean degraded = false;
        boolean down = false;
        for (String suffix : List.of("status", "modules", "connections")) {
            String tool = "ignition.gateway." + suffix;
            ObjectNode step = steps.addObject().put("tool", tool);
            if (!context.safetyPolicy().isToolAllowed(context.authContext().tokenName(), tool, false)) {
                step.put("ok", false).put("code", "TOOL_NOT_AUTHORIZED");
                complete = false;
                continue;
            }
            ToolExecutionResult result = new GatewayInspectionToolHandler(tool).execute(args, context);
            step.put("ok", !result.isError());
            if (result.isError()) {
                step.put("code", result.structuredContent().path("code").asText("SDK_FAILURE"));
                complete = false;
                continue;
            }
            JsonNode data = result.structuredContent();
            results.set(suffix, data);
            complete &= data.path("complete").asBoolean(false);
            if (suffix.equals("status")) {
                String state = data.path("state").asText();
                down = state.equals("STOPPED") || state.equals("FAULTED");
                degraded |= !state.equals("RUNNING") && !state.equals("UNKNOWN");
            } else if (suffix.equals("modules")) {
                for (var module : data.path("modules")) {
                    String state = module.path("state").asText();
                    // Installed modules may be intentionally disabled. INACTIVE alone is not a failure.
                    degraded |= state.equals("FAULTED") || state.equals("PENDING");
                    if (state.equals("UNKNOWN")) { complete = false; }
                }
            } else {
                for (var db : data.path("databases")) {
                    String state = db.path("status").asText();
                    degraded |= !state.equals("VALID") && !state.equals("UNKNOWN");
                    if (state.equals("UNKNOWN")) { complete = false; }
                }
                for (var opc : data.path("opc")) {
                    String state = opc.path("status").asText();
                    degraded |= !state.equals("CONNECTED") && !state.equals("UNKNOWN");
                    if (state.equals("UNKNOWN")) { complete = false; }
                }
            }
        }
        out.put("complete", complete);
        out.put("verdict", down ? "down" : degraded ? "degraded" : complete ? "healthy" : "unknown");
        out.put("scope", "Gateway lifecycle, installed modules, database/OPC connections only; excludes device logic, project correctness, licensing and notification delivery");
        return InspectionSupport.result("Gateway diagnostic checks completed", out);
    }

    private ObjectNode schema() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("maxResults").put("type", "integer").put("minimum", 1).put("maximum", 500);
        props.putObject("lastMinutes").put("type", "integer").put("minimum", 1).put("maximum", 1440);
        for (String key : List.of("minLevel", "logger", "search")) { props.putObject(key).put("type", "string"); }
        return schema;
    }

}
