package io.devflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.devflow.ai.internal.llm.AiChatGateway;
import io.devflow.ai.internal.llm.AiCompletion;
import io.devflow.ai.internal.llm.AiFeature;
import io.devflow.ai.internal.llm.DeterministicChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T-030 acceptance criteria 2 and 4 on the full application: it boots without an OpenAI key, LLM calls
 * use the deterministic fallback, and request/latency/token metrics plus provider health are exported.
 */
@SpringBootTest(properties = {
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.endpoint.health.show-details=always"
})
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@DisplayName("AI LLM client observability (/actuator/prometheus, /actuator/health)")
class AiObservabilityIntegrationTest {

    @Autowired
    private AiChatGateway gateway;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportsRequestLatencyAndTokenMetrics() throws Exception {
        AiCompletion completion = gateway.complete(AiFeature.PROJECT_QA, "system", "How many tasks are left?");
        assertThat(completion.text()).isEqualTo(DeterministicChatModel.RESPONSE_TEXT);

        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .containsPattern("devflow_ai_requests_total\\{feature=\"qa\",result=\"success\"} [1-9]")
                .containsPattern("devflow_ai_request_latency_seconds_count\\{feature=\"qa\",result=\"success\"} [1-9]")
                .containsPattern("devflow_ai_tokens_total\\{feature=\"qa\",type=\"prompt\"} [1-9]")
                .containsPattern("devflow_ai_tokens_total\\{feature=\"qa\",type=\"completion\"} [1-9]");
    }

    @Test
    void healthReportsProviderWithoutAffectingOverallStatus() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.aiProvider.status").value("UP"))
                .andExpect(jsonPath("$.components.aiProvider.details.provider").value("deterministic"))
                .andExpect(jsonPath("$.components.aiProvider.details.circuitBreaker").value("CLOSED"));
    }
}
