package io.devflow.auth.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the T-007 workspace_invitations migration on PostgreSQL. */
@Testcontainers
class WorkspaceInvitationMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

    private String schema;
    private UUID owner;
    private UUID workspace;

    @BeforeEach
    void migrate() {
        schema = "auth_t007_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        owner = insertUser("owner");
        workspace = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".workspaces(id, name, slug, owner_id) VALUES (?, 'W', ?, ?)",
                workspace, "w-" + workspace, owner);
    }

    @Test
    void onlyOneOpenInvitationPerWorkspaceAndEmail() {
        UUID first = insertInvitation("dup@example.test", "MEMBER");

        assertThatThrownBy(() -> insertInvitation("dup@example.test", "MEMBER"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE " + schema + ".workspace_invitations SET status = 'REVOKED', revoked_at = now() "
                + "WHERE id = ?", first);
        insertInvitation("dup@example.test", "MEMBER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".workspace_invitations", Long.class))
                .isEqualTo(2);
    }

    @Test
    void tokenHashIsUnique() {
        String hash = "c".repeat(64);
        insertInvitation("a@example.test", "MEMBER", hash);

        assertThatThrownBy(() -> insertInvitation("b@example.test", "MEMBER", hash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintsGuardRoleStatusEmailCaseAndTimestamps() {
        assertThatThrownBy(() -> insertInvitation("owner@example.test", "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvitation("Mixed@Example.test", "MEMBER"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID invitation = insertInvitation("state@example.test", "ADMIN");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + schema + ".workspace_invitations SET status = 'EXPIRED' "
                + "WHERE id = ?", invitation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + schema + ".workspace_invitations SET status = 'ACCEPTED' "
                + "WHERE id = ?", invitation)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invitationsCascadeWithTheirWorkspaceAndSurviveInviterDeletion() {
        UUID inviter = insertUser("inviter");
        UUID invitation = insertInvitation("keep@example.test", "MEMBER");
        jdbc.update("UPDATE " + schema + ".workspace_invitations SET invited_by = ? WHERE id = ?", inviter, invitation);

        jdbc.update("DELETE FROM " + schema + ".users WHERE id = ?", inviter);
        assertThat(jdbc.queryForObject("SELECT invited_by FROM " + schema + ".workspace_invitations WHERE id = ?",
                UUID.class, invitation)).isNull();

        jdbc.update("DELETE FROM " + schema + ".workspaces WHERE id = ?", workspace);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".workspace_invitations", Long.class))
                .isZero();
    }

    private UUID insertUser(String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".users(id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                id, "t007-" + label + "-" + id + "@example.test", "not-a-real-hash", "T-007 " + label);
        return id;
    }

    private UUID insertInvitation(String email, String role) {
        return insertInvitation(email, role, UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private UUID insertInvitation(String email, String role, String tokenHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".workspace_invitations(id, workspace_id, email, role, token_hash, "
                        + "expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, workspace, email, role, tokenHash, Timestamp.from(Instant.now().plusSeconds(3600)));
        return id;
    }
}
