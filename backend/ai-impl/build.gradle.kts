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

    // LLM client — provider-agnostic ChatClient; OpenAI is the default provider, enabled
    // with AI_MODEL_CHAT=openai + OPENAI_API_KEY (swappable per Section 9).
    api("org.springframework.ai:spring-ai-starter-model-openai")

    // MCP server (SSE transport) exposing AI service capabilities to AI coding agents
    // (Cursor, Claude Code) — PRODUCT_SPEC.md Section 5.2c, ARCHITECTURE.md Sections 5 & 9.
    api("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
}
