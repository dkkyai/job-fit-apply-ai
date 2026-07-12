plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("jacoco")
}

group = "com.jd"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // HTTP client (bridge feed + submit)
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")

    // Gmail API (owned by the Poller — the only Gmail-touching service)
    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20250331-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.35.0")

    // Jakarta Mail (MIME assembly for draft replies)
    implementation("com.sun.mail:javax.mail:1.6.2")

    // HTML parsing (email body extraction)
    implementation("org.jsoup:jsoup:1.17.2")

    // Configuration
    implementation("io.github.cdimascio:dotenv-java:3.0.2")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.22")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
}

application {
    mainClass.set("com.jd.poller.cli.Main")
}

tasks.named<JavaExec>("run") {
    systemProperty("dotenv.file", System.getProperty("dotenv.file", ".env"))
    // Forward stdin so interactive prompts (e.g. --reauth) can read user input.
    standardInput = System.`in`
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
