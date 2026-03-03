plugins {
    id("io.ia.sdk.modl") version("0.1.1")
}

val sdkVersion by extra("8.3.3")
val moduleVersion = "0.1.0-SNAPSHOT"

group = "com.jg.ignition"
version = moduleVersion

allprojects {
    group = "com.jg.ignition"
    version = moduleVersion
}

ignitionModule {
    name.set("Ignition MCP")
    id.set("com.jg.ignition.mcp")
    fileName.set("ignition-mcp")
    moduleVersion.set("${project.version}")
    moduleDescription.set("Gateway-hosted MCP server for Ignition 8.3")
    requiredIgnitionVersion.set("8.3.0")

    projectScopes.putAll(
        mapOf(
            ":common" to "G",
            ":gateway" to "G"
        )
    )

    moduleDependencies.set(emptyMap())

    hooks.putAll(
        mapOf(
            "com.jg.ignition.mcp.gateway.IgnitionMcpGatewayHook" to "G"
        )
    )

    skipModlSigning.set(true)
}
