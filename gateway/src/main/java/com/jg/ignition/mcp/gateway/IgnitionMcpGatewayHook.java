package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
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
            McpConstants.MOUNT_ALIAS_DEFAULT,
            mapper,
            configService,
            authService,
            safetyPolicyEngine,
            sessionManager,
            dispatcher,
            auditLogger
        );
        logger.info("Ignition MCP gateway setup complete");
    }

    @Override
    public void startup(LicenseState activationState) {
        configService.startup();
        registerWebUi();
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
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    private void registerWebUi() {
        String alias = configService.getConfig().mountAlias();
        String resourcePath = "/res/" + alias + "/mcp-admin.js";

        SystemJsModule jsModule = new SystemJsModule(
            "com.jg.ignition.mcp.gateway",
            resourcePath
        );

        gatewayContext.getWebResourceManager().getNavigationModel().getServices()
            .addCategory("ignition-mcp", category -> category
                .label("Ignition MCP")
                .addPage("Configuration", page -> page
                    .position(50)
                    .mount("/ignition-mcp", "IgnitionMcpAdmin", jsModule)
                )
            );
    }
}
