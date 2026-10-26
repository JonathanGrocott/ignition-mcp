package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.ImmutableBytes;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.McpDispatcher;
import com.jg.ignition.mcp.gateway.McpPermissionEvaluator;
import com.jg.ignition.mcp.gateway.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProjectInspectionToolHandlerTest extends InspectionTestSupport {
    private static final String PERSPECTIVE = "com.inductiveautomation.perspective";
    private static final String VIEW = """
        {"params":{"tag":"[default]MCP/Speed"},"propConfig":{"params.tag":{"paramDirection":"input"}},
         "root":{"type":"ia.container.flex","meta":{"name":"Root"},"children":[
          {"type":"ia.display.label","meta":{"name":"Speed"},"custom":{"unknownExtension":42},
           "propConfig":{"props.text":{"binding":{"type":"tag","config":{"path":"[default]MCP/Speed"},
             "transforms":[{"type":"script","script":"return value * 2","customFlag":true}]}}},
           "events":{"component":{"onActionPerformed":[{"type":"script","config":{"script":"project.util.run()"}},
              {"type":"nav","config":{"view":"Details"}}]}}},
          {"type":"ia.display.view","meta":{"name":"Details"},"props":{"path":"Equipment/Details"}}]}}
        """;

    private ToolExecutionResult call(String name, ObjectNode args) {
        return new ProjectInspectionToolHandler(name).execute(args, context());
    }

    @Test
    void viewPreservesBindingTargetsTransformsEventsAndSourceLocations() {
        Resource view = resource(PERSPECTIVE, "views", "Main", "view.json", VIEW);
        project(view);
        var args = mapper.createObjectNode().put("project", "Plant").put("path", "Main").put("includeScripts", true);
        var result = call("ignition.perspective.views.read", args);
        assertFalse(result.isError(), result.text());
        JsonNode out = result.structuredContent();
        assertEquals(3, out.path("componentCount").asInt());
        assertEquals("input", out.at("/propConfig/params.tag/paramDirection").asText());
        assertEquals("/root/children/0", out.at("/root/children/0/jsonPointer").asText());
        assertEquals("[default]MCP/Speed", out.at("/root/children/0/bindings/0/definition/config/path").asText());
        assertEquals("return value * 2", out.at("/root/children/0/bindings/0/definition/transforms/0/script").asText());
        assertTrue(out.at("/root/children/0/bindings/0/definition/transforms/0/customFlag").asBoolean());
        assertEquals(2, out.at("/root/children/0/events/component/onActionPerformed").size());
        assertEquals("Equipment/Details", out.at("/root/children/1/embeddedView").asText());
        assertEquals("parent", out.path("definingProject").asText());
        assertFalse(out.path("truncated").asBoolean());
    }

    @Test
    void viewOmitsScriptsByDefaultAndHonorsComponentBudget() {
        project(resource(PERSPECTIVE, "views", "Main", "view.json", VIEW));
        var args = mapper.createObjectNode().put("project", "Plant").put("path", "Main").put("maxComponents", 2);
        var result = call("ignition.perspective.views.read", args);
        assertFalse(result.isError());
        assertEquals(2, result.structuredContent().path("componentCount").asInt());
        assertTrue(result.structuredContent().path("truncated").asBoolean());
        assertFalse(result.structuredContent().toString().contains("return value"));
        assertFalse(result.structuredContent().toString().contains("project.util.run"));
        assertTrue(result.structuredContent().toString().contains("scriptOmitted"));
    }

    @Test
    void resourcePolicyBlocksReadBeforeTouchingContent() {
        Resource hidden = resource(PERSPECTIVE, "views", "Private", "view.json", VIEW);
        var manager = project(hidden);
        when(config.allowedProjectResourceReadPatterns()).thenReturn(List.of("Plant/ignition/*/*"));
        clearInvocations(hidden, manager);
        var result = call("ignition.perspective.views.read", mapper.createObjectNode().put("project", "Plant").put("path", "Private"));
        assertTrue(result.isError());
        assertEquals("ALLOWLIST_BLOCKED", result.structuredContent().path("code").asText());
        verifyNoInteractions(manager);
        verify(hidden, never()).getData(anyString());
    }

    @Test
    void searchReportsJsonPointersAndTextLinesAndNeverReadsDeniedResources() {
        Resource view = resource(PERSPECTIVE, "views", "Main", "view.json", VIEW);
        Resource script = resource("ignition", "script-python", "Util", "code.py", "# comment\nreturn '[default]MCP/Speed'\n");
        Resource hidden = resource("secret", "private", "Hidden", "config.json", "{\"password\":\"secret\"}");
        project(view, script, hidden);
        when(config.allowedProjectResourceReadPatterns()).thenReturn(List.of("Plant/ignition/*/*", "Plant/" + PERSPECTIVE + "/*/*"));
        clearInvocations(hidden);
        var result = call("ignition.projects.references", mapper.createObjectNode().put("target", "[default]MCP/Speed"));
        assertFalse(result.isError());
        assertEquals(3, result.structuredContent().path("count").asInt());
        assertEquals("literal", result.structuredContent().path("matchKind").asText());
        assertTrue(result.structuredContent().toString().contains("/params/tag"));
        assertTrue(result.structuredContent().toString().contains("\"line\":2"));
        assertTrue(result.structuredContent().path("complete").asBoolean());
        verify(hidden, never()).getDataKeys();
        verify(hidden, never()).getData(anyString());
    }

    @Test
    void searchSignalsBothResultAndScanBudgets() {
        project(resource(PERSPECTIVE, "views", "Main", "view.json", VIEW),
            resource(PERSPECTIVE, "views", "Other", "view.json", VIEW));
        var result = call("ignition.projects.search", mapper.createObjectNode().put("query", "MCP").put("maxResults", 1));
        assertEquals(1, result.structuredContent().path("count").asInt());
        assertTrue(result.structuredContent().path("truncated").asBoolean());
        var list = call("ignition.perspective.views.list", mapper.createObjectNode().put("maxResourcesExamined", 1));
        assertTrue(list.structuredContent().path("truncated").asBoolean());
    }

    @Test
    void oversizedAndBinaryFilesAreExplicitlyIncomplete() {
        Resource huge = resource("ignition", "script-python", "Huge", "code.py", "x".repeat(1_048_577));
        project(huge);
        var result = call("ignition.projects.search", mapper.createObjectNode().put("query", "x"));
        assertEquals(1, result.structuredContent().path("skippedFiles").asInt());
        assertFalse(result.structuredContent().path("complete").asBoolean());
        assertEquals(0, result.structuredContent().path("count").asInt());
    }

    @Test
    void pipelineListsDiscoveredTypesAndRejectsUnsupportedBinaryWithoutInventingStages() {
        Resource pipeline = resource("com.inductiveautomation.alarm-notification", "pipeline", "Main", "data.bin", "binary");
        project(pipeline);
        var list = call("ignition.alarms.pipelines.list", mapper.createObjectNode());
        assertEquals("pipeline", list.structuredContent().at("/pipelines/0/resourceType").asText());
        var result = call("ignition.alarms.pipelines.read", mapper.createObjectNode().put("project", "Plant").put("path", "Main"));
        assertTrue(result.isError());
        assertEquals("UNSUPPORTED_FORMAT", result.structuredContent().path("code").asText());
        assertFalse(result.structuredContent().has("stages"));
    }

    @Test
    void jsonPipelineRetainsTransitionsAndUnknownStageConfiguration() {
        Resource pipeline = resource("com.inductiveautomation.alarm-notification", "alarm-pipelines", "Main", "alarm-config.json",
            "{\"stages\":[{\"type\":\"notification\",\"config\":{\"profile\":\"TestEmail\",\"vendorField\":5},\"transitions\":[\"Next\"]}]}" );
        project(pipeline);
        var result = call("ignition.alarms.pipelines.read", mapper.createObjectNode().put("project", "Plant").put("path", "Main"));
        assertFalse(result.isError(), result.text());
        assertEquals(5, result.structuredContent().at("/configuration/alarm-config.json/stages/0/config/vendorField").asInt());
    }

    @Test
    void invalidBoundsFailBeforeResourceLookupAndDispatcherEnforcesToolPolicy() {
        var result = call("ignition.perspective.views.read", mapper.createObjectNode().put("project", "Plant").put("path", "Main").put("maxComponents", -1));
        assertEquals("VALIDATION_ERROR", result.structuredContent().path("code").asText());
        when(config.allowedReadToolPatterns()).thenReturn(List.of("ignition.tags.*"));
        var dispatcher = new McpDispatcher(mapper, new ToolRegistry(), new McpPermissionEvaluator());
        ObjectNode request = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", "ignition.projects.search").putObject("arguments").put("query", "secret");
        JsonNode response = dispatcher.dispatch(request, context());
        assertEquals("TOOL_NOT_AUTHORIZED", response.at("/error/data/code").asText());
        verifyNoInteractions(gateway);
    }

    @Test
    void registryDeclaresNewToolsReadOnlyAndRequiredArguments() {
        var registry = new ToolRegistry();
        assertEquals(42, registry.definitions().size());
        var definition = registry.find("ignition.perspective.views.read").orElseThrow().definition();
        assertFalse(definition.mutating());
        assertEquals("project", definition.inputSchema().path("required").get(0).asText());
    }
}
