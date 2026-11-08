package io.devflow.gitci.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent.Outcome;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent.Resolution;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Called only after T-021 authentication; all writes join the receipt transaction. */
@Component
final class GitWebhookIngestion {
    record Commit(String sha, String message, Instant timestamp, GitReferenceSourcePolicy.References references) { }
    record PullRequest(int number, String title, String head, String base, String status, Instant updatedAt,
                       String fingerprint, GitReferenceSourcePolicy.References references) { }
    record Plan(List<Commit> commits, PullRequest pr, String ignoredReason) {
        Plan { commits = List.copyOf(commits); }
    }
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final GitReferenceSourcePolicy policy;
    private final MeterRegistry metrics;

    GitWebhookIngestion(JdbcTemplate jdbc, ApplicationEventPublisher events, GitReferenceSourcePolicy policy, MeterRegistry metrics) {
        this.jdbc = jdbc; this.events = events; this.policy = policy; this.metrics = metrics;
    }

    Plan prepare(String event, JsonNode root, String remoteId) {
        long start = System.nanoTime();
        try {
            Plan plan = switch (event) {
                case "push" -> push(root);
                case "pull_request" -> pullRequest(root, remoteId);
                default -> new Plan(List.of(), null, "none");
            };
            var all = new LinkedHashSet<String>();
            plan.commits().forEach(c -> all.addAll(c.references().keys()));
            if (plan.pr() != null) all.addAll(plan.pr().references().keys());
            if (all.size() > 100) throw new WebhookFailure(422, "GIT_REFERENCE_LIMIT");
            metrics.counter("devflow.gitci.references", "outcome", "parsed").increment(all.size());
            if (plan.ignoredReason() != null && !plan.ignoredReason().equals("none"))
                metrics.counter("devflow.gitci.ingestion.ignored", "reason", plan.ignoredReason()).increment();
            return plan;
        } finally { metrics.timer("devflow.gitci.parse.duration").record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS); }
    }

    private Plan push(JsonNode root) {
        String ref = text(root, "ref");
        if (!root.path("commits").isArray() || !root.path("deleted").isBoolean()) invalid();
        if (!ref.startsWith("refs/heads/")) return new Plan(List.of(), null, "non_branch");
        String branch = ref.substring("refs/heads/".length());
        GitReferenceSourcePolicy.bounded(branch);
        if (root.path("deleted").booleanValue()) return new Plan(List.of(), null, "deleted_branch");
        sha(text(root, "after"));
        JsonNode commits = root.path("commits");
        if (commits.size() > 100) throw new WebhookFailure(422, "GIT_BATCH_LIMIT");
        // Some provider versions include size; reject a declared incomplete snapshot instead of fetching arbitrary URLs.
        for (String field : List.of("size", "distinct_size")) {
            JsonNode size = root.get(field);
            if (size != null && (!size.isIntegralNumber() || !size.canConvertToInt() || size.intValue() < 0)) invalid();
            if (size != null && size.intValue() > commits.size()) throw new WebhookFailure(422, "GIT_INCOMPLETE_PUSH");
        }
        if (commits.isEmpty()) return new Plan(List.of(), null, "empty_push");
        var result = new ArrayList<Commit>();
        var seen = new java.util.HashSet<String>();
        for (JsonNode item : commits) {
            String id = sha(text(item, "id"));
            if (!seen.add(id)) invalid();
            String message = text(item, "message");
            Instant timestamp = timestamp(text(item, "timestamp"));
            result.add(new Commit(id, message, timestamp, policy.select(message, branch, GitReferenceSourcePolicy.Source.MESSAGE)));
        }
        return new Plan(result, null, null);
    }

    private Plan pullRequest(JsonNode root, String remoteId) {
        String action = text(root, "action");
        if (!root.path("pull_request").isObject()) invalid();
        if (!Set.of("opened", "reopened", "edited", "synchronize", "closed").contains(action))
            return new Plan(List.of(), null, "unsupported_pr_action");
        JsonNode pr = root.path("pull_request");
        JsonNode baseId = pr.path("base").path("repo").path("id");
        if (!baseId.isIntegralNumber() || !baseId.bigIntegerValue().toString().equals(remoteId))
            throw new WebhookFailure(401, "WEBHOOK_UNAUTHORIZED");
        JsonNode number = pr.path("number");
        if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 1
                || !root.path("number").equals(number)) invalid();
        String title = text(pr, "title");
        String head = text(pr.path("head"), "ref");
        String base = text(pr.path("base"), "ref");
        GitReferenceSourcePolicy.bounded(title);
        GitReferenceSourcePolicy.bounded(head);
        GitReferenceSourcePolicy.bounded(base);
        String state = text(pr, "state");
        if (!pr.path("merged").isBoolean()) invalid();
        boolean closed = action.equals("closed");
        if (!(closed ? state.equals("closed") : state.equals("open")) || (!closed && pr.path("merged").booleanValue())) invalid();
        String status = closed ? (pr.path("merged").booleanValue() ? "MERGED" : "CLOSED") : "OPEN";
        Instant updated = timestamp(text(pr, "updated_at"));
        String fingerprint = fingerprint(title, head, base, status);
        return new Plan(List.of(), new PullRequest(number.intValue(), title, head, base, status, updated,
                fingerprint, policy.select(title, head, GitReferenceSourcePolicy.Source.TITLE)), null);
    }

    void persist(UUID repository, UUID workspace, String repositoryUrl, Plan plan) {
        long start = System.nanoTime();
        try {
            var fresh = new ArrayList<Commit>();
            var known = new java.util.HashMap<String, Commit>();
            if (!plan.commits().isEmpty()) {
                var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
                named.query("SELECT sha,message,committed_at FROM git_commits WHERE repository_id=:repo AND sha IN (:shas)",
                        Map.of("repo", repository, "shas", plan.commits().stream().map(Commit::sha).toList()), rs -> {
                            String sha = rs.getString(1);
                            known.put(sha, new Commit(sha, rs.getString(2), rs.getTimestamp(3).toInstant(), null));
                        });
            }
            for (Commit commit : plan.commits()) {
                Commit previous = known.get(commit.sha());
                if (previous == null) fresh.add(commit);
                else if (!previous.message().equals(commit.message()) || !previous.timestamp().equals(commit.timestamp()))
                    throw new WebhookFailure(409, "GIT_COMMIT_CONFLICT");
                else metrics.counter("devflow.gitci.references", "outcome", "duplicate").increment();
            }
            PullRequest pr = plan.pr();
            UUID previousPr = null;
            if (pr != null) {
                var previous = jdbc.query("SELECT id,provider_updated_at,source_fingerprint FROM pull_requests WHERE repository_id=? AND pr_number=?",
                        (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getTimestamp(2), rs.getString(3)}, repository, pr.number());
                if (!previous.isEmpty()) {
                    previousPr = (UUID) previous.get(0)[0];
                    Timestamp version = (Timestamp) previous.get(0)[1];
                    if (version == null) throw new WebhookFailure(409, "GIT_PR_VERSION_REQUIRED");
                    int ordering = pr.updatedAt().compareTo(version.toInstant());
                    if (ordering < 0) pr = null;
                    else if (ordering == 0) {
                        if (!pr.fingerprint().equals(previous.get(0)[2])) throw new WebhookFailure(409, "GIT_PR_VERSION_CONFLICT");
                        pr = null;
                    }
                }
            }
            var keys = new LinkedHashSet<String>();
            fresh.forEach(c -> keys.addAll(c.references().keys()));
            if (pr != null) keys.addAll(pr.references().keys());
            Map<String, Resolution> resolved = resolve(workspace, keys);
            for (Commit commit : fresh) {
                UUID id = UUID.randomUUID();
                var tasks = eligible(commit.references(), resolved);
                jdbc.update("INSERT INTO git_commits(id,repository_id,sha,message,committed_at,task_id) VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(repository_id,sha) DO NOTHING", id, repository, commit.sha(), commit.message(),
                        Timestamp.from(commit.timestamp()), mirror(tasks));
                for (UUID task : tasks) jdbc.update("INSERT INTO git_commit_task_links(commit_id,task_id,source_kind) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                        id, task, commit.references().source().name());
            }
            if (pr != null) {
                UUID id = previousPr == null ? UUID.randomUUID() : previousPr;
                var tasks = eligible(pr.references(), resolved);
                String url = repositoryBase(repositoryUrl) + "/pull/" + pr.number();
                jdbc.update("INSERT INTO pull_requests(id,repository_id,pr_number,title,source_branch,target_branch,status,task_id,pr_url,provider_updated_at,source_fingerprint) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(repository_id,pr_number) DO UPDATE SET title=EXCLUDED.title,source_branch=EXCLUDED.source_branch,"
                        + "target_branch=EXCLUDED.target_branch,status=EXCLUDED.status,task_id=EXCLUDED.task_id,pr_url=EXCLUDED.pr_url,"
                        + "provider_updated_at=EXCLUDED.provider_updated_at,source_fingerprint=EXCLUDED.source_fingerprint,updated_at=CURRENT_TIMESTAMP",
                        id, repository, pr.number(), pr.title(), pr.head(), pr.base(), pr.status(), mirror(tasks), url,
                        Timestamp.from(pr.updatedAt()), pr.fingerprint());
                jdbc.update("DELETE FROM git_pull_request_task_links WHERE pull_request_id=?", id);
                for (UUID task : tasks) jdbc.update("INSERT INTO git_pull_request_task_links(pull_request_id,task_id,source_kind) VALUES (?,?,?)",
                        id, task, pr.references().source().name());
            }
        } finally { metrics.timer("devflow.gitci.persist.duration").record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS); }
    }

    private Map<String, Resolution> resolve(UUID workspace, Set<String> keys) {
        if (keys.isEmpty()) return Map.of();
        long start = System.nanoTime();
        try {
            var request = new TaskReferencesResolveRequestedEvent(workspace, keys, UUID.randomUUID());
            events.publishEvent(request);
            var result = request.requireResolutions();
            long found = result.values().stream().filter(r -> r.outcome() == Outcome.ELIGIBLE).count();
            metrics.counter("devflow.gitci.references", "outcome", "resolved").increment(found);
            metrics.counter("devflow.gitci.references", "outcome", "unresolved").increment(keys.size() - found);
            return result;
        } catch (RuntimeException ex) { throw new WebhookFailure(503, "GIT_RESOLVER_UNAVAILABLE"); }
        finally { metrics.timer("devflow.gitci.resolve.duration").record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS); }
    }
    private static List<UUID> eligible(GitReferenceSourcePolicy.References references, Map<String, Resolution> resolved) {
        return references.keys().stream().map(resolved::get).filter(r -> r.outcome() == Outcome.ELIGIBLE).map(Resolution::taskId).toList();
    }
    private static UUID mirror(List<UUID> tasks) { return tasks.size() == 1 ? tasks.get(0) : null; }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().indexOf('\0') >= 0) { invalid(); }
        return value.textValue();
    }
    private static String sha(String value) {
        if (!value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})") || value.matches("0+")) invalid();
        return value;
    }
    private static Instant timestamp(String value) {
        try {
            if (value.length() > 64) throw new IllegalArgumentException();
            Instant parsed = OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
            int year = parsed.atOffset(java.time.ZoneOffset.UTC).getYear();
            if (year < 1 || year > 9999) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException ex) { throw new WebhookFailure(400, "GIT_INVALID_TIMESTAMP"); }
    }
    private static String fingerprint(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) { digest.update(field.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String repositoryBase(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getPort() != -1
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?")) throw new IllegalArgumentException();
            String path = uri.getRawPath().replaceFirst("/$", "").replaceFirst("\\.git$", "");
            return "https://github.com" + path;
        } catch (RuntimeException ex) { throw new WebhookFailure(503, "GIT_REPOSITORY_CONFIG_INVALID"); }
    }
    private static void invalid() { throw new WebhookFailure(400, "GIT_INVALID_PAYLOAD"); }
}
