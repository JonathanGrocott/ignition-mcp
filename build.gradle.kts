plugins {
    id("io.ia.sdk.modl") version("0.1.1")
}

val sdkVersion by extra("8.3.3")
val moduleVersion = "0.4.0"

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

// The SDK plugin does not otherwise invalidate this staging task when a project JAR changes.
// Without these inputs an incremental zipModule can silently package the previous implementation.
tasks.named("assembleModlStructure") {
    dependsOn(":common:jar", ":gateway:jar")
    inputs.files(
        project(":common").layout.buildDirectory.file("libs/common-$moduleVersion.jar"),
        project(":gateway").layout.buildDirectory.file("libs/gateway-$moduleVersion.jar")
    )
    doFirst { delete(layout.buildDirectory.dir("moduleContent")) }
}

subprojects {
    tasks.matching { it.name == "collectModlDependencies" }.configureEach {
        inputs.file(layout.buildDirectory.file("libs/${project.name}-$moduleVersion.jar"))
        doFirst { delete(layout.buildDirectory.dir("artifacts")) }
    }
}

val verifyModulePackage by tasks.registering {
    dependsOn("zipModule")
    val moduleFile = layout.buildDirectory.file("ignition-mcp.unsigned.modl")
    inputs.file(moduleFile)
    val projectJars = listOf("common", "gateway").map { name ->
        project(":$name").layout.buildDirectory.file("libs/$name-$moduleVersion.jar")
    }
    inputs.files(projectJars)
    doLast {
        java.util.zip.ZipFile(moduleFile.get().asFile).use { module ->
            projectJars.forEach { provider ->
                val jar = provider.get().asFile
                val projectName = jar.name.substringBefore('-')
                val stagedNames = module.entries().asSequence()
                    .map { it.name }.filter { it.startsWith("$projectName-") && it.endsWith(".jar") }.toList()
                check(stagedNames == listOf(jar.name)) {
                    "Module contains missing, duplicate or stale $projectName JARs: $stagedNames"
                }
                val entry = module.getEntry(jar.name)
                    ?: error("Module is missing current project JAR: ${jar.name}")
                check(module.getInputStream(entry).use { it.readBytes() }.contentEquals(jar.readBytes())) {
                    "Module contains a stale project JAR: ${jar.name}"
                }
            }
        }
    }
}

tasks.named("checksumModl") { dependsOn(verifyModulePackage) }
