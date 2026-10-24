dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":common"))
    api(project(":notification-api"))
    implementation(project(":board-api"))
    implementation(project(":auth-api"))
    // Other modules' -api projects may be added here when a synchronous call is truly
    // needed. NEVER depend on another module's -impl (verified by :verifyModuleBoundaries).

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-websocket")
    implementation("io.micrometer:micrometer-core")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.security:spring-security-core")
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}

sourceSets.test {
    resources.srcDir("../app/src/main/resources")
    resources.include("db/migration/V11__notification_schema.sql")
}
