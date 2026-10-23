package io.devflow.ai.internal.llm;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only HTTP entry point into {@link AiChatGateway}, so tests can assert the RFC 7807 mapping of
 * provider failures. No production endpoint calls the LLM yet (T-031..T-034 add them).
 */
@RestController
class GatewayProbeController {

    private final AiChatGateway gateway;

    GatewayProbeController(AiChatGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/test/ai/complete")
    Map<String, String> complete(@RequestBody String prompt) {
        return Map.of("text", gateway.complete(AiFeature.PROJECT_QA, "You are a test assistant.", prompt).text());
    }
}
