package io.devflow.ai.internal.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.devflow.ai.internal.TestAiApplication;
import io.devflow.ai.internal.exception.AiPromptRejectedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance criterion 2: without a key the module boots and every caller gets the deterministic
 * answer through the same shared ChatClient.
 */
@SpringBootTest(classes = TestAiApplication.class, properties = {
        "devflow.ai.chat.model=gpt-4o-mini",
        "devflow.ai.openai.api-key=",
        "devflow.ai.chat.max-prompt-chars=100"
})
@AutoConfigureMockMvc
@DisplayName("Deterministic fallback ChatModel (no OPENAI_API_KEY)")
class DeterministicFallbackIntegrationTest {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private AiChatGateway gateway;

    @Autowired
    private AiProviderHealthIndicator healthIndicator;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registersDeterministicModelBehindSharedChatClient() {
        assertThat(chatModel).isInstanceOf(DeterministicChatModel.class);

        String first = chatClient.prompt().user("How many tasks are left?").call().content();
        String second = chatClient.prompt().user("Something completely different").call().content();

        assertThat(first).isEqualTo(DeterministicChatModel.RESPONSE_TEXT).isEqualTo(second);
    }

    @Test
    void gatewayReturnsDeterministicCompletionAndRecordsMetrics() {
        AiCompletion completion = gateway.complete(AiFeature.TASK_BREAKDOWN, "system", "Break down the login page");

        assertThat(completion.text()).isEqualTo(DeterministicChatModel.RESPONSE_TEXT);
        assertThat(completion.model()).isEqualTo(DeterministicChatModel.MODEL);
        assertThat(completion.promptTokens()).isPositive();
        assertThat(meterRegistry.counter("devflow.ai.requests", "feature", "task_breakdown", "result", "success")
                .count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.timer("devflow.ai.request.latency", "feature", "task_breakdown", "result", "success")
                .count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.counter("devflow.ai.tokens", "feature", "task_breakdown", "type", "prompt")
                .count()).isPositive();
    }

    @Test
    void rejectsEmptyAndOversizedPromptsBeforeAnyModelCall() {
        assertThatThrownBy(() -> gateway.complete(AiFeature.PROJECT_QA, null, "   "))
                .isInstanceOf(AiPromptRejectedException.class)
                .extracting("code").isEqualTo(AiChatGateway.PROMPT_EMPTY);
        assertThatThrownBy(() -> gateway.complete(AiFeature.PROJECT_QA, "s", "x".repeat(100)))
                .isInstanceOf(AiPromptRejectedException.class)
                .extracting("code").isEqualTo(AiChatGateway.PROMPT_TOO_LARGE);
        assertThat(meterRegistry.counter("devflow.ai.requests", "feature", "project_qa", "result", "rejected")
                .count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void oversizedPromptMapsToProblemDetail400() throws Exception {
        mockMvc.perform(post("/test/ai/complete").contentType(MediaType.TEXT_PLAIN).content("x".repeat(200)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AiChatGateway.PROMPT_TOO_LARGE))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void healthReportsDeterministicProvider() {
        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("provider", AiProviderInfo.DETERMINISTIC)
                .containsEntry("fallbackReason", "api_key_missing");
    }
}
