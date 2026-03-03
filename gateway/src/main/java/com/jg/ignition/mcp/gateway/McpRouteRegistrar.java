package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.inductiveautomation.ignition.gateway.auth.apitoken.ApiTokenManager;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jg.ignition.mcp.common.GlobMatcher;
import com.jg.ignition.mcp.common.McpConstants;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class McpRouteRegistrar {

    private static final String EVENT_STREAM_TYPE = "text/event-stream";
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final GatewayContext gatewayContext;
    private final String mountAlias;
    private final ObjectMapper objectMapper;
    private final McpConfigService configService;
    private final McpAuthService authService;
    private final SafetyPolicyEngine safetyPolicyEngine;
    private final McpSessionManager sessionManager;
    private final McpDispatcher dispatcher;
    private final McpAuditLogger auditLogger;

    public McpRouteRegistrar(
        GatewayContext gatewayContext,
        String mountAlias,
        ObjectMapper objectMapper,
        McpConfigService configService,
        McpAuthService authService,
        SafetyPolicyEngine safetyPolicyEngine,
        McpSessionManager sessionManager,
        McpDispatcher dispatcher,
        McpAuditLogger auditLogger
    ) {
        this.gatewayContext = gatewayContext;
        this.mountAlias = mountAlias;
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.authService = authService;
        this.safetyPolicyEngine = safetyPolicyEngine;
        this.sessionManager = sessionManager;
        this.dispatcher = dispatcher;
        this.auditLogger = auditLogger;
    }

    public void mount(RouteGroup routes) {
        routes.newRoute("/mcp")
            .method(HttpMethod.POST)
            .type(RouteGroup.TYPE_JSON)
            .acceptedTypes(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.ACCESS)
            .accessControl(ApiTokenManager.TOKEN_ACCESS)
            .handler(this::handleMcpPost)
            .mount();

        routes.newRoute("/mcp")
            .method(HttpMethod.GET)
            .type(EVENT_STREAM_TYPE)
            .requirePermission(PermissionType.ACCESS)
            .accessControl(ApiTokenManager.TOKEN_ACCESS)
            .handler(this::handleMcpGet)
            .mount();

        routes.newRoute("/mcp")
            .method(HttpMethod.DELETE)
            .type(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.ACCESS)
            .accessControl(ApiTokenManager.TOKEN_ACCESS)
            .handler(this::handleMcpDelete)
            .mount();

        routes.newRoute("/sse")
            .method(HttpMethod.GET)
            .type(EVENT_STREAM_TYPE)
            .requirePermission(PermissionType.ACCESS)
            .accessControl(ApiTokenManager.TOKEN_ACCESS)
            .handler(this::handleSseGet)
            .mount();

        routes.newRoute("/message")
            .method(HttpMethod.POST)
            .type(RouteGroup.TYPE_JSON)
            .acceptedTypes(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.ACCESS)
            .accessControl(ApiTokenManager.TOKEN_ACCESS)
            .handler(this::handleSseMessagePost)
            .mount();
    }

    private Object handleMcpPost(RequestContext requestContext, HttpServletResponse response) throws IOException {
        McpServerConfigResource config = configService.getConfig();
        Optional<ObjectNode> preflightError = checkPreflight(config, requestContext, response);
        if (preflightError.isPresent()) {
            return preflightError.get();
        }
        if (!config.streamableEnabled()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return simpleError("streamable transport is disabled");
        }

        McpAuthService.AuthContext authContext = authenticate(requestContext, response);
        if (authContext == null) {
            return simpleError("Unauthorized");
        }

        String sessionId = resolveSessionId(requestContext).orElseGet(() -> createSession(authContext, "streamable", config));
        if (!sessionManager.isOwnedBy(sessionId, authContext.actorFingerprint())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return simpleError("session is not owned by caller");
        }
        response.setHeader(SESSION_HEADER, sessionId);

        String body = requestContext.readBody();
        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        }
        catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return dispatcher.errorResponse(
                null,
                McpConstants.JSONRPC_PARSE_ERROR,
                "Parse error",
                e.getMessage()
            );
        }

        ToolCallContext toolContext = new ToolCallContext(
            gatewayContext,
            requestContext,
            authContext,
            safetyPolicyEngine,
            auditLogger,
            config,
            objectMapper
        );
        JsonNode rpcResponse = dispatcher.dispatch(payload, toolContext);
        if (rpcResponse == null) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return null;
        }

        sessionManager.enqueueEvent(sessionId, objectMapper.writeValueAsString(rpcResponse));
        return rpcResponse;
    }

    private Object handleMcpGet(RequestContext requestContext, HttpServletResponse response) {
        McpServerConfigResource config = configService.getConfig();
        Optional<String> preflightError = checkPreflightEventStream(config, requestContext, response);
        if (preflightError.isPresent()) {
            return preflightError.get();
        }
        if (!config.streamableEnabled()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "event: error\ndata: {\"message\":\"streamable transport disabled\"}\n\n";
        }

        McpAuthService.AuthContext authContext = authenticate(requestContext, response);
        if (authContext == null) {
            return "event: error\ndata: {\"message\":\"unauthorized\"}\n\n";
        }

        Optional<String> sessionId = resolveSessionId(requestContext);
        if (sessionId.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "event: error\ndata: {\"message\":\"sessionId required\"}\n\n";
        }
        if (!sessionManager.isOwnedBy(sessionId.get(), authContext.actorFingerprint())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "event: error\ndata: {\"message\":\"session not owned by caller\"}\n\n";
        }
        response.setHeader(SESSION_HEADER, sessionId.get());
        return toSsePayload(sessionManager.drainEvents(sessionId.get()));
    }

    private Object handleMcpDelete(RequestContext requestContext, HttpServletResponse response) {
        McpServerConfigResource config = configService.getConfig();
        Optional<ObjectNode> preflightError = checkPreflight(config, requestContext, response);
        if (preflightError.isPresent()) {
            return preflightError.get();
        }

        McpAuthService.AuthContext authContext = authenticate(requestContext, response);
        if (authContext == null) {
            return simpleError("Unauthorized");
        }

        Optional<String> sessionId = resolveSessionId(requestContext);
        if (sessionId.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return simpleError("sessionId required");
        }
        if (!sessionManager.isOwnedBy(sessionId.get(), authContext.actorFingerprint())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return simpleError("session not owned by caller");
        }
        sessionManager.closeSession(sessionId.get());

        ObjectNode out = objectMapper.createObjectNode();
        out.put("closed", true);
        out.put("sessionId", sessionId.get());
        return out;
    }

    private Object handleSseGet(RequestContext requestContext, HttpServletResponse response) throws IOException {
        McpServerConfigResource config = configService.getConfig();
        Optional<String> preflightError = checkPreflightEventStream(config, requestContext, response);
        if (preflightError.isPresent()) {
            return preflightError.get();
        }
        if (!config.sseFallbackEnabled()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "event: error\ndata: {\"message\":\"sse fallback disabled\"}\n\n";
        }

        McpAuthService.AuthContext authContext = authenticate(requestContext, response);
        if (authContext == null) {
            return "event: error\ndata: {\"message\":\"unauthorized\"}\n\n";
        }

        String sessionId = createSession(authContext, "sse", config);
        response.setHeader(SESSION_HEADER, sessionId);

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("sessionId", sessionId);
        metadata.put("messageEndpoint", "/main/data/" + mountAlias + "/message?sessionId=" + sessionId);
        sessionManager.enqueueEvent(sessionId, objectMapper.writeValueAsString(metadata));

        return toSsePayload(sessionManager.drainEvents(sessionId));
    }

    private Object handleSseMessagePost(RequestContext requestContext, HttpServletResponse response) throws IOException {
        McpServerConfigResource config = configService.getConfig();
        Optional<ObjectNode> preflightError = checkPreflight(config, requestContext, response);
        if (preflightError.isPresent()) {
            return preflightError.get();
        }
        if (!config.sseFallbackEnabled()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return simpleError("sse fallback transport is disabled");
        }

        McpAuthService.AuthContext authContext = authenticate(requestContext, response);
        if (authContext == null) {
            return simpleError("Unauthorized");
        }

        Optional<String> sessionId = resolveSessionId(requestContext);
        if (sessionId.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return simpleError("sessionId required");
        }
        if (!sessionManager.isOwnedBy(sessionId.get(), authContext.actorFingerprint())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return simpleError("session is not owned by caller");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(requestContext.readBody());
        }
        catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return dispatcher.errorResponse(
                null,
                McpConstants.JSONRPC_PARSE_ERROR,
                "Parse error",
                e.getMessage()
            );
        }

        ToolCallContext toolContext = new ToolCallContext(
            gatewayContext,
            requestContext,
            authContext,
            safetyPolicyEngine,
            auditLogger,
            config,
            objectMapper
        );
        JsonNode rpcResponse = dispatcher.dispatch(payload, toolContext);
        if (rpcResponse != null) {
            sessionManager.enqueueEvent(sessionId.get(), objectMapper.writeValueAsString(rpcResponse));
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("accepted", true);
        out.put("sessionId", sessionId.get());
        out.put("responseQueued", rpcResponse != null);
        return out;
    }

    private Optional<ObjectNode> checkPreflight(
        McpServerConfigResource config,
        RequestContext requestContext,
        HttpServletResponse response
    ) {
        if (!config.enabled()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return Optional.of(simpleError("module is disabled"));
        }

        if (!isOriginAllowed(config.allowedOrigins(), requestContext)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return Optional.of(simpleError("Origin not allowed"));
        }

        if (!isHostAllowed(config.allowedHosts(), requestContext)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return Optional.of(simpleError("Host not allowed"));
        }

        response.setHeader("Cache-Control", "no-store");
        return Optional.empty();
    }

    private Optional<String> checkPreflightEventStream(
        McpServerConfigResource config,
        RequestContext requestContext,
        HttpServletResponse response
    ) {
        if (!config.enabled()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return Optional.of("event: error\ndata: {\"message\":\"module is disabled\"}\n\n");
        }

        if (!isOriginAllowed(config.allowedOrigins(), requestContext)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return Optional.of("event: error\ndata: {\"message\":\"Origin not allowed\"}\n\n");
        }

        if (!isHostAllowed(config.allowedHosts(), requestContext)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return Optional.of("event: error\ndata: {\"message\":\"Host not allowed\"}\n\n");
        }

        response.setHeader("Cache-Control", "no-store");
        return Optional.empty();
    }

    private McpAuthService.AuthContext authenticate(RequestContext requestContext, HttpServletResponse response) {
        Optional<McpAuthService.AuthContext> auth = authService.authenticate(requestContext);
        if (auth.isPresent()) {
            return auth.get();
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return null;
    }

    private String createSession(
        McpAuthService.AuthContext authContext,
        String transportMode,
        McpServerConfigResource config
    ) {
        return sessionManager.createSession(authContext.actorFingerprint(), transportMode, config.maxConcurrentSessions());
    }

    private Optional<String> resolveSessionId(RequestContext requestContext) {
        String header = requestContext.getRequest().getHeader(SESSION_HEADER);
        if (StringUtils.isNotBlank(header)) {
            return Optional.of(header.trim());
        }
        String query = requestContext.getParameter("sessionId");
        if (StringUtils.isNotBlank(query)) {
            return Optional.of(query.trim());
        }
        return Optional.empty();
    }

    private boolean isOriginAllowed(List<String> allowedOrigins, RequestContext requestContext) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return true;
        }
        String origin = requestContext.getRequest().getHeader("Origin");
        if (StringUtils.isBlank(origin)) {
            return false;
        }
        return matchesAny(allowedOrigins, origin);
    }

    private boolean isHostAllowed(List<String> allowedHosts, RequestContext requestContext) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return true;
        }

        String host = requestContext.getRequest().getHeader("Host");
        if (StringUtils.isBlank(host)) {
            host = requestContext.getRequest().getServerName();
        }
        return matchesAny(allowedHosts, host);
    }

    private static boolean matchesAny(List<String> patterns, String value) {
        for (String pattern : patterns) {
            if (GlobMatcher.matches(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private static String toSsePayload(List<String> events) {
        StringBuilder builder = new StringBuilder();
        if (events == null || events.isEmpty()) {
            builder.append(": heartbeat\n\n");
            return builder.toString();
        }
        for (String event : events) {
            builder.append("event: message\n");
            builder.append("data: ").append(event).append("\n\n");
        }
        return builder.toString();
    }

    private ObjectNode simpleError(String message) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set("error", TextNode.valueOf(message));
        return out;
    }
}
