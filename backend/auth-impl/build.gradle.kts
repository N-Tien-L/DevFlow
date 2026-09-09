dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":common"))
    api(project(":auth-api"))
    // Other modules' -api projects may be added here when a synchronous call is truly
    // needed. NEVER depend on another module's -impl (verified by :verifyModuleBoundaries).

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-security")
}
