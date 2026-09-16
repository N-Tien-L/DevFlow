plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))

    // The ONLY project allowed to depend on -impl modules: it wires the modular
    // monolith into one deployable (ARCHITECTURE.md Sections 1 and 7).
    implementation(project(":common"))
    implementation(project(":auth-impl"))
    implementation(project(":board-impl"))
    implementation(project(":gitci-impl"))
    implementation(project(":ai-impl"))
    implementation(project(":notification-impl"))

    // API-layer infrastructure owned by the bootstrap: observability (ARCHITECTURE.md
    // Section 9 — Actuator + Prometheus) and the shared WebSocket broker (Section 5).
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Database migrations (T-001: Flyway)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
