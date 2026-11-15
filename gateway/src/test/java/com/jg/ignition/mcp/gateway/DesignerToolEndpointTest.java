package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inductiveautomation.ignition.gateway.clientcomm.ClientReqSession;
import com.inductiveautomation.ignition.gateway.rpc.RpcContext;
import com.inductiveautomation.ignition.gateway.rpc.RpcDelegate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesignerToolEndpointTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void signedInDesignerCatalogAndCallsUseSeparateGrantsAndTheOpenProject() throws Exception {
        var config = mapper.readValue("""
            {"enabled":true,"allowedReadToolPatterns":["*"],"authorizationProfiles":[
              {"name":"An API grant","tokenPatterns":["*"],"fullAccess":true},
              {"name":"Designer tools","tokenPatterns":["designer:qa"],"fullAccess":true}]}
            """, McpServerConfigResource.class);
        var registry = new ToolRegistry();
        var endpoint = new DesignerToolEndpoint(null, mapper, () -> config, registry,
            new McpDispatcher(mapper, registry, new McpPermissionEvaluator()), new McpAuditLogger());
        var session = mock(ClientReqSession.class);
        var context = mock(RpcContext.class);
        when(context.session()).thenReturn(session);
        when(session.isValid()).thenReturn(true);
        when(session.getAttribute(ClientReqSession.SESSION_USERNAME)).thenReturn("qa");
        when(session.getAttribute(ClientReqSession.SESSION_PROJECT_NAME)).thenReturn("ActiveProject");
        RpcDelegate.CURRENT_CONTEXT.set(context);
        try {
            assertThrows(java.io.IOException.class, endpoint::tools);
            when(session.isDesigner()).thenReturn(true);
            var catalog = mapper.readTree(endpoint.tools());
            assertEquals(registry.definitions().size(), catalog.size());
            assertTrue(catalog.size() >= 43);
            for (var tool : catalog) {
                if (tool.path("mutating").asBoolean()) {
                    assertEquals("boolean", tool.path("inputSchema").path("properties").path("commit").path("type").asText(),
                        tool.path("name").asText());
                }
            }
            assertThrows(java.io.IOException.class,
                () -> endpoint.call("ignition.scripts.project.write", "{\"project\":\"OtherProject\",\"commit\":true}"));
            var rejected = mapper.readTree(endpoint.call("ignition.scripts.project.write", "{\"commit\":true}"));
            assertEquals("WRITES_DISABLED", rejected.path("error").path("data").path("code").asText());

            when(session.getAttribute(ClientReqSession.SESSION_USERNAME)).thenReturn("other");
            assertTrue(mapper.readTree(endpoint.tools()).isEmpty());
            assertEquals("TOOL_NOT_AUTHORIZED",
                mapper.readTree(endpoint.call("ignition.scripts.project.list", "{}"))
                    .path("error").path("data").path("code").asText());

            var args = mapper.createObjectNode();
            DesignerToolEndpoint.pinProject(registry.find("ignition.namedqueries.import").orElseThrow().definition(),
                args, "ActiveProject");
            assertEquals("ActiveProject", args.path("project").asText());
            assertEquals("ActiveProject", args.path("targetProject").asText());
        } finally {
            RpcDelegate.CURRENT_CONTEXT.remove();
        }
    }

    @Test
    void designerProfileCannotGrantHttpApiTokenAccess() throws Exception {
        var config = mapper.readValue("""
            {"authorizationProfiles":[
              {"name":"Designer only","tokenPatterns":["designer:qa"],"fullAccess":true}]}
            """, McpServerConfigResource.class);
        assertFalse(new SafetyPolicyEngine(() -> config).isTagWriteAllowed("designer:qa", "[default]Test"));
        assertTrue(new SafetyPolicyEngine(() -> config, true).isTagWriteAllowed("designer:qa", "[default]Test"));
    }
}
