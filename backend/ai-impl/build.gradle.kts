dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    // Spring AI BOM — pins the MCP Java SDK and model starters (ARCHITECTURE.md Section 9).
    api(platform("org.springframework.ai:spring-ai-bom:1.0.0"))
    api(project(":common"))
    api(project(":ai-api"))
    // Other modules' -api projects may be added here when a synchronous call is truly
    // needed (e.g. :board-api to read tasks for Q&A). NEVER another module's -impl.

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-websocket")

    // LLM client — provider-agnostic ChatClient. The OpenAI ChatModel is built by
    // AiChatClientConfig (not the starter's auto-config) so timeouts, retries and the
    // deterministic fallback stay under DevFlow's control (T-030).
    api("org.springframework.ai:spring-ai-starter-model-openai")

    // MCP server (SSE transport) exposing AI service capabilities to AI coding agents
    // (Cursor, Claude Code) — PRODUCT_SPEC.md Section 5.2c, ARCHITECTURE.md Sections 5 & 9.
    api("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // Resilience, metrics and health for outbound LLM calls (ARCHITECTURE.md Section 9.2).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-actuator")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.micrometer:micrometer-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Resilience4j's Boot auto-config references actuator auto-config once spring-boot-actuator is present.
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
}
