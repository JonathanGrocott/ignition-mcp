plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdkVersion"]}")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdkVersion"]}")

    implementation(project(":common"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    modlImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdkVersion"]}")
    testImplementation("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdkVersion"]}")
}

tasks.test {
    useJUnitPlatform()
}
