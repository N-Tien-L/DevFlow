package io.devflow.board.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.board.internal.BoardRealtimeBroadcaster;
import io.devflow.common.event.*;
import io.devflow.gitci.internal.GitDomainEventOutbox;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real PostgreSQL/Flyway, HTTP/HMAC, event bus, transaction proxies and two authorized STOMP clients. */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "devflow.gitci.outbox.worker-enabled=false", "devflow.board.git.auto-transition-enabled=true",
        "devflow.board.tasks.max-per-column=3"})
class GitTaskTransitionIntegrationTest {
    static final UUID REPO = UUID.fromString("c199e15a-7a6a-4e56-bc4b-625f47642424");
    static final String KEY = "test-only-t024-secret-0123456789-abcdef";
    static final Instant TIME = Instant.parse("2026-11-18T01:00:00Z");
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher events;
    @Autowired GitDomainEventOutbox outbox;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @Autowired Probe probe;
    @Autowired SimpUserRegistry users;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired io.devflow.board.internal.TaskManagementService taskService;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @MockitoSpyBean io.devflow.board.internal.BoardAuditService auditService;
    @MockitoSpyBean BoardRealtimeBroadcaster broadcaster;
    UUID workspace, board, task, member, secondMember, outsider;
    Map<String, UUID> columns;

    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.flyway.enabled", () -> true);
        r.add("devflow.gitci.github-webhook.enabled", () -> true);
        String prefix = "devflow.gitci.github-webhook.bindings[" + REPO + "].";
        r.add(prefix + "reference", () -> "test:t024"); r.add(prefix + "current", () -> KEY);
    }
    @TestConfiguration static class Config { @Bean Probe probe() { return new Probe(); } }
    static class Probe {
        volatile boolean rejectStatus;
        final List<TaskStatusChangedEvent> statuses = new CopyOnWriteArrayList<>();
        final List<String> correlations = new CopyOnWriteArrayList<>();
        @EventListener public void on(TaskStatusChangedEvent event) {
            statuses.add(event); correlations.add(MDC.get("correlationId"));
            if (rejectStatus) throw new IllegalStateException("synthetic downstream failure");
        }
    }

    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM repositories");
        jdbc.update("DELETE FROM boards");
        jdbc.update("DELETE FROM board_audit_events");
        probe.rejectStatus=false;
        probe.statuses.clear(); probe.correlations.clear(); clearInvocations(broadcaster);
        workspace = UUID.randomUUID(); member = user(); secondMember = user(); outsider = user();
        jdbc.update("INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,'fixture',?,?)", workspace,workspace.toString(),member);
        for (UUID id : List.of(member,secondMember))
            jdbc.update("INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'OWNER')",workspace,id);
        board = jdbc.queryForObject("INSERT INTO boards(workspace_id,name) VALUES (?,'fixture') RETURNING id",UUID.class,workspace);
        columns = new LinkedHashMap<>();
        for (String category : List.of("TODO","IN_PROGRESS","IN_REVIEW","DONE")) columns.put(category,column(category));
        task = task(columns.get("TODO"),0);
        jdbc.update("INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref) "
                + "VALUES (?,?,'GITHUB','123','fixture','https://github.com/test/repo','test:t024')",REPO,workspace);
    }

    @ParameterizedTest
    @CsvSource({"TODO,0,true", "IN_PROGRESS,0,false", "IN_REVIEW,0,false", "DONE,0,false",
        "TODO,1,true", "IN_PROGRESS,1,true", "IN_REVIEW,1,false", "DONE,1,false",
        "TODO,2,true", "IN_PROGRESS,2,true", "IN_REVIEW,2,true", "DONE,2,false"})
    void allTwelveTransitionsAndReplayHaveExactSideEffects(String source,int kind,boolean applied) {
        jdbc.update("UPDATE tasks SET column_id=? WHERE id=?",columns.get(source),task);
        UUID destination=columns.get(target(kind));
        task(destination,0);
        DevFlowEvent event=event(kind,UUID.randomUUID(),TIME);
        var before=row();
        events.publishEvent(event);
        assertThat(row().get("column_id")).isEqualTo(applied?destination:columns.get(source));
        assertThat(count("board_git_event_receipts")).isEqualTo(1);
        assertThat(probe.statuses).hasSize(applied?1:0);
        assertThat(count("board_audit_events")).isEqualTo(applied?1:0);
        if (applied) {
            assertThat(row().get("position")).isEqualTo(1);
            assertThat(row().get("version")).isEqualTo(1L);
            var audit=jdbc.queryForMap("SELECT * FROM board_audit_events WHERE resource_id=?",task);
            assertThat(audit.get("actor_id")).isNull(); assertThat(audit.get("cause")).isEqualTo("GIT");
            assertThat(audit.get("source_event_id")).isEqualTo(id(event));
            assertThat(audit.get("source_column_id")).isEqualTo(columns.get(source));
            assertThat(audit.get("destination_column_id")).isEqualTo(destination);
            var status=probe.statuses.get(0);
            assertThat(status.cause()).isEqualTo(TaskStatusChangedEvent.StatusChangeCause.GIT);
            assertThat(status.actorId()).isNull(); assertThat(status.boardId()).isEqualTo(board);
            assertThat(status.workspaceId()).isEqualTo(workspace);
            assertThat(status.oldStatusCategory()).isEqualTo(source);
            assertThat(status.newStatusCategory()).isEqualTo(target(kind));
            assertThat(status.correlationId().toString()).isEqualTo(probe.correlations.get(0));
            assertThat(status.correlationId()).isEqualTo(audit.get("correlation_id"));
            verify(broadcaster).broadcast(argThat(message -> message.type()==BoardMutationType.CARD_MOVED
                    && message.actorId()==null && message.correlationId().equals(status.correlationId())
                    && message.data().affectedColumnIds().containsAll(List.of(columns.get(source),destination))));
        } else assertThat(row()).isEqualTo(before);
        var after=row(); events.publishEvent(event);
        assertThat(row()).isEqualTo(after); assertThat(count("board_git_event_receipts")).isEqualTo(1);
        assertThat(count("board_audit_events")).isEqualTo(applied?1:0);
        assertThat(probe.statuses).hasSize(applied?1:0);
    }

    @ParameterizedTest @CsvSource({"012","021","102","120","201","210"})
    void allSixDeliveryOrdersEndDone(String order) {
        for (char kind:order.toCharArray()) events.publishEvent(event(kind-'0',UUID.randomUUID(),TIME));
        assertThat(row().get("column_id")).isEqualTo(columns.get("DONE"));
        assertThat(count("board_git_event_receipts")).isEqualTo(3);
    }

    @Test void manualWatermarkEqualityReopenAndReplayAreProtected() throws Exception {
        DevFlowEvent merged=event(2,UUID.randomUUID(),TIME); events.publishEvent(merged);
        move(task,columns.get("TODO"),0,member,200);
        var after=row();
        assertThat(after.get("last_manual_status_changed_at")).isNotNull();
        events.publishEvent(merged); assertThat(row()).isEqualTo(after);
        Instant watermark=((Timestamp)after.get("last_manual_status_changed_at")).toInstant();
        events.publishEvent(event(2,UUID.randomUUID(),watermark)); assertThat(row()).isEqualTo(after);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM board_git_event_receipts WHERE outcome='STALE_MANUAL'",Integer.class)).isEqualTo(1);
        events.publishEvent(event(0,UUID.randomUUID(),watermark.plusSeconds(1)));
        assertThat(row().get("column_id")).isEqualTo(columns.get("IN_PROGRESS"));
        move(task,columns.get("IN_PROGRESS"),0,member,200);
        assertThat(row().get("last_manual_status_changed_at")).isEqualTo(after.get("last_manual_status_changed_at"));
        UUID otherDoing=column("IN_PROGRESS"); move(task,otherDoing,0,member,200);
        assertThat(((Timestamp)row().get("last_manual_status_changed_at")).toInstant()).isAfter(watermark);
    }

    @Test void categoryAmbiguityMissingAndCapacityRollbackThenRecover() {
        var event=event(0,UUID.randomUUID(),TIME);
        UUID extra=column("IN_PROGRESS"); reject(event,"TARGET_AMBIGUOUS");
        jdbc.update("DELETE FROM columns WHERE id IN (?,?)",extra,columns.get("IN_PROGRESS"));
        reject(event,"TARGET_MISSING");
        UUID destination=column("IN_PROGRESS"); columns.put("IN_PROGRESS",destination);
        UUID a=task(destination,0),b=task(destination,1),c=task(destination,2);
        reject(event,"TARGET_FULL"); jdbc.update("DELETE FROM tasks WHERE id=?",c);
        events.publishEvent(event); assertThat(row().get("position")).isEqualTo(2);
        assertThat(row().get("column_id")).isEqualTo(destination);
        assertThat(jdbc.queryForList("SELECT position FROM tasks WHERE column_id=? ORDER BY position",Integer.class,destination)).containsExactly(0,1,2);
    }

    @Test void noopDoesNotResolveAmbiguousTargetAndPreservesVersion() {
        jdbc.update("UPDATE tasks SET column_id=? WHERE id=?",columns.get("IN_PROGRESS"),task); column("IN_PROGRESS");
        var before=row(); events.publishEvent(event(0,UUID.randomUUID(),TIME)); assertThat(row()).isEqualTo(before);
    }

    @Test void archivedMissingForeignAndLegacyEventsFailClosed() {
        reject(new GitCommitLinkedEvent(task,"a".repeat(40),"test/repo",null,TIME),"INVALID_EVENT");
        reject(new GitCommitLinkedEvent(task,"a".repeat(40),"test/repo",null,TIME,UUID.randomUUID(),REPO,UUID.randomUUID()),"TENANT_MISMATCH");
        jdbc.update("UPDATE boards SET is_archived=true WHERE id=?",board);
        events.publishEvent(event(2,UUID.randomUUID(),TIME));
        assertThat(row().get("column_id")).isEqualTo(columns.get("TODO"));
        assertThat(jdbc.queryForObject("SELECT outcome FROM board_git_event_receipts",String.class)).isEqualTo("ARCHIVED");
        jdbc.update("DELETE FROM tasks WHERE id=?",task); assertThat(count("board_git_event_receipts")).isZero();
        events.publishEvent(event(2,UUID.randomUUID(),TIME)); assertThat(count("board_audit_events")).isZero();
    }

    @Test void conflictingPayloadCannotReuseAnEventId() {
        UUID id=UUID.randomUUID(); events.publishEvent(event(0,id,TIME));
        assertThatThrownBy(() -> events.publishEvent(event(2,id,TIME))).hasMessageContaining("EVENT_CONFLICT");
        assertThat(row().get("column_id")).isEqualTo(columns.get("IN_PROGRESS"));
        assertThat(count("board_git_event_receipts")).isEqualTo(1); assertThat(count("board_audit_events")).isEqualTo(1);
    }

    @Test void auditFailureRollsBackPositionsReceiptAndAfterCommitSignals() {
        task(columns.get("TODO"),1); task(columns.get("IN_PROGRESS"),0);
        var before=row();
        jdbc.execute("CREATE FUNCTION t024_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic'; END $$");
        jdbc.execute("CREATE TRIGGER t024_fail BEFORE INSERT ON board_audit_events FOR EACH ROW EXECUTE FUNCTION t024_fail()");
        var event=event(0,UUID.randomUUID(),TIME);
        try {
            assertThatThrownBy(() -> events.publishEvent(event)).isInstanceOf(RuntimeException.class);
            assertThat(row()).isEqualTo(before); assertThat(count("board_git_event_receipts")).isZero();
            assertThat(probe.statuses).isEmpty(); verify(broadcaster,never()).broadcast(any());
        } finally { jdbc.execute("DROP TRIGGER t024_fail ON board_audit_events"); jdbc.execute("DROP FUNCTION t024_fail()"); }
        events.publishEvent(event); assertThat(row().get("column_id")).isEqualTo(columns.get("IN_PROGRESS"));
    }

    @Test void realtimeFailureDoesNotRollbackCommittedMoveOrReplayIt() {
        doThrow(new IllegalStateException("synthetic")).when(broadcaster).broadcast(any());
        var event=event(2,UUID.randomUUID(),TIME);
        try {
            events.publishEvent(event); events.publishEvent(event);
            assertThat(row().get("column_id")).isEqualTo(columns.get("DONE"));
            assertThat(count("board_git_event_receipts")).isEqualTo(1); assertThat(probe.statuses).hasSize(1);
            verify(broadcaster,times(1)).broadcast(any());
        } finally { doCallRealMethod().when(broadcaster).broadcast(any()); }
    }

    @Test void concurrentSameAndDifferentEventsRemainIdempotent() throws Exception {
        var same=event(0,UUID.randomUUID(),TIME);
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(6);
        try {
            var jobs=Stream.of(same,same,same,event(1,UUID.randomUUID(),TIME),event(2,UUID.randomUUID(),TIME),same)
                    .map(e -> pool.submit(() -> { start.await(); events.publishEvent(e); return true; })).toList();
            start.countDown(); for (var job:jobs) assertThat(job.get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(row().get("column_id")).isEqualTo(columns.get("DONE"));
            assertThat(count("board_git_event_receipts")).isEqualTo(3);
            assertThat(probe.statuses).hasSizeBetween(1,3);
        } finally { pool.shutdownNow(); }
    }

    @Test void boardLockTimeoutRollsBackThenRetryWorks() throws Exception {
        var event=event(0,UUID.randomUUID(),TIME);
        try (var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("SELECT id FROM boards WHERE id=? FOR UPDATE")) {
                statement.setObject(1,board); statement.execute();
            }
            assertThatThrownBy(() -> events.publishEvent(event)).isInstanceOf(RuntimeException.class);
            assertThat(count("board_git_event_receipts")).isZero(); assertThat(probe.statuses).isEmpty();
            connection.rollback();
        }
        events.publishEvent(event); assertThat(row().get("column_id")).isEqualTo(columns.get("IN_PROGRESS"));
    }

    @Test void manualAuthorizationIsUnchangedAndMdcRestored() throws Exception {
        move(task,columns.get("DONE"),0,outsider,404);
        mvc.perform(patch("/api/v1/tasks/"+task+"/move").contentType("application/json")
                .content(json.writeValueAsString(Map.of("columnId",columns.get("DONE"),"position",0)))).andExpect(status().isUnauthorized());
        MDC.put("correlationId","parent-context"); MDC.put("userId","parent-user");
        var previous=MDC.getCopyOfContextMap();
        try { events.publishEvent(event(0,UUID.randomUUID(),TIME)); assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous); }
        finally { MDC.clear(); }
    }

    @Test void signedWebhookDrivesTwoRealClientsAndAllThreeTransitions() throws Exception {
        try (var first=socket(member); var second=socket(secondMember)) {
            for (int kind=0;kind<3;kind++) {
                assertThat(webhook(kind)).isEqualTo(204); outbox.dispatch();
                for (var socket:List.of(first,second)) {
                    Map<?,?> frame=socket.messages.poll(5,TimeUnit.SECONDS);
                    assertThat(frame).isNotNull(); assertThat(frame.get("type")).isEqualTo("CARD_MOVED");
                    assertThat(frame.get("actorId")).isNull();
                    var data=(Map<?,?>)frame.get("data");
                    assertThat(data.get("taskId")).isEqualTo(task.toString());
                    assertThat(data.get("destinationColumnId")).isEqualTo(columns.get(target(kind)).toString());
                }
                assertThat(row().get("column_id")).isEqualTo(columns.get(target(kind)));
                mvc.perform(get("/api/v1/tasks/"+task).header("Authorization",bearer(member)))
                        .andExpect(status().isOk());
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM git_domain_event_outbox WHERE status='PUBLISHED'",Integer.class)).isEqualTo(3);
            assertThat(count("board_git_event_receipts")).isEqualTo(3); assertThat(probe.statuses).hasSize(3);
        }
    }

    @Test void workerFailureExhaustionAndRequeuePreserveIdAndSnapshot() throws Exception {
        jdbc.update("DELETE FROM columns WHERE id=?",columns.get("IN_PROGRESS"));
        assertThat(webhook(0)).isEqualTo(204);
        UUID eventId=jdbc.queryForObject("SELECT id FROM git_domain_event_outbox",UUID.class);
        for(int i=0;i<8;i++) { jdbc.update("UPDATE git_domain_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP"); outbox.dispatch(); }
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox",String.class)).isEqualTo("FAILED");
        assertThat(count("board_git_event_receipts")).isZero();
        UUID fixed=column("IN_PROGRESS"); assertThat(outbox.requeue(eventId)).isTrue(); outbox.dispatch();
        assertThat(row().get("column_id")).isEqualTo(fixed);
        assertThat(jdbc.queryForObject("SELECT event_id FROM board_git_event_receipts",UUID.class)).isEqualTo(eventId);
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM git_domain_event_outbox",UUID.class)).isEqualTo(workspace);
    }


    @ParameterizedTest @ValueSource(ints={0,1,2})
    void newGitActivityCanAdvanceAfterManualReopen(int kind) throws Exception {
        move(task,columns.get("DONE"),0,member,200);
        move(task,columns.get("TODO"),0,member,200);
        Instant watermark=((Timestamp)row().get("last_manual_status_changed_at")).toInstant();
        var before=row();
        events.publishEvent(event(kind,UUID.randomUUID(),watermark.minusSeconds(1)));
        assertThat(row()).isEqualTo(before);
        events.publishEvent(event(kind,UUID.randomUUID(),watermark));
        assertThat(row()).isEqualTo(before);
        events.publishEvent(event(kind,UUID.randomUUID(),watermark.plusSeconds(1)));
        assertThat(row().get("column_id")).isEqualTo(columns.get(target(kind)));
        assertThat(row().get("last_manual_status_changed_at")).isEqualTo(before.get("last_manual_status_changed_at"));
    }

    @Test void editingContentDoesNotSetTheManualStatusWatermark() throws Exception {
        mvc.perform(put("/api/v1/tasks/"+task).header("Authorization",bearer(member))
                .header("If-Match","\"task-"+task+"-v0\"").contentType("application/json")
                .content(json.writeValueAsString(Map.of("title","Edited title","priority","MEDIUM"))))
                .andExpect(status().isOk());
        assertThat(row().get("last_manual_status_changed_at")).isNull();
        events.publishEvent(event(0,UUID.randomUUID(),TIME));
        assertThat(row().get("column_id")).isEqualTo(columns.get("IN_PROGRESS"));
        assertThat(row().get("title")).isEqualTo("Edited title");
    }

    @Test void targetOnAnotherBoardIsNeverUsed() {
        jdbc.update("DELETE FROM columns WHERE id=?",columns.get("IN_PROGRESS"));
        UUID other=jdbc.queryForObject("INSERT INTO boards(workspace_id,name) VALUES (?,'other') RETURNING id",UUID.class,workspace);
        jdbc.update("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'other target',0,'IN_PROGRESS')",other);
        reject(event(0,UUID.randomUUID(),TIME),"TARGET_MISSING");
    }

    @ParameterizedTest @ValueSource(ints={0,1,2})
    void movingEverySourcePositionNormalizesBothListsAndPreservesTaskData(int sourcePosition) {
        jdbc.update("UPDATE tasks SET position=?,assignee_id=?,priority='HIGH',due_date=CURRENT_TIMESTAMP WHERE id=?",sourcePosition,outsider,task);
        for(int i=0;i<3;i++) if(i!=sourcePosition) task(columns.get("TODO"),i);
        task(columns.get("IN_PROGRESS"),0);
        jdbc.update("INSERT INTO task_comments(task_id,author_id,content) VALUES (?,?,'preserve comment')",task,outsider);
        var before=row(); events.publishEvent(event(0,UUID.randomUUID(),TIME)); var after=row();
        for(String field:List.of("title","description","assignee_id","priority","due_date","task_number","created_at"))
            assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_comments WHERE task_id=?",Integer.class,task)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT position FROM tasks WHERE column_id=? ORDER BY position",Integer.class,columns.get("TODO"))).containsExactly(0,1);
        assertThat(jdbc.queryForList("SELECT position FROM tasks WHERE column_id=? ORDER BY position",Integer.class,columns.get("IN_PROGRESS"))).containsExactly(0,1);
    }

    @Test void receiptFailureAlsoRollsBackTheFlushedMoveAndAudit() {
        var before=row();
        jdbc.execute("CREATE FUNCTION t024_receipt_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic'; END $$");
        jdbc.execute("CREATE TRIGGER t024_receipt_fail BEFORE INSERT ON board_git_event_receipts FOR EACH ROW EXECUTE FUNCTION t024_receipt_fail()");
        try {
            assertThatThrownBy(()->events.publishEvent(event(0,UUID.randomUUID(),TIME))).isInstanceOf(RuntimeException.class);
            assertThat(row()).isEqualTo(before); assertThat(count("board_audit_events")).isZero();
            assertThat(probe.statuses).isEmpty(); verify(broadcaster,never()).broadcast(any());
        } finally { jdbc.execute("DROP TRIGGER t024_receipt_fail ON board_git_event_receipts"); jdbc.execute("DROP FUNCTION t024_receipt_fail()"); }
    }

    @Test void downstreamTaskSubscriberFailureDoesNotUndoOrRepeatTheMove() {
        double before=metrics.counter("devflow.task.events.dispatch","eventType",EventTypes.TASK_STATUS_CHANGED,"outcome","failure").count();
        probe.rejectStatus=true;
        var event=event(2,UUID.randomUUID(),TIME);
        try { events.publishEvent(event); events.publishEvent(event); }
        finally { probe.rejectStatus=false; }
        assertThat(row().get("column_id")).isEqualTo(columns.get("DONE"));
        assertThat(probe.statuses).hasSize(1); verify(broadcaster,times(1)).broadcast(any());
        assertThat(metrics.counter("devflow.task.events.dispatch","eventType",EventTypes.TASK_STATUS_CHANGED,"outcome","failure").count()).isEqualTo(before+1);
    }

    @Test void leaseRecoveryAfterBoardCommitAndOutboxRetentionDoNotRepeatSideEffects() throws Exception {
        assertThat(webhook(0)).isEqualTo(204);
        var claimed=outbox.claim().get(0);
        var delivered=new GitCommitLinkedEvent(claimed.taskId(),claimed.key(),claimed.repo(),claimed.author(),
                claimed.occurredAt(),claimed.id(),claimed.repositoryId(),claimed.workspaceId());
        events.publishEvent(delivered);
        var committed=row();
        // Crash boundary: Board committed, worker has not acknowledged the leased delivery.
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox",String.class)).isEqualTo("PROCESSING");
        jdbc.update("UPDATE git_domain_event_outbox SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        var restarted=new GitDomainEventOutbox(jdbc,transactionManager,events,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),false);
        restarted.dispatch();
        assertThat(row()).isEqualTo(committed); assertThat(probe.statuses).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM git_domain_event_outbox",String.class)).isEqualTo("PUBLISHED");
        jdbc.update("UPDATE git_domain_event_outbox SET published_at=CURRENT_TIMESTAMP-INTERVAL '8 days'");
        restarted.clean(); assertThat(count("git_domain_event_outbox")).isZero();
        events.publishEvent(delivered); assertThat(row()).isEqualTo(committed);
        assertThat(count("board_git_event_receipts")).isEqualTo(1); assertThat(count("board_audit_events")).isEqualTo(1);
        verify(broadcaster,times(1)).broadcast(any());
    }

    @Test void manualTransactionWinningTheLockMakesOlderGitStale() throws Exception {
        var pool=Executors.newSingleThreadExecutor();
        final Future<?>[] worker={null};
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT id FROM boards WHERE id=? FOR UPDATE",UUID.class,board);
                int pid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                asMember(()->taskService.moveTask(task,new MoveTaskRequest(columns.get("DONE"),0)));
                worker[0]=pool.submit(()->events.publishEvent(event(0,UUID.randomUUID(),TIME)));
                awaitBlocked(pid);
            });
            worker[0].get(10,TimeUnit.SECONDS);
            assertThat(row().get("column_id")).isEqualTo(columns.get("DONE"));
            assertThat(jdbc.queryForObject("SELECT outcome FROM board_git_event_receipts",String.class)).isEqualTo("STALE_MANUAL");
            assertThat(probe.statuses).hasSize(1).allMatch(e->e.cause()==TaskStatusChangedEvent.StatusChangeCause.MANUAL);
        } finally { pool.shutdownNow(); }
    }

    @Test void gitWinningTheLockAllowsSubsequentManualReopenWithoutReplay() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        doAnswer(invocation->{ invocation.callRealMethod(); entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout"); return null;
        }).when(auditService).recordTaskMove(isNull(),eq(task),eq(workspace),any(),any(),notNull());
        var event=event(2,UUID.randomUUID(),TIME);
        try {
            var git=pool.submit(()->events.publishEvent(event)); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(row().get("column_id")).isEqualTo(columns.get("TODO"));
            assertThat(count("board_audit_events")).isZero(); assertThat(count("board_git_event_receipts")).isZero();
            assertThat(probe.statuses).isEmpty(); verify(broadcaster,never()).broadcast(any());
            var manual=pool.submit(()->{ move(task,columns.get("TODO"),0,member,200); return true; });
            awaitAnyBlocked(); release.countDown(); git.get(10,TimeUnit.SECONDS); assertThat(manual.get(10,TimeUnit.SECONDS)).isTrue();
            var committed=row(); events.publishEvent(event); assertThat(row()).isEqualTo(committed);
            assertThat(row().get("column_id")).isEqualTo(columns.get("TODO"));
            assertThat(count("board_audit_events")).isEqualTo(2);
        } finally { release.countDown(); doCallRealMethod().when(auditService).recordTaskMove(any(),any(),any(),any(),any(),any()); pool.shutdownNow(); }
    }

    @Test void automationAndManualMoveCompeteForTheLastSlotWithoutOverflow() throws Exception {
        task(columns.get("IN_PROGRESS"),0); task(columns.get("IN_PROGRESS"),1);
        UUID other=task(columns.get("TODO"),1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        doAnswer(invocation->{ invocation.callRealMethod(); entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout"); return null;
        }).when(auditService).recordTaskMove(isNull(),eq(task),eq(workspace),any(),any(),notNull());
        try {
            var git=pool.submit(()->events.publishEvent(event(0,UUID.randomUUID(),TIME)));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var manual=pool.submit(()->{ move(other,columns.get("IN_PROGRESS"),0,member,409); return true; });
            awaitAnyBlocked(); release.countDown(); git.get(10,TimeUnit.SECONDS); assertThat(manual.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForList("SELECT position FROM tasks WHERE column_id=? ORDER BY position",Integer.class,columns.get("IN_PROGRESS"))).containsExactly(0,1,2);
            assertThat(jdbc.queryForObject("SELECT column_id FROM tasks WHERE id=?",UUID.class,other)).isEqualTo(columns.get("TODO"));
        } finally { release.countDown(); doCallRealMethod().when(auditService).recordTaskMove(any(),any(),any(),any(),any(),any()); pool.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings={"archive","deleteTask","deleteBoard","deleteTarget","recategorize"})
    void lifecycleChangeCommittedWhileGitWaitsIsReadAgain(String action) throws Exception {
        var pool=Executors.newSingleThreadExecutor(); final Future<?>[] git={null};
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT id FROM boards WHERE id=? FOR UPDATE",UUID.class,board);
                int pid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                switch(action) {
                    case "archive" -> jdbc.update("UPDATE boards SET is_archived=true WHERE id=?",board);
                    case "deleteTask" -> jdbc.update("DELETE FROM tasks WHERE id=?",task);
                    case "deleteBoard" -> jdbc.update("DELETE FROM boards WHERE id=?",board);
                    case "deleteTarget" -> jdbc.update("DELETE FROM columns WHERE id=?",columns.get("IN_PROGRESS"));
                    default -> jdbc.update("UPDATE columns SET status_category='IN_REVIEW' WHERE id=?",columns.get("IN_PROGRESS"));
                }
                git[0]=pool.submit(()->events.publishEvent(event(0,UUID.randomUUID(),TIME)));
                awaitBlocked(pid);
            });
            if(action.equals("deleteTarget")||action.equals("recategorize"))
                assertThatThrownBy(()->git[0].get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(RuntimeException.class).hasMessageContaining("TARGET_MISSING");
            else git[0].get(10,TimeUnit.SECONDS);
            assertThat(count("board_audit_events")).isZero(); assertThat(probe.statuses).isEmpty();
            assertThat(count("board_git_event_receipts")).isEqualTo(action.equals("archive")?1:0);
        } finally { pool.shutdownNow(); }
    }

    @Test void revokedMemberReceivesNoGitFrameAndCannotReadTheTask() throws Exception {
        try(var socket=socket(secondMember)) {
            double denied=metrics.counter("devflow.board.realtime.deliveries","outcome","denied").count();
            jdbc.update("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?",workspace,secondMember);
            events.publishEvent(event(2,UUID.randomUUID(),TIME));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline && metrics.counter("devflow.board.realtime.deliveries","outcome","denied").count()==denied) Thread.sleep(10);
            assertThat(metrics.counter("devflow.board.realtime.deliveries","outcome","denied").count()).isGreaterThan(denied);
            assertThat(socket.messages).isEmpty();
            mvc.perform(get("/api/v1/tasks/"+task).header("Authorization",bearer(secondMember))).andExpect(status().isNotFound());
        }
    }

    @Test void fiftyDeliveriesAcrossBoardsFitLeaseAndKeepCrudResponsive() throws Exception {
        var taskIds=new ArrayList<UUID>(); taskIds.add(task);
        for(int i=1;i<50;i++) {
            UUID b=jdbc.queryForObject("INSERT INTO boards(workspace_id,name) VALUES (?,'load fixture') RETURNING id",UUID.class,workspace);
            UUID c=jdbc.queryForObject("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'source',0,'TODO') RETURNING id",UUID.class,b);
            jdbc.update("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'target',1,'IN_PROGRESS')",b);
            taskIds.add(task(c,0));
        }
        for(UUID id:taskIds) jdbc.update("INSERT INTO git_domain_event_outbox(id,repository_id,workspace_id,task_id,event_type,git_object_key,repo,occurred_at) VALUES (?,?,?,?,'git.commit_linked',?,'test/repo',?)",UUID.randomUUID(),REPO,workspace,id,"a".repeat(40),Timestamp.from(TIME));
        var claimed=outbox.claim(); assertThat(claimed).hasSize(50);
        var durations=new ArrayList<Long>(); var crudDurations=new ArrayList<Long>();
        var pool=Executors.newSingleThreadExecutor(); String token=bearer(member);
        long batchStarted=System.nanoTime();
        try {
            var reads=pool.submit(()->{
                for(int i=0;i<50;i++) {
                    long start=System.nanoTime(); mvc.perform(get("/api/v1/tasks/"+task).header("Authorization",token)).andExpect(status().isOk());
                    crudDurations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
                }
                return true;
            });
            for(var row:claimed) { long start=System.nanoTime(); outbox.publish(row); durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)); }
            assertThat(reads.get(20,TimeUnit.SECONDS)).isTrue();
        } finally { pool.shutdownNow(); }
        long batchMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-batchStarted);
        Collections.sort(durations); Collections.sort(crudDurations);
        System.out.printf("T024_LOAD_50 batch_ms=%d transition_p95_ms=%d transition_p99_ms=%d crud_p95_ms=%d crud_p99_ms=%d%n",batchMs,durations.get(47),durations.get(49),crudDurations.get(47),crudDurations.get(49));
        assertThat(batchMs).isLessThan(45000); assertThat(count("board_git_event_receipts")).isEqualTo(50);
        assertThat(count("board_audit_events")).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_domain_event_outbox WHERE status='PUBLISHED'",Integer.class)).isEqualTo(50);
    }

    void asMember(Runnable action) {
        io.devflow.common.security.AuthenticatedActor actor=()->member;
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of()));
        try { action.run(); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    void awaitBlocked(int pid) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))",Integer.class,pid)>0) return;
            try { Thread.sleep(10); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        }
        throw new AssertionError("Expected the second transaction to wait on the Board lock");
    }
    void awaitAnyBlocked() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>0) return;
            try { Thread.sleep(10); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        }
        throw new AssertionError("Expected a transaction waiting on a lock");
    }


    @Test void staleEditorCannotOverwriteTheGitMove() throws Exception {
        events.publishEvent(event(0,UUID.randomUUID(),TIME));
        var body=json.writeValueAsString(Map.of("title","Saved after refetch","priority","MEDIUM"));
        mvc.perform(put("/api/v1/tasks/"+task).header("Authorization",bearer(member))
                .header("If-Match","\"task-"+task+"-v0\"").contentType("application/json").content(body))
                .andExpect(status().isPreconditionFailed());
        assertThat(row().get("title")).isEqualTo("Preserved title");
        mvc.perform(put("/api/v1/tasks/"+task).header("Authorization",bearer(member))
                .header("If-Match","\"task-"+task+"-v1\"").contentType("application/json").content(body))
                .andExpect(status().isOk());
        assertThat(row().get("title")).isEqualTo("Saved after refetch");
        assertThat(row().get("column_id")).isEqualTo(columns.get("IN_PROGRESS"));
    }

    @Test void taskFlushFailureIsAtomicAndRetrySucceeds() {
        var event=event(0,UUID.randomUUID(),TIME); var before=row();
        jdbc.execute("CREATE FUNCTION t024_task_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.column_id<>OLD.column_id THEN RAISE EXCEPTION 'synthetic'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER t024_task_fail BEFORE UPDATE ON tasks FOR EACH ROW EXECUTE FUNCTION t024_task_fail()");
        try {
            assertThatThrownBy(()->events.publishEvent(event)).isInstanceOf(RuntimeException.class);
            assertThat(row()).isEqualTo(before); assertThat(count("board_audit_events")).isZero();
            assertThat(count("board_git_event_receipts")).isZero(); assertThat(probe.statuses).isEmpty();
            verify(broadcaster,never()).broadcast(any());
        } finally { jdbc.execute("DROP TRIGGER t024_task_fail ON tasks"); jdbc.execute("DROP FUNCTION t024_task_fail()"); }
        events.publishEvent(event); assertThat(count("board_git_event_receipts")).isEqualTo(1);
        assertThat(row().get("column_id")).isEqualTo(columns.get("IN_PROGRESS"));
    }

    @Test void aNoopReceiptPreventsReplayAfterManualReopenEvenWhenTimeWouldAllowIt() throws Exception {
        jdbc.update("UPDATE tasks SET column_id=? WHERE id=?",columns.get("DONE"),task);
        var event=event(0,UUID.randomUUID(),Instant.now().plusSeconds(60)); events.publishEvent(event);
        assertThat(jdbc.queryForObject("SELECT outcome FROM board_git_event_receipts",String.class)).isEqualTo("NOOP");
        move(task,columns.get("TODO"),0,member,200); var manual=row();
        events.publishEvent(event); assertThat(row()).isEqualTo(manual);
        assertThat(count("board_audit_events")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"task","workspace","repository","timestamp","objectKey","author","type"})
    void theCompleteFingerprintRejectsConflictingSnapshots(String changed) {
        UUID eventId=UUID.randomUUID(); var initial=(GitCommitLinkedEvent)event(0,eventId,TIME);
        events.publishEvent(initial); UUID otherTask=task(columns.get("TODO"),0);
        var before=row(); var otherBefore=jdbc.queryForMap("SELECT * FROM tasks WHERE id=?",otherTask);
        DevFlowEvent conflict=changed.equals("type")?event(2,eventId,TIME):new GitCommitLinkedEvent(
                changed.equals("task")?otherTask:task,changed.equals("objectKey")?"b".repeat(40):initial.commitSha(),initial.repo(),
                changed.equals("author")?"different-author":null,changed.equals("timestamp")?TIME.plusSeconds(1):TIME,eventId,
                changed.equals("repository")?UUID.randomUUID():REPO,changed.equals("workspace")?UUID.randomUUID():workspace);
        assertThatThrownBy(()->events.publishEvent(conflict)).hasMessageContaining(changed.equals("workspace")?"TENANT_MISMATCH":"EVENT_CONFLICT");
        assertThat(row()).isEqualTo(before); assertThat(jdbc.queryForMap("SELECT * FROM tasks WHERE id=?",otherTask)).isEqualTo(otherBefore);
        assertThat(count("board_git_event_receipts")).isEqualTo(1); assertThat(count("board_audit_events")).isEqualTo(1);
    }

    @Test void multipleTasksRepositoriesAndPullRequestsUseTheFirstEligibleMerge() {
        UUID secondTask=task(columns.get("TODO"),1), otherRepository=UUID.randomUUID();
        for(UUID id:List.of(task,secondTask)) events.publishEvent(new GitPrOpenedEvent(id,"https://github.com/test/repo/pull/1","test/repo",null,TIME,UUID.randomUUID(),REPO,workspace));
        events.publishEvent(event(2,UUID.randomUUID(),TIME));
        events.publishEvent(new GitPrMergedEvent(task,"https://github.com/test/other/pull/2","test/other",null,TIME.plusSeconds(1),UUID.randomUUID(),otherRepository,workspace));
        events.publishEvent(new GitPrMergedEvent(secondTask,"https://github.com/test/repo/pull/3","test/repo",null,TIME.plusSeconds(2),UUID.randomUUID(),REPO,workspace));
        assertThat(jdbc.queryForList("SELECT c.status_category FROM tasks t JOIN columns c ON c.id=t.column_id WHERE t.id IN (?,?)",String.class,task,secondTask)).containsOnly("DONE");
        assertThat(count("board_git_event_receipts")).isEqualTo(5); assertThat(count("board_audit_events")).isEqualTo(4);
        assertThat(probe.statuses).hasSize(4);
    }

    void reject(DevFlowEvent event,String reason) {
        var before=row(); assertThatThrownBy(() -> events.publishEvent(event)).hasMessageContaining(reason);
        assertThat(row()).isEqualTo(before); assertThat(count("board_git_event_receipts")).isZero();
        assertThat(count("board_audit_events")).isZero(); assertThat(probe.statuses).isEmpty();
    }
    String target(int kind) { return List.of("IN_PROGRESS","IN_REVIEW","DONE").get(kind); }
    DevFlowEvent event(int kind,UUID id,Instant time) {
        return switch(kind) {
            case 0 -> new GitCommitLinkedEvent(task,"a".repeat(40),"test/repo",null,time,id,REPO,workspace);
            case 1 -> new GitPrOpenedEvent(task,"https://github.com/test/repo/pull/1","test/repo",null,time,id,REPO,workspace);
            default -> new GitPrMergedEvent(task,"https://github.com/test/repo/pull/1","test/repo",null,time,id,REPO,workspace);
        };
    }
    UUID id(DevFlowEvent e) { return e instanceof GitCommitLinkedEvent c?c.eventId():e instanceof GitPrOpenedEvent o?o.eventId():((GitPrMergedEvent)e).eventId(); }
    UUID column(String category) { return jdbc.queryForObject("INSERT INTO columns(board_id,name,position,status_category) VALUES (?,'Custom name',?,?) RETURNING id",UUID.class,board,jdbc.queryForObject("SELECT count(*) FROM columns WHERE board_id=?",Integer.class,board),category); }
    UUID task(UUID column,int position) { return jdbc.queryForObject("INSERT INTO tasks(column_id,title,description,position) VALUES (?,'Preserved title','Preserved description',?) RETURNING id",UUID.class,column,position); }
    UUID user() { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO users(id,email,password_hash,full_name) VALUES (?,?,'test-hash','fixture')",id,id+"@devflow.test"); return id; }
    Map<String,Object> row() { return jdbc.queryForMap("SELECT * FROM tasks WHERE id=?",task); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    String bearer(UUID id) { return "Bearer "+tokens.generateAccessToken(id,id+"@devflow.test"); }
    void move(UUID id,UUID column,int position,UUID actor,int expected) throws Exception {
        mvc.perform(patch("/api/v1/tasks/"+id+"/move").header("Authorization",bearer(actor)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("columnId",column,"position",position)))).andExpect(status().is(expected));
    }
    int webhook(int kind) throws Exception {
        Map<String,Object> payload;
        if(kind==0) payload=Map.of("repository",Map.of("id",123),"ref","refs/heads/main","after","a".repeat(40),"deleted",false,
                "commits",List.of(Map.of("id","a".repeat(40),"message","T-001","timestamp",TIME.toString())));
        else payload=Map.of("repository",Map.of("id",123),"action",kind==1?"opened":"closed","number",1,
                "pull_request",Map.of("number",1,"title","T-001","head",Map.of("ref","feature/T-001","repo",Map.of("id",987)),
                "base",Map.of("ref","main","repo",Map.of("id",123)),"updated_at",TIME.plusSeconds(kind).toString(),
                "state",kind==1?"open":"closed","merged",kind==2));
        byte[] body=json.writeValueAsBytes(payload);
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/gitci/webhook/github?repositoryId="+REPO))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type","application/json")
                .header("X-Hub-Signature-256","sha256="+HexFormat.of().formatHex(mac.doFinal(body)))
                .header("X-GitHub-Delivery",UUID.randomUUID().toString()).header("X-GitHub-Event",kind==0?"push":"pull_request")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    final class Socket implements AutoCloseable {
        final WebSocketStompClient client;
        final StompSession session;
        final BlockingQueue<Map<?,?>> messages=new LinkedBlockingQueue<>();
        Socket(UUID member) throws Exception {
            client=new WebSocketStompClient(new StandardWebSocketClient());
            client.setMessageConverter(new MappingJackson2MessageConverter());
            var headers=new StompHeaders(); headers.add("Authorization",bearer(member));
            var handshake=new WebSocketHttpHeaders(); handshake.setOrigin("http://localhost:5173");
            session=client.connectAsync(URI.create("ws://localhost:"+port+"/ws"),handshake,headers,new StompSessionHandlerAdapter(){}).get(5,TimeUnit.SECONDS);
            session.subscribe("/topic/boards/"+board,new StompFrameHandler(){
                public java.lang.reflect.Type getPayloadType(StompHeaders h){ return Map.class; }
                public void handleFrame(StompHeaders h,Object data){ messages.add((Map<?,?>)data); }
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline) {
                var user=users.getUser(member.toString());
                if(user!=null && user.getSessions().stream().flatMap(s->s.getSubscriptions().stream())
                        .anyMatch(s->s.getDestination().equals("/topic/boards/"+board))) return;
                Thread.sleep(10);
            }
            close(); throw new AssertionError("Subscription not registered");
        }
        public void close(){ if(session.isConnected())session.disconnect(); client.stop(); }
    }
    Socket socket(UUID id) throws Exception { return new Socket(id); }
}
