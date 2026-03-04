package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.ItemListRouteHandler;
import com.inductiveautomation.ignition.gateway.dataroutes.OpenApiOperationBuilder;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.ResponseRenderer;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.GroupObject;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.jg.ignition.mcp.common.McpConstants;
import com.jg.ignition.mcp.common.PermissionRequirement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpRouteRegistrarIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private GatewayContext gatewayContext;
    private AtomicReference<McpServerConfigResource> configRef;
    private McpConfigService configService;
    private McpSessionManager sessionManager;
    private McpRouteRegistrar registrar;
    private TestRouteGroup routes;

    @BeforeEach
    void setUp() {
        gatewayContext = mock(GatewayContext.class);

        configRef = new AtomicReference<>(defaultConfig());
        configService = mock(McpConfigService.class);
        when(configService.getConfig()).thenAnswer(invocation -> configRef.get());

        sessionManager = new McpSessionManager();
        SafetyPolicyEngine safetyPolicy = new SafetyPolicyEngine(() -> configRef.get());
        McpPermissionEvaluator permissiveEvaluator = new McpPermissionEvaluator() {
            @Override
            public PermissionDecision evaluate(PermissionRequirement requirement, RequestContext requestContext) {
                return PermissionDecision.allow();
            }
        };
        McpDispatcher dispatcher = new McpDispatcher(mapper, new ToolRegistry(), permissiveEvaluator);

        registrar = new McpRouteRegistrar(
            gatewayContext,
            McpConstants.MOUNT_ALIAS_DEFAULT,
            mapper,
            configService,
            new HeaderTokenAuthService(),
            safetyPolicy,
            sessionManager,
            dispatcher,
            new McpAuditLogger()
        );
        routes = new TestRouteGroup();
        registrar.mount(routes);
    }

    @Test
    void mcpInitializeListAndCallFlowOverPostMcp() throws Exception {
        InvocationResult init = invoke(
            "/mcp",
            HttpMethod.POST,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            Map.of("X-Ignition-API-Token", "tok-a"),
            Map.of()
        );
        assertEquals(200, init.statusCode());
        assertNotNull(init.headers().get("Mcp-Session-Id"));
        ObjectNode initBody = asObjectNode(init.body());
        assertEquals("2.0", initBody.path("jsonrpc").asText());
        assertEquals(McpConstants.PROTOCOL_VERSION, initBody.path("result").path("protocolVersion").asText());

        String sessionId = init.headers().get("Mcp-Session-Id");
        InvocationResult list = invoke(
            "/mcp",
            HttpMethod.POST,
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
            Map.of("X-Ignition-API-Token", "tok-a", "Mcp-Session-Id", sessionId),
            Map.of()
        );
        ObjectNode listBody = asObjectNode(list.body());
        assertTrue(listBody.path("result").path("tools").isArray());
        assertTrue(listBody.path("result").path("tools").size() >= 8);
        assertTrue(
            listBody.path("result").path("tools").toString().contains("ignition.tags.definition.read")
        );
        assertTrue(
            listBody.path("result").path("tools").toString().contains("ignition.tags.definition.write")
        );

        InvocationResult call = invoke(
            "/mcp",
            HttpMethod.POST,
            """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"ignition.tags.write",
                  "arguments":{"writes":[{"path":"[default]MCP/Setpoint","value":42}]}
                }}
                """,
            Map.of("X-Ignition-API-Token", "tok-a", "Mcp-Session-Id", sessionId),
            Map.of()
        );
        ObjectNode callBody = asObjectNode(call.body());
        assertFalse(callBody.path("result").path("isError").asBoolean());
        assertTrue(callBody.path("result").path("content").path(0).path("text").asText().contains("Dry-run"));
    }

    @Test
    void sseFallbackHandshakeAndMessageFlow() throws Exception {
        InvocationResult sse = invoke(
            "/sse",
            HttpMethod.GET,
            "",
            Map.of("X-Ignition-API-Token", "tok-sse"),
            Map.of()
        );
        assertEquals(200, sse.statusCode());
        String sessionId = sse.headers().get("Mcp-Session-Id");
        assertNotNull(sessionId);
        assertTrue(sse.body().toString().contains("messageEndpoint"));

        InvocationResult msg = invoke(
            "/message",
            HttpMethod.POST,
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\",\"params\":{}}",
            Map.of("X-Ignition-API-Token", "tok-sse"),
            Map.of("sessionId", sessionId)
        );
        ObjectNode msgBody = asObjectNode(msg.body());
        assertTrue(msgBody.path("accepted").asBoolean());
        assertTrue(msgBody.path("responseQueued").asBoolean());

        InvocationResult stream = invoke(
            "/mcp",
            HttpMethod.GET,
            "",
            Map.of("X-Ignition-API-Token", "tok-sse", "Mcp-Session-Id", sessionId),
            Map.of()
        );
        String payload = stream.body().toString();
        assertTrue(payload.contains("\"id\":9"));
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        InvocationResult result = invoke(
            "/mcp",
            HttpMethod.POST,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            Map.of(),
            Map.of()
        );
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, result.statusCode());
    }

    @Test
    void sessionHijackWithDifferentTokenIsRejected() throws Exception {
        InvocationResult sse = invoke(
            "/sse",
            HttpMethod.GET,
            "",
            Map.of("X-Ignition-API-Token", "owner-token"),
            Map.of()
        );
        String sessionId = sse.headers().get("Mcp-Session-Id");

        InvocationResult hijack = invoke(
            "/message",
            HttpMethod.POST,
            "{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"ping\",\"params\":{}}",
            Map.of("X-Ignition-API-Token", "other-token"),
            Map.of("sessionId", sessionId)
        );
        assertEquals(HttpServletResponse.SC_FORBIDDEN, hijack.statusCode());
    }

    @Test
    void writeBlockedByAllowlist() throws Exception {
        configRef.set(configWith(List.of("[default]SAFE/*"), 60));
        InvocationResult result = invoke(
            "/mcp",
            HttpMethod.POST,
            """
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{
                  "name":"ignition.tags.write",
                  "arguments":{"commit":true,"writes":[{"path":"[default]MCP/Setpoint","value":1}]}
                }}
                """,
            Map.of("X-Ignition-API-Token", "tok-block"),
            Map.of()
        );

        ObjectNode body = asObjectNode(result.body());
        assertTrue(body.path("result").path("isError").asBoolean());
        String text = body.path("result").path("content").path(0).path("text").asText();
        assertTrue(text.contains("blocked"));
    }

    @Test
    void writeBlockedByRateLimit() throws Exception {
        configRef.set(configWith(List.of("[default]MCP/*"), 1));

        InvocationResult first = invoke(
            "/mcp",
            HttpMethod.POST,
            """
                {"jsonrpc":"2.0","id":21,"method":"tools/call","params":{
                  "name":"ignition.tags.write",
                  "arguments":{"writes":[{"path":"[default]MCP/Setpoint","value":1}]}
                }}
                """,
            Map.of("X-Ignition-API-Token", "tok-rate"),
            Map.of()
        );
        assertFalse(asObjectNode(first.body()).path("result").path("isError").asBoolean());

        InvocationResult second = invoke(
            "/mcp",
            HttpMethod.POST,
            """
                {"jsonrpc":"2.0","id":22,"method":"tools/call","params":{
                  "name":"ignition.tags.write",
                  "arguments":{"writes":[{"path":"[default]MCP/Setpoint","value":2}]}
                }}
                """,
            Map.of("X-Ignition-API-Token", "tok-rate"),
            Map.of()
        );
        ObjectNode secondBody = asObjectNode(second.body());
        assertEquals(McpDispatcher.JSONRPC_RATE_LIMIT, secondBody.path("error").path("code").asInt());
    }

    private InvocationResult invoke(
        String path,
        HttpMethod method,
        String body,
        Map<String, String> headers,
        Map<String, String> params
    ) throws Exception {
        RouteHandler handler = routes.handlerFor(path, method);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(anyString())).thenAnswer(invocation -> headers.get(invocation.getArgument(0)));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getMethod()).thenReturn(method.name());
        when(request.getScheme()).thenReturn("http");
        when(request.getServerPort()).thenReturn(8088);
        when(request.getRequestURI()).thenReturn("/main/data/" + McpConstants.MOUNT_ALIAS_DEFAULT + path);
        when(request.getQueryString()).thenReturn(null);
        ServletContext servletContext = mock(ServletContext.class);
        when(servletContext.getAttribute(GatewayContext.SERVLET_CONTEXT_KEY)).thenReturn(gatewayContext);
        when(request.getServletContext()).thenReturn(servletContext);

        AtomicInteger statusCode = new AtomicInteger(HttpServletResponse.SC_OK);
        Map<String, String> responseHeaders = new HashMap<>();
        HttpServletResponse response = mock(HttpServletResponse.class);
        doAnswer(invocation -> {
            statusCode.set(invocation.getArgument(0));
            return null;
        }).when(response).setStatus(anyInt());
        doAnswer(invocation -> {
            responseHeaders.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(response).setHeader(anyString(), anyString());

        RequestContext requestContext = new StubRequestContext(request, path, body, params);
        Object result = handler.handle(requestContext, response);
        return new InvocationResult(result, statusCode.get(), responseHeaders);
    }

    private static ObjectNode asObjectNode(Object object) {
        return (ObjectNode) object;
    }

    private static McpServerConfigResource defaultConfig() {
        return new McpServerConfigResource(
            true,
            McpConstants.MOUNT_ALIAS_DEFAULT,
            List.of(),
            List.of(),
            true,
            true,
            200,
            300,
            60,
            true,
            50,
            List.of("*"),
            List.of("[default]MCP/*"),
            List.of("*"),
            List.of("*"),
            "",
            5000,
            1000
        );
    }

    private static McpServerConfigResource configWith(List<String> writePatterns, int maxWritesPerMinute) {
        return new McpServerConfigResource(
            true,
            McpConstants.MOUNT_ALIAS_DEFAULT,
            List.of(),
            List.of(),
            true,
            true,
            200,
            300,
            maxWritesPerMinute,
            true,
            50,
            List.of("*"),
            writePatterns,
            List.of("*"),
            List.of("*"),
            "",
            5000,
            1000
        );
    }

    private record InvocationResult(Object body, int statusCode, Map<String, String> headers) {
    }

    private static final class HeaderTokenAuthService extends McpAuthService {
        private HeaderTokenAuthService() {
            super(mock(GatewayContext.class));
        }

        @Override
        public Optional<AuthContext> authenticate(RequestContext requestContext) {
            String token = requestContext.getRequest().getHeader("X-Ignition-API-Token");
            if (token == null || token.isBlank()) {
                return Optional.empty();
            }
            String trimmed = token.trim();
            return Optional.of(new AuthContext(trimmed, "fp-" + trimmed, null));
        }
    }

    private static final class StubRequestContext extends RequestContext {
        private final String body;
        private final Map<String, String> params;

        private StubRequestContext(
            HttpServletRequest request,
            String path,
            String body,
            Map<String, String> params
        ) {
            super(request, path);
            this.body = body == null ? "" : body;
            this.params = params == null ? Map.of() : Map.copyOf(params);
        }

        @Override
        public String readBody() {
            return body;
        }

        @Override
        public String getParameter(String name) {
            return params.get(name);
        }
    }

    private static final class TestRouteGroup implements RouteGroup {
        private final Map<String, RouteHandler> handlers = new HashMap<>();

        @Override
        public RouteMounter newRoute(String path) {
            return new TestRouteMounter(path, handlers);
        }

        @Override
        public RouteGroup addOpenApiGroup(GroupObject groupObject) {
            return this;
        }

        private RouteHandler handlerFor(String path, HttpMethod method) {
            RouteHandler handler = handlers.get(key(path, method));
            if (handler == null) {
                throw new IllegalStateException("No handler mounted for " + method + " " + path);
            }
            return handler;
        }

        private static String key(String path, HttpMethod method) {
            return method.name() + " " + path;
        }
    }

    private static final class TestRouteMounter implements RouteGroup.RouteMounter {
        private final String path;
        private final Map<String, RouteHandler> handlers;
        private HttpMethod method = HttpMethod.GET;
        private RouteHandler handler;

        private TestRouteMounter(String path, Map<String, RouteHandler> handlers) {
            this.path = path;
            this.handlers = handlers;
        }

        @Override
        public RouteGroup.RouteMounter method(HttpMethod method) {
            this.method = method;
            return this;
        }

        @Override
        public RouteGroup.RouteMounter type(String type) {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter acceptedTypes(String... acceptedTypes) {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter handler(RouteHandler handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public <T> RouteGroup.ListRouteMounter<T> itemListHandler(ItemListRouteHandler<T> handler) {
            throw new UnsupportedOperationException("itemListHandler is not used in this test");
        }

        @Override
        public RouteGroup.RouteMounter renderer(ResponseRenderer renderer) {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter accessControl(AccessControlStrategy accessControlStrategy) {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter requirePermission(PermissionType permissionType) {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter concurrency(int maxConcurrentRequests, int maxQueuedRequests) {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter cache(long duration, TimeUnit unit, RouteGroup.CacheKeyGen cacheKeyGen) {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter nocache() {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter gzip() {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter gzip(boolean gzip) {
            return this;
        }

        @Override
        public RouteGroup.RouteMounter openApi(
            java.util.function.Consumer<OpenApiOperationBuilder> operationBuilderConsumer
        ) {
            return this;
        }

        @Override
        public void mount() {
            if (handler == null) {
                throw new IllegalStateException("Route handler is required");
            }
            handlers.put(TestRouteGroup.key(path, method), handler);
        }
    }
}
