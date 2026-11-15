package com.jg.ignition.mcp.bridge;

import com.inductiveautomation.ignition.common.rpc.RpcInterface;

/** Wire contract compiled into both modules. Uses the current Designer's authenticated connection. */
@RpcInterface(packageId = "designer-assistant")
public interface DesignerMcpRpc {
    String MODULE_ID = "com.jg.ignition.mcp";

    String tools() throws Exception;

    String call(String tool, String arguments) throws Exception;
}
