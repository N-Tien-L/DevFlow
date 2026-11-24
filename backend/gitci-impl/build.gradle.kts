dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":common"))
    api(project(":gitci-api"))
    // Other modules' -api projects may be added here when a synchronous call is truly
    // needed. NEVER depend on another module's -impl (verified by :verifyModuleBoundaries).

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("io.micrometer:micrometer-core")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
