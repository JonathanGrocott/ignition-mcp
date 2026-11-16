plugins { java }

version = "0.4.4"
val designerSdkVersion = "8.3.9"
val localSdk = providers.gradleProperty("assistantSdkDir")
    .orElse(providers.systemProperty("user.home").map { "$it/.local/share/ignition-sdk/$designerSdkVersion" })

java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }

sourceSets.main { java.srcDir(rootProject.file("designer-rpc/src/main/java")) }

dependencies {
    val sdk = file(localSdk.get())
    if (sdk.resolve("core/designer/designer-$designerSdkVersion.jar").isFile) {
        compileOnly(fileTree(sdk.resolve("core")) {
            include("designer/*.jar", "client/*.jar", "common/*.jar")
        })
    } else {
        compileOnly("com.inductiveautomation.ignitionsdk:designer-api:$designerSdkVersion")
        compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:$designerSdkVersion")
    }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test { useJUnitPlatform() }

val descriptorValues = mapOf("version" to version.toString(), "sdk" to designerSdkVersion)
val moduleDescriptor by tasks.registering(Copy::class) {
    inputs.properties(descriptorValues)
    from("module.xml")
    into(layout.buildDirectory.dir("descriptor"))
    expand(descriptorValues)
}

val zipModule by tasks.registering(Zip::class) {
    dependsOn(moduleDescriptor, tasks.jar)
    from(moduleDescriptor)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    archiveFileName.set("ignition-designer-assistant.unsigned.modl")
    destinationDirectory.set(layout.buildDirectory)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.assemble { dependsOn(zipModule) }
