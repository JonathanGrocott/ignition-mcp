package com.jg.ignition.mcp.common;

public record JsonRpcErrorPayload(int code, String message, Object data) {
}
