package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WriteAuthorizationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private McpServerConfigResource config(ObjectNode fields) {
        return mapper.convertValue(fields, McpServerConfigResource.class);
    }

    @Test
    void absentWriteGateMigratesToDisabledAndInvalidModesAreRejected() {
        ObjectNode legacy = mapper.valueToTree(McpServerConfigResource.DEFAULT);
        legacy.remove("writesEnabled");
        legacy.remove("authorizationMode");
        McpServerConfigResource migrated = config(legacy);
        assertFalse(migrated.writesEnabled());
        assertEquals("additive", migrated.authorizationMode());
        legacy.put("authorizationMode", "allow-everything");
        assertThrows(IllegalArgumentException.class, () -> config(legacy));
    }

    @Test
    void commitTrueAndCallerSuppliedGateCannotEnableWrites() {
        GatewayContext gateway = mock(GatewayContext.class);
        var config = McpServerConfigResource.DEFAULT;
        var context = new ToolCallContext(gateway, mock(RequestContext.class),
            new McpAuthService.AuthContext("reader", "fp", null), new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(), config, mapper);
        var dispatcher = new McpDispatcher(mapper, new ToolRegistry(), new McpPermissionEvaluator());
        ObjectNode payload = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        payload.putObject("params").put("name", "ignition.tags.write").putObject("arguments")
            .put("commit", true).put("writesEnabled", true).putArray("writes").addObject().put("path", "[default]MCP/Test").put("value", 1);
        JsonNode result = dispatcher.dispatch(payload, context);
        assertEquals("WRITES_DISABLED", result.at("/error/data/code").asText());
        verifyNoInteractions(gateway);
        assertEquals(1, context.auditLogger().snapshot().blockedWriteAttempts());
    }

    @Test
    void disabledGateStillPermitsLegacyDryRunPreviews() {
        GatewayContext gateway = mock(GatewayContext.class);
        var config = McpServerConfigResource.DEFAULT;
        var context = new ToolCallContext(gateway, mock(RequestContext.class),
            new McpAuthService.AuthContext("reader", "fp", null), new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(), config, mapper);
        var dispatcher = new McpDispatcher(mapper, new ToolRegistry(), new McpPermissionEvaluator());
        ObjectNode payload = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        payload.putObject("params").put("name", "ignition.tags.write").putObject("arguments")
            .put("commit", false).putArray("writes").addObject().put("path", "[default]MCP/Test").put("value", 1);
        JsonNode result = dispatcher.dispatch(payload, context);
        assertTrue(result.at("/result/structuredContent/dryRun").asBoolean(), result.toString());
        verifyNoInteractions(gateway);
    }

    @Test
    void disabledGateCoversEveryMutatingToolBeforeHandlerExecution() {
        GatewayContext gateway = mock(GatewayContext.class);
        var config = McpServerConfigResource.DEFAULT;
        var context = new ToolCallContext(gateway, mock(RequestContext.class),
            new McpAuthService.AuthContext("reader", "fp", null), new SafetyPolicyEngine(() -> config),
            new McpAuditLogger(), config, mapper);
        var registry = new ToolRegistry();
        var dispatcher = new McpDispatcher(mapper, registry, new McpPermissionEvaluator());
        for (var definition : registry.definitions()) {
            if (!definition.mutating()) { continue; }
            ObjectNode payload = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
            payload.putObject("params").put("name", definition.name()).putObject("arguments").put("commit", true);
            assertEquals("WRITES_DISABLED", dispatcher.dispatch(payload, context).at("/error/data/code").asText(), definition.name());
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void profilesOnlyIgnoresAllGlobalWildcardsAndUnmatchedTokensHaveNoGrants() throws Exception {
        ObjectNode fields = mapper.valueToTree(McpServerConfigResource.DEFAULT);
        fields.put("authorizationMode", "profiles-only");
        fields.set("authorizationProfiles", mapper.readTree("""
            [{"name":"ops","tokenPatterns":["ops-*"],
              "allowedReadToolPatterns":["ignition.tags.read"],"allowedWriteToolPatterns":["ignition.tags.write"],
              "allowedTagReadPatterns":["[default]Ops/*"],"allowedTagWritePatterns":["[default]Ops/*"],
              "allowedAlarmAckSources":["prov:ops/*"],"allowedNamedQueryExecutePatterns":["Ops/Reports/*"],
              "allowedProjectResourceReadPatterns":["Ops/ignition/*/*"],"allowedProjectScriptWritePatterns":["Ops/library/*"],
              "allowedNamedQueryWritePatterns":["Ops/Reports/*"]}]
            """));
        McpServerConfigResource restricted = config(fields);
        var policy = new SafetyPolicyEngine(() -> restricted);
        assertTrue(policy.isToolAllowed("ops-a", "ignition.tags.read", false));
        assertTrue(policy.isToolAllowed("ops-a", "ignition.tags.write", true));
        assertFalse(policy.isToolAllowed("ops-a", "ignition.gateway.logs", false));
        assertFalse(policy.isToolAllowed("unknown", "ignition.tags.read", false));
        assertTrue(policy.isTagReadAllowed("ops-a", "[default]Ops/Value"));
        assertFalse(policy.isTagReadAllowed("ops-a", "[default]Private/Value"));
        assertFalse(policy.isTagWriteAllowed("ops-a", "[default]MCP/Value"));
        assertTrue(policy.isTagWriteAllowed("ops-a", "[default]Ops/Value"));
        assertTrue(policy.isAlarmAckAllowed("ops-a", "prov:ops/Alarm"));
        assertFalse(policy.isAlarmAckAllowed("ops-a", "prov:private/Alarm"));
        assertTrue(policy.isNamedQueryExecuteAllowed("ops-a", "Ops", "Reports/Read"));
        assertFalse(policy.isNamedQueryExecuteAllowed("ops-a", "Private", "Reports/Read"));
        assertTrue(policy.isNamedQueryWriteAllowed("ops-a", "Ops", "Reports/Read"));
        assertFalse(policy.isNamedQueryWriteAllowed("ops-a", "Private", "Reports/Read"));
        assertTrue(policy.isProjectResourceReadAllowed("ops-a", "Ops", "ignition", "script-python", "Lib"));
        assertFalse(policy.isProjectResourceReadAllowed("ops-a", "Private", "ignition", "script-python", "Lib"));
        assertTrue(policy.isProjectScriptWriteAllowed("ops-a", "Ops", "library/Lib"));
        assertFalse(policy.isProjectScriptWriteAllowed("ops-a", "Private", "library/Lib"));
        fields.put("authorizationMode", "additive");
        var additive = config(fields);
        assertTrue(new SafetyPolicyEngine(() -> additive).isToolAllowed("unknown", "ignition.gateway.logs", false));
    }
}
