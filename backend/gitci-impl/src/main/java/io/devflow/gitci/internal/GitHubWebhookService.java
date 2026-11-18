package io.devflow.gitci.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
class GitHubWebhookService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GitHubWebhookProperties properties;
    private final GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier(Clock.systemUTC());
    private final GitHubIntegrationQuota quota = new GitHubIntegrationQuota();
    private final ObjectMapper mapper;
    private final GitWebhookIngestion ingestion;

    GitHubWebhookService(JdbcTemplate jdbc, PlatformTransactionManager manager, GitHubWebhookProperties properties, GitWebhookIngestion ingestion) {
        this.ingestion = ingestion;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.properties = properties;
        this.transactions.setTimeout(6);
        JsonFactory factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                        .maxStringLength(properties.getMaxBodyBytes()).maxNumberLength(64).build()).build();
        mapper = new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    int receive(UUID id, String signature, String delivery, String event, byte[] body) {
        try {
            List<Binding> rows = jdbc.query("SELECT workspace_id, provider, remote_repo_id, webhook_secret_ref, repository_url "
                    + "FROM repositories WHERE id = ?", (rs, n) -> new Binding(rs.getObject(1, UUID.class),
                    rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id);
            GitHubWebhookProperties.Binding key = properties.getBindings().get(id);
            if (rows.size() != 1 || key == null || !"GITHUB".equals(rows.get(0).provider())
                    || !rows.get(0).reference().equals(key.getReference())
                    || !verifier.verify(body, signature, key.getCurrent(), key.getPrevious(), key.getPreviousValidUntil()))
                throw new WebhookFailure(401, "WEBHOOK_UNAUTHORIZED");
            if (!quota.allow(id)) throw new WebhookFailure(429, "WEBHOOK_RATE_LIMITED");
            Binding binding = rows.get(0);
            JsonNode root = parse(body);
            JsonNode remote = root.path("repository").path("id");
            if (!remote.isIntegralNumber() || remote.bigIntegerValue().signum() <= 0)
                throw new WebhookFailure(400, "WEBHOOK_INVALID_PAYLOAD");
            if (!remote.bigIntegerValue().toString().equals(binding.remoteId()))
                throw new WebhookFailure(401, "WEBHOOK_UNAUTHORIZED");
            if (!delivery.matches("[A-Za-z0-9-]{1,128}") || !event.matches("[a-z0-9_]{1,64}"))
                throw new WebhookFailure(400, "WEBHOOK_INVALID_HEADER");
            switch (event) {
                case "ping" -> {
                    if (!root.path("zen").isTextual() || !root.path("hook_id").isIntegralNumber())
                        throw new WebhookFailure(400, "WEBHOOK_INVALID_PAYLOAD");
                }
                case "push" -> {
                    if (!root.path("ref").isTextual() || !root.path("commits").isArray()
                            || !root.path("after").isTextual())
                        throw new WebhookFailure(400, "WEBHOOK_INVALID_PAYLOAD");
                }
                case "pull_request", "workflow_run" -> {
                    if (!root.path("action").isTextual() || !root.path(event).isObject())
                        throw new WebhookFailure(400, "WEBHOOK_INVALID_PAYLOAD");
                    if (event.equals("workflow_run")) throw new WebhookFailure(503, "WEBHOOK_HANDLER_NOT_READY");
                }
                default -> { return 204; }
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            GitWebhookIngestion.Plan plan = ingestion.prepare(event, root, binding.remoteId());
            return transactions.execute(status -> storeWebhook(id, binding, delivery, digest, event, plan));
        } catch (WebhookFailure ex) {
            throw ex;
        } catch (org.springframework.transaction.TransactionException | DataAccessException ex) {
            throw new WebhookFailure(503, "WEBHOOK_UNAVAILABLE");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private int storeWebhook(UUID id, Binding expected, String delivery, String digest, String event, GitWebhookIngestion.Plan plan) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '4s'");
        List<Binding> rows = jdbc.query("SELECT workspace_id, provider, remote_repo_id, webhook_secret_ref, repository_url "
                + "FROM repositories WHERE id = ? FOR UPDATE", (rs, n) -> new Binding(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id);
        if (rows.size() != 1 || !rows.get(0).equals(expected)) throw new WebhookFailure(503, "WEBHOOK_UNAVAILABLE");
        int inserted = jdbc.update("INSERT INTO github_webhook_receipts "
                + "(repository_id, delivery_id, payload_sha256, event_name, status) VALUES (?, ?, ?, ?, 'PROCESSING') "
                + "ON CONFLICT DO NOTHING", id, delivery, digest, event);
        if (inserted == 0) {
            List<String> byDelivery = jdbc.query("SELECT payload_sha256 FROM github_webhook_receipts "
                    + "WHERE repository_id = ? AND delivery_id = ?", (rs, n) -> rs.getString(1), id, delivery);
            if (!byDelivery.isEmpty() && !byDelivery.get(0).equals(digest))
                throw new WebhookFailure(409, "WEBHOOK_DELIVERY_CONFLICT");
            Integer completed = jdbc.queryForObject("SELECT COUNT(*) FROM github_webhook_receipts "
                    + "WHERE repository_id = ? AND payload_sha256 = ? AND status = 'COMPLETED'", Integer.class, id, digest);
            if (completed == null || completed != 1) throw new WebhookFailure(503, "WEBHOOK_UNAVAILABLE");
            return 204;
        }
        ingestion.persist(id, expected.workspaceId(), expected.url(), plan);
        jdbc.update("UPDATE github_webhook_receipts SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP, "
                + "updated_at = CURRENT_TIMESTAMP WHERE repository_id = ? AND delivery_id = ?", id, delivery);
        return event.equals("ping") ? 200 : 204;
    }

    private JsonNode parse(byte[] body) {
        try {
            if (body.length == 0 || (body.length >= 3 && body[0] == (byte) 0xef
                    && body[1] == (byte) 0xbb && body[2] == (byte) 0xbf)) throw new IllegalArgumentException();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            JsonNode result = mapper.readTree(text);
            if (result == null || !result.isObject()) throw new IllegalArgumentException();
            return result;
        } catch (Exception ex) {
            throw new WebhookFailure(400, "WEBHOOK_INVALID_PAYLOAD");
        }
    }

    private record Binding(UUID workspaceId, String provider, String remoteId, String reference, String url) { }
}
