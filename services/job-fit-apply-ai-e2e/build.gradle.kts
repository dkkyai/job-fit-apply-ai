// Black-box e2e suite for the compose slice (bridge → processor → notifier).
// Test-only module: the fake LLM + notification sink run in the test JVM; the
// stack under test runs in Docker (make e2e-up). Every endpoint is an env var
// with a localhost default — see E2eConfig.

plugins {
    kotlin("jvm") version "1.9.25"
    id("io.qameta.allure") version "2.11.2"
}

group = "com.jd.e2e"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"
val jacksonVersion = "2.15.3"

dependencies {
    // In-process fake LLM + notification sink servers
    testImplementation("io.ktor:ktor-server-netty:$ktorVersion")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    // Direct `tracks` assertions
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("ch.qos.logback:logback-classic:1.4.14")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.25")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.qameta.allure:allure-junit5:2.27.0")
}

kotlin {
    jvmToolchain(21)
}

allure {
    version.set("2.27.0")
    useJUnit5 { adapterVersion.set("2.27.0") }
}

tasks.test {
    useJUnitPlatform()
    // An e2e run is never up-to-date — it exercises external state, not inputs Gradle can hash.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
