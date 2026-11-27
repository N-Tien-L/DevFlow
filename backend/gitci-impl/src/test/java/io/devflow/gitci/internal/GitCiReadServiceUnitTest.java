package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import io.devflow.common.security.AuthenticatedActor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for fail-closed membership checks (SEC-04), per-actor rate limiting (SEC-07),
 * and RFC 7807 ProblemDetail error responses via GitCiReadControllerAdvice.
 */
class GitCiReadServiceUnitTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void membershipCheckFailsClosedWith503WhenNoListenerAmbiguousOrException() {
        UUID actorId = UUID.randomUUID();
        UUID wsId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        setActor(actorId);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        // 0 responses (no listener)
        GitCiReadService noListener = new GitCiReadService(
                jdbc, event -> {}, new GitCiReadRateLimiter(60, 60), new SimpleMeterRegistry());
        assertThatThrownBy(() -> noListener.getTaskSummaries(wsId, List.of(taskId)))
                .isInstanceOf(GitCiReadException.class)
                .satisfies(ex -> {
                    GitCiReadException readEx = (GitCiReadException) ex;
                    assertThat(readEx.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(readEx.code()).isEqualTo("SERVICE_UNAVAILABLE");
                });

        // 2 responses (ambiguous duplicate listener)
        ApplicationEventPublisher ambiguousPublisher = event -> {
            WorkspaceMembershipCheckRequestedEvent check = (WorkspaceMembershipCheckRequestedEvent) event;
            check.recordDecision(true);
            check.recordDecision(true);
        };
        GitCiReadService ambiguous = new GitCiReadService(
                jdbc, ambiguousPublisher, new GitCiReadRateLimiter(60, 60), new SimpleMeterRegistry());
        assertThatThrownBy(() -> ambiguous.getTaskSummaries(wsId, List.of(taskId)))
                .isInstanceOf(GitCiReadException.class)
                .satisfies(ex -> assertThat(((GitCiReadException) ex).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        // Exception from listener
        ApplicationEventPublisher throwingPublisher = event -> {
            throw new IllegalStateException("Auth DB down");
        };
        GitCiReadService throwing = new GitCiReadService(
                jdbc, throwingPublisher, new GitCiReadRateLimiter(60, 60), new SimpleMeterRegistry());
        assertThatThrownBy(() -> throwing.getTaskSummaries(wsId, List.of(taskId)))
                .isInstanceOf(GitCiReadException.class)
                .satisfies(ex -> assertThat(((GitCiReadException) ex).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void rateLimiterReturns429ProblemDetailWithRetryAfterAndIsolatesActors() throws Exception {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID wsId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        GitCiReadRateLimiter limiter = new GitCiReadRateLimiter(2, 60);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApplicationEventPublisher allowAll = event -> {
            if (event instanceof WorkspaceMembershipCheckRequestedEvent check) {
                check.recordDecision(true);
            }
        };
        GitCiReadService service = new GitCiReadService(mock(JdbcTemplate.class), allowAll, limiter, registry);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new GitCiReadController(service))
                .setControllerAdvice(new GitCiReadControllerAdvice(registry))
                .build();

        // Actor A consumes 2 tokens (both succeed against empty mock JdbcTemplate)
        setActor(actorA);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsId).param("taskIds", taskId.toString()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsId).param("taskIds", taskId.toString()))
                .andExpect(status().isOk());

        // 3rd call from Actor A hits 429 RATE_LIMIT_EXCEEDED with Retry-After header
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsId).param("taskIds", taskId.toString()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        // Actor B in same workspace still has independent quota and gets 200
        setActor(actorB);
        mvc.perform(get("/api/v1/gitci/workspaces/{ws}/task-summaries", wsId).param("taskIds", taskId.toString()))
                .andExpect(status().isOk());

        assertThat(registry.find("devflow.gitci.read.requests")
                .tag("outcome", "rate_limited")
                .tag("status", "429")
                .counter())
                .isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
    }

    private static void setActor(UUID userId) {
        AuthenticatedActor principal = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
