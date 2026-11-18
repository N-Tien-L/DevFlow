package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitHubWebhookHttpIntegrationTest {
    private static final UUID REPO = UUID.fromString("b199e15a-7a6a-4e56-bc4b-625f47645555");
    private static final String KEY = "test-only-secret-0123456789-abcdef";
    private static final String BODY = "{\"zen\":\"test\",\"hook_id\":1,\"repository\":{\"id\":123}}";
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("devflow.gitci.github-webhook.enabled", () -> "true");
        String prefix = "devflow.gitci.github-webhook.bindings[" + REPO + "].";
        registry.add(prefix + "reference", () -> "test:repo");
        registry.add(prefix + "current", () -> KEY);
    }

    @Test
    void authenticatesRawBytesAndDeduplicatesOnlyCommittedPing() throws Exception {
        jdbc.update("INSERT INTO repositories(id, workspace_id, provider, remote_repo_id, name, repository_url, webhook_secret_ref) "
                + "VALUES (?, ?, 'GITHUB', '123', 'fixture', 'https://github.com/test/repo', 'test:repo')", REPO, UUID.randomUUID());
        assertThat(send(BODY, "wrong", "one", "ping").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_webhook_receipts", Integer.class)).isZero();
        String signature = sign(BODY);
        assertThat(send(BODY.replace("test", "other"), signature, "tamper", "ping").statusCode()).isEqualTo(401);
        String malformed = "{\"repository\":{\"id\":123},";
        assertThat(send(malformed, sign(malformed), "bad-json", "ping").statusCode()).isEqualTo(400);
        assertThat(send(BODY, signature, "one", "ping").statusCode()).isEqualTo(200);
        assertThat(send(BODY, signature, "two", "ping").statusCode()).isEqualTo(204);
        String modified = BODY.replace("test", "other");
        assertThat(send(modified, sign(modified), "one", "ping").statusCode()).isEqualTo(409);
        assertThat(send(BODY, signature, "three", "push").statusCode()).isEqualTo(400);
        String push = "{\"ref\":\"refs/heads/main\",\"after\":\"123\",\"commits\":[],\"repository\":{\"id\":123}}";
        assertThat(send(push, sign(push), "four", "push").statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_webhook_receipts", Integer.class)).isEqualTo(1);
        String concurrent = BODY.replace("test", "concurrent");
        String concurrentSignature = sign(concurrent);
        var deliveries = IntStream.range(0, 6).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try { return send(concurrent, concurrentSignature, "parallel-" + i, "ping").statusCode(); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        })).toList();
        var statuses = deliveries.stream().map(CompletableFuture::join).toList();
        assertThat(statuses).containsOnly(200, 204);
        assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_webhook_receipts", Integer.class)).isEqualTo(2);
    }

    private HttpResponse<String> send(String body, String signature, String delivery, String event) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/gitci/webhook/github?repositoryId=" + REPO))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer invalid-token")
                .header("X-Hub-Signature-256", signature).header("X-GitHub-Delivery", delivery)
                .header("X-GitHub-Event", event).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
