package com.jg.ignition.mcp.gateway;

import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.gateway.tools.AlarmToolHandler;
import com.jg.ignition.mcp.gateway.tools.HistorianToolHandler;
import com.jg.ignition.mcp.gateway.tools.ProjectToolHandler;
import com.jg.ignition.mcp.gateway.tools.TagDefinitionToolHandler;
import com.jg.ignition.mcp.gateway.tools.TagToolHandler;
import com.jg.ignition.mcp.gateway.tools.ToolHandler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ToolRegistry {

    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    public ToolRegistry() {
        register(new TagToolHandler("ignition.tags.browse"));
        register(new TagToolHandler("ignition.tags.read"));
        register(new TagToolHandler("ignition.tags.write"));
        register(new TagDefinitionToolHandler("ignition.tags.definition.read"));
        register(new TagDefinitionToolHandler("ignition.tags.definition.write"));
        register(new ProjectToolHandler("ignition.projects.list"));
        register(new ProjectToolHandler("ignition.namedqueries.list"));
        register(new ProjectToolHandler("ignition.namedqueries.read"));
        register(new ProjectToolHandler("ignition.namedqueries.execute"));
        register(new HistorianToolHandler());
        register(new AlarmToolHandler("ignition.alarms.list"));
        register(new AlarmToolHandler("ignition.alarms.acknowledge"));
    }

    public void register(ToolHandler handler) {
        handlers.put(handler.definition().name(), handler);
    }

    public Collection<ToolDefinition> definitions() {
        return handlers.values().stream().map(ToolHandler::definition).toList();
    }

    public Optional<ToolHandler> find(String toolName) {
        return Optional.ofNullable(handlers.get(toolName));
    }
}
