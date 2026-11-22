package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.gateway.clientcomm.ClientReqSession;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.rpc.RpcDelegate;
import com.jg.ignition.mcp.bridge.DesignerMcpRpc;
import com.jg.ignition.mcp.common.McpConstants;
import com.jg.ignition.mcp.common.ToolDefinition;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/** Authenticates via Ignition's Designer RPC session and delegates to the existing MCP handlers. */
public final class DesignerToolEndpoint implements DesignerMcpRpc {
    static final String ACTOR_PREFIX = "designer:";
    private static final int MAX_ARGUMENT_CHARS = 3_000_000;

    private final GatewayContext gateway;
    private final ObjectMapper mapper;
    private final Supplier<McpServerConfigResource> config;
    private final ToolRegistry registry;
    private final McpDispatcher dispatcher;
    private final McpAuditLogger audit;
    private final SafetyPolicyEngine policy;

    DesignerToolEndpoint(GatewayContext gateway, ObjectMapper mapper, Supplier<McpServerConfigResource> config,
                         ToolRegistry registry, McpDispatcher dispatcher, McpAuditLogger audit) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.config = config;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.audit = audit;
        this.policy = new SafetyPolicyEngine(config, true);
    }

    @Override
    public String moduleVersion() {
        return McpConstants.MODULE_VERSION;
    }

    @Override
    public String tools() throws IOException {
        var context = context();
        if (!policy.allowRequest(context.authContext().tokenName())) {
            throw new IOException("Designer tool request limit reached. Try again shortly.");
        }
        var catalog = mapper.createArrayNode();
        for (ToolDefinition tool : registry.definitions()) {
            if (policy.isToolAllowed(context.authContext().tokenName(), tool.name(), tool.mutating())) {
                catalog.addObject().put("name", tool.name()).put("description", tool.description())
                    .put("mutating", tool.mutating()).set("inputSchema", tool.inputSchema());
            }
        }
        return catalog.toString();
    }

    @Override
    public String call(String tool, String arguments) throws IOException {
        var context = context();
        ToolDefinition definition = registry.find(tool).orElseThrow(() -> new IOException("Unknown Designer tool."))
            .definition();
        if (arguments == null || arguments.length() > MAX_ARGUMENT_CHARS
                || !(mapper.readTree(arguments) instanceof ObjectNode args)) {
            throw new IOException("Designer action needs an argument object within the Gateway size limit.");
        }
        pinProject(definition, args, context.designerProject());
        if ("ignition.tags.definition.write".equals(tool)) {
            normalizeDefinitionCommits(args, !policy.isDryRun(args), 0);
        }
        var request = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", tool).set("arguments", args);
        return dispatcher.dispatch(request, context).toString();
    }

    static void pinProject(ToolDefinition tool, ObjectNode args, String project) throws IOException {
        var properties = tool.inputSchema().path("properties");
        for (String field : List.of("project", "targetProject")) {
            if (args.has(field) && (!args.path(field).isTextual() || !project.equals(args.path(field).asText()))) {
                throw new IOException("Open that project in Designer before requesting a project-specific action.");
            }
            if (properties.has(field)) {
                args.put(field, project);
            }
        }
    }

    private static void normalizeDefinitionCommits(ObjectNode args, boolean commit, int depth) throws IOException {
        if (depth > 8) {
            throw new IOException("The tag definition batch is nested too deeply.");
        }
        args.put("commit", commit);
        if (args.path("definitions").isArray()) {
            for (JsonNode child : args.path("definitions")) {
                if (!(child instanceof ObjectNode definition)) {
                    throw new IOException("Each tag definition needs a path and configuration.");
                }
                normalizeDefinitionCommits(definition, commit, depth + 1);
            }
        }
    }

    private ToolCallContext context() throws IOException {
        var session = RpcDelegate.session();
        if (session == null || !session.isValid() || !session.isDesigner()
                || !(session.getAttribute(ClientReqSession.SESSION_USERNAME) instanceof String username)
                || username.isBlank()) {
            throw new IOException("Sign in to Ignition Designer to use project tools.");
        }
        if (!(session.getAttribute(ClientReqSession.SESSION_PROJECT_NAME) instanceof String project)
                || project.isBlank()) {
            throw new IOException("Open a project in Ignition Designer to use project tools.");
        }
        var settings = config.get();
        if (!settings.enabled()) {
            throw new IOException("Ignition MCP tools are disabled on this Gateway.");
        }
        var actor = new McpAuthService.AuthContext(ACTOR_PREFIX + username, session.getPublicId(), null);
        return new ToolCallContext(gateway, RpcDelegate.getRequest(), actor, policy, audit, settings, mapper, project);
    }
}
