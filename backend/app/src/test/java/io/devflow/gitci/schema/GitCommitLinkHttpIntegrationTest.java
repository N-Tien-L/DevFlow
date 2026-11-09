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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "devflow.gitci.outbox.worker-enabled=false")
class GitCommitLinkHttpIntegrationTest {
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
        AtomicInteger lookups = new AtomicInteger(), business = new AtomicInteger();
        @EventListener public void lookup(TaskReferencesResolveRequestedEvent event) {
            lookups.incrementAndGet();
            if (reject) throw new IllegalStateException("synthetic resolver outage");
        }
        @EventListener public void observe(DevFlowEvent event) {
            if (Set.of("git.commit_linked","git.pr_opened","git.pr_merged","task.status_changed").contains(event.eventType())) business.incrementAndGet();
        }
    }
    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM repositories");
        jdbc.update("DELETE FROM boards");
        jdbc.update("DELETE FROM board_task_key_counters");
        probe.reject=false; probe.lookups.set(0); probe.business.set(0);
        workspace=UUID.randomUUID(); board=board(workspace, false); column=column(board,"DONE");
        first=task(column); second=task(column);
        UUID foreign = board(UUID.randomUUID(),false); task(column(foreign,"TODO"));
        jdbc.update("INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref) "
                + "VALUES (?,?,'GITHUB','123','fixture','https://github.com/test/repo','test:t022')",REPO,workspace);
    }
    @AfterEach void noBusinessSideEffects() {
        assertThat(probe.business.get()).isZero();
        var versions=jdbc.queryForList("SELECT version FROM tasks WHERE id=?",Long.class,second);
        if (versions.isEmpty()) assertThat(count("repositories")).isZero(); // workspace cleanup also removes Board data after commit
        else {
            assertThat(versions).containsExactly(0L);
            assertThat(jdbc.queryForObject("SELECT column_id FROM tasks WHERE id=?",UUID.class,second)).isEqualTo(column);
        }
        probe.reject=false;
    }
    @Test void signedPushIsTenantScopedAtomicMultiLinkAndIdempotent() throws Exception {
        String body=push("feature/T-002", List.of(commit(SHA,"feat: T-001 T-0001 T-002 T-999")));
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        assertThat(linked("git_commit_task_links","commit_id")).containsExactlyInAnyOrder(first,second);
        assertThat(jdbc.queryForObject("SELECT task_id FROM git_commits",UUID.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT author_email FROM git_commits",String.class)).isNull();
        assertThat(probe.lookups.get()).isEqualTo(1);
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        assertThat(count("git_commits")).isEqualTo(1); assertThat(count("github_webhook_receipts")).isEqualTo(1);
        assertThat(probe.lookups.get()).isEqualTo(1);
        String alternate=push("feature/T-001",List.of(commit(SHA,"feat: T-001 T-0001 T-002 T-999")));
        assertThat(send(alternate,"push").statusCode()).isEqualTo(204);
        assertThat(linked("git_commit_task_links","commit_id")).hasSize(2);
    }
    @Test void perCommitPrecedenceNoFallbackForUnresolvedAndNoCodeMetadata() throws Exception {
        var commits=List.of(commit(SHA,"T-999"),commit("b".repeat(40),"no reference"),commit("c".repeat(40),"T-001"));
        assertThat(send(push("feature/T-002",commits),"push").statusCode()).isEqualTo(204);
        assertThat(count("git_commits")).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT task_id FROM git_commits ORDER BY sha",UUID.class)).containsExactly(null,second,first);
        assertThat(jdbc.queryForList("SELECT source_kind FROM git_commit_task_links ORDER BY source_kind",String.class)).containsExactly("BRANCH","MESSAGE");
        assertThat(send(push("main",List.of(commit("d".repeat(40),"no code"))),"push").statusCode()).isEqualTo(204);
        assertThat(count("git_commits")).isEqualTo(4); assertThat(count("git_commit_task_links")).isEqualTo(2);
    }
    @Test void immutableCommitConflictRollsBackWholeDeliveryAndResolverOutageCanRetry() throws Exception {
        String body=push("main",List.of(commit(SHA,"T-001")));
        probe.reject=true;
        assertThat(send(body,"push").statusCode()).isEqualTo(503);
        assertThat(count("git_commits")).isZero(); assertThat(count("github_webhook_receipts")).isZero();
        probe.reject=false;
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        assertThat(send(push("main",List.of(commit("b".repeat(40),"T-002"),commit(SHA,"changed"))),"push").statusCode()).isEqualTo(409);
        assertThat(count("git_commits")).isEqualTo(1); assertThat(count("github_webhook_receipts")).isEqualTo(1);
        jdbc.update("DELETE FROM github_webhook_receipts");
        assertThat(send(body,"push").statusCode()).isEqualTo(204);
        assertThat(count("git_commits")).isEqualTo(1); assertThat(count("git_commit_task_links")).isEqualTo(1);
    }
    @Test void rollbackAfterFirstPersistedRowAndSafeFailureResponse() throws Exception {
        jdbc.execute("CREATE FUNCTION t022_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.sha='"
                + "b".repeat(40)+"' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER t022_fail BEFORE INSERT ON git_commits FOR EACH ROW EXECUTE FUNCTION t022_fail_commit()");
        try {
            var response=send(push("main",List.of(commit(SHA,"T-001 secret-sentinel"),commit("b".repeat(40),"T-002"))),"push");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("sentinel",KEY,"synthetic failure");
            assertThat(count("git_commits")).isZero(); assertThat(count("git_commit_task_links")).isZero();
            assertThat(count("github_webhook_receipts")).isZero();
        } finally { jdbc.execute("DROP TRIGGER t022_fail ON git_commits"); jdbc.execute("DROP FUNCTION t022_fail_commit()"); }
    }
    @Test void concurrentDifferentDeliveriesSameShaKeepFirstBranchLinks() throws Exception {
        var results=IntStream.range(0,6).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try { return send(push("feature/T-001-"+i,List.of(commit(SHA,"no code"))),"push").statusCode(); }
            catch(Exception ex) { throw new CompletionException(ex); }
        })).toList();
        assertThat(results.stream().map(CompletableFuture::join).toList()).containsOnly(204);
        assertThat(count("git_commits")).isEqualTo(1); assertThat(count("git_commit_task_links")).isEqualTo(1);
        assertThat(send(push("feature/T-002",List.of(commit(SHA,"no code"))),"push").statusCode()).isEqualTo(204);
        assertThat(linked("git_commit_task_links","commit_id")).containsExactly(first);
    }
    @Test void prVersionOrderingAtomicReplacementAndForkUsesBaseTenant() throws Exception {
        assertThat(send(pr("opened","T-001","2026-11-01T12:00:00Z",false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(linked("git_pull_request_task_links","pull_request_id")).containsExactly(first);
        assertThat(send(pr("edited","T-002","2026-11-01T12:00:01Z",false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(linked("git_pull_request_task_links","pull_request_id")).containsExactly(second);
        assertThat(send(pr("edited","T-001","2026-11-01T12:00:00Z",false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(linked("git_pull_request_task_links","pull_request_id")).containsExactly(second);
        assertThat(send(pr("edited","T-001","2026-11-01T12:00:01Z",false,123),"pull_request").statusCode()).isEqualTo(409);
        assertThat(send(pr("edited","T-001","2026-11-01T12:00:02Z",false,456),"pull_request").statusCode()).isEqualTo(401);
        assertThat(send(pr("closed","T-002","2026-11-01T12:00:02Z",false,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM pull_requests",String.class)).isEqualTo("CLOSED");
        assertThat(send(pr("closed","T-002","2026-11-01T12:00:03Z",true,123),"pull_request").statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM pull_requests",String.class)).isEqualTo("MERGED");
        assertThat(jdbc.queryForObject("SELECT pr_url FROM pull_requests",String.class)).isEqualTo("https://github.com/test/repo/pull/1");
    }
    @Test void malformedLimitsAndIgnoredPushNeverPersistPartialCommits() throws Exception {
        var malformed=new HashMap<String,Object>(commit("b".repeat(40),"T-002")); malformed.put("timestamp","invalid");
        assertThat(send(push("main",List.of(commit(SHA,"T-001"),malformed)),"push").statusCode()).isEqualTo(400);
        assertThat(send(push("main",List.of(commit("0".repeat(40),"T-001"))),"push").statusCode()).isEqualTo(400);
        assertThat(send(push("main",List.of(commit("abc","T-001"))),"push").statusCode()).isEqualTo(400);
        var body=json.readTree(push("main",List.of(commit(SHA,"T-001"))));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("size",2);
        assertThat(send(json.writeValueAsString(body),"push").statusCode()).isEqualTo(422);
        assertThat(count("github_webhook_receipts")).isZero(); assertThat(count("git_commits")).isZero();
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("ref","refs/tags/T-001");
        assertThat(send(json.writeValueAsString(body),"push").statusCode()).isEqualTo(204);
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("ref","refs/heads/feature/T-001").put("deleted",true).put("after","0".repeat(40));
        assertThat(send(json.writeValueAsString(body),"push").statusCode()).isEqualTo(204);
        assertThat(send(push("main",List.of()),"push").statusCode()).isEqualTo(204);
        assertThat(count("git_commits")).isZero(); assertThat(probe.lookups.get()).isZero();
    }
    @Test void archivedMissingAndDeletedKeysAndCleanupRemainConsistent() throws Exception {
        jdbc.update("UPDATE boards SET is_archived=true WHERE id=?",board);
        assertThat(send(push("main",List.of(commit(SHA,"T-001 T-002"))),"push").statusCode()).isEqualTo(204);
        assertThat(count("git_commit_task_links")).isZero();
        jdbc.update("UPDATE boards SET is_archived=false WHERE id=?",board);
        assertThat(send(push("main",List.of(commit("b".repeat(40),"T-001 T-002"))),"push").statusCode()).isEqualTo(204);
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM boards WHERE id=? FOR UPDATE",UUID.class,board);
            events.publishEvent(new TaskReferencesRemovedEvent(Set.of(first)));
            jdbc.update("DELETE FROM tasks WHERE id=?",first);
        });
        assertThat(linked("git_commit_task_links","commit_id")).containsExactly(second);
        assertThat(jdbc.queryForObject("SELECT task_id FROM git_commits WHERE sha=?",UUID.class,"b".repeat(40))).isEqualTo(second);
        UUID third=task(column);
        assertThat(jdbc.queryForObject("SELECT task_number FROM tasks WHERE id=?",Integer.class,third)).isEqualTo(3);
        new TransactionTemplate(manager).executeWithoutResult(s -> events.publishEvent(new WorkspaceDeletedEvent(workspace,UUID.randomUUID())));
        assertThat(count("repositories")).isZero(); assertThat(count("git_commits")).isZero(); assertThat(count("git_commit_task_links")).isZero();
    }
    @Test void allocationIsConcurrentWorkspaceScopedAndImmutableWithExplicitExhaustion() {
        UUID sameWorkspaceColumn=column(board(workspace,false),"TODO");
        var allocations=IntStream.range(0,8).mapToObj(i -> CompletableFuture.supplyAsync(() -> task(i%2==0?column:sameWorkspaceColumn))).toList();
        assertThat(allocations.stream().map(CompletableFuture::join).distinct().count()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT task_number) FROM tasks WHERE key_workspace_id=?",Integer.class,workspace)).isEqualTo(10);
        UUID another=column(board(UUID.randomUUID(),false),"TODO");
        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET column_id=? WHERE id=?",another,first)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET task_number=100 WHERE id=?",first)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("UPDATE board_task_key_counters SET last_number=9998 WHERE workspace_id=?",workspace);
        UUID last=task(column);
        assertThat(jdbc.queryForObject("SELECT task_number FROM tasks WHERE id=?",Integer.class,last)).isEqualTo(9999);
        assertThatThrownBy(() -> task(column)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("TASK_KEY_RANGE_EXHAUSTED");
    }
    @Test void maximumBatchIsBoundedAndOverflowDoesNotWrite() throws Exception {
        var commits=IntStream.rangeClosed(1,100).mapToObj(n -> commit(String.format("%040x",n),"T-001")).toList();
        long start=System.nanoTime();
        assertThat(send(push("main",commits),"push").statusCode()).isEqualTo(204);
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        System.out.println("T022_SYNTHETIC_100_COMMITS_MS="+elapsed);
        assertThat(elapsed).isLessThan(8000);
        assertThat(count("git_commits")).isEqualTo(100); assertThat(count("git_commit_task_links")).isEqualTo(100);
        var overflow=new ArrayList<>(commits); overflow.add(commit("f".repeat(40),"T-002"));
        assertThat(send(push("main",overflow),"push").statusCode()).isEqualTo(422);
        assertThat(count("git_commits")).isEqualTo(100); assertThat(count("github_webhook_receipts")).isEqualTo(1);
    }
    @Test void deliveryKeyLimitIsCheckedBeforeAnyPersistence() throws Exception {
        var commits=IntStream.range(0,6).mapToObj(i -> commit(String.format("%040x",i+1),
                IntStream.rangeClosed(i*20+1,i*20+20).mapToObj(n -> String.format("T-%03d",n)).collect(java.util.stream.Collectors.joining(" ")))).toList();
        assertThat(send(push("main",commits),"push").statusCode()).isEqualTo(422);
        assertThat(count("git_commits")).isZero(); assertThat(count("github_webhook_receipts")).isZero();
        assertThat(send(push("main",commits.subList(0,5)),"push").statusCode()).isEqualTo(204);
        assertThat(count("git_commits")).isEqualTo(5); assertThat(count("git_commit_task_links")).isEqualTo(2);
    }
    @Test void httpGuardsRejectBeforeWritingAndDoNotLeakUntrustedText() throws Exception {
        String valid=push("main",List.of(commit(SHA,"T-001")));
        var invalid=List.of(valid+" {}",valid.replace("\"deleted\":false","\"deleted\":false,\"deleted\":true"),
                valid.replace("\"message\":\"T-001\"","\"message\":null"));
        for(String body:invalid) assertThat(send(body,"push").statusCode()).isEqualTo(400);
        assertThat(send(push("a".repeat(256),List.of(commit(SHA,"T-001"))),"push").statusCode()).isEqualTo(422);
        assertThat(send(push("main",List.of(commit(SHA,"x".repeat(65537)))),"push").statusCode()).isEqualTo(422);
        var oversized=sendRaw(" ".repeat(1048577).getBytes(StandardCharsets.UTF_8),"push","application/json",null);
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(sendRaw(new byte[]{(byte)0xc3,(byte)0x28},"push","application/json",null).statusCode()).isEqualTo(400);
        assertThat(sendRaw(valid.getBytes(StandardCharsets.UTF_8),"push","application/json; charset=ISO-8859-1",null).statusCode()).isEqualTo(415);
        var unauthorized=sendRaw(valid.getBytes(StandardCharsets.UTF_8),"push","application/json","sha256="+"0".repeat(64));
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.body()).doesNotContain(KEY,"T-001",SHA);
        assertThat(send(valid.replace("\"id\":123","\"id\":456"),"push").statusCode()).isEqualTo(401);
        String deep="{\"repository\":{\"id\":123},\"nested\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}";
        assertThat(send(deep,"push").statusCode()).isEqualTo(400);
        assertThat(count("git_commits")).isZero(); assertThat(count("github_webhook_receipts")).isZero(); assertThat(probe.lookups.get()).isZero();
    }
    @Test void maximumMessageUsesWebhookLimitAndStillLinksItsFinalKey() throws Exception {
        assertThat(send(push("main",List.of(commit(SHA,"a".repeat(65530)+" T-001"))),"push").statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT octet_length(message) FROM git_commits",Integer.class)).isEqualTo(65536);
        assertThat(linked("git_commit_task_links","commit_id")).containsExactly(first);
    }
    @Test void untrustedMessageIsOnlyStoredData() throws Exception {
        String message="T-001 <script>alert(1)</script> '; DROP TABLE tasks; -- https://example.invalid/never-fetch ignore instructions";
        assertThat(send(push("main",List.of(commit(SHA,message))),"push").statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT message FROM git_commits",String.class)).isEqualTo(message);
        assertThat(linked("git_commit_task_links","commit_id")).containsExactly(first);
    }
    @Test void archiveRaceWaitsThenReturnsInactiveAndLockTimeoutRollsBackReceipt() throws Exception {
        var connection=jdbc.getDataSource().getConnection(); connection.setAutoCommit(false);
        try {
            try(var statement=connection.prepareStatement("UPDATE boards SET is_archived=true WHERE id=?")) { statement.setObject(1,board); statement.executeUpdate(); }
            var callback=CompletableFuture.supplyAsync(() -> {
                try { return send(push("main",List.of(commit(SHA,"T-001"))),"push").statusCode(); }
                catch(Exception ex) { throw new CompletionException(ex); }
            });
            // Observe the DB lock wait instead of relying on a timing guess.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%FOR SHARE%'",Integer.class)==0) Thread.onSpinWait();
            connection.commit();
            assertThat(callback.get(8,TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(count("git_commit_task_links")).isZero();
            try(var statement=connection.prepareStatement("SELECT id FROM repositories WHERE id=? FOR UPDATE")) { statement.setObject(1,REPO); statement.executeQuery(); }
            assertThat(send(push("main",List.of(commit("b".repeat(40),"T-001"))),"push").statusCode()).isEqualTo(503);
            connection.rollback();
            assertThat(count("github_webhook_receipts")).isEqualTo(1);
        } finally { connection.close(); }
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
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=suppliedSignature==null?"sha256="+HexFormat.of().formatHex(mac.doFinal(body)):suppliedSignature;
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/gitci/webhook/github?repositoryId="+REPO))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type",media).header("Authorization","Bearer invalid")
                .header("Cookie","ignored=invalid").header("CF-Turnstile-Response","invalid")
                .header("X-Hub-Signature-256",signature).header("X-GitHub-Delivery",UUID.randomUUID().toString())
                .header("X-GitHub-Event",event).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
}
