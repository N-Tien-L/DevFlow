package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CiWorkflowIngestionTest {
    private static final String SHA40 = "a".repeat(40);
    private static final String SHA64 = "f".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private final CiWorkflowIngestion ingestion = new CiWorkflowIngestion(
            null, new GitHubActionsProperties(), new SimpleMeterRegistry());

    @ParameterizedTest
    @CsvSource({
            "failure,FAILED",
            "success,SUCCESS",
            "cancelled,CANCELLED",
            "skipped,SKIPPED",
            "timed_out,TIMED_OUT",
            "action_required,ACTION_REQUIRED",
            "neutral,NEUTRAL",
            "stale,STALE",
            "future_value,UNKNOWN"
    })
    void mapsCompletedConclusionsDeterministically(String conclusion, String expectedStatus) {
        var attempt = ingestion.prepare(valid(conclusion), "123");
        assertThat(attempt).isNotNull();
        assertThat(attempt.status()).isEqualTo(expectedStatus);
        assertThat(attempt.conclusion()).isEqualTo(conclusion);
        assertThat(attempt.runId()).isEqualTo("777");
        assertThat(attempt.number()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"requested", "in_progress", "future_action"})
    void ignoresNonCompletedActions(String action) {
        var root = valid("failure").put("action", action);
        assertThat(ingestion.prepare(root, "123")).isNull();
    }

    @Test
    void acceptsValidSha256NullableBranchMaxLengthBranchAndForkHeadRepository() {
        var root = valid("failure");
        root.withObject("/workflow_run")
                .put("head_sha", SHA64)
                .putNull("head_branch")
                .putObject("head_repository").put("id", 999999);
        var attempt = ingestion.prepare(root, "123");
        assertThat(attempt.sha()).isEqualTo(SHA64);
        assertThat(attempt.branch()).isNull();

        var maxBranch = valid("failure");
        maxBranch.withObject("/workflow_run").put("head_branch", "b".repeat(255));
        assertThat(ingestion.prepare(maxBranch, "123").branch()).hasSize(255);
    }

    @Test
    void rejectsMalformedOrOverflowingFieldsAndInvalidTimeOrSha() {
        // Invalid action format
        assertInvalid(valid("failure").put("action", "COMPLETED!"));
        assertInvalid(valid("failure").put("action", ""));
        // Missing/invalid workflow_run.id
        var missingId = valid("failure");
        missingId.withObject("/workflow_run").remove("id");
        assertInvalid(missingId);
        assertInvalid(mutateRun(r -> r.put("id", "777")));
        assertInvalid(mutateRun(r -> r.put("id", 0)));
        assertInvalid(mutateRun(r -> r.put("id", -5)));
        assertInvalid(mutateRun(r -> r.put("id", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))));
        // Missing/invalid run_attempt
        var missingAttempt = valid("failure");
        missingAttempt.withObject("/workflow_run").remove("run_attempt");
        assertInvalid(missingAttempt);
        assertInvalid(mutateRun(r -> r.put("run_attempt", "1")));
        assertInvalid(mutateRun(r -> r.put("run_attempt", 0)));
        assertInvalid(mutateRun(r -> r.put("run_attempt", -1)));
        assertInvalid(mutateRun(r -> r.put("run_attempt", (long) Integer.MAX_VALUE + 1L)));
        // Missing/invalid conclusion or status
        var missingConclusion = valid("failure");
        missingConclusion.withObject("/workflow_run").remove("conclusion");
        assertInvalid(missingConclusion);
        assertInvalid(mutateRun(r -> r.putNull("conclusion")));
        assertInvalid(mutateRun(r -> r.put("conclusion", "FAILURE")));
        assertInvalid(mutateRun(r -> r.put("conclusion", "a".repeat(51))));
        assertInvalid(mutateRun(r -> r.put("status", "in_progress")));
        // Invalid SHA variants
        assertInvalid(mutateRun(r -> r.put("head_sha", "a".repeat(39))));
        assertInvalid(mutateRun(r -> r.put("head_sha", "A".repeat(40))));
        assertInvalid(mutateRun(r -> r.put("head_sha", "g".repeat(40))));
        assertInvalid(mutateRun(r -> r.put("head_sha", "0".repeat(40))));
        assertInvalid(mutateRun(r -> r.put("head_sha", "0".repeat(64))));
        // Invalid branch variants
        assertInvalid(mutateRun(r -> r.put("head_branch", "")));
        assertInvalid(mutateRun(r -> r.put("head_branch", "   ")));
        assertInvalid(mutateRun(r -> r.put("head_branch", "b".repeat(256))));
        assertInvalid(mutateRun(r -> r.put("head_branch", "main\u0007")));
        assertInvalid(mutateRun(r -> r.put("head_branch", 42)));
        // Invalid timestamps and time ordering (updated_at < run_started_at)
        assertInvalid(mutateRun(r -> r.put("updated_at", "not-a-timestamp")));
        assertInvalid(mutateRun(r -> r.put("updated_at", "1969-12-31T23:59:59Z")));
        assertInvalid(mutateRun(r -> r.put("run_started_at", "2026-11-10T10:00:11Z")));
        // Invalid workflow_run.repository structure -> 400; mismatched integral ID -> 401
        assertInvalid(mutateRun(r -> r.putNull("repository")));
        assertInvalid(mutateRun(r -> r.putObject("repository").put("id", 0)));
        assertThatThrownBy(() -> ingestion.prepare(mutateRun(r -> r.putObject("repository").put("id", 999)), "123"))
                .isInstanceOf(WebhookFailure.class)
                .satisfies(ex -> {
                    var failure = (WebhookFailure) ex;
                    assertThat(failure.status).isEqualTo(401);
                    assertThat(failure.code).isEqualTo("WEBHOOK_UNAUTHORIZED");
                });
    }

    private void assertInvalid(ObjectNode root) {
        assertThatThrownBy(() -> ingestion.prepare(root, "123"))
                .isInstanceOf(WebhookFailure.class)
                .satisfies(ex -> {
                    var failure = (WebhookFailure) ex;
                    assertThat(failure.status).isEqualTo(400);
                    assertThat(failure.code).isEqualTo("WEBHOOK_INVALID_PAYLOAD");
                });
    }

    private ObjectNode mutateRun(java.util.function.Consumer<ObjectNode> mutator) {
        ObjectNode root = valid("failure");
        mutator.accept(root.withObject("/workflow_run"));
        return root;
    }

    private ObjectNode valid(String conclusion) {
        ObjectNode root = json.createObjectNode().put("action", "completed");
        root.putObject("repository").put("id", 123);
        root.putObject("workflow_run")
                .put("id", 777)
                .put("run_attempt", 1)
                .put("head_sha", SHA40)
                .put("head_branch", "main")
                .put("status", "completed")
                .put("conclusion", conclusion)
                .put("run_started_at", "2026-11-10T10:00:00Z")
                .put("updated_at", "2026-11-10T10:00:10Z")
                .putObject("repository").put("id", 123);
        return root;
    }
}
