package com.jg.ignition.mcp.bridge;

import com.inductiveautomation.ignition.common.rpc.RpcInterface;

/** Wire contract compiled into both modules. Uses the current Designer's authenticated connection. */
@RpcInterface(packageId = "designer-assistant")
public interface DesignerMcpRpc {
    String MODULE_ID = "com.jg.ignition.mcp";

    /**
     * Version of the installed Ignition MCP Gateway module. Designer Assistant must match it exactly.
     * A Gateway module older than 0.6.0 lacks this method, so the call fails and Designer reports a mismatch.
     */
    String moduleVersion() throws Exception;

    String tools() throws Exception;

    String call(String tool, String arguments) throws Exception;
}
