package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.tools.TagDefinitionToolHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagDefinitionToolHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsTagDefinitionFromProviderConfig() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        GatewayTagManager tagManager = mock(GatewayTagManager.class);
        TagProvider provider = mock(TagProvider.class);
        when(gatewayContext.getTagManager()).thenReturn(tagManager);
        when(tagManager.getTagProvider("default")).thenReturn(provider);

        TagPath path = TagPathParser.parseSafe("[default]MCP/Foo");
        TagConfiguration local = BasicTagConfiguration.createEdit(path);
        local.getTagProperties().set(WellKnownTagProps.Enabled, true);
        local.getTagProperties().set(WellKnownTagProps.Documentation, "Test tag");

        TagConfigurationModel model = mock(TagConfigurationModel.class);
        when(model.getName()).thenReturn("Foo");
        when(model.getPath()).thenReturn(path);
        when(model.getType()).thenReturn(TagObjectType.AtomicTag);
        when(model.isEditable()).thenReturn(true);
        when(model.isInherited()).thenReturn(false);
        when(model.isUdtMember()).thenReturn(false);
        when(model.getTagProperties()).thenReturn(local.getTagProperties());
        when(model.getLocalConfiguration()).thenReturn(local);
        when(model.getChildren()).thenReturn(List.of());

        when(provider.getTagConfigsAsync(any(), eq(false), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(List.of(model)));

        McpServerConfigResource config = new McpServerConfigResource(
            true,
            "ignition-mcp",
            List.of(),
            List.of(),
            true,
            true,
            100,
            300,
            60,
            true,
            50,
            List.of("*"),
            List.of("[default]MCP/*"),
            List.of("*"),
            List.of("*"),
            "",
            5000,
            1000
        );

        ToolCallContext context = new ToolCallContext(
            gatewayContext,
            mock(RequestContext.class),
            new McpAuthService.AuthContext("tok", "fp", null),
            new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(),
            config,
            mapper
        );
        TagDefinitionToolHandler handler = new TagDefinitionToolHandler("ignition.tags.definition.read");

        ObjectNode args = mapper.createObjectNode();
        args.putArray("paths").add("[default]MCP/Foo");

        ToolExecutionResult result = handler.execute(args, context);
        assertFalse(result.isError(), result.text());
        assertTrue(result.structuredContent().path("count").asInt() >= 1);
        assertTrue(result.structuredContent().toString().contains("Test tag"));
    }

    @Test
    void writesTagDefinitionWhenCommitEnabled() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        GatewayTagManager tagManager = mock(GatewayTagManager.class);
        TagProvider provider = mock(TagProvider.class);
        when(gatewayContext.getTagManager()).thenReturn(tagManager);
        when(tagManager.getTagProvider("default")).thenReturn(provider);
        when(provider.saveTagConfigsAsync(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(List.of(QualityCode.Good)));

        McpServerConfigResource config = new McpServerConfigResource(
            true,
            "ignition-mcp",
            List.of(),
            List.of(),
            true,
            true,
            100,
            300,
            60,
            false,
            50,
            List.of("*"),
            List.of("[default]MCP/*"),
            List.of("*"),
            List.of("*"),
            "",
            5000,
            1000
        );

        ToolCallContext context = new ToolCallContext(
            gatewayContext,
            mock(RequestContext.class),
            new McpAuthService.AuthContext("tok", "fp", null),
            new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(),
            config,
            mapper
        );
        TagDefinitionToolHandler handler = new TagDefinitionToolHandler("ignition.tags.definition.write");

        ObjectNode args = mapper.createObjectNode();
        args.put("operation", "create");
        args.put("path", "[default]MCP/NewTag");
        args.put("tagObjectType", "AtomicTag");
        args.putObject("properties").put("enabled", true);
        args.put("commit", true);

        ToolExecutionResult result = handler.execute(args, context);
        assertFalse(result.isError(), result.text());
        assertTrue(result.structuredContent().path("updated").asBoolean());
    }

    @Test
    void writesTagDefinitionBatchDryRunWithChildren() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        GatewayTagManager tagManager = mock(GatewayTagManager.class);
        TagProvider provider = mock(TagProvider.class);
        when(gatewayContext.getTagManager()).thenReturn(tagManager);
        when(tagManager.getTagProvider("default")).thenReturn(provider);

        ToolCallContext context = context(gatewayContext, McpServerConfigResource.DEFAULT);
        TagDefinitionToolHandler handler = new TagDefinitionToolHandler("ignition.tags.definition.write");

        ObjectNode args = mapper.createObjectNode();
        args.put("commit", false);
        ObjectNode definition = args.putArray("definitions").addObject();
        definition.put("operation", "create");
        definition.put("path", "[default]MCP/Types/MyType");
        definition.put("tagObjectType", "UdtType");
        ObjectNode child = definition.putArray("children").addObject();
        child.put("name", "Value");
        child.put("tagObjectType", "AtomicTag");
        child.putObject("properties").put("enabled", true);

        ToolExecutionResult result = handler.execute(args, context);
        assertFalse(result.isError(), result.text());
        assertEquals(1, result.structuredContent().path("count").asInt());
        verify(provider, never()).saveTagConfigsAsync(any(), any());
    }

    @Test
    void deletesTagDefinitionWhenCommitEnabled() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        GatewayTagManager tagManager = mock(GatewayTagManager.class);
        TagProvider provider = mock(TagProvider.class);
        when(gatewayContext.getTagManager()).thenReturn(tagManager);
        when(tagManager.getTagProvider("default")).thenReturn(provider);
        when(provider.removeTagConfigsAsync(any()))
            .thenReturn(CompletableFuture.completedFuture(List.of(QualityCode.Good)));

        ToolCallContext context = context(gatewayContext, committedConfig());
        TagDefinitionToolHandler handler = new TagDefinitionToolHandler("ignition.tags.definition.write");

        ObjectNode args = mapper.createObjectNode();
        args.put("operation", "delete");
        args.put("path", "[default]MCP/OldTag");
        args.put("commit", true);

        ToolExecutionResult result = handler.execute(args, context);
        assertFalse(result.isError(), result.text());
        assertTrue(result.structuredContent().path("deleted").asBoolean());
    }

    private ToolCallContext context(GatewayContext gatewayContext, McpServerConfigResource config) {
        return new ToolCallContext(
            gatewayContext,
            mock(RequestContext.class),
            new McpAuthService.AuthContext("tok", "fp", null),
            new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(),
            config,
            mapper
        );
    }

    private static McpServerConfigResource committedConfig() {
        return new McpServerConfigResource(
            true,
            "ignition-mcp",
            List.of(),
            List.of(),
            true,
            true,
            100,
            300,
            60,
            false,
            50,
            List.of("*"),
            List.of("[default]MCP/*"),
            List.of("*"),
            List.of("*"),
            "",
            5000,
            1000
        );
    }
}
