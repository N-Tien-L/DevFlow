dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":common"))
    api(project(":board-api"))
    // Other modules' -api projects may be added here when a synchronous call is truly
    // needed. NEVER depend on another module's -impl (verified by :verifyModuleBoundaries).

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "devflow.test.migration-location",
        rootProject.file("app/src/main/resources/db/migration").absolutePath
    )
}
