dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":common"))
    // Nothing else: an -api module exposes the module's public contract and may only
    // reference shared-kernel types from :common (ARCHITECTURE.md Section 10).
}
