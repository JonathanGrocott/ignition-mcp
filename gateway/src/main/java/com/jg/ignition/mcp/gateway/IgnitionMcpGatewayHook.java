package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jg.ignition.mcp.common.McpConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class IgnitionMcpGatewayHook extends AbstractGatewayModuleHook {

    private final Logger logger = LoggerFactory.getLogger(IgnitionMcpGatewayHook.class);

    private GatewayContext gatewayContext;
    private McpConfigService configService;
    private McpRouteRegistrar routeRegistrar;

    @Override
    public void setup(GatewayContext context) {
        this.gatewayContext = context;
        this.configService = new McpConfigService(context);
        logger.info("Ignition MCP gateway setup complete");
    }

    @Override
    public void startup(LicenseState activationState) {
        configService.startup();

        ObjectMapper mapper = new ObjectMapper();
        McpAuthService authService = new McpAuthService(gatewayContext);
        McpPermissionEvaluator permissionEvaluator = new McpPermissionEvaluator();
        McpSessionManager sessionManager = new McpSessionManager();
        McpAuditLogger auditLogger = new McpAuditLogger();
        SafetyPolicyEngine safetyPolicyEngine = new SafetyPolicyEngine(configService::getConfig);
        ToolRegistry toolRegistry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(mapper, toolRegistry, permissionEvaluator);

        this.routeRegistrar = new McpRouteRegistrar(
            gatewayContext,
            configService.getConfig().mountAlias(),
            mapper,
            configService,
            authService,
            safetyPolicyEngine,
            sessionManager,
            dispatcher,
            auditLogger
        );

        logger.info("Ignition MCP gateway started");
    }

    @Override
    public void shutdown() {
        if (configService != null) {
            configService.shutdown();
        }
        logger.info("Ignition MCP gateway shutdown complete");
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        if (routeRegistrar == null) {
            logger.warn("Route registrar not initialized yet; skipping route mount");
            return;
        }
        routeRegistrar.mount(routes);
    }

    @Override
    public Optional<String> getMountPathAlias() {
        if (configService != null) {
            return Optional.of(configService.getConfig().mountAlias());
        }
        return Optional.of(McpConstants.MOUNT_ALIAS_DEFAULT);
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }
}
