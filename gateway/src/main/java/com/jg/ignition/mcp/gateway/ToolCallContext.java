package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

public record ToolCallContext(
    GatewayContext gatewayContext,
    RequestContext requestContext,
    McpAuthService.AuthContext authContext,
    SafetyPolicyEngine safetyPolicy,
    McpAuditLogger auditLogger,
    McpServerConfigResource config,
    ObjectMapper objectMapper
) {
}
