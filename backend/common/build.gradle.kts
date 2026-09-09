dependencies {
    // Shared kernel: event contract (ARCHITECTURE.md Section 4) + base entities.
    // Must not depend on any other module.
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api("org.springframework:spring-context")
    api("org.springframework.data:spring-data-jpa")
    // JPA annotations used by BaseEntity — not transitively exposed by spring-data-jpa.
    api("jakarta.persistence:jakarta.persistence-api")
    api("jakarta.annotation:jakarta.annotation-api")
}
