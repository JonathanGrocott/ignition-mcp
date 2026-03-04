package com.jg.ignition.mcp.gateway;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import com.jg.ignition.mcp.common.PermissionRequirement;

import java.util.List;

public class McpPermissionEvaluator {

    public PermissionDecision evaluate(PermissionRequirement requirement, RequestContext requestContext) {
        // Ignition API tokens in 8.3 commonly resolve to ACCESS-level auth only.
        // Tool-level read/write safety is enforced by allowlists, dry-run defaults, and write rate limits.
        if (requirement == PermissionRequirement.READ || requirement == PermissionRequirement.WRITE) {
            return PermissionDecision.allow();
        }

        PermissionType permissionType = switch (requirement) {
            case ACCESS -> PermissionType.ACCESS;
            case READ -> PermissionType.READ;
            case WRITE -> PermissionType.WRITE;
        };

        List<AccessControlStrategy> strategies = PermissionType.getStrategies(permissionType);
        boolean unauthorized = false;

        for (AccessControlStrategy strategy : strategies) {
            RouteAccess access = strategy.canAccess(requestContext);
            if (access == RouteAccess.GRANTED) {
                return PermissionDecision.allow();
            }
            if (access == RouteAccess.UNAUTHORIZED) {
                unauthorized = true;
            }
        }

        if (unauthorized) {
            return PermissionDecision.deny(401, "Unauthorized for permission " + permissionType);
        }
        return PermissionDecision.deny(403, "Forbidden for permission " + permissionType);
    }

    public record PermissionDecision(boolean granted, int statusCode, String message) {
        public static PermissionDecision allow() {
            return new PermissionDecision(true, 200, "");
        }

        public static PermissionDecision deny(int statusCode, String message) {
            return new PermissionDecision(false, statusCode, message);
        }
    }
}
