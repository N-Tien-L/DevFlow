dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":common"))
    // Bean Validation annotations on request DTOs, as in auth-api and board-api.
    api("jakarta.validation:jakarta.validation-api")
    // Nothing else: an -api module exposes the module's public contract and may only
    // reference shared-kernel types from :common (ARCHITECTURE.md Section 10).
}
