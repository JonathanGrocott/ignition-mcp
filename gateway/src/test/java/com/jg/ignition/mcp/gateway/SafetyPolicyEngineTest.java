package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyPolicyEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesDefaultDryRunWhenCommitMissing() {
        McpServerConfigResource config = new McpServerConfigResource(
            true,
            "ignition-mcp",
            java.util.List.of(),
            java.util.List.of(),
            true,
            true,
            100,
            100,
            50,
            true,
            25,
            java.util.List.of("[default]Line1/*"),
            java.util.List.of("[default]MCP/*"),
            java.util.List.of("*"),
            java.util.List.of("*"),
            "",
            5000,
            1000
        );

        SafetyPolicyEngine engine = new SafetyPolicyEngine(() -> config);
        ObjectNode args = mapper.createObjectNode();

        assertTrue(engine.isDryRun(args));
    }

    @Test
    void commitTrueTurnsOffDryRun() {
        SafetyPolicyEngine engine = new SafetyPolicyEngine(() -> McpServerConfigResource.DEFAULT);
        ObjectNode args = mapper.createObjectNode().put("commit", true);
        assertFalse(engine.isDryRun(args));
    }

    @Test
    void enforcesTagAllowlistPatterns() {
        McpServerConfigResource config = new McpServerConfigResource(
            true,
            "ignition-mcp",
            java.util.List.of(),
            java.util.List.of(),
            true,
            true,
            100,
            100,
            50,
            true,
            25,
            java.util.List.of("[default]AreaA/*"),
            java.util.List.of("[default]MCP/*"),
            java.util.List.of("prov:*"),
            java.util.List.of("*"),
            "",
            5000,
            1000
        );
        SafetyPolicyEngine engine = new SafetyPolicyEngine(() -> config);

        assertTrue(engine.isTagReadAllowed("[default]AreaA/Line1/Speed"));
        assertFalse(engine.isTagReadAllowed("[default]AreaB/Line9/Speed"));
        assertTrue(engine.isTagWriteAllowed("[default]MCP/Setpoint"));
        assertFalse(engine.isTagWriteAllowed("[default]Other/Setpoint"));
        assertTrue(engine.isAlarmAckAllowed("prov:line1/source1"));
    }

    @Test
    void authorizationProfilesGrantTokenScopedToolAndResourceAccess() {
        McpServerConfigResource.AuthorizationProfile profile = new McpServerConfigResource.AuthorizationProfile(
            "ops-profile",
            java.util.List.of("ops-*"),
            java.util.List.of("ignition.tags.*"),
            java.util.List.of("ignition.tags.write"),
            java.util.List.of("[default]Ops/*"),
            java.util.List.of("[default]Ops/Write/*"),
            java.util.List.of("prov:ops/*"),
            java.util.List.of("OpsProject/Reports/*"),
            java.util.List.of("OpsProject/ignition/*/*"),
            java.util.List.of("OpsProject/library/*"),
            java.util.List.of("OpsProject/Reports/*")
        );
        McpServerConfigResource config = new McpServerConfigResource(
            true,
            "ignition-mcp",
            java.util.List.of(),
            java.util.List.of(),
            true,
            true,
            100,
            100,
            50,
            true,
            25,
            java.util.List.of("[default]Public/*"),
            java.util.List.of("[default]PublicWrites/*"),
            java.util.List.of("prov:public/*"),
            java.util.List.of("PublicProject/*"),
            java.util.List.of("PublicProject/ignition/*/*"),
            java.util.List.of("PublicProject/library/*"),
            java.util.List.of("PublicProject/Queries/*"),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(profile),
            "",
            5000,
            1000
        );
        SafetyPolicyEngine engine = new SafetyPolicyEngine(() -> config);

        assertTrue(engine.isToolAllowed("ops-a", "ignition.tags.write", true));
        assertFalse(engine.isToolAllowed("viewer-a", "ignition.tags.write", true));
        assertTrue(engine.isTagReadAllowed("ops-a", "[default]Ops/Line1/Speed"));
        assertFalse(engine.isTagReadAllowed("viewer-a", "[default]Ops/Line1/Speed"));
        assertTrue(engine.isProjectResourceReadAllowed("ops-a", "OpsProject", "ignition", "view", "Main"));
        assertFalse(engine.isProjectResourceReadAllowed("viewer-a", "OpsProject", "ignition", "view", "Main"));
        assertTrue(engine.isNamedQueryWriteAllowed("ops-a", "OpsProject", "Reports/Daily"));
        assertFalse(engine.isNamedQueryWriteAllowed("viewer-a", "OpsProject", "Reports/Daily"));
    }
}
