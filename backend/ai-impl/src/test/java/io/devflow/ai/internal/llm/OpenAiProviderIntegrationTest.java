package io.devflow.ai.internal.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.ai.internal.TestAiApplication;
import io.devflow.ai.internal.exception.AiProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance criteria 1 and 3: with a key, the shared ChatClient calls the (stubbed) OpenAI API with the
 * model from configuration; timeouts, retry and circuit breaker bound every failure, which surfaces as
 * an RFC 7807 503 without provider details. Never contacts the real OpenAI API.
 */
@SpringBootTest(classes = TestAiApplication.class, properties = {
        "devflow.ai.chat.model=gpt-test-model",
        "devflow.ai.chat.max-tokens=256",
        "devflow.ai.openai.api-key=sk-test-not-real",
        "devflow.ai.openai.read-timeout=300ms",
        "devflow.ai.resilience.max-attempts=3",
        "devflow.ai.resilience.initial-backoff=10ms",
        "devflow.ai.resilience.sliding-window-size=4",
        "devflow.ai.resilience.minimum-number-of-calls=4",
        "devflow.ai.resilience.failure-rate-threshold=50",
        "devflow.ai.resilience.wait-duration-in-open-state=60s"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("OpenAI ChatModel against a local provider stub")
class OpenAiProviderIntegrationTest {

    private static final OpenAiStubServer PROVIDER = new OpenAiStubServer();
    private static final String PROMPT_CANARY = "PROMPT-CANARY-7731";

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private AiChatGateway gateway;

    @Autowired
    private AiResilience resilience;

    @Autowired
    private AiProviderHealthIndicator healthIndicator;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("devflow.ai.openai.base-url", PROVIDER::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @BeforeEach
    void resetProvider() {
        PROVIDER.reset();
        resilience.circuitBreaker().reset();
    }

    @Test
    void callsOpenAiWithModelFromConfiguration() throws Exception {
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);

        AiCompletion completion = gateway.complete(AiFeature.CI_SUMMARY, "Summarize the failure.", PROMPT_CANARY);

        assertThat(completion.text()).isEqualTo(OpenAiStubServer.COMPLETION_TEXT);
        assertThat(completion.promptTokens()).isEqualTo(42);
        assertThat(completion.completionTokens()).isEqualTo(7);
        assertThat(PROVIDER.requests()).hasSize(1);
        OpenAiStubServer.RecordedRequest request = PROVIDER.requests().get(0);
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer sk-test-not-real");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.path("model").asText()).isEqualTo("gpt-test-model");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo(PROMPT_CANARY);
        assertThat(meterRegistry.counter("devflow.ai.tokens", "feature", "ci_summary", "type", "completion")
                .count()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void transientProviderErrorIsRetriedThenReturns503WithoutDetails(CapturedOutput output) throws Exception {
        PROVIDER.respond(503, "{\"error\":{\"message\":\"SECRET-PROVIDER-DETAIL\"}}", 0);

        String body = mockMvc.perform(post("/test/ai/complete").contentType(MediaType.TEXT_PLAIN)
                        .content(PROMPT_CANARY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andReturn().getResponse().getContentAsString();

        assertThat(PROVIDER.requests()).hasSize(3);
        assertThat(body).doesNotContain("SECRET-PROVIDER-DETAIL", "503 -", "openai", "sk-test");
        assertThat(output).doesNotContain(PROMPT_CANARY, "SECRET-PROVIDER-DETAIL", "sk-test-not-real");
        assertThat(meterRegistry.counter("devflow.ai.requests", "feature", "project_qa", "result", "error")
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rateLimitIsTreatedAsTransient() {
        PROVIDER.respond(429, "{}", 0);

        assertThatThrownBy(() -> gateway.complete(AiFeature.PROJECT_QA, null, "question"))
                .isInstanceOf(AiProviderUnavailableException.class);
        assertThat(PROVIDER.requests()).hasSize(3);
    }

    @Test
    void nonTransientProviderErrorIsNotRetried() {
        PROVIDER.respond(401, "{\"error\":{\"message\":\"bad key\"}}", 0);

        assertThatThrownBy(() -> gateway.complete(AiFeature.PROJECT_QA, null, "question"))
                .isInstanceOf(AiProviderUnavailableException.class);
        assertThat(PROVIDER.requests()).hasSize(1);
        assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void readTimeoutBoundsTheRequest() {
        PROVIDER.respond(200, OpenAiStubServer.SUCCESS_BODY, 2_000);

        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway.complete(AiFeature.PROJECT_QA, null, "question"))
                .isInstanceOf(AiProviderUnavailableException.class);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // 3 attempts x 300 ms read timeout + backoff, well under a single 2 s provider response.
        assertThat(PROVIDER.requests()).hasSize(3);
        assertThat(elapsedMs).isLessThan(1_900);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingProvider() {
        PROVIDER.respond(500, "{}", 0);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> gateway.complete(AiFeature.PROJECT_QA, null, "question"))
                    .isInstanceOf(AiProviderUnavailableException.class);
        }
        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhileClosed = PROVIDER.requests().size();

        assertThatThrownBy(() -> gateway.complete(AiFeature.PROJECT_QA, null, "question"))
                .isInstanceOf(AiProviderUnavailableException.class);

        assertThat(PROVIDER.requests()).hasSize(callsWhileClosed);
        assertThat(meterRegistry.counter("devflow.ai.requests", "feature", "project_qa", "result", "circuit_open")
                .count()).isGreaterThanOrEqualTo(1);
        assertThat(healthIndicator.health().getStatus()).isEqualTo(AiProviderHealthIndicator.DEGRADED);
        assertThat(healthIndicator.health().getDetails()).containsEntry("provider", AiProviderInfo.OPENAI)
                .containsEntry("model", "gpt-test-model");
    }
}
