package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.common.event.DevFlowEvent;
import io.devflow.common.event.TaskReferencesRemovedEvent;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent;
import io.devflow.common.event.WorkspaceDeletedEvent;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "devflow.gitci.outbox.worker-enabled=false")
class GitDomainEventHttpIntegrationTest {
    static final UUID REPO = UUID.fromString("c199e15a-7a6a-4e56-bc4b-625f47645555");
    static final String KEY = "test-only-t022-secret-0123456789-abcdef";
    static final String SHA = "a".repeat(40);
    static final String TIME = "2026-11-01T12:00:00Z";
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationEventPublisher events;
    @Autowired Probe probe;
    @Autowired io.devflow.gitci.internal.GitDomainEventOutbox outbox;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired org.springframework.context.ApplicationContext context;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
    UUID workspace, board, column, first, second;

    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("devflow.gitci.github-webhook.enabled", () -> "true");
        String prefix = "devflow.gitci.github-webhook.bindings[" + REPO + "].";
        r.add(prefix + "reference", () -> "test:t022"); r.add(prefix + "current", () -> KEY);
    }
    @TestConfiguration static class Config { @Bean Probe probe() { return new Probe(); } }
    static class Probe {
        volatile boolean reject;
        volatile CountDownLatch entered, release;
        final List<DevFlowEvent> received = new CopyOnWriteArrayList<>();
        @EventListener public void observe(DevFlowEvent event) {
            if (!Set.of("git.commit_linked","git.pr_opened","git.pr_merged").contains(event.eventType())) return;
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("probe timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
            if (reject) throw new IllegalStateException("synthetic listener failure");
            received.add(event);
        }
    }
    @BeforeEach void fixtures() {
        // Each method is an independent delivery scenario, not cumulative load against one 60-token bucket.
        Object quota=org.springframework.test.util.ReflectionTestUtils.getField(context.getBean("gitHubWebhookService"),"quota");
        ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(quota,"buckets")).clear();
        Object peerQuota=org.springframework.test.util.ReflectionTestUtils.getField(context.getBean("gitHubWebhookController"),"quota");
        ((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(peerQuota,"peers")).clear();
        Object global=org.springframework.test.util.ReflectionTestUtils.getField(peerQuota,"global");
        org.springframework.test.util.ReflectionTestUtils.setField(global,"tokens",60.0);
        org.springframework.test.util.ReflectionTestUtils.setField(global,"lastSeen",System.nanoTime());
        jdbc.update("DELETE FROM repositories");
        jdbc.update("DELETE FROM boards");
        jdbc.update("DELETE FROM board_task_key_counters");
        probe.reject=false; probe.received.clear(); probe.entered=null; probe.release=null;
        workspace=UUID.randomUUID(); board=board(workspace, false); column=column(board,"DONE");
        first=task(column); second=task(column);
        UUID foreign = board(UUID.randomUUID(),false); task(column(foreign,"TODO"));
        jdbc.update("INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref) "
                + "VALUES (?,?,'GITHUB','123','fixture','https://github.com/test/repo','test:t022')",REPO,workspace);
    }

    @Test void commitPayloadsPrecedenceAuthorsAndReplay() throws Exception {
        var a = new HashMap<String,Object>(commit(SHA,"T-001 T-0001 T-002"));
        a.put("author",Map.of("username","valid-author","email","sentinel@example.invalid"));
        var b = new HashMap<String,Object>(commit("b".repeat(40),"no key"));
        b.put("author",Map.of("username","bad\nusername"));
        String body=push("feature/T-001",List.of(a,b,commit("c".repeat(40),"T-999")));
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        assertThat(count("git_domain_event_outbox")).isEqualTo(3);
        assertThat(probe.received).isEmpty();
        outbox.dispatch();
        assertThat(probe.received).hasSize(3).allSatisfy(event -> {
            var linked=(io.devflow.common.event.GitCommitLinkedEvent)event;
            assertThat(linked.repo()).isEqualTo("test/repo");
            assertThat(linked.occurredAt()).isEqualTo(Instant.parse(TIME));
            if(linked.commitSha().equals(SHA)) assertThat(linked.author()).isEqualTo("valid-author");
            else { assertThat(linked.taskId()).isEqualTo(first); assertThat(linked.author()).isNull(); }
        });
        assertThat(probe.received.stream().filter(e -> ((io.devflow.common.event.GitCommitLinkedEvent)e).commitSha().equals(SHA))
                .map(e -> ((io.devflow.common.event.GitCommitLinkedEvent)e).taskId())).containsExactlyInAnyOrder(first,second);
        assertThat(jdbc.queryForObject("SELECT task_id FROM git_commits WHERE sha=?",UUID.class,SHA)).isNull();
        jdbc.update("DELETE FROM github_webhook_receipts");
        jdbc.update("DELETE FROM git_domain_event_outbox");
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        outbox.dispatch();
        assertThat(probe.received).hasSize(3);
        assertThat(count("git_domain_event_outbox")).isZero();
    }

    @Test void prLifecycleUsesWinnerLinksProviderTimesAndCanonicalForkUrl() throws Exception {
        var opened=prPayload("opened","T-001 T-002",0,false);
        opened.withObject("/pull_request").put("created_at","2026-10-31T12:00:00Z").put("html_url","https://evil.invalid/leak");
        assertThat(send(json.writeValueAsString(opened),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("edited","T-002",time(1),false,123),"pull_request").statusCode()).isEqualTo(204);
        var merged=prPayload("closed","T-002",2,true);
        merged.withObject("/pull_request").put("merged_at",time(2));
        assertThat(send(json.writeValueAsString(merged),"pull_request").statusCode()).isEqualTo(204);
        outbox.dispatch();
        assertThat(probe.received).hasSize(3);
        assertThat(probe.received.stream().filter(e -> e instanceof io.devflow.common.event.GitPrOpenedEvent)).hasSize(2).allSatisfy(e -> {
            var event=(io.devflow.common.event.GitPrOpenedEvent)e;
            assertThat(event.prUrl()).isEqualTo("https://github.com/test/repo/pull/1");
            assertThat(event.repo()).isEqualTo("test/repo"); assertThat(event.actorId()).isNull();
            assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-31T12:00:00Z"));
        });
        var merge=(io.devflow.common.event.GitPrMergedEvent)probe.received.stream().filter(e -> e instanceof io.devflow.common.event.GitPrMergedEvent).findFirst().orElseThrow();
        assertThat(merge.taskId()).isEqualTo(second); assertThat(merge.occurredAt()).isEqualTo(Instant.parse(time(2)));
        jdbc.update("DELETE FROM github_webhook_receipts"); jdbc.update("DELETE FROM git_domain_event_outbox");
        assertThat(send(pr("closed","T-001",time(3),true,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("reopened","T-001",time(4),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM pull_requests",String.class)).isEqualTo("MERGED");
        assertThat(count("git_domain_event_outbox")).isZero();
    }

    @Test void metadataClosedReopenedAndLateOpenDoNotEmitHistoricalEvents() throws Exception {
        assertThat(send(pr("edited","T-001",time(2),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("opened","T-002",time(1),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("opened","T-002",time(3),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("closed","T-002",time(4),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("reopened","T-002",time(5),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("synchronize","T-002",time(6),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(count("git_domain_event_outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM pull_requests",String.class)).isEqualTo("OPEN");
    }

    @Test void noLinkAtOpenCanLinkAtMergeAndMergeCanArriveFirst() throws Exception {
        assertThat(send(pr("opened","T-999",time(0),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("edited","T-001",time(1),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(count("git_domain_event_outbox")).isZero();
        assertThat(send(pr("closed","T-001",time(2),true,123),"pull_request").statusCode()).isEqualTo(204);
        var other=prPayload("closed","T-002",3,true); other.put("number",2); other.withObject("/pull_request").put("number",2);
        assertThat(send(json.writeValueAsString(other),"pull_request").statusCode()).isEqualTo(204);
        outbox.dispatch();
        assertThat(probe.received).hasSize(2).allMatch(e -> e instanceof io.devflow.common.event.GitPrMergedEvent);
    }

    @Test void timestampFallbackNullAndInvalidSpecializedTimeAreAtomic() throws Exception {
        var bad=prPayload("opened","T-001",0,false); bad.withObject("/pull_request").put("created_at",123);
        assertThat(send(json.writeValueAsString(bad),"pull_request").statusCode()).isEqualTo(400);
        bad.withObject("/pull_request").put("created_at","invalid");
        assertThat(send(json.writeValueAsString(bad),"pull_request").statusCode()).isEqualTo(400);
        assertThat(count("github_webhook_receipts")).isZero();
        bad.withObject("/pull_request").putNull("created_at");
        assertThat(send(json.writeValueAsString(bad),"pull_request").statusCode()).isEqualTo(204);
        var merge=prPayload("closed","T-001",1,true); merge.withObject("/pull_request").put("merged_at",false);
        assertThat(send(json.writeValueAsString(merge),"pull_request").statusCode()).isEqualTo(400);
        merge.withObject("/pull_request").put("merged_at","bad");
        assertThat(send(json.writeValueAsString(merge),"pull_request").statusCode()).isEqualTo(400);
        merge.withObject("/pull_request").putNull("merged_at");
        assertThat(send(json.writeValueAsString(merge),"pull_request").statusCode()).isEqualTo(204);
        outbox.dispatch();
        assertThat(probe.received.stream().map(DevFlowEvent::occurredAt)).containsExactlyInAnyOrder(Instant.parse(TIME),Instant.parse(time(1)));
    }

    @Test void concurrentCommitAndMergeReplaysOnlyEnqueueOneLogicalEvent() throws Exception {
        var statuses=IntStream.range(0,6).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try { return send(push("branch-"+i,List.of(commit(SHA,"T-001"))),"push").statusCode(); }
            catch(Exception ex) { throw new CompletionException(ex); }
        })).toList();
        assertThat(statuses.stream().map(CompletableFuture::join)).containsOnly(204);
        statuses=IntStream.range(0,6).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try { return send(pr("closed","T-001",time(i),true,123),"pull_request").statusCode(); }
            catch(Exception ex) { throw new CompletionException(ex); }
        })).toList();
        assertThat(statuses.stream().map(CompletableFuture::join)).containsOnly(204);
        assertThat(count("git_domain_event_outbox")).isEqualTo(2);
        outbox.dispatch(); assertThat(probe.received).hasSize(2);
    }

    @Test void outboxWriteFailureRollsBackReceiptGitAndAssociationsAndRedeliveryRecovers() throws Exception {
        jdbc.execute("CREATE FUNCTION t023_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'sentinel-outbox-secret'; END $$");
        jdbc.execute("CREATE TRIGGER t023_fail AFTER INSERT ON git_domain_event_outbox FOR EACH ROW EXECUTE FUNCTION t023_fail()");
        String body=push("main",List.of(commit(SHA,"T-001 T-002")));
        try {
            var response=send(body,"push"); assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("sentinel",KEY);
            for(String table:List.of("git_commits","git_commit_task_links","github_webhook_receipts","git_domain_event_outbox")) assertThat(count(table)).isZero();
            outbox.dispatch(); assertThat(probe.received).isEmpty();
        } finally { jdbc.execute("DROP TRIGGER t023_fail ON git_domain_event_outbox"); jdbc.execute("DROP FUNCTION t023_fail()"); }
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        outbox.dispatch(); assertThat(probe.received).hasSize(2);
    }

    @Test void retryBudgetBackoffRequeueAndPayloadRemainStable() throws Exception {
        assertThat(send(push("main",List.of(commit(SHA,"T-001"))),"push").statusCode()).isEqualTo(204);
        UUID id=jdbc.queryForObject("SELECT id FROM git_domain_event_outbox",UUID.class);
        probe.reject=true; outbox.dispatch();
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_attempt_at-CURRENT_TIMESTAMP) FROM git_domain_event_outbox",Double.class)).isBetween(0.0,2.1);
        outbox.dispatch(); assertThat(jdbc.queryForObject("SELECT attempts FROM git_domain_event_outbox",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE git_domain_event_outbox SET attempts=7,next_attempt_at=CURRENT_TIMESTAMP");
        outbox.dispatch();
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox",String.class)).isEqualTo("FAILED");
        probe.reject=false; assertThat(outbox.requeue(id)).isTrue(); assertThat(outbox.requeue(id)).isFalse();
        outbox.dispatch();
        assertThat(probe.received).hasSize(1); assertThat(probe.received.get(0).occurredAt()).isEqualTo(Instant.parse(TIME));
    }

    @Test void leasesTwoWorkersAndStaleTokenFencing() throws Exception {
        assertThat(send(push("main",List.of(commit(SHA,"T-001 T-002"))),"push").statusCode()).isEqualTo(204);
        var calls=List.of(CompletableFuture.supplyAsync(outbox::claim),CompletableFuture.supplyAsync(outbox::claim));
        var claims=calls.stream().flatMap(f -> f.join().stream()).toList();
        assertThat(claims).hasSize(2); assertThat(claims.stream().map(e -> e.id()).distinct()).hasSize(2);
        assertThat(outbox.claim()).isEmpty();
        jdbc.update("UPDATE git_domain_event_outbox SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var fresh=outbox.claim(); assertThat(fresh).hasSize(2);
        claims.forEach(outbox::publish); assertThat(probe.received).isEmpty();
        fresh.forEach(outbox::publish); assertThat(probe.received).hasSize(2);
        claims.forEach(outbox::publish); assertThat(probe.received).hasSize(2);
        assertThat(jdbc.queryForList("SELECT status FROM git_domain_event_outbox",String.class)).containsOnly("PUBLISHED");
    }

    @Test void expiredFinalAttemptMovesToFailed() throws Exception {
        send(push("main",List.of(commit(SHA,"T-001"))),"push"); outbox.claim();
        jdbc.update("UPDATE git_domain_event_outbox SET attempts=8,lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertThat(outbox.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox",String.class)).isEqualTo("FAILED");
    }

    @Test void blockedListenerDoesNotDelayNextWebhookAndMetricsAvoidSensitiveLabels(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        send(push("main",List.of(commit(SHA,"T-001 secret-sentinel"))),"push");
        probe.entered=new CountDownLatch(1); probe.release=new CountDownLatch(1);
        var worker=CompletableFuture.runAsync(outbox::dispatch);
        try {
            assertThat(probe.entered.await(5,TimeUnit.SECONDS)).isTrue();
            long start=System.nanoTime();
            assertThat(send(push("main",List.of(commit("b".repeat(40),"T-002"))),"push").statusCode()).isEqualTo(204);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(4000);
            assertThat(metrics.get("devflow.gitci.outbox.depth").tag("status","PENDING").gauge().value()).isEqualTo(1);
        } finally { probe.release.countDown(); worker.get(10,TimeUnit.SECONDS); probe.entered=null; }
        outbox.dispatch(); assertThat(probe.received).hasSize(2);
        assertThat(metrics.getMeters().stream().filter(m -> m.getId().getName().startsWith("devflow.gitci.outbox"))
                .flatMap(m -> m.getId().getTags().stream()).map(Object::toString).toList().toString())
                .doesNotContain("sentinel",SHA,"test/repo",KEY,first.toString());
        assertThat(output.getAll()).doesNotContain("secret-sentinel","sentinel@example.invalid",KEY,SHA,"https://github.com/test/repo");
    }

    @Test void cleanupDeletionRollbackAndRecreatedWorkerRecoverDurableRows() throws Exception {
        send(push("main",List.of(commit(SHA,"T-001 T-002"))),"push");
        var claimed=outbox.claim();
        new TransactionTemplate(manager).executeWithoutResult(s -> { events.publishEvent(new TaskReferencesRemovedEvent(Set.of(first))); s.setRollbackOnly(); });
        assertThat(count("git_domain_event_outbox")).isEqualTo(2);
        new TransactionTemplate(manager).executeWithoutResult(s -> events.publishEvent(new TaskReferencesRemovedEvent(Set.of(first))));
        claimed.forEach(outbox::publish); assertThat(probe.received).hasSize(1);
        assertThat(((io.devflow.common.event.GitCommitLinkedEvent)probe.received.get(0)).taskId()).isEqualTo(second);
        jdbc.update("UPDATE git_domain_event_outbox SET published_at=CURRENT_TIMESTAMP - INTERVAL '8 days'");
        outbox.clean(); assertThat(count("git_domain_event_outbox")).isZero();
        send(push("main",List.of(commit("b".repeat(40),"T-002"))),"push");
        var restarted=new io.devflow.gitci.internal.GitDomainEventOutbox(jdbc,manager,events,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),false);
        restarted.dispatch(); assertThat(probe.received).hasSize(2);
        jdbc.update("DELETE FROM repositories WHERE id=?",REPO); assertThat(count("git_domain_event_outbox")).isZero();
    }

    @Test void maximumTwoThousandAssociationsFitWebhookBudgetAndOverflowIsAtomic() throws Exception {
        IntStream.range(2,20).forEach(i -> task(column));
        String keys=IntStream.rangeClosed(1,20).mapToObj(n -> String.format("T-%03d",n)).collect(java.util.stream.Collectors.joining(" "));
        var batch=IntStream.rangeClosed(1,100).mapToObj(n -> commit(String.format("%040x",n),keys)).toList();
        long start=System.nanoTime();
        assertThat(send(push("main",batch),"push").statusCode()).isEqualTo(204);
        long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); System.out.println("T023_2000_OUTBOX_HTTP_MS="+ms);
        assertThat(ms).isLessThan(8000); assertThat(count("git_domain_event_outbox")).isEqualTo(2000);
        assertThat(send(push("main",List.of(commit("e".repeat(40),keys+" T-021"))),"push").statusCode()).isEqualTo(422);
        var overflow=new ArrayList<>(batch); overflow.add(commit("f".repeat(40),"T-001"));
        assertThat(send(push("main",overflow),"push").statusCode()).isEqualTo(422);
        assertThat(count("git_domain_event_outbox")).isEqualTo(2000);
    }

    @Test void notificationDeduplicatesCrashAfterPublishAndSkipsFormerMembers() throws Exception {
        UUID user=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,status) VALUES (?,'fixture@example.invalid','test-only','fixture','ACTIVE')",user);
        jdbc.update("INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,'fixture',?,?)",workspace,workspace.toString(),user);
        jdbc.update("INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'OWNER')",workspace,user);
        jdbc.update("UPDATE tasks SET assignee_id=? WHERE id=?",user,first);
        send(pr("opened","T-001",time(0),false,123),"pull_request");
        // Listener delivery commits, then an abrupt process failure prevents PUBLISHED being saved.
        jdbc.execute("CREATE FUNCTION t023_crash() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='PUBLISHED' THEN RAISE EXCEPTION 'synthetic crash'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER t023_crash BEFORE UPDATE ON git_domain_event_outbox FOR EACH ROW EXECUTE FUNCTION t023_crash()");
        try { outbox.dispatch(); }
        finally { jdbc.execute("DROP TRIGGER t023_crash ON git_domain_event_outbox"); jdbc.execute("DROP FUNCTION t023_crash()"); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE reference_id=?",Integer.class,first)).isEqualTo(1);
        jdbc.update("UPDATE git_domain_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP");
        outbox.dispatch(); assertThat(probe.received).hasSize(2);
        assertThat(probe.received.get(0)).isEqualTo(probe.received.get(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE reference_id=?",Integer.class,first)).isEqualTo(1);
        send(pr("closed","T-001",time(1),true,123),"pull_request"); outbox.dispatch();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE reference_id=?",Integer.class,first)).isEqualTo(2);
        jdbc.update("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?",workspace,user);
        var other=prPayload("opened","T-001",2,false); other.put("number",2); other.withObject("/pull_request").put("number",2);
        send(json.writeValueAsString(other),"pull_request"); outbox.dispatch();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE reference_id=?",Integer.class,first)).isEqualTo(2);
    }

    @Test void versionConflictsAndTenantEligibilityNeverEnqueueRejectedChanges() throws Exception {
        send(pr("opened","T-001",time(2),false,123),"pull_request");
        assertThat(send(pr("edited","T-002",time(2),false,123),"pull_request").statusCode()).isEqualTo(409);
        assertThat(send(pr("closed","T-002",time(1),true,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(count("git_domain_event_outbox")).isEqualTo(1);
        jdbc.update("UPDATE boards SET is_archived=true WHERE id=?",board);
        send(push("main",List.of(commit(SHA,"T-001 T-002"))),"push");
        assertThat(count("git_domain_event_outbox")).isEqualTo(1);
        jdbc.update("UPDATE boards SET is_archived=false WHERE id=?",board);
        new TransactionTemplate(manager).executeWithoutResult(s -> { events.publishEvent(new TaskReferencesRemovedEvent(Set.of(first))); jdbc.update("DELETE FROM tasks WHERE id=?",first); });
        send(push("main",List.of(commit("b".repeat(40),"T-001 T-002 T-999"))),"push");
        outbox.dispatch(); assertThat(probe.received).hasSize(1);
        assertThat(((io.devflow.common.event.GitCommitLinkedEvent)probe.received.get(0)).taskId()).isEqualTo(second);
    }

    @Test void retentionKeepsPendingFailedAndRecentPublishedRowsAndLimitsBatch() throws Exception {
        send(push("main",List.of(commit(SHA,"T-001 T-002"))),"push"); outbox.dispatch();
        send(push("main",List.of(commit("b".repeat(40),"T-001 T-002"))),"push");
        jdbc.update("UPDATE git_domain_event_outbox SET status='FAILED' WHERE git_object_key=? AND task_id=?","b".repeat(40),first);
        outbox.clean(); assertThat(count("git_domain_event_outbox")).isEqualTo(4);
        jdbc.update("INSERT INTO git_domain_event_outbox(id,repository_id,task_id,event_type,git_object_key,repo,occurred_at,status,published_at) SELECT gen_random_uuid(),?,?,'git.commit_linked',lpad(n::text,40,'0'),'test/repo',CURRENT_TIMESTAMP,'PUBLISHED',CURRENT_TIMESTAMP-INTERVAL '8 days' FROM generate_series(1,1001) n",REPO,first);
        outbox.clean(); assertThat(count("git_domain_event_outbox")).isEqualTo(5);
        outbox.clean(); assertThat(count("git_domain_event_outbox")).isEqualTo(4);
    }

    @Test void uncommittedHttpOutboxIsInvisibleToWorkerAndOtherConnections() throws Exception {
        jdbc.execute("CREATE FUNCTION t023_hold() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(23001); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER t023_hold AFTER INSERT ON git_domain_event_outbox FOR EACH ROW EXECUTE FUNCTION t023_hold()");
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.createStatement().execute("SELECT pg_advisory_xact_lock(23001)");
            var request=CompletableFuture.supplyAsync(() -> {
                try { return send(push("main",List.of(commit(SHA,"T-001"))),"push").statusCode(); }
                catch(Exception ex) { throw new CompletionException(ex); }
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline && jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",Integer.class)==0) Thread.onSpinWait();
            try {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",Integer.class)).isPositive();
                assertThat(count("git_domain_event_outbox")).isZero();
                outbox.dispatch(); assertThat(probe.received).isEmpty();
            } finally { connection.commit(); }
            assertThat(request.get(8,TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(count("git_domain_event_outbox")).isEqualTo(1);
            outbox.dispatch(); assertThat(probe.received).hasSize(1);
        } finally { jdbc.execute("DROP TRIGGER t023_hold ON git_domain_event_outbox"); jdbc.execute("DROP FUNCTION t023_hold()"); }
    }

    @Test void failureAfterOutboxBeforeReceiptCompletionRollsBackEverything() throws Exception {
        jdbc.execute("CREATE FUNCTION t023_receipt_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic receipt failure'; END $$");
        jdbc.execute("CREATE TRIGGER t023_receipt_fail BEFORE UPDATE ON github_webhook_receipts FOR EACH ROW EXECUTE FUNCTION t023_receipt_fail()");
        try {
            assertThat(send(push("main",List.of(commit(SHA,"T-001"))),"push").statusCode()).isEqualTo(503);
            for(String table:List.of("git_commits","git_commit_task_links","github_webhook_receipts","git_domain_event_outbox")) assertThat(count(table)).isZero();
            outbox.dispatch(); assertThat(probe.received).isEmpty();
        } finally { jdbc.execute("DROP TRIGGER t023_receipt_fail ON github_webhook_receipts"); jdbc.execute("DROP FUNCTION t023_receipt_fail()"); }
    }

    @Test void authenticationInvalidPayloadAndIgnoredEventsNeverEnqueueBusinessEvents() throws Exception {
        String valid=push("main",List.of(commit(SHA,"T-001")));
        assertThat(sendRaw(valid.getBytes(StandardCharsets.UTF_8),"push","application/json","sha256="+"0".repeat(64)).statusCode()).isEqualTo(401);
        assertThat(send(valid.replace("\"id\":123","\"id\":456"),"push").statusCode()).isEqualTo(401);
        assertThat(send(pr("opened","T-001",time(0),false,456),"pull_request").statusCode()).isEqualTo(401);
        assertThat(send(valid+" {}","push").statusCode()).isEqualTo(400);
        assertThat(sendRaw(valid.getBytes(StandardCharsets.UTF_8),"push","text/plain",null).statusCode()).isEqualTo(415);
        var malformed=prPayload("closed","T-001",0,true); malformed.withObject("/pull_request").put("merged","true");
        assertThat(send(json.writeValueAsString(malformed),"pull_request").statusCode()).isEqualTo(400);
        malformed=prPayload("opened","T-001",0,false); malformed.put("number",99);
        assertThat(send(json.writeValueAsString(malformed),"pull_request").statusCode()).isEqualTo(400);
        assertThat(send(valid.replace("refs/heads/main","refs/tags/T-001"),"push").statusCode()).isEqualTo(204);
        assertThat(send(valid.replace("\"deleted\":false","\"deleted\":true"),"push").statusCode()).isEqualTo(204);
        assertThat(send(push("main",List.of()),"push").statusCode()).isEqualTo(204);
        var ignored=prPayload("opened","T-001",0,false); ignored.put("action","labeled");
        assertThat(send(json.writeValueAsString(ignored),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send("{\"zen\":\"test\",\"hook_id\":1,\"repository\":{\"id\":123}}","ping").statusCode()).isEqualTo(200);
        assertThat(send("{\"action\":\"completed\",\"workflow_run\":{},\"repository\":{\"id\":123}}","workflow_run").statusCode()).isEqualTo(503);
        outbox.dispatch(); assertThat(probe.received).isEmpty(); assertThat(count("git_domain_event_outbox")).isZero();
    }

    @Test void senderCannotReplaceCommitAuthorAndCompatibilityConstructorsRemainCallable() throws Exception {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(push("main",List.of(commit(SHA,"T-001"))));
        body.putObject("sender").put("login","different-sender");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("commits").get(0)).withObject("/author").put("username","actual-author");
        assertThat(send(json.writeValueAsString(body),"push").statusCode()).isEqualTo(204);
        outbox.dispatch();
        assertThat(((io.devflow.common.event.GitCommitLinkedEvent)probe.received.get(0)).author()).isEqualTo("actual-author");
        assertThat(new io.devflow.common.event.GitCommitLinkedEvent(first,SHA,"test/repo",null).occurredAt()).isNotNull();
        assertThat(new io.devflow.common.event.GitPrOpenedEvent(first,"https://github.com/test/repo/pull/1","test/repo").actorId()).isNull();
        assertThat(new io.devflow.common.event.GitPrMergedEvent(first,"https://github.com/test/repo/pull/1","test/repo").actorId()).isNull();
    }

    @Test void mergeFirstRejectsBothOlderAndNewerOpenAndLegacyVersionlessPrFailsClosed() throws Exception {
        assertThat(send(pr("closed","T-001",time(2),true,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("opened","T-002",time(1),false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(send(pr("opened","T-002",time(3),false,123),"pull_request").statusCode()).isEqualTo(204);
        outbox.dispatch(); assertThat(probe.received).hasSize(1).allMatch(e -> e instanceof io.devflow.common.event.GitPrMergedEvent);
        assertThat(jdbc.queryForObject("SELECT status FROM pull_requests",String.class)).isEqualTo("MERGED");
        jdbc.update("UPDATE pull_requests SET provider_updated_at=NULL");
        assertThat(send(pr("closed","T-001",time(4),true,123),"pull_request").statusCode()).isEqualTo(409);
        assertThat(count("git_domain_event_outbox")).isEqualTo(1);
    }

    @Test void associationFailureRollsBackBeforeEnqueueAndRedeliveryRecovers() throws Exception {
        jdbc.execute("CREATE FUNCTION t023_link_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic association failure'; END $$");
        jdbc.execute("CREATE TRIGGER t023_link_fail AFTER INSERT ON git_commit_task_links FOR EACH ROW EXECUTE FUNCTION t023_link_fail()");
        String body=push("main",List.of(commit(SHA,"T-001 T-002")));
        try {
            assertThat(send(body,"push").statusCode()).isEqualTo(503);
            for(String table:List.of("git_commits","git_commit_task_links","github_webhook_receipts","git_domain_event_outbox")) assertThat(count(table)).isZero();
            outbox.dispatch(); assertThat(probe.received).isEmpty();
        } finally { jdbc.execute("DROP TRIGGER t023_link_fail ON git_commit_task_links"); jdbc.execute("DROP FUNCTION t023_link_fail()"); }
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        outbox.dispatch(); assertThat(probe.received).hasSize(2);
    }

    @Test void selectorSignatureOversizeAndQuotaFailuresLeaveOutboxEmpty() throws Exception {
        byte[] body=push("main",List.of(commit(SHA,"T-001"))).getBytes(StandardCharsets.UTF_8);
        assertThat(sendRawTo(body,"push","application/json",null,"not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(sendRawTo(body,"push","application/json",null,UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
        assertThat(sendRawTo(body,"push","application/json",null,REPO+"&repositoryId="+REPO).statusCode()).isEqualTo(400);
        assertThat(sendRaw(body,"push","application/json","").statusCode()).isEqualTo(401);
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature="sha256="+HexFormat.of().formatHex(mac.doFinal(body));
        byte[] tampered=new String(body,StandardCharsets.UTF_8).replace("T-001","T-002").getBytes(StandardCharsets.UTF_8);
        assertThat(sendRaw(tampered,"push","application/json",signature).statusCode()).isEqualTo(401);
        assertThat(sendRaw(new byte[1048577],"push","application/json",null).statusCode()).isEqualTo(413);
        boolean limited=false;
        for(int i=0;i<200;i++) {
            int status=send("{\"zen\":\"quota\",\"hook_id\":1,\"repository\":{\"id\":123}}","ping").statusCode();
            if(status==429) { limited=true; break; }
            assertThat(status).isIn(200,204);
        }
        assertThat(limited).isTrue(); outbox.dispatch(); assertThat(probe.received).isEmpty();
        assertThat(count("git_domain_event_outbox")).isZero(); assertThat(count("git_commits")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"board","workspace","repository"})
    void scopeDeletionRollbackAndClaimedRowsAreSafe(String scope) throws Exception {
        send(push("main",List.of(commit(SHA,"T-001 T-002"))),"push");
        var claims=outbox.claim();
        java.util.function.Consumer<org.springframework.transaction.TransactionStatus> deletion=s -> {
            if(scope.equals("repository")) jdbc.update("DELETE FROM repositories WHERE id=?",REPO);
            else if(scope.equals("workspace")) events.publishEvent(new WorkspaceDeletedEvent(workspace,UUID.randomUUID()));
            else {
                events.publishEvent(new TaskReferencesRemovedEvent(Set.of(first,second)));
                jdbc.update("DELETE FROM boards WHERE id=?",board);
            }
        };
        new TransactionTemplate(manager).executeWithoutResult(s -> { deletion.accept(s); s.setRollbackOnly(); });
        assertThat(count("git_domain_event_outbox")).isEqualTo(2);
        new TransactionTemplate(manager).executeWithoutResult(deletion);
        claims.forEach(outbox::publish); assertThat(probe.received).isEmpty();
        assertThat(count("git_domain_event_outbox")).isZero();
    }

    @Test void repositoryLockTimeoutDoesNotLeaveReceiptOrOutboxAndRetryRecovers() throws Exception {
        String body=push("main",List.of(commit(SHA,"T-001")));
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM repositories WHERE id=? FOR UPDATE")) {
                statement.setObject(1,REPO); statement.executeQuery();
            }
            try {
                assertThat(send(body,"push").statusCode()).isEqualTo(503);
                assertThat(count("git_domain_event_outbox")).isZero(); assertThat(count("github_webhook_receipts")).isZero();
                outbox.dispatch(); assertThat(probe.received).isEmpty();
            } finally { connection.rollback(); }
        }
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        outbox.dispatch(); assertThat(probe.received).hasSize(1);
    }

    String time(int second) { return String.format("2026-11-01T12:00:%02dZ",second); }
    com.fasterxml.jackson.databind.node.ObjectNode prPayload(String action,String title,int second,boolean merged) throws Exception {
        return (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(pr(action,title,time(second),merged,123));
    }
    UUID board(UUID ws,boolean archived) { return jdbc.queryForObject("INSERT INTO boards(workspace_id,name,is_archived) VALUES (?,'fixture',?) RETURNING id",UUID.class,ws,archived); }
    UUID column(UUID b,String status) { return jdbc.queryForObject("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'fixture',0,?) RETURNING id",UUID.class,b,status); }
    UUID task(UUID c) { return jdbc.queryForObject("INSERT INTO tasks(column_id,title,position) VALUES (?,'fixture',0) RETURNING id",UUID.class,c); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    List<UUID> linked(String table,String parent) { return jdbc.queryForList("SELECT task_id FROM "+table+" ORDER BY "+parent,UUID.class); }
    Map<String,Object> commit(String sha,String message) { return Map.of("id",sha,"message",message,"timestamp",TIME,"author",Map.of("email","sentinel@example.invalid")); }
    String push(String branch,List<Map<String,Object>> commits) throws Exception { return json.writeValueAsString(Map.of("repository",Map.of("id",123),"ref","refs/heads/"+branch,"after",SHA,"deleted",false,"commits",commits)); }
    String pr(String action,String title,String time,boolean merged,int baseId) throws Exception {
        return json.writeValueAsString(Map.of("repository",Map.of("id",123),"action",action,"number",1,"pull_request",Map.of("number",1,"title",title,
                "head",Map.of("ref","feature/T-001","repo",Map.of("id",987)),"base",Map.of("ref","T-002","repo",Map.of("id",baseId)),
                "updated_at",time,"state",action.equals("closed")?"closed":"open","merged",merged)));
    }
    HttpResponse<String> send(String body,String event) throws Exception {
        return sendRaw(body.getBytes(StandardCharsets.UTF_8),event,"application/json",null);
    }
    HttpResponse<String> sendRaw(byte[] body,String event,String media,String suppliedSignature) throws Exception {
        return sendRawTo(body,event,media,suppliedSignature,REPO.toString());
    }
    HttpResponse<String> sendRawTo(byte[] body,String event,String media,String suppliedSignature,String selector) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=suppliedSignature==null?"sha256="+HexFormat.of().formatHex(mac.doFinal(body)):suppliedSignature;
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/gitci/webhook/github?repositoryId="+selector))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type",media).header("Authorization","Bearer invalid")
                .header("Cookie","ignored=invalid").header("CF-Turnstile-Response","invalid")
                .header("X-Hub-Signature-256",signature).header("X-GitHub-Delivery",UUID.randomUUID().toString())
                .header("X-GitHub-Event",event).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
}
