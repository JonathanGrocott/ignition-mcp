package com.jg.ignition.mcp.common;

public final class McpConstants {
    public static final String MODULE_ID = "com.jg.ignition.mcp";
    public static final String MOUNT_ALIAS_DEFAULT = "ignition-mcp";

    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_PING = "ping";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_NOTIFICATIONS_INITIALIZED = "notifications/initialized";

    public static final String PROTOCOL_VERSION = "2025-06-18";

    public static final int JSONRPC_PARSE_ERROR = -32700;
    public static final int JSONRPC_INVALID_REQUEST = -32600;
    public static final int JSONRPC_METHOD_NOT_FOUND = -32601;
    public static final int JSONRPC_INVALID_PARAMS = -32602;
    public static final int JSONRPC_INTERNAL_ERROR = -32603;

    private McpConstants() {
    }
}
