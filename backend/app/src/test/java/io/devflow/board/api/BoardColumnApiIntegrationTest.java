package io.devflow.board.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.security.JwtTokenProvider;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the public Board/Column API, Auth membership event, and migrations on PostgreSQL 17. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none"
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

    private UUID memberId;
    private UUID outsiderId;
    private UUID workspaceId;
    private UUID otherWorkspaceId;

    @BeforeEach
    void createWorkspaceFixtures() {
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
