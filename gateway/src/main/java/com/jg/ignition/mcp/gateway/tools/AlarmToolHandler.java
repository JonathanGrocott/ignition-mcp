package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.alarming.AlarmFilterBuilder;
import com.inductiveautomation.ignition.common.alarming.AlarmState;
import com.inductiveautomation.ignition.common.alarming.EventData;
import com.inductiveautomation.ignition.common.alarming.config.CommonAlarmProperties;
import com.inductiveautomation.ignition.common.alarming.query.AlarmQueryResult;
import com.inductiveautomation.ignition.gateway.alarming.AlarmManager;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class AlarmToolHandler implements ToolHandler {

    private final String toolName;
    private final ToolDefinition definition;

    public AlarmToolHandler(String toolName) {
        this.toolName = toolName;
        this.definition = new ToolDefinition(
            toolName,
            description(toolName),
            toolName.endsWith("acknowledge") ? PermissionRequirement.WRITE : PermissionRequirement.READ,
            inputSchema(toolName),
            toolName.endsWith("acknowledge")
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        if (toolName.endsWith("list")) {
            return list(arguments, context);
        }
        return acknowledge(arguments, context);
    }

    private ToolExecutionResult list(JsonNode arguments, ToolCallContext context) {
        String state = arguments == null ? "active" : arguments.path("state").asText("active").trim();
        String source = arguments == null ? "*" : arguments.path("source").asText("*").trim();
        int maxResults = arguments == null ? 200 : arguments.path("maxResults").asInt(200);
        maxResults = Math.max(1, Math.min(maxResults, 2_000));

        AlarmFilterBuilder filter = new AlarmFilterBuilder().allowShelved();
        if (StringUtils.isNotBlank(source) && !"*".equals(source)) {
            filter.sourceOrDisplayPath(new String[] {source});
        }
        if (!applyStateFilter(filter, state)) {
            return ToolExecutionResult.error("Unsupported alarm state filter: " + state);
        }

        AlarmManager alarmManager = context.gatewayContext().getAlarmManager();
        AlarmQueryResult queryResult = alarmManager.queryStatus(filter.build());

        ObjectNode result = context.objectMapper().createObjectNode();
        result.put("state", StringUtils.defaultIfBlank(state, "active"));
        result.put("sourceFilter", StringUtils.defaultIfBlank(source, "*"));
        result.put("matched", queryResult.size());

        ArrayNode alarms = result.putArray("alarms");
        for (AlarmEvent event : queryResult) {
            if (alarms.size() >= maxResults) {
                break;
            }
            ObjectNode item = alarms.addObject();
            item.put("id", event.getId().toString());
            item.put("source", event.getSource() == null ? "" : event.getSource().toString());
            item.put("displayPath", event.getDisplayPathOrSource());
            item.put("name", event.getName());
            item.put("label", event.getLabel());
            item.put("priority", event.getPriority() == null ? "" : event.getPriority().name());
            item.put("state", event.getState() == null ? "" : event.getState().name());
            item.put("acked", event.isAcked());
            item.put("cleared", event.isCleared());
            item.put("shelved", event.isShelved());
            item.put("notes", StringUtils.defaultString(event.getNotes()));

            Date eventTime = event.get(CommonAlarmProperties.EventTime);
            if (eventTime != null) {
                item.put("eventTime", eventTime.getTime());
            }
            else {
                item.putNull("eventTime");
            }
            String ackUserName = event.getOrElse(CommonAlarmProperties.AckUserName, "");
            item.put("ackUserName", ackUserName);
        }
        result.put("returned", alarms.size());
        result.put("truncated", queryResult.size() > alarms.size());
        return ToolExecutionResult.ok("Alarm list processed", result);
    }

    private ToolExecutionResult acknowledge(JsonNode arguments, ToolCallContext context) {
        String source = arguments.path("source").asText("");
        if (source.isBlank()) {
            return ToolExecutionResult.error("Alarm acknowledge requires 'source'");
        }
        if (!context.safetyPolicy().isAlarmAckAllowed(source)) {
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, false, false, source);
            return ToolExecutionResult.error("Alarm source blocked by allowlist: " + source);
        }

        boolean dryRun = context.safetyPolicy().isDryRun(arguments);
        if (dryRun) {
            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("dryRun", true);
            result.put("source", source);
            result.put("note", "Ack dry-run only; pass commit=true to execute");
            result.set("candidateEventIds", context.objectMapper().valueToTree(findCandidateEventIds(context, source, arguments)));
            return ToolExecutionResult.ok("Alarm acknowledge dry-run", result);
        }

        List<UUID> eventIds = findCandidateEventIds(context, source, arguments);
        if (eventIds.isEmpty()) {
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, true, source);
            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("acknowledged", true);
            result.put("source", source);
            result.put("requestedCount", 0);
            result.put("note", "No matching unacknowledged alarms found");
            return ToolExecutionResult.ok("No matching alarms to acknowledge", result);
        }

        EventData ackData = new EventData(System.currentTimeMillis());
        ackData.set(CommonAlarmProperties.AckUserName, context.authContext().tokenName());
        String notes = arguments.path("notes").asText("").trim();
        if (!notes.isBlank()) {
            ackData.set(CommonAlarmProperties.AckNotes, notes);
        }

        boolean acknowledged = context.gatewayContext().getAlarmManager().acknowledge(eventIds, ackData);
        context.auditLogger().logWriteAttempt(
            context.authContext().tokenName(),
            toolName,
            true,
            acknowledged,
            source
        );
        if (!acknowledged) {
            return ToolExecutionResult.error("Alarm acknowledge failed for one or more events");
        }

        ObjectNode result = context.objectMapper().createObjectNode();
        result.put("acknowledged", true);
        result.put("source", source);
        result.put("requestedCount", eventIds.size());
        result.set("eventIds", context.objectMapper().valueToTree(eventIds.stream().map(UUID::toString).toList()));
        result.put("note", "Alarm acknowledge accepted");
        return ToolExecutionResult.ok("Alarm acknowledge processed", result);
    }

    private static String description(String name) {
        return switch (name) {
            case "ignition.alarms.list" -> "List active/cleared alarms";
            case "ignition.alarms.acknowledge" -> "Acknowledge an alarm by source (allowlisted, rate limited)";
            default -> "Alarm tool";
        };
    }

    private static JsonNode inputSchema(String name) {
        ObjectNode schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        if (name.endsWith("list")) {
            props.putObject("state").put("type", "string");
            props.putObject("source").put("type", "string");
            props.putObject("maxResults").put("type", "integer");
        }
        else {
            props.putObject("source").put("type", "string");
            props.putObject("eventIds").put("type", "array").putObject("items").put("type", "string");
            props.putObject("notes").put("type", "string");
            props.putObject("commit").put("type", "boolean");
            ArrayNode required = schema.putArray("required");
            required.add("source");
        }

        return schema;
    }

    private static boolean applyStateFilter(AlarmFilterBuilder filter, String stateRaw) {
        String state = StringUtils.defaultIfBlank(stateRaw, "active").trim().toLowerCase();
        switch (state) {
            case "all":
                return true;
            case "active":
                filter.isState(AlarmState.ActiveAcked, AlarmState.ActiveUnacked);
                return true;
            case "clear":
            case "cleared":
                filter.isState(AlarmState.ClearAcked, AlarmState.ClearUnacked);
                return true;
            case "acked":
                filter.isState(AlarmState.ActiveAcked, AlarmState.ClearAcked);
                return true;
            case "unacked":
                filter.isState(AlarmState.ActiveUnacked, AlarmState.ClearUnacked);
                return true;
            default:
                return false;
        }
    }

    private static List<UUID> findCandidateEventIds(
        ToolCallContext context,
        String source,
        JsonNode arguments
    ) {
        List<UUID> provided = parseEventIds(arguments.path("eventIds"));
        if (!provided.isEmpty()) {
            return provided;
        }

        AlarmFilterBuilder filter = new AlarmFilterBuilder()
            .allowShelved()
            .sourceOrDisplayPath(new String[] {source})
            .isState(AlarmState.ActiveUnacked, AlarmState.ClearUnacked);
        AlarmQueryResult queryResult = context.gatewayContext().getAlarmManager().queryStatus(filter.build());

        List<UUID> ids = new ArrayList<>(queryResult.size());
        for (AlarmEvent event : queryResult) {
            ids.add(event.getId());
        }
        return ids;
    }

    private static List<UUID> parseEventIds(JsonNode eventIdsNode) {
        if (eventIdsNode == null || !eventIdsNode.isArray()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(eventIdsNode.size());
        for (JsonNode eventIdNode : eventIdsNode) {
            String value = eventIdNode.asText("").trim();
            if (value.isBlank()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(value));
            }
            catch (IllegalArgumentException ignored) {
                // Skip malformed UUID entries.
            }
        }
        return ids;
    }
}
