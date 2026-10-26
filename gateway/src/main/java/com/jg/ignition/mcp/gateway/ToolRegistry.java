package com.jg.ignition.mcp.gateway;

import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.gateway.tools.AlarmToolHandler;
import com.jg.ignition.mcp.gateway.tools.HistorianToolHandler;
import com.jg.ignition.mcp.gateway.tools.ProjectResourceToolHandler;
import com.jg.ignition.mcp.gateway.tools.ProjectScriptToolHandler;
import com.jg.ignition.mcp.gateway.tools.ProjectToolHandler;
import com.jg.ignition.mcp.gateway.tools.TagDefinitionToolHandler;
import com.jg.ignition.mcp.gateway.tools.TagToolHandler;
import com.jg.ignition.mcp.gateway.tools.ToolHandler;
import com.jg.ignition.mcp.gateway.tools.ProjectInspectionToolHandler;
import com.jg.ignition.mcp.gateway.tools.TagInspectionToolHandler;
import com.jg.ignition.mcp.gateway.tools.GatewayInspectionToolHandler;
import com.jg.ignition.mcp.gateway.tools.ProjectChangeToolHandler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ToolRegistry {

    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    public ToolRegistry() {
        ProjectChangeToolHandler.tools().forEach(this::register);
        register(new TagToolHandler("ignition.tags.browse"));
        register(new TagToolHandler("ignition.tags.read"));
        register(new TagToolHandler("ignition.tags.write"));
        register(new TagDefinitionToolHandler("ignition.tags.definition.read"));
        register(new TagDefinitionToolHandler("ignition.tags.definition.write"));
        register(new ProjectToolHandler("ignition.projects.list"));
        register(new ProjectResourceToolHandler("ignition.projects.resource.list"));
        register(new ProjectResourceToolHandler("ignition.projects.resource.read"));
        register(new ProjectResourceToolHandler("ignition.projects.resource.export"));
        register(new ProjectToolHandler("ignition.namedqueries.list"));
        register(new ProjectToolHandler("ignition.namedqueries.read"));
        register(new ProjectToolHandler("ignition.namedqueries.execute"));
        register(new ProjectToolHandler("ignition.namedqueries.write"));
        register(new ProjectToolHandler("ignition.namedqueries.delete"));
        register(new ProjectToolHandler("ignition.namedqueries.import"));
        register(new ProjectScriptToolHandler("ignition.scripts.project.list"));
        register(new ProjectScriptToolHandler("ignition.scripts.project.read"));
        register(new ProjectScriptToolHandler("ignition.scripts.project.write"));
        register(new ProjectScriptToolHandler("ignition.scripts.project.delete"));
        register(new ProjectScriptToolHandler("ignition.scripts.project.import"));
        register(new HistorianToolHandler());
        register(new AlarmToolHandler("ignition.alarms.list"));
        register(new AlarmToolHandler("ignition.alarms.acknowledge"));
        for (String name : java.util.List.of(
            "ignition.perspective.views.list", "ignition.perspective.views.read",
            "ignition.alarms.pipelines.list", "ignition.alarms.pipelines.read",
            "ignition.projects.search", "ignition.projects.references"
        )) {
            register(new ProjectInspectionToolHandler(name));
        }
        for (String name : java.util.List.of(
            "ignition.udts.list", "ignition.udts.read", "ignition.tags.opc.search", "ignition.tags.snapshot"
        )) {
            register(new TagInspectionToolHandler(name));
        }
        for (String name : java.util.List.of(
            "ignition.gateway.status", "ignition.gateway.modules", "ignition.gateway.connections",
            "ignition.gateway.logs", "ignition.gateway.diagnose"
        )) {
            register(new GatewayInspectionToolHandler(name));
        }
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
