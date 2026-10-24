package io.devflow.ai.internal.breakdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.api.TaskBreakdownProposal.ProposedTask;
import io.devflow.ai.internal.TestAiApplication;
import io.devflow.ai.internal.llm.AiResilience;
import io.devflow.ai.internal.llm.OpenAiStubServer;
import io.devflow.ai.internal.support.TestActors;
import io.devflow.ai.internal.support.TestMembershipDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T-032 against a stubbed OpenAI provider: structured output request shape, one retry on a malformed
 * or invalid answer, and a clean 502 (never partial data) when the retry also fails.
 */
@SpringBootTest(classes = TestAiApplication.class, properties = {
        "devflow.ai.chat.model=gpt-test-model",
        "devflow.ai.openai.api-key=sk-test-not-real",
        "devflow.ai.openai.read-timeout=2s",
        "devflow.ai.resilience.initial-backoff=10ms",
        "devflow.ai.breakdown.max-output-tokens=1500"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Task breakdown against a local provider stub")
class TaskBreakdownProviderIntegrationTest {

    private static final OpenAiStubServer PROVIDER = new OpenAiStubServer();
    private static final String PATH = "/api/v1/ai/task-breakdown";
    private static final String INPUT_CANARY = "INPUT-CANARY-5521";
    private static final String ANSWER_CANARY = "ANSWER-CANARY-9034";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestMembershipDirectory membershipDirectory;

    @Autowired
    private AiResilience resilience;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID workspaceId;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("devflow.ai.openai.base-url", PROVIDER::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @BeforeEach
    void setUp() {
        PROVIDER.reset();
        resilience.circuitBreaker().reset();
        UUID userId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        membershipDirectory.addMember(userId, workspaceId);
        TestActors.signIn(userId);
    }

    @AfterEach
    void signOut() {
        TestActors.signOut();
    }

    @Test
    void sendsStructuredOutputRequestAndReturnsProposal() throws Exception {
        PROVIDER.respondInSequence(List.of(OpenAiStubServer.completionWithContent(json(validProposal(4)))));

        request("Add CSV export to the reports page. " + INPUT_CANARY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtasks", hasSize(4)))
                .andExpect(jsonPath("$.subtasks[0].title").value("Subtask 1"))
                .andExpect(jsonPath("$.subtasks[0].checklist[1]").value("Step b"));

        assertThat(PROVIDER.requests()).hasSize(1);
        JsonNode body = objectMapper.readTree(PROVIDER.requests().get(0).body());
        assertThat(body.path("model").asText()).isEqualTo("gpt-test-model");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1500);
        JsonNode messages = body.path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText())
                .startsWith(TaskBreakdownPrompts.ROLE_LINE)
                .doesNotContain(INPUT_CANARY);
        String user = messages.get(1).path("content").asText();
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(user).containsPattern("BEGIN REQUIREMENT [0-9a-f]{24}\\n[^\\n]*" + INPUT_CANARY)
                .contains("JSON Schema", "\"subtasks\"", "\"checklist\"");
    }

    @Test
    void retriesOnceAfterMalformedAnswer() throws Exception {
        PROVIDER.respondInSequence(List.of(
                OpenAiStubServer.completionWithContent("Sure! Here is the plan: " + ANSWER_CANARY),
                OpenAiStubServer.completionWithContent(json(validProposal(3)))));

        request("Build a notification bell").andExpect(status().isOk())
                .andExpect(jsonPath("$.subtasks", hasSize(3)));

        assertThat(PROVIDER.requests()).hasSize(2);
    }

    @Test
    void failsWith502AfterTwoMalformedAnswersWithoutLeakingThem(CapturedOutput output) throws Exception {
        double invalidBefore = breakdownRequests("invalid_output");
        PROVIDER.respondInSequence(List.of(
                OpenAiStubServer.completionWithContent("not json " + ANSWER_CANARY),
                OpenAiStubServer.completionWithContent("{\"subtasks\": [ " + ANSWER_CANARY)));

        String body = request("Build a notification bell " + INPUT_CANARY)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_OUTPUT_INVALID"))
                .andExpect(jsonPath("$.subtasks").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(PROVIDER.requests()).hasSize(2);
        assertThat(body).doesNotContain(ANSWER_CANARY);
        assertThat(output).doesNotContain(ANSWER_CANARY, INPUT_CANARY);
        assertThat(breakdownRequests("invalid_output")).isEqualTo(invalidBefore + 2);
        // The provider answered: malformed output is not an outage.
        assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void rejectsAnswersOutsideTheContractInsteadOfReturningThem() throws Exception {
        PROVIDER.respondInSequence(List.of(
                OpenAiStubServer.completionWithContent(json(validProposal(2))),
                OpenAiStubServer.completionWithContent(json(validProposal(6)))));

        request("Build a notification bell")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_OUTPUT_INVALID"));

        assertThat(PROVIDER.requests()).hasSize(2);
    }

    @Test
    void providerOutageIs503AndNotRetriedAsMalformed() throws Exception {
        PROVIDER.respond(503, "{}", 0);

        request("Build a notification bell")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));

        // Transport retry only (3 attempts); no extra output retry on top.
        assertThat(PROVIDER.requests()).hasSize(3);
    }

    private ResultActions request(String description) throws Exception {
        return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("workspaceId", workspaceId,
                        "description", description))));
    }

    private static TaskBreakdownProposal validProposal(int subtasks) {
        return new TaskBreakdownProposal(IntStream.rangeClosed(1, subtasks)
                .mapToObj(i -> new ProposedTask("Subtask " + i, "Do part " + i + ".", List.of("Step a", "Step b")))
                .toList());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private double breakdownRequests(String result) {
        return meterRegistry.counter("devflow.ai.requests", "feature", "breakdown", "result", result).count();
    }
}
