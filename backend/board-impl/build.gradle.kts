dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":common"))
    api(project(":board-api"))
    // Other modules' -api projects may be added here when a synchronous call is truly
    // needed. NEVER depend on another module's -impl (verified by :verifyModuleBoundaries).

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("io.micrometer:micrometer-core")

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
