package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.devflow.gitci.internal.GitHubActionsReadClient.FetchFailure;
import io.devflow.gitci.internal.GitHubActionsReadClient.Kind;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubActionsReadClientTest {
    private static final UUID REPO = UUID.randomUUID();
    private static final String TOKEN = "github_pat_synthetic_token_canary";
    private static final String SHA = "a".repeat(40);
    private static final String STORAGE = "results-receiver.actions.githubusercontent.com";
    private static final String STORAGE_2 = "pipelinesghubeus1.blob.core.windows.net";
    private static final String PREFIX = "https://api.github.com/repos/owner/project/actions/runs/77/attempts/2";
    private static final GitHubActionsReadClient.Target TARGET = new GitHubActionsReadClient.Target(
            REPO, "123", "https://github.com/owner/project.git", "77", 2, SHA);

    @Test
    void requestsExactAttemptAndNeverSendsApiCredentialsToArchive() throws Exception {
        var http = mock(CloseableHttpClient.class);
        var archive = new byte[] {1, 2, 3};
        var responses = new ArrayDeque<>(List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(99, 77, 2, SHA, "build", "failure", "tests"))),
                redirect("https://" + STORAGE + "/signed?test=example"),
                archiveReply(archive)));
        var requests = new ArrayList<ClassicHttpRequest>();
        var uris = new ArrayList<String>();
        stubHttp(http, responses, requests, uris);
        try (var client = new ManagedClient(new GitHubActionsReadClient(configuredProperties(), http))) {
            var result = client.value().fetch(TARGET, deadline());
            assertThat(result.bytes()).containsExactly(archive);
            assertThat(result.jobs()).containsExactly(new GitHubActionsReadClient.Job(99, "build", true, List.of("tests")));
            assertThat(result.truncated()).isFalse();
        }
        assertThat(uris).containsExactly(
                PREFIX, PREFIX + "/jobs?per_page=100&page=1", PREFIX + "/logs", "https://" + STORAGE + "/signed?test=example");
        assertThat(uris).noneMatch(u -> u.endsWith("/runs/77/logs"));
        assertThat(requests.subList(0, 3)).allSatisfy(request -> {
            assertThat(request.getFirstHeader("Authorization").getValue()).isEqualTo("Bearer " + TOKEN);
            assertThat(request.getFirstHeader("Accept").getValue()).isEqualTo("application/vnd.github+json");
            assertThat(request.getFirstHeader("X-GitHub-Api-Version").getValue()).isEqualTo("2026-03-10");
        });
        assertThat(requests.get(3).getFirstHeader("Authorization")).isNull();
        assertThat(requests.get(3).getFirstHeader("Cookie")).isNull();
        assertThat(requests.get(3).getFirstHeader("X-GitHub-Api-Version")).isNull();
    }

    @Test
    void paginatesJobsUpToConfiguredPageCapAndIgnoresMaliciousLinkHeaders() throws Exception {
        var http = mock(CloseableHttpClient.class);
        var props = configuredProperties();
        props.setMaxJobPages(2);
        var page1Jobs = new ArrayList<String>();
        var page2Jobs = new ArrayList<String>();
        for (int i = 1; i <= 100; i++) {
            page1Jobs.add(jobEntry(i, 77, 2, SHA, "job-" + i, i == 1 ? "failure" : "success", "step"));
            page2Jobs.add(jobEntry(100 + i, 77, 2, SHA, "job-" + (100 + i), "success", "step"));
        }
        var page1 = jobsJson(250, page1Jobs);
        page1.setHeader("Link", "<http://127.0.0.1/evil?page=2>; rel=\"next\"");
        var page2 = jobsJson(250, page2Jobs);
        page2.setHeader("Link", "<https://evil.invalid/page=3>; rel=\"next\"");
        var responses = new ArrayDeque<>(List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                page1,
                page2,
                redirect("https://" + STORAGE + "/signed?ok=1"),
                archiveReply(new byte[] {9, 8})));
        var uris = new ArrayList<String>();
        stubHttp(http, responses, new ArrayList<>(), uris);
        try (var client = new ManagedClient(new GitHubActionsReadClient(props, http))) {
            var result = client.value().fetch(TARGET, deadline());
            assertThat(result.jobs()).hasSize(200);
            assertThat(result.truncated()).isTrue();
        }
        assertThat(uris).containsExactly(
                PREFIX,
                PREFIX + "/jobs?per_page=100&page=1",
                PREFIX + "/jobs?per_page=100&page=2",
                PREFIX + "/logs",
                "https://" + STORAGE + "/signed?ok=1");
    }

    @Test
    void rejectsRunAndJobMetadataIdentityMismatches() throws Exception {
        // Wrong attempt in run metadata
        assertFetchFailure(
                List.of(runJson(77, 1, 123, SHA, "completed", "failure")),
                Kind.UNAVAILABLE, "RUN_IDENTITY_MISMATCH");
        // Wrong SHA in run metadata
        assertFetchFailure(
                List.of(runJson(77, 2, 123, "b".repeat(40), "completed", "failure")),
                Kind.UNAVAILABLE, "RUN_IDENTITY_MISMATCH");
        // Wrong repository ID in run metadata
        assertFetchFailure(
                List.of(runJson(77, 2, 999, SHA, "completed", "failure")),
                Kind.UNAVAILABLE, "RUN_IDENTITY_MISMATCH");
        // Wrong conclusion in run metadata
        assertFetchFailure(
                List.of(runJson(77, 2, 123, SHA, "completed", "success")),
                Kind.UNAVAILABLE, "RUN_IDENTITY_MISMATCH");
        // Wrong job SHA
        assertFetchFailure(
                List.of(runJson(77, 2, 123, SHA, "completed", "failure"),
                        jobsJson(1, List.of(jobEntry(10, 77, 2, "c".repeat(40), "build", "failure", "s")))),
                Kind.UNAVAILABLE, "JOB_IDENTITY_MISMATCH");
        // Duplicate job ID
        assertFetchFailure(
                List.of(runJson(77, 2, 123, SHA, "completed", "failure"),
                        jobsJson(2, List.of(
                                jobEntry(10, 77, 2, SHA, "build", "failure", "s"),
                                jobEntry(10, 77, 2, SHA, "build", "failure", "s")))),
                Kind.UNAVAILABLE, "JOB_IDENTITY_MISMATCH");
    }

    @Test
    void classifiesAuthThrottleNotFoundGoneExpiredArchiveAndUpstreamStatuses() throws Exception {
        // Missing credential never invokes HTTP client
        var unusedHttp = mock(CloseableHttpClient.class);
        try (var client = new ManagedClient(new GitHubActionsReadClient(new GitHubActionsProperties(), unusedHttp))) {
            assertThatThrownBy(() -> client.value().fetch(TARGET, deadline()))
                    .isInstanceOf(FetchFailure.class)
                    .satisfies(ex -> {
                        var failure = (FetchFailure) ex;
                        assertThat(failure.kind).isEqualTo(Kind.DENIED);
                        assertThat(failure.code).isEqualTo("CREDENTIAL_NOT_CONFIGURED");
                    });
            verifyNoInteractions(unusedHttp);
        }

        // API 401 & 403 -> DENIED
        assertFetchFailure(List.of(statusResponse(401)), Kind.DENIED, "ACTIONS_READ_DENIED");
        assertFetchFailure(List.of(statusResponse(403)), Kind.DENIED, "ACTIONS_READ_DENIED");

        // API 429 with Retry-After -> TRANSIENT + delay
        var tooMany = statusResponse(429);
        tooMany.setHeader("Retry-After", "45");
        var throttled = assertFetchFailure(List.of(tooMany), Kind.TRANSIENT, "GITHUB_THROTTLED");
        assertThat(throttled.delay).isEqualTo(45);

        // Secondary rate limit 403 with X-RateLimit-Remaining: 0
        var secondary = statusResponse(403);
        secondary.setHeader("X-RateLimit-Remaining", "0");
        secondary.setHeader("X-RateLimit-Reset", Long.toString(Instant.now().getEpochSecond() + 90));
        var secondaryFailure = assertFetchFailure(List.of(secondary), Kind.TRANSIENT, "GITHUB_THROTTLED");
        assertThat(secondaryFailure.delay).isBetween(80L, 95L);

        // API 503 -> TRANSIENT
        assertFetchFailure(List.of(statusResponse(503)), Kind.TRANSIENT, "GITHUB_UPSTREAM");

        // 404 on run, jobs, and logs
        assertFetchFailure(List.of(statusResponse(404)), Kind.NOT_FOUND, "RUN_NOT_FOUND");
        assertFetchFailure(List.of(runJson(77, 2, 123, SHA, "completed", "failure"), statusResponse(404)),
                Kind.NOT_FOUND, "JOBS_NOT_FOUND");
        assertFetchFailure(List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(9, 77, 2, SHA, "build", "failure", "s"))),
                statusResponse(404)), Kind.NOT_FOUND, "ARCHIVE_NOT_FOUND");

        // 410 Gone -> UNAVAILABLE
        assertFetchFailure(List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(9, 77, 2, SHA, "build", "failure", "s"))),
                statusResponse(410)), Kind.UNAVAILABLE, "ARCHIVE_GONE");

        // Expired signed archive URL (401/403 on storage host) -> TRANSIENT ARCHIVE_URL_EXPIRED
        assertFetchFailure(List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(9, 77, 2, SHA, "build", "failure", "s"))),
                redirect("https://" + STORAGE + "/signed?exp=0"),
                statusResponse(403)), Kind.TRANSIENT, "ARCHIVE_URL_EXPIRED");

        // Non-redirect 200 on /logs rejected
        assertFetchFailure(List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(9, 77, 2, SHA, "build", "failure", "s"))),
                statusResponse(200)), Kind.UNAVAILABLE, "ARCHIVE_REDIRECT_REQUIRED");
    }

    @Test
    void enforcesRedirectLoopsHopLimitStreamSizeCapAndDeadline() throws Exception {
        // Multi-hop within limit succeeds
        var props = configuredProperties();
        props.setArchiveHosts(Set.of(STORAGE, STORAGE_2));
        var http = mock(CloseableHttpClient.class);
        var uris = new ArrayList<String>();
        stubHttp(http, new ArrayDeque<>(List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(9, 77, 2, SHA, "build", "failure", "s"))),
                redirect("https://" + STORAGE + "/hop1"),
                redirect("https://" + STORAGE_2 + "/hop2"),
                archiveReply(new byte[] {4, 5}))), new ArrayList<>(), uris);
        try (var client = new ManagedClient(new GitHubActionsReadClient(props, http))) {
            assertThat(client.value().fetch(TARGET, deadline()).bytes()).containsExactly(4, 5);
        }

        // Redirect loop (A -> B -> A) rejected
        assertFetchFailure(props, List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(9, 77, 2, SHA, "build", "failure", "s"))),
                redirect("https://" + STORAGE + "/loopA"),
                redirect("https://" + STORAGE_2 + "/loopB"),
                redirect("https://" + STORAGE + "/loopA")), Kind.UNAVAILABLE, "ARCHIVE_REDIRECT_LIMIT");

        // Too many hops (> maxArchiveHops=3) rejected
        assertFetchFailure(props, List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(9, 77, 2, SHA, "build", "failure", "s"))),
                redirect("https://" + STORAGE + "/1"),
                redirect("https://" + STORAGE + "/2"),
                redirect("https://" + STORAGE + "/3"),
                redirect("https://" + STORAGE + "/4")), Kind.UNAVAILABLE, "ARCHIVE_REDIRECT_LIMIT");

        // Chunked stream exceeding maxArchiveBytes mid-stream (unknown Content-Length = -1) rejected
        var smallProps = configuredProperties();
        smallProps.setMaxArchiveBytes(1024);
        var streamed = new BasicClassicHttpResponse(200);
        streamed.setEntity(new InputStreamEntity(
                new java.io.ByteArrayInputStream(new byte[1025]), -1, ContentType.APPLICATION_OCTET_STREAM));
        assertFetchFailure(smallProps, List.of(
                runJson(77, 2, 123, SHA, "completed", "failure"),
                jobsJson(1, List.of(jobEntry(9, 77, 2, SHA, "build", "failure", "s"))),
                redirect("https://" + STORAGE + "/stream"),
                streamed), Kind.UNAVAILABLE, "LIMIT_EXCEEDED");

        // Expired deadline before request -> CLAIM_TIMEOUT without HTTP call
        var noCallHttp = mock(CloseableHttpClient.class);
        try (var client = new ManagedClient(new GitHubActionsReadClient(configuredProperties(), noCallHttp))) {
            assertThatThrownBy(() -> client.value().fetch(TARGET, System.nanoTime() - 1))
                    .isInstanceOf(FetchFailure.class)
                    .hasMessage("CLAIM_TIMEOUT");
            verifyNoInteractions(noCallHttp);
        }
    }

    @Test
    void circuitBreakerOpensOnlyOnTransientFailuresAndShortCircuitsFurtherCalls() throws Exception {
        var http = mock(CloseableHttpClient.class);
        when(http.execute(any(ClassicHttpRequest.class), org.mockito.ArgumentMatchers.<HttpClientResponseHandler<Object>>any()))
                .thenThrow(new IOException("connection reset"));
        try (var client = new ManagedClient(new GitHubActionsReadClient(configuredProperties(), http))) {
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> client.value().fetch(TARGET, deadline()))
                        .isInstanceOf(FetchFailure.class)
                        .satisfies(ex -> assertThat(((FetchFailure) ex).kind).isEqualTo(Kind.TRANSIENT));
            }
            // 6th call must hit open circuit without calling HTTP
            assertThatThrownBy(() -> client.value().fetch(TARGET, deadline()))
                    .isInstanceOf(FetchFailure.class)
                    .satisfies(ex -> {
                        var failure = (FetchFailure) ex;
                        assertThat(failure.kind).isEqualTo(Kind.CIRCUIT_OPEN);
                        assertThat(failure.code).isEqualTo("CIRCUIT_OPEN");
                        assertThat(failure.delay).isEqualTo(30);
                    });
        }
        verify(http, times(5)).execute(any(ClassicHttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpClientResponseHandler<Object>>any());

        // Non-transient DENIED failures must not trip the circuit breaker
        var authHttp = mock(CloseableHttpClient.class);
        when(authHttp.execute(any(ClassicHttpRequest.class), org.mockito.ArgumentMatchers.<HttpClientResponseHandler<Object>>any()))
                .thenAnswer(call -> call.<HttpClientResponseHandler<Object>>getArgument(1)
                        .handleResponse(statusResponse(401)));
        try (var client = new ManagedClient(new GitHubActionsReadClient(configuredProperties(), authHttp))) {
            for (int i = 0; i < 6; i++) {
                assertThatThrownBy(() -> client.value().fetch(TARGET, deadline()))
                        .isInstanceOf(FetchFailure.class)
                        .hasMessage("ACTIONS_READ_DENIED");
            }
        }
        verify(authHttp, times(6)).execute(any(ClassicHttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpClientResponseHandler<Object>>any());

        // UnknownHostException maps to TRANSIENT GITHUB_DNS
        var dnsHttp = mock(CloseableHttpClient.class);
        when(dnsHttp.execute(any(ClassicHttpRequest.class), org.mockito.ArgumentMatchers.<HttpClientResponseHandler<Object>>any()))
                .thenThrow(new UnknownHostException("api.github.com"));
        try (var client = new ManagedClient(new GitHubActionsReadClient(configuredProperties(), dnsHttp))) {
            assertThatThrownBy(() -> client.value().fetch(TARGET, deadline()))
                    .isInstanceOf(FetchFailure.class)
                    .hasMessage("GITHUB_DNS");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://results-receiver.actions.githubusercontent.com/test",
            "https://localhost/test",
            "https://127.0.0.1/test",
            "https://evil.com/test",
            "https://fakeactions.githubusercontent.com/test",
            "https://results-receiver.actions.githubusercontent.com:8443/test",
            "https://example@results-receiver.actions.githubusercontent.com/test",
            "https://results-receiver.actions.githubusercontent.com/test#frag",
            "/relative/path"
    })
    void rejectsRedirectOutsideApprovedHttpsTarget(String destination) {
        assertThatThrownBy(() -> GitHubActionsReadClient.archiveUri(destination, Set.of(STORAGE)))
                .hasMessage("ARCHIVE_HOST_BLOCKED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/owner/repo",
            "https://localhost/owner/repo",
            "https://127.0.0.1/owner/repo",
            "file:///etc/passwd",
            "https://user:pass@github.com/owner/repo",
            "https://github.com:443/owner/repo",
            "https://github.com/owner/repo?x=1",
            "https://github.com/owner/..",
            "https://evilgithub.com/owner/repo"
    })
    void rejectsUnsafeRepositoryUrls(String url) {
        assertThatThrownBy(() -> GitHubActionsReadClient.repositorySlug(url))
                .hasMessage("REPOSITORY_BINDING_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "100.64.1.1", "169.254.169.254", "192.0.2.1", "198.18.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "::1", "fc00::1", "fe80::1", "2001:db8::1", "2002::1"
    })
    void refusesNonpublicAddresses(String address) throws Exception {
        assertThat(GitHubActionsReadClient.publicAddress(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"140.82.121.6", "1.1.1.1", "2606:4700:4700::1111"})
    void allowsPublicAddresses(String address) throws Exception {
        assertThat(GitHubActionsReadClient.publicAddress(InetAddress.getByName(address))).isTrue();
    }

    @Test
    void boundsProviderDelayIncludingRfc1123Dates() {
        Instant now = Instant.parse("2026-11-11T00:00:00Z");
        assertThat(GitHubActionsReadClient.retryDelay("9999", "", now)).isEqualTo(3600);
        assertThat(GitHubActionsReadClient.retryDelay("12", Long.toString(now.getEpochSecond() + 30), now)).isEqualTo(30);
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(75).atOffset(ZoneOffset.UTC));
        assertThat(GitHubActionsReadClient.retryDelay(httpDate, "", now)).isEqualTo(75);
        assertThat(GitHubActionsReadClient.retryDelay("broken", "broken", now)).isZero();
    }

    private FetchFailure assertFetchFailure(
            List<BasicClassicHttpResponse> replies, Kind expectedKind, String expectedCode) throws Exception {
        return assertFetchFailure(configuredProperties(), replies, expectedKind, expectedCode);
    }

    private FetchFailure assertFetchFailure(
            GitHubActionsProperties properties, List<BasicClassicHttpResponse> replies,
            Kind expectedKind, String expectedCode) throws Exception {
        var http = mock(CloseableHttpClient.class);
        stubHttp(http, new ArrayDeque<>(replies), new ArrayList<>(), new ArrayList<>());
        try (var client = new ManagedClient(new GitHubActionsReadClient(properties, http))) {
            var thrown = org.assertj.core.api.Assertions.catchThrowable(() -> client.value().fetch(TARGET, deadline()));
            assertThat(thrown).isInstanceOf(FetchFailure.class);
            var failure = (FetchFailure) thrown;
            assertThat(failure.kind).isEqualTo(expectedKind);
            assertThat(failure.code).isEqualTo(expectedCode);
            return failure;
        }
    }

    private static void stubHttp(
            CloseableHttpClient http, ArrayDeque<BasicClassicHttpResponse> responses,
            List<ClassicHttpRequest> requests, List<String> uris) throws Exception {
        when(http.execute(any(ClassicHttpRequest.class), org.mockito.ArgumentMatchers.<HttpClientResponseHandler<Object>>any()))
                .thenAnswer(call -> {
                    ClassicHttpRequest request = call.getArgument(0);
                    requests.add(request);
                    uris.add(request.getUri().toString());
                    return call.<HttpClientResponseHandler<Object>>getArgument(1).handleResponse(responses.remove());
                });
    }

    private static GitHubActionsProperties configuredProperties() {
        var config = new GitHubActionsProperties();
        var credential = new GitHubActionsProperties.Credential();
        credential.setRepositoryId("123");
        credential.setToken(TOKEN);
        config.getCredentials().put(REPO, credential);
        return config;
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    private static BasicClassicHttpResponse runJson(
            long id, int attempt, long repoId, String sha, String status, String conclusion) {
        return json("""
                {"id":%d,"run_attempt":%d,"repository":{"id":%d},"head_sha":"%s","status":"%s","conclusion":"%s"}
                """.formatted(id, attempt, repoId, sha, status, conclusion));
    }

    private static String jobEntry(long id, long runId, int attempt, String sha, String name, String conclusion, String step) {
        return """
                {"id":%d,"run_id":%d,"run_attempt":%d,"head_sha":"%s","name":"%s","conclusion":"%s",
                "steps":[{"name":"%s","conclusion":"%s"}]}
                """.formatted(id, runId, attempt, sha, name, conclusion, step, conclusion);
    }

    private static BasicClassicHttpResponse jobsJson(int totalCount, List<String> entries) {
        return json("{\"total_count\":" + totalCount + ",\"jobs\":[" + String.join(",", entries) + "]}");
    }

    private static BasicClassicHttpResponse redirect(String location) {
        var response = new BasicClassicHttpResponse(302);
        response.setHeader("Location", location);
        return response;
    }

    private static BasicClassicHttpResponse archiveReply(byte[] bytes) {
        var reply = new BasicClassicHttpResponse(200);
        reply.setEntity(new ByteArrayEntity(bytes, ContentType.APPLICATION_OCTET_STREAM));
        return reply;
    }

    private static BasicClassicHttpResponse statusResponse(int status) {
        return new BasicClassicHttpResponse(status);
    }

    private static BasicClassicHttpResponse json(String body) {
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity(body));
        return response;
    }

    private record ManagedClient(GitHubActionsReadClient value) implements AutoCloseable {
        @Override public void close() throws IOException { value.close(); }
    }
}
