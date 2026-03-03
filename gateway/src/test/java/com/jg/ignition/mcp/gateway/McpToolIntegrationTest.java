package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.StreamingDatasetWriter;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.history.TagHistoryQueryParams;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.historian.TagHistoryManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jg.ignition.mcp.common.McpConstants;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.tools.AlarmToolHandler;
import com.jg.ignition.mcp.gateway.tools.HistorianToolHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void historianQueryEnforcesRowLimit() {
        GatewayContext gatewayContext = mock(GatewayContext.class);
        TagHistoryManager historyManager = mock(TagHistoryManager.class);
        when(gatewayContext.getTagHistoryManager()).thenReturn(historyManager);

        ArgumentCaptor<TagHistoryQueryParams> paramsCaptor = ArgumentCaptor.forClass(TagHistoryQueryParams.class);
        doAnswer(invocation -> {
            TagHistoryQueryParams params = invocation.getArgument(0);
            StreamingDatasetWriter writer = invocation.getArgument(1);
            writer.initialize(new String[] {"t_stamp", "value"}, new Class[] {Date.class, Double.class}, true, 1);
            writer.write(
                new Object[] {new Date(1_700_000_000_000L), 12.34d},
                new QualityCode[] {QualityCode.Good, QualityCode.Good}
            );
            writer.finish();
            return null;
        }).when(historyManager).queryHistory(paramsCaptor.capture(), any(StreamingDatasetWriter.class));

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
            "",
            5
        );

        ToolCallContext callContext = new ToolCallContext(
            gatewayContext,
            mock(RequestContext.class),
            new McpAuthService.AuthContext("tok-hist", "fp-hist", null),
            new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(),
            config,
            mapper
        );
        HistorianToolHandler handler = new HistorianToolHandler();

        ObjectNode args = mapper.createObjectNode();
        args.putArray("paths").add("[default]MCP/Temperature");
        args.put("start", 1_700_000_000_000L);
        args.put("end", 1_700_000_600_000L);
        args.put("maxRows", 999);

        ToolExecutionResult result = handler.execute(args, callContext);
        assertFalse(result.isError(), result.text());
        assertEquals(5, paramsCaptor.getValue().getReturnSize());
        assertEquals(5, result.structuredContent().path("maxRows").asInt());
        assertEquals(1, result.structuredContent().path("rowCount").asInt());
    }

    @Test
    void alarmAcknowledgeRespectsAllowlistPolicy() {
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
            List.of("prov:allowed*"),
            "",
            5000
        );

        ToolCallContext context = new ToolCallContext(
            mock(GatewayContext.class),
            mock(RequestContext.class),
            new McpAuthService.AuthContext("tok-alarm", "fp-alarm", null),
            new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(),
            config,
            mapper
        );
        AlarmToolHandler handler = new AlarmToolHandler("ignition.alarms.acknowledge");

        ObjectNode args = mapper.createObjectNode();
        args.put("source", "prov:blocked/source1");
        args.put("commit", true);

        ToolExecutionResult result = handler.execute(args, context);
        assertTrue(result.isError());
        assertTrue(result.text().contains("blocked"));
    }

    @Test
    void dispatcherReturnsPermissionDeniedOnWriteWhenEvaluatorRejects() {
        McpPermissionEvaluator evaluator = new McpPermissionEvaluator() {
            @Override
            public PermissionDecision evaluate(PermissionRequirement requirement, RequestContext requestContext) {
                if (requirement == PermissionRequirement.WRITE) {
                    return PermissionDecision.deny(403, "Denied for test");
                }
                return PermissionDecision.allow();
            }
        };

        McpDispatcher dispatcher = new McpDispatcher(mapper, new ToolRegistry(), evaluator);
        McpServerConfigResource config = McpServerConfigResource.DEFAULT;
        ToolCallContext context = new ToolCallContext(
            mock(GatewayContext.class),
            mock(RequestContext.class),
            new McpAuthService.AuthContext("tok-deny", "fp-deny", null),
            new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(),
            config,
            mapper
        );

        ObjectNode payload = mapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", 1);
        payload.put("method", McpConstants.METHOD_TOOLS_CALL);
        ObjectNode params = payload.putObject("params");
        params.put("name", "ignition.tags.write");
        params.putObject("arguments").putArray("writes").addObject()
            .put("path", "[default]MCP/Setpoint")
            .put("value", 1);

        ObjectNode response = (ObjectNode) dispatcher.dispatch(payload, context);
        assertEquals(McpDispatcher.JSONRPC_PERMISSION_DENIED, response.path("error").path("code").asInt());
    }
}
