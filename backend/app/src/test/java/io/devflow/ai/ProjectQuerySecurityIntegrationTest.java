package io.devflow.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.internal.security.JwtTokenProvider;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T-033 through the real application wiring: the Auth module's JWT filter chain and membership listener,
 * Board's {@code BoardApi} and Auth's {@code AuthApi} behind POST /api/v1/ai/project-query (deterministic
 * fallback model, no key).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Project Q&A endpoint: authentication, membership and board data (full app)")
class ProjectQuerySecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID memberId;
    private UUID outsiderId;
    private UUID workspaceId;

    @BeforeEach
    void createProject() {
        memberId = UUID.randomUUID();
        outsiderId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        insertUser(memberId, "Minh Pham");
        insertUser(outsiderId, "Outsider");
        jdbc.update("INSERT INTO workspaces(id, name, slug, owner_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                workspaceId, "Q&A workspace", "qa-workspace-" + workspaceId, memberId);
        jdbc.update("INSERT INTO workspace_members(id, workspace_id, user_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'OWNER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), workspaceId, memberId);

        UUID boardId = UUID.randomUUID();
        jdbc.update("INSERT INTO boards(id, workspace_id, name, is_archived, created_at, updated_at) "
                + "VALUES (?, ?, 'Sprint board', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", boardId, workspaceId);
        UUID todo = insertColumn(boardId, "To do", 0, "TODO");
        UUID doing = insertColumn(boardId, "Doing", 1, "IN_PROGRESS");
        UUID done = insertColumn(boardId, "Done", 2, "DONE");
        insertTask(todo, "Write release notes", 0, null);
        insertTask(doing, "Build login form", 0, memberId);
        insertTask(done, "Set up repository", 0, memberId);
    }

    @Test
    void rejectsAnonymousCallers() throws Exception {
        ask(null, "How many tasks are left?").andExpect(status().isUnauthorized());
    }

    @Test
    void answersFromTheProjectsBoardData() throws Exception {
        ask(memberId, "How many tasks are left?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer")
                        .value("2 of 3 tasks are not done yet (to do: 1, in progress: 1, in review: 0; done: 1)."))
                .andExpect(jsonPath("$.totalTasks").value(3));
        ask(memberId, "Who is working on Build login form?")
                .andExpect(jsonPath("$.answer")
                        .value("\"Build login form\" is assigned to Minh Pham (status: in progress)."));
    }

    @Test
    void deniesUsersOutsideTheWorkspace() throws Exception {
        ask(outsiderId, "How many tasks are left?")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    private ResultActions ask(UUID userId, String question) throws Exception {
        var builder = post("/api/v1/ai/project-query").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("projectId", workspaceId, "question", question)));
        if (userId != null) {
            builder.header(HttpHeaders.AUTHORIZATION,
                    "Bearer " + tokenProvider.generateAccessToken(userId, userId + "@devflow.test"));
        }
        return mockMvc.perform(builder);
    }

    private void insertUser(UUID id, String fullName) {
        // H2 schema comes from the JPA entities (test profile), so audit columns have no defaults.
        jdbc.update("INSERT INTO users(id, email, password_hash, full_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'test-hash', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, "user-" + id + "@devflow.test", fullName);
    }

    private UUID insertColumn(UUID boardId, String name, int position, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO columns(id, board_id, name, position, status_category, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, boardId, name, position, category);
        return id;
    }

    private void insertTask(UUID columnId, String title, int position, UUID assigneeId) {
        jdbc.update("INSERT INTO tasks(id, column_id, title, position, priority, assignee_id, version, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, 'MEDIUM', ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), columnId, title, position, assigneeId);
    }
}
