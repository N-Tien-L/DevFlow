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
 * T-032 through the real application wiring: the Auth module's JWT filter chain and its workspace
 * membership listener guard POST /api/v1/ai/task-breakdown (deterministic fallback model, no key).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Task breakdown endpoint: authentication and workspace membership (full app)")
class TaskBreakdownSecurityIntegrationTest {

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
    void createWorkspace() {
        memberId = UUID.randomUUID();
        outsiderId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        insertUser(memberId, "member");
        insertUser(outsiderId, "outsider");
        jdbc.update("INSERT INTO workspaces(id, name, slug, owner_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                workspaceId, "AI workspace", "ai-workspace-" + workspaceId, memberId);
        jdbc.update("INSERT INTO workspace_members(id, workspace_id, user_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'OWNER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), workspaceId, memberId);
    }

    @Test
    void rejectsAnonymousCallers() throws Exception {
        request(null).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsProposalToWorkspaceMember() throws Exception {
        request(memberId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtasks.length()").value(3))
                .andExpect(jsonPath("$.subtasks[0].checklist").isArray());
    }

    @Test
    void deniesUsersOutsideTheWorkspace() throws Exception {
        request(outsiderId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    private ResultActions request(UUID userId) throws Exception {
        var builder = post("/api/v1/ai/task-breakdown").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("workspaceId", workspaceId,
                        "description", "Let users export the board as CSV.")));
        if (userId != null) {
            builder.header(HttpHeaders.AUTHORIZATION,
                    "Bearer " + tokenProvider.generateAccessToken(userId, userId + "@devflow.test"));
        }
        return mockMvc.perform(builder);
    }

    private void insertUser(UUID id, String label) {
        // H2 schema comes from the JPA entities (test profile), so audit columns have no defaults.
        jdbc.update("INSERT INTO users(id, email, password_hash, full_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'test-hash', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, label + "-" + id + "@devflow.test", label);
    }
}
