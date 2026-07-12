plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("jacoco")
}

group = "com.jd"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // JSON (JSearch API responses + bridge submit). HTTP uses the JDK's java.net.http client.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")

    // Configuration
    implementation("io.github.cdimascio:dotenv-java:3.0.2")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.22")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
}

application {
    mainClass.set("com.jd.jsearch.cli.Main")
}

tasks.named<JavaExec>("run") {
    systemProperty("dotenv.file", System.getProperty("dotenv.file", ".env"))
}

tasks.test {
    useJUnitPlatform()
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

kotlin {
    jvmToolchain(21)
}
