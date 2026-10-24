package io.devflow.ai.internal.breakdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.ai.internal.TestAiApplication;
import io.devflow.ai.internal.support.TestActors;
import io.devflow.ai.internal.support.TestMembershipDirectory;
import io.devflow.common.event.DevFlowEvent;
import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T-032 acceptance criteria on the deterministic fallback model (no provider key): a valid 3-5 subtask
 * proposal, input limits, authentication, workspace membership (IDOR), per-user rate limit, and no task
 * created or task event published.
 */
@SpringBootTest(classes = TestAiApplication.class, properties = {
        "devflow.ai.rate-limit.capacity=3",
        "devflow.ai.rate-limit.period=1h"
})
@AutoConfigureMockMvc
@RecordApplicationEvents
@DisplayName("POST /api/v1/ai/task-breakdown (deterministic fallback model)")
class TaskBreakdownApiIntegrationTest {

    private static final String PATH = "/api/v1/ai/task-breakdown";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestMembershipDirectory membershipDirectory;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationEvents events;

    private UUID userId;
    private UUID workspaceId;

    @BeforeEach
    void signInMember() {
        userId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        membershipDirectory.addMember(userId, workspaceId);
        TestActors.signIn(userId);
    }

    @AfterEach
    void signOut() {
        TestActors.signOut();
    }

    @Test
    void returnsAValidProposalWithoutCreatingTasks() throws Exception {
        double successes = breakdownRequests("success");

        request(workspaceId, "Users need to reset a forgotten password by email.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtasks", hasSize(3)))
                .andExpect(jsonPath("$.subtasks[0].title").value("Clarify scope and acceptance criteria"))
                .andExpect(jsonPath("$.subtasks[*].description").isNotEmpty())
                .andExpect(jsonPath("$.subtasks[0].checklist", hasSize(3)))
                .andExpect(jsonPath("$.subtasks[2].checklist[0]").value("Add automated tests"));

        assertThat(breakdownRequests("success")).isEqualTo(successes + 1);
        // The only event is the read-only membership check: no task.created or any other domain event.
        assertThat(events.stream(DevFlowEvent.class))
                .allMatch(WorkspaceMembershipCheckRequestedEvent.class::isInstance)
                .hasSize(1);
    }

    @Test
    void acceptsExactlyTheMaximumDescriptionLength() throws Exception {
        request(workspaceId, "x".repeat(4000)).andExpect(status().isOk());
    }

    @Test
    void rejectsDescriptionOverFourThousandCharacters() throws Exception {
        double before = breakdownRequests("success");

        request(workspaceId, "x".repeat(4001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.description").exists());

        assertThat(breakdownRequests("success")).isEqualTo(before);
    }

    @Test
    void rejectsBlankDescriptionAndMissingWorkspace() throws Exception {
        request(workspaceId, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.description").exists());
        request(null, "A real requirement")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.workspaceId").exists());
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        TestActors.signOut();

        request(workspaceId, "A real requirement")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void deniesWorkspacesTheCallerIsNotAMemberOf() throws Exception {
        double before = breakdownRequests("success");

        request(UUID.randomUUID(), "A real requirement")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));

        assertThat(breakdownRequests("success")).isEqualTo(before);
    }

    @Test
    void rateLimitsPerUserWithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            request(workspaceId, "Requirement " + i).andExpect(status().isOk());
        }

        request(workspaceId, "One too many")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("AI_RATE_LIMITED"));

        // Another user keeps their own budget.
        UUID otherUser = UUID.randomUUID();
        membershipDirectory.addMember(otherUser, workspaceId);
        TestActors.signIn(otherUser);
        request(workspaceId, "Different user").andExpect(status().isOk());
        assertThat(meterRegistry.counter("devflow.ai.rate_limited", "feature", "breakdown").count())
                .isGreaterThanOrEqualTo(1);
    }

    private ResultActions request(UUID workspace, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", workspace);
        body.put("description", description);
        return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private double breakdownRequests(String result) {
        return meterRegistry.counter("devflow.ai.requests", "feature", "breakdown", "result", result).count();
    }
}
