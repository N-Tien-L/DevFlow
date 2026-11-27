package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.devflow.gitci.api.TaskGitCiSummary;
import io.devflow.gitci.internal.GitCiSummaryAggregator.CiCandidateRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubUrlBuilderAndAggregatorTest {

    private static final String SHA_1 = "a".repeat(40);
    private static final String SHA_2 = "b".repeat(40);
    private static final String SHA_64 = "c".repeat(64);
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID REPO_A = UUID.randomUUID();
    private static final UUID REPO_B = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");

    @Test
    void buildsCanonicalGitHubUrlsAndRejectsUntrustedInputs() {
        assertThat(GitHubUrlBuilder.commitUrl("https://github.com/devflow/app.git", SHA_1))
                .isEqualTo("https://github.com/devflow/app/commit/" + SHA_1);
        assertThat(GitHubUrlBuilder.commitUrl("https://github.com/devflow/app/", SHA_64))
                .isEqualTo("https://github.com/devflow/app/commit/" + SHA_64);
        assertThat(GitHubUrlBuilder.pullRequestUrl("https://github.com/devflow/app.git", 42))
                .isEqualTo("https://github.com/devflow/app/pull/42");
        assertThat(GitHubUrlBuilder.ciAttemptUrl("https://github.com/devflow/app", "987654", 2))
                .isEqualTo("https://github.com/devflow/app/actions/runs/987654/attempts/2");

        // Reject zero SHA, invalid SHA, non-positive PR/run/attempt
        assertThat(GitHubUrlBuilder.commitUrl("https://github.com/devflow/app", "0".repeat(40))).isNull();
        assertThat(GitHubUrlBuilder.commitUrl("https://github.com/devflow/app", "ABCDEF".repeat(7))).isNull();
        assertThat(GitHubUrlBuilder.pullRequestUrl("https://github.com/devflow/app", 0)).isNull();
        assertThat(GitHubUrlBuilder.ciAttemptUrl("https://github.com/devflow/app", "0", 1)).isNull();
        assertThat(GitHubUrlBuilder.ciAttemptUrl("https://github.com/devflow/app", "abc", 1)).isNull();
        assertThat(GitHubUrlBuilder.ciAttemptUrl("https://github.com/devflow/app", "123", 0)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/devflow/app",
            "https://evil.example/devflow/app",
            "https://github.com.evil.example/devflow/app",
            "https://user:pass@github.com/devflow/app",
            "https://github.com:443/devflow/app",
            "https://github.com/devflow/app?token=secret",
            "https://github.com/devflow/app#frag",
            "https://github.com/../app",
            "https://github.com/devflow/..",
            "javascript:alert(1)",
            ""
    })
    void rejectsMaliciousRepositoryUrls(String badRepoUrl) {
        assertThat(GitHubUrlBuilder.normalizeRepositoryBase(badRepoUrl)).isNull();
        assertThat(GitHubUrlBuilder.commitUrl(badRepoUrl, SHA_1)).isNull();
        assertThat(GitHubUrlBuilder.pullRequestUrl(badRepoUrl, 1)).isNull();
        assertThat(GitHubUrlBuilder.ciAttemptUrl(badRepoUrl, "100", 1)).isNull();
    }

    @Test
    void sanitizesControlCharsAndTruncatesUnicodeCleanly() {
        String raw = "\u0000\u0007  feat(ui): T-026 hiển thị huy hiệu 🚀\nsecond line ignored";
        assertThat(GitHubUrlBuilder.messagePreview(raw))
                .isEqualTo("feat(ui): T-026 hiển thị huy hiệu 🚀");

        String longEmoji = "🚀".repeat(160);
        String truncated = GitHubUrlBuilder.messagePreview(longEmoji);
        assertThat(truncated).endsWith("…");
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(141);
    }

    @Test
    void rerunAttemptTwoSuccessSupersedesAttemptOneFailure() {
        CiCandidateRow attempt1 = candidate(REPO_A, "devflow/api", "https://github.com/devflow/api",
                SHA_1, T0, false, "101", 1, "FAILED", "failure", T0.plusSeconds(60));
        CiCandidateRow attempt2 = candidate(REPO_A, "devflow/api", "https://github.com/devflow/api",
                SHA_1, T0, false, "101", 2, "SUCCESS", "success", T0.plusSeconds(120));

        TaskGitCiSummary summary = GitCiSummaryAggregator.aggregateCi(List.of(attempt1, attempt2));
        assertThat(summary).isNotNull();
        assertThat(summary.status()).isEqualTo("SUCCESS");
        assertThat(summary.failedRunCount()).isZero();
        assertThat(summary.runAttempt()).isEqualTo(2);
        assertThat(summary.url()).isEqualTo("https://github.com/devflow/api/actions/runs/101/attempts/2");
    }

    @Test
    void multiWorkflowOnSameCommitFlagsFailureWithExactFailedRunCount() {
        CiCandidateRow lintPass = candidate(REPO_A, "devflow/api", "https://github.com/devflow/api",
                SHA_1, T0, false, "201", 1, "SUCCESS", "success", T0.plusSeconds(30));
        CiCandidateRow testFail = candidate(REPO_A, "devflow/api", "https://github.com/devflow/api",
                SHA_1, T0, false, "202", 1, "FAILED", "failure", T0.plusSeconds(45));
        CiCandidateRow secFail = candidate(REPO_A, "devflow/api", "https://github.com/devflow/api",
                SHA_1, T0, false, "203", 1, "FAILED", "failure", T0.plusSeconds(50));

        TaskGitCiSummary oneFail = GitCiSummaryAggregator.aggregateCi(List.of(lintPass, testFail));
        assertThat(oneFail.status()).isEqualTo("FAILED");
        assertThat(oneFail.failedRunCount()).isEqualTo(1);
        assertThat(oneFail.runId()).isEqualTo("202");

        TaskGitCiSummary twoFails = GitCiSummaryAggregator.aggregateCi(List.of(lintPass, testFail, secFail));
        assertThat(twoFails.status()).isEqualTo("FAILED");
        assertThat(twoFails.failedRunCount()).isEqualTo(2);
        assertThat(twoFails.runId()).isEqualTo("203");
    }

    @Test
    void newerGreenCommitWinsOverOlderFailedCommitEvenAfterOldRerun() {
        // Commit 1 failed at 10:00 and had a late rerun failure at 10:20
        CiCandidateRow oldCommitRerunFail = candidate(REPO_A, "devflow/api", "https://github.com/devflow/api",
                SHA_1, T0, true, "301", 2, "FAILED", "failure", T0.plusSeconds(1200));
        // Commit 2 (newer commit at 10:15) passed at 10:17
        CiCandidateRow newCommitPass = candidate(REPO_A, "devflow/api", "https://github.com/devflow/api",
                SHA_2, T0.plusSeconds(900), false, "302", 1, "SUCCESS", "success", T0.plusSeconds(1020));

        TaskGitCiSummary summary = GitCiSummaryAggregator.aggregateCi(List.of(oldCommitRerunFail, newCommitPass));
        assertThat(summary.status()).isEqualTo("SUCCESS");
        assertThat(summary.commitSha()).isEqualTo(SHA_2);
        assertThat(summary.staleForLatestCommit()).isFalse();
    }

    @Test
    void preservesFailureWithStaleFlagWhenNewerCommitHasNoCiYet() {
        CiCandidateRow oldCommitFail = candidate(REPO_A, "devflow/api", "https://github.com/devflow/api",
                SHA_1, T0, true, "401", 1, "FAILED", "failure", T0.plusSeconds(60));

        TaskGitCiSummary summary = GitCiSummaryAggregator.aggregateCi(List.of(oldCommitFail));
        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.staleForLatestCommit()).isTrue();
    }

    @Test
    void multiRepoAggregatesFailureAcrossRepositories() {
        CiCandidateRow fePass = candidate(REPO_A, "devflow/web", "https://github.com/devflow/web",
                SHA_1, T0, false, "501", 1, "SUCCESS", "success", T0.plusSeconds(60));
        CiCandidateRow beFail = candidate(REPO_B, "devflow/api", "https://github.com/devflow/api",
                SHA_2, T0, false, "502", 1, "FAILED", "failure", T0.plusSeconds(50));

        TaskGitCiSummary summary = GitCiSummaryAggregator.aggregateCi(List.of(fePass, beFail));
        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.repositoryName()).isEqualTo("devflow/api");
        assertThat(summary.url()).isEqualTo("https://github.com/devflow/api/actions/runs/502/attempts/1");
    }

    @ParameterizedTest
    @CsvSource({
            "FAILED,1",
            "TIMED_OUT,2",
            "ACTION_REQUIRED,3",
            "RUNNING,4",
            "PENDING,5",
            "CANCELLED,6",
            "SUCCESS,7",
            "SKIPPED,8",
            "NEUTRAL,8",
            "STALE,8",
            "UNKNOWN,8"
    })
    void explicitCiSeverityAndPrPriorityAreDeterministic(String status, int expectedSeverity) {
        assertThat(GitCiSummaryAggregator.ciStatusSeverity(status)).isEqualTo(expectedSeverity);
        assertThat(GitCiSummaryAggregator.prStatusPriority("OPEN")).isLessThan(GitCiSummaryAggregator.prStatusPriority("MERGED"));
        assertThat(GitCiSummaryAggregator.prStatusPriority("MERGED")).isLessThan(GitCiSummaryAggregator.prStatusPriority("CLOSED"));
    }

    @Test
    void readRateLimiterIsolatesPerActorAndReturnsPositiveRetryAfter() {
        GitCiReadRateLimiter limiter = new GitCiReadRateLimiter(2, 60);
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();

        limiter.check(actorA);
        limiter.check(actorA);
        assertThatThrownBy(() -> limiter.check(actorA))
                .isInstanceOfSatisfying(GitCiReadException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(429);
                    assertThat(ex.code()).isEqualTo("RATE_LIMIT_EXCEEDED");
                    assertThat(ex.retryAfterSeconds()).isPositive();
                });

        // Actor B has an independent bucket
        limiter.check(actorB);
    }

    private static CiCandidateRow candidate(
            UUID repoId, String repoName, String repoUrl, String sha, Instant shaTime, boolean stale,
            String runId, int attempt, String status, String conclusion, Instant finishedAt) {
        return new CiCandidateRow(
                TASK, repoId, sha, shaTime, shaTime, stale, repoName, repoUrl,
                runId, attempt, status, conclusion, shaTime, finishedAt, finishedAt, shaTime);
    }
}
