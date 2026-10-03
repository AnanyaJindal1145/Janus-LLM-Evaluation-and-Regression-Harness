plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.janus"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // CLI framework — `eval run`, `eval compare`, `eval report`
    implementation("info.picocli:picocli:4.7.6")

    // YAML parsing for suites + targets (Jackson Kotlin module handles data classes)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.1")

    // Coroutines — the M5 concurrency substrate; Model interface is already suspend-based
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // SQLite (embedded, zero-ops) — persists run history for later comparison (M5)
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")

    // HTTP client for real provider calls (Gemini judge, Ollama subject, Groq). JDK
    // java.net.http would work too; OkHttp keeps timeouts + interceptors simple for M5.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.janus.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
