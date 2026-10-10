package io.devflow.board.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.ai.internal.AiEventListeners;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.board.api.CreateTaskRequest;
import io.devflow.board.internal.TaskManagementService;
import io.devflow.common.security.AuthenticatedActor;
import io.devflow.common.event.TaskCreatedEvent;
import io.devflow.common.event.TaskStatusChangedEvent;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import io.devflow.notification.internal.NotificationEventListeners;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the public Board/Column API, Auth membership event, and migrations on PostgreSQL 17. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none",
        "devflow.turnstile.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class BoardColumnApiIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @LocalServerPort
    private int port;

    @MockitoSpyBean
    private AiEventListeners aiEventListeners;

    @MockitoSpyBean
    private NotificationEventListeners notificationEventListeners;

    @Autowired
    private TaskManagementService taskManagementService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID memberId;
    private UUID outsiderId;
    private UUID workspaceId;
    private UUID otherWorkspaceId;

    @BeforeEach
    void createWorkspaceFixtures() {
        clearInvocations(aiEventListeners, notificationEventListeners);
        memberId = UUID.randomUUID();
        outsiderId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        otherWorkspaceId = UUID.randomUUID();
        insertUser(memberId, "member");
        insertUser(outsiderId, "outsider");
        insertWorkspace(workspaceId, memberId, "member-workspace");
        insertWorkspace(otherWorkspaceId, outsiderId, "outsider-workspace");
        insertMember(workspaceId, memberId);
        insertMember(otherWorkspaceId, outsiderId);
    }

    @Test
    void publishesCommittedCreatedEventToAiAndNotificationListeners() throws Exception {
        UUID boardId = createBoard("Task event board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        String correlationId = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(post("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(memberId))
                        .header("X-Correlation-ID", correlationId)
                        .contentType("application/json")
                        .content(json(Map.of("title", "Created for event", "description", "safe description"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID taskId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());

        var aiCaptor = org.mockito.ArgumentCaptor.forClass(TaskCreatedEvent.class);
        var notificationCaptor = org.mockito.ArgumentCaptor.forClass(TaskCreatedEvent.class);
        verify(aiEventListeners).on(aiCaptor.capture());
        verify(notificationEventListeners).on(notificationCaptor.capture());
        TaskCreatedEvent aiEvent = aiCaptor.getValue();
        TaskCreatedEvent notificationEvent = notificationCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(aiEvent.eventId()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(aiEvent.eventId()).isEqualTo(notificationEvent.eventId());
        org.assertj.core.api.Assertions.assertThat(aiEvent.taskId()).isEqualTo(taskId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.boardId()).isEqualTo(boardId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.projectId()).isEqualTo(workspaceId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.columnId()).isEqualTo(columnId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.actorId()).isEqualTo(memberId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.correlationId()).isEqualTo(UUID.fromString(correlationId));
        org.assertj.core.api.Assertions.assertThat(aiEvent.description()).isEqualTo("safe description");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE id = ?", Long.class, taskId)).isEqualTo(1L);
        verify(aiEventListeners, never()).on(org.mockito.ArgumentMatchers.any(TaskStatusChangedEvent.class));
        verify(notificationEventListeners, never()).on(
                org.mockito.ArgumentMatchers.any(TaskStatusChangedEvent.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void broadcastsCommittedBoardMutationToAnAuthenticatedTopicSubscriber() throws Exception {
        UUID boardId = createBoard("Realtime board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = null;
        CompletableFuture<Map<String, Object>> received = new CompletableFuture<>();
        try {
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("Authorization", bearer(memberId));
            WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
            handshakeHeaders.setOrigin("http://localhost:5173");
            session = stompClient.connectAsync(
                            URI.create("ws://localhost:" + port + "/ws"),
                            handshakeHeaders,
                            connectHeaders,
                            new StompSessionHandlerAdapter() {
                                @Override
                                public void handleException(StompSession session, StompCommand command,
                                        StompHeaders headers, byte[] payload, Throwable exception) {
                                    received.completeExceptionally(exception);
                                }
                            })
                    .get(5, TimeUnit.SECONDS);

            session.subscribe("/topic/boards/" + boardId, new StompFrameHandler() {
                @Override
                public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        received.complete((Map<String, Object>) payload);
                    } catch (Exception exception) {
                        received.completeExceptionally(exception);
                    }
                }
            });
            awaitBoardSubscription(memberId, "/topic/boards/" + boardId);

            createTask(columnId, "Created over REST", memberId);

            Map<String, Object> payload = received.get(3, TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThat(payload.get("schemaVersion")).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(payload.get("type")).isEqualTo("CARD_CREATED");
            org.assertj.core.api.Assertions.assertThat(payload.get("boardId")).isEqualTo(boardId.toString());
            Map<?, ?> data = (Map<?, ?>) payload.get("data");
            org.assertj.core.api.Assertions.assertThat(data.get("columnId")).isEqualTo(columnId.toString());
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            stompClient.stop();
        }
    }

    @Test
    void publishesOnlyCrossColumnMovesAndPreservesTheCommittedStatusSnapshot() throws Exception {
        UUID boardId = createBoard("Move event board", memberId);
        UUID sourceColumnId = createColumn(boardId, "To do", "TODO");
        UUID destinationColumnId = createColumn(boardId, "Done", "DONE");
        UUID taskId = createTask(sourceColumnId, "Move me", memberId);
        clearInvocations(aiEventListeners, notificationEventListeners);

        mockMvc.perform(patch("/api/v1/tasks/{taskId}/move", taskId)
                        .header("Authorization", bearer(memberId))
                        .contentType("application/json")
                        .content(json(Map.of("columnId", destinationColumnId, "position", 0))))
                .andExpect(status().isOk());

        var aiCaptor = org.mockito.ArgumentCaptor.forClass(TaskStatusChangedEvent.class);
        var notificationCaptor = org.mockito.ArgumentCaptor.forClass(TaskStatusChangedEvent.class);
        verify(aiEventListeners).on(aiCaptor.capture());
        verify(notificationEventListeners).on(notificationCaptor.capture());
        TaskStatusChangedEvent aiEvent = aiCaptor.getValue();
        TaskStatusChangedEvent notificationEvent = notificationCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(aiEvent.eventId()).isEqualTo(notificationEvent.eventId());
        org.assertj.core.api.Assertions.assertThat(aiEvent.taskId()).isEqualTo(taskId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.boardId()).isEqualTo(boardId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.workspaceId()).isEqualTo(workspaceId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.sourceColumnId()).isEqualTo(sourceColumnId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.destinationColumnId()).isEqualTo(destinationColumnId);
        org.assertj.core.api.Assertions.assertThat(aiEvent.oldStatus()).isEqualTo("To do");
        org.assertj.core.api.Assertions.assertThat(aiEvent.newStatus()).isEqualTo("Done");
        org.assertj.core.api.Assertions.assertThat(aiEvent.oldStatusCategory()).isEqualTo("TODO");
        org.assertj.core.api.Assertions.assertThat(aiEvent.newStatusCategory()).isEqualTo("DONE");
        org.assertj.core.api.Assertions.assertThat(aiEvent.cause())
                .isEqualTo(TaskStatusChangedEvent.StatusChangeCause.MANUAL);
        org.assertj.core.api.Assertions.assertThat(aiEvent.actorId()).isEqualTo(memberId);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT column_id FROM tasks WHERE id = ?", UUID.class, taskId)).isEqualTo(destinationColumnId);
        verify(aiEventListeners, never()).on(org.mockito.ArgumentMatchers.any(TaskCreatedEvent.class));
        verify(notificationEventListeners, never()).on(org.mockito.ArgumentMatchers.any(TaskCreatedEvent.class));
    }

    @Test
    void reorderAndRejectedMoveDoNotPublishTaskLifecycleEvents() throws Exception {
        UUID boardId = createBoard("No event board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        UUID taskId = createTask(columnId, "Stay here", memberId);
        clearInvocations(aiEventListeners, notificationEventListeners);

        mockMvc.perform(patch("/api/v1/tasks/{taskId}/move", taskId)
                        .header("Authorization", bearer(memberId))
                        .contentType("application/json")
                        .content(json(Map.of("columnId", columnId, "position", 0))))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/tasks/{taskId}/move", taskId)
                        .header("Authorization", bearer(memberId))
                        .contentType("application/json")
                        .content(json(Map.of("columnId", columnId, "position", 1))))
                .andExpect(status().isBadRequest());

        verify(aiEventListeners, never()).on(org.mockito.ArgumentMatchers.any(TaskCreatedEvent.class));
        verify(aiEventListeners, never()).on(org.mockito.ArgumentMatchers.any(TaskStatusChangedEvent.class));
        verify(notificationEventListeners, never()).on(org.mockito.ArgumentMatchers.any(TaskCreatedEvent.class));
        verify(notificationEventListeners, never()).on(
                org.mockito.ArgumentMatchers.any(TaskStatusChangedEvent.class));
    }

    @Test
    void outerTransactionRollbackLeavesNoTaskOrTaskEvent() throws Exception {
        UUID boardId = createBoard("Rollback event board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTitle("Rolled back task");
        AuthenticatedActor actor = () -> memberId;
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, "test", List.of()));

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                taskManagementService.createTask(columnId, request);
                status.setRollbackOnly();
            });
        } finally {
            SecurityContextHolder.clearContext();
        }

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE column_id = ?", Long.class, columnId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_audit_events WHERE workspace_id = ? AND resource_type = 'TASK'",
                Long.class, workspaceId)).isZero();
        verify(aiEventListeners, never()).on(org.mockito.ArgumentMatchers.any(TaskCreatedEvent.class));
        verify(notificationEventListeners, never()).on(org.mockito.ArgumentMatchers.any(TaskCreatedEvent.class));
    }

    @AfterEach
    void removeWorkspaceFixtures() {
        if (workspaceId == null) {
            return;
        }
        jdbc.update("DELETE FROM board_audit_events WHERE workspace_id IN (?, ?)", workspaceId, otherWorkspaceId);
        jdbc.update("DELETE FROM boards WHERE workspace_id IN (?, ?)", workspaceId, otherWorkspaceId);
        jdbc.update("DELETE FROM workspaces WHERE id IN (?, ?)", workspaceId, otherWorkspaceId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", memberId, outsiderId);
    }

    @Test
    void createsAndListsBoardsWithinTheAuthorizedWorkspace() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/boards")
                        .header("Authorization", bearer(memberId))
                        .contentType("application/json")
                        .content(json(Map.of(
                                "workspaceId", workspaceId,
                                "name", "  Kế hoạch <b>Q4</b>  ",
                                "description", "<script>alert('x')</script>Roadmap"))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/boards/")))
                .andExpect(jsonPath("$.name").value("Kế hoạch Q4"))
                .andExpect(jsonPath("$.description").value("Roadmap"))
                .andExpect(jsonPath("$.columns").isEmpty())
                .andReturn();
        UUID firstBoard = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());
        UUID createCorrelation = UUID.fromString(created.getResponse().getHeader("X-Correlation-ID"));
        createBoard("Second board", memberId);

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/boards", workspaceId)
                        .header("Authorization", bearer(memberId)).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(patch("/api/v1/boards/{boardId}", firstBoard)
                        .header("Authorization", bearer(memberId))
                        .contentType("application/json").content("{\"archived\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/boards", workspaceId)
                        .header("Authorization", bearer(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/boards", workspaceId)
                        .header("Authorization", bearer(memberId)).param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_audit_events WHERE resource_id = ? AND actor_id = ?",
                Long.class, firstBoard, memberId)).isEqualTo(2L);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_audit_events WHERE resource_id = ? AND correlation_id = ?",
                Long.class, firstBoard, createCorrelation)).isEqualTo(1L);
    }

    @Test
    void createsListsEditsReordersAndSafelyDeletesColumns() throws Exception {
        UUID boardId = createBoard("Board", memberId);
        UUID first = createColumn(boardId, "A", "TODO");
        UUID second = createColumn(boardId, "B", "IN_PROGRESS");
        UUID third = createColumn(boardId, "C", "DONE");

        UUID taskId = jdbc.queryForObject(
                "INSERT INTO tasks(column_id, title, position) VALUES (?, 'Keep task', 0) RETURNING id",
                UUID.class, second);

        mockMvc.perform(get("/api/v1/boards/{boardId}/columns", boardId)
                        .header("Authorization", bearer(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("A"))
                .andExpect(jsonPath("$[2].name").value("C"));

        mockMvc.perform(patch("/api/v1/columns/{columnId}", first)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"statusCategory\":\"IN_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCategory").value("IN_REVIEW"));

        mockMvc.perform(patch("/api/v1/columns/{columnId}/reorder", third)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"position\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(third.toString()))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[2].id").value(second.toString()))
                .andExpect(jsonPath("$[2].position").value(2));

        mockMvc.perform(patch("/api/v1/columns/{columnId}", first)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
        mockMvc.perform(get("/api/v1/columns/{columnId}", second)
                        .header("Authorization", bearer(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCategory").value("IN_PROGRESS"));

        mockMvc.perform(delete("/api/v1/columns/{columnId}", first).header("Authorization", bearer(memberId)))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT position FROM columns WHERE id = ?", Integer.class, second)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/columns/{columnId}", second).header("Authorization", bearer(memberId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLUMN_NOT_EMPTY"));
        mockMvc.perform(patch("/api/v1/columns/{columnId}", second)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"name\":\"Must roll back\",\"statusCategory\":\"DONE\"}"))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT title FROM tasks WHERE id = ?", String.class, taskId)).isEqualTo("Keep task");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT name FROM columns WHERE id = ?", String.class, second)).isEqualTo("B");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status_category FROM columns WHERE id = ?", String.class, second))
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void concealsForeignWorkspaceResourcesAndRejectsUnauthorizedWorkspaceListing() throws Exception {
        UUID boardId = createBoard("Private board", memberId);
        UUID columnId = createColumn(boardId, "Private column", "TODO");

        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId).header("Authorization", bearer(outsiderId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/columns/{columnId}", columnId).header("Authorization", bearer(outsiderId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header("Authorization", bearer(outsiderId)).contentType("application/json")
                        .content("{\"name\":\"stolen\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/boards", workspaceId)
                        .header("Authorization", bearer(outsiderId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void archivesRestoresAndDeletesTheBoardGraphWhileKeepingAuthRecords() throws Exception {
        UUID boardId = createBoard("Lifecycle board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        UUID taskId = jdbc.queryForObject(
                "INSERT INTO tasks(column_id, title, position) VALUES (?, 'Task', 0) RETURNING id",
                UUID.class, columnId);
        UUID commentId = jdbc.queryForObject(
                "INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, 'Comment') RETURNING id",
                UUID.class, taskId, memberId);
        UUID tagId = jdbc.queryForObject(
                "INSERT INTO task_tags(board_id, name, color) VALUES (?, 'backend', '#123456') RETURNING id",
                UUID.class, boardId);
        jdbc.update("INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", taskId, tagId);

        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"archived\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
        mockMvc.perform(post("/api/v1/boards/{boardId}/columns", boardId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("name", "Blocked", "statusCategory", "TODO"))))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"name\":\"Cannot rename while archived\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"archived\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(false));
        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"name\":\"Renamed lifecycle\",\"description\":\"<i>safe</i>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed lifecycle"))
                .andExpect(jsonPath("$.description").value("safe"));

        mockMvc.perform(delete("/api/v1/boards/{boardId}", boardId).header("Authorization", bearer(memberId)))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_comments WHERE id = ?", Long.class, commentId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ?", Long.class, taskId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_audit_events WHERE resource_id = ? AND action = 'DELETE'",
                Long.class, boardId)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspaces WHERE id = ?", Long.class, workspaceId)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Long.class, memberId)).isEqualTo(1L);
    }

    @Test
    void returnsProblemDetailForInvalidInputAndRejectsPatchMassAssignment() throws Exception {
        mockMvc.perform(post("/api/v1/boards").header("Authorization", bearer(memberId))
                        .contentType("application/json")
                        .content(json(Map.of("workspaceId", workspaceId, "name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").exists());

        UUID boardId = createBoard("Safe board", memberId);
        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"workspaceId\":\"00000000-0000-0000-0000-000000000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELDS"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT workspace_id FROM boards WHERE id = ?", UUID.class, boardId)).isEqualTo(workspaceId);
    }

    @Test
    void rejectsInvalidPagePositionAndPathValuesWithoutChangingColumnOrder() throws Exception {
        UUID boardId = createBoard("Bounds board", memberId);
        UUID first = createColumn(boardId, "First", "TODO");
        UUID second = createColumn(boardId, "Second", "DONE");

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/boards", workspaceId)
                        .header("Authorization", bearer(memberId)).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/boards", workspaceId)
                        .header("Authorization", bearer(memberId)).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mockMvc.perform(patch("/api/v1/columns/{columnId}/reorder", second)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"position\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POSITION"));
        mockMvc.perform(get("/api/v1/boards/not-a-uuid").header("Authorization", bearer(memberId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT position FROM columns WHERE id = ?", Integer.class, first)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT position FROM columns WHERE id = ?", Integer.class, second)).isEqualTo(1);
    }

    @Test
    void createsListsAndMovesTasksWithStablePositionsAndMoveAudit() throws Exception {
        UUID boardId = createBoard("Task API board", memberId);
        UUID todo = createColumn(boardId, "To do", "TODO");
        UUID inProgress = createColumn(boardId, "In progress", "IN_PROGRESS");
        UUID first = createTask(todo, "First", memberId);
        UUID second = createTask(todo, "Second", memberId);
        UUID third = createTask(todo, "Third", memberId);
        UUID destinationExisting = createTask(inProgress, "Destination", memberId);

        mockMvc.perform(get("/api/v1/columns/{columnId}/tasks", todo)
                        .header("Authorization", bearer(memberId)).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.items[0].priority").value("MEDIUM"))
                .andExpect(jsonPath("$.items[1].position").value(1));

        mockMvc.perform(patch("/api/v1/tasks/{taskId}/move", third)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("columnId", todo, "position", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value(third.toString()))
                .andExpect(jsonPath("$.task.position").value(0))
                .andExpect(jsonPath("$.sourceColumnId").value(todo.toString()))
                .andExpect(jsonPath("$.destinationColumnId").value(todo.toString()));

        mockMvc.perform(patch("/api/v1/tasks/{taskId}/move", second)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("columnId", inProgress, "position", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.columnId").value(inProgress.toString()))
                .andExpect(jsonPath("$.task.statusCategory").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.task.position").value(1));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT id FROM tasks WHERE column_id = ? ORDER BY position, id", UUID.class, todo))
                .containsExactly(third, first);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "SELECT id FROM tasks WHERE column_id = ? ORDER BY position, id", UUID.class, inProgress))
                .containsExactly(destinationExisting, second);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_audit_events WHERE resource_type = 'TASK' AND action = 'MOVE' "
                        + "AND resource_id = ? AND source_column_id = ? AND destination_column_id = ?",
                Long.class, second, todo, inProgress)).isEqualTo(1L);
    }

    @Test
    void replacesTaskContentAndValidatesAssigneeMembership() throws Exception {
        UUID boardId = createBoard("Task update board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        UUID taskId = createTask(columnId, "Original", memberId);
        String dueDate = "2026-10-06T10:15:30.123456789Z";

        Map<String, Object> replacement = new java.util.LinkedHashMap<>();
        replacement.put("title", "  Revised <b>task</b>  ");
        replacement.put("description", "**markdown** <script>alert(1)</script>");
        replacement.put("priority", "HIGH");
        replacement.put("assigneeId", memberId);
        replacement.put("dueDate", dueDate);
        mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Revised task"))
                .andExpect(jsonPath("$.description").value("**markdown**"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.assigneeId").value(memberId.toString()))
                .andExpect(jsonPath("$.dueDate").value("2026-10-06T10:15:30.123456Z"))
                .andExpect(jsonPath("$.columnId").value(columnId.toString()))
                .andExpect(jsonPath("$.position").value(0));

        Map<String, Object> clearOptional = new java.util.LinkedHashMap<>();
        clearOptional.put("title", "Revised task");
        clearOptional.put("description", null);
        clearOptional.put("priority", "HIGH");
        clearOptional.put("assigneeId", null);
        clearOptional.put("dueDate", null);
        mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(clearOptional)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.assigneeId").doesNotExist())
                .andExpect(jsonPath("$.dueDate").doesNotExist());

        Map<String, Object> invalidAssignee = new java.util.LinkedHashMap<>(clearOptional);
        invalidAssignee.put("assigneeId", outsiderId);
        mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(invalidAssignee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ASSIGNEE"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT assignee_id FROM tasks WHERE id = ?", UUID.class, taskId)).isNull();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_audit_events WHERE resource_id = ? AND resource_type = 'TASK'",
                Long.class, taskId)).isEqualTo(3L);
    }

    @Test
    void deletesTaskGraphAndReindexesWithoutDeletingSharedTags() throws Exception {
        UUID boardId = createBoard("Task delete board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        UUID first = createTask(columnId, "Delete me", memberId);
        UUID survivor = createTask(columnId, "Keep me", memberId);
        UUID commentId = jdbc.queryForObject(
                "INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, 'private comment') RETURNING id",
                UUID.class, first, memberId);
        UUID tagId = jdbc.queryForObject(
                "INSERT INTO task_tags(board_id, name, color) VALUES (?, 'shared', '#123456') RETURNING id",
                UUID.class, boardId);
        jdbc.update("INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", first, tagId);
        jdbc.update("INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", survivor, tagId);

        mockMvc.perform(delete("/api/v1/tasks/{taskId}", first).header("Authorization", bearer(memberId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/tasks/{taskId}", first).header("Authorization", bearer(memberId)))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_comments WHERE id = ?", Long.class, commentId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ?", Long.class, first)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_tags WHERE id = ?", Long.class, tagId)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ? AND tag_id = ?",
                Long.class, survivor, tagId)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT position FROM tasks WHERE id = ?", Integer.class, survivor)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_audit_events WHERE resource_type = 'TASK' AND action = 'DELETE' "
                        + "AND resource_id = ?", Long.class, first)).isEqualTo(1L);
    }

    @Test
    void concealsForeignTaskResourcesAndRejectsInvalidOrArchivedMoves() throws Exception {
        UUID boardId = createBoard("Task access board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        UUID taskId = createTask(columnId, "Private task", memberId);
        UUID otherBoard = createBoard("Other board", memberId);
        UUID otherColumn = createColumn(otherBoard, "Other", "DONE");

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId).header("Authorization", bearer(outsiderId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(outsiderId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/tasks/{taskId}/move", taskId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("columnId", otherColumn, "position", 0))))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/tasks/{taskId}/move", taskId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("columnId", columnId, "position", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POSITION"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT column_id FROM tasks WHERE id = ?", UUID.class, taskId)).isEqualTo(columnId);

        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"archived\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/tasks/{taskId}/move", taskId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("columnId", columnId, "position", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARD_ARCHIVED"));
        mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("title", "Can't edit", "priority", "LOW"))))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", bearer(memberId)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("title", "Blocked"))))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE id = ?", Long.class, taskId)).isEqualTo(1L);
    }

    @Test
    void validatesTaskPayloadDefaultsAndPagination() throws Exception {
        UUID boardId = createBoard("Task validation board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        MvcResult created = mockMvc.perform(post("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("title", "  **ready**  "))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/tasks/")))
                .andExpect(jsonPath("$.title").value("**ready**"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.statusCategory").value("TODO"))
                .andReturn();
        UUID taskId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());

        mockMvc.perform(post("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"title\":\"\",\"priority\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"title\":\"valid\",\"priority\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRIORITY_REQUIRED"));
        mockMvc.perform(get("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(memberId)).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(memberId)).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/tasks/not-a-uuid").header("Authorization", bearer(memberId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content("{\"title\":\"missing priority\"}"))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE column_id = ?", Long.class, columnId)).isEqualTo(1L);
    }

    @Test
    void rejectsOversizedTaskMutationBodyBeforePersistence() throws Exception {
        UUID boardId = createBoard("Oversized task board", memberId);
        UUID columnId = createColumn(boardId, "To do", "TODO");
        String oversizedBody = json(Map.of("title", "Task", "description", "x".repeat(70_000)));

        mockMvc.perform(post("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.correlationId").exists());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE column_id = ?", Long.class, columnId)).isZero();
    }

    private UUID createTask(UUID columnId, String title, UUID actorId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/columns/{columnId}/tasks", columnId)
                        .header("Authorization", bearer(actorId)).contentType("application/json")
                        .content(json(Map.of("title", title))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID createBoard(String name, UUID actorId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/boards")
                        .header("Authorization", bearer(actorId)).contentType("application/json")
                        .content(json(Map.of("workspaceId", workspaceId, "name", name))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID createColumn(UUID boardId, String name, String category) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/boards/{boardId}/columns", boardId)
                        .header("Authorization", bearer(memberId)).contentType("application/json")
                        .content(json(Map.of("name", name, "statusCategory", category))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private String bearer(UUID userId) {
        return "Bearer " + tokenProvider.generateAccessToken(userId, userId + "@devflow.test");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private void awaitBoardSubscription(UUID userId, String destination) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var user = simpUserRegistry.getUser(userId.toString());
            if (user != null && user.getSessions().stream()
                    .flatMap(activeSession -> activeSession.getSubscriptions().stream())
                    .anyMatch(subscription -> destination.equals(subscription.getDestination()))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("STOMP broker did not register the board subscription in time");
    }

    private void insertUser(UUID id, String label) {
        jdbc.update("INSERT INTO users(id, email, password_hash, full_name) VALUES (?, ?, 'test-hash', ?)",
                id, label + "-" + id + "@devflow.test", label);
    }

    private void insertWorkspace(UUID id, UUID ownerId, String slug) {
        jdbc.update("INSERT INTO workspaces(id, name, slug, owner_id) VALUES (?, ?, ?, ?)",
                id, slug, slug + "-" + id, ownerId);
    }

    private void insertMember(UUID parentWorkspaceId, UUID userId) {
        jdbc.update("INSERT INTO workspace_members(workspace_id, user_id, role) VALUES (?, ?, 'OWNER')",
                parentWorkspaceId, userId);
    }
}
