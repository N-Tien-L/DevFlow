package io.devflow.auth.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the T-004A V7 migration (user status + verification_tokens) on PostgreSQL. */
@Testcontainers
class AuthEmailVerificationMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

    @Test
    void existingUsersAreBackfilledActiveAndNewUsersStartPending() {
        String schema = newSchema();
        migration(schema, MigrationVersion.fromVersion("2")).migrate();
        UUID legacyUser = insertUser(schema, "legacy");

        migration(schema, null).migrate();
        UUID newUser = insertUser(schema, "new");

        assertThat(status(schema, legacyUser)).isEqualTo("ACTIVE");
        assertThat(status(schema, newUser)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE version = '7' AND success",
                Long.class)).isEqualTo(1);
    }

    @Test
    void unknownStatusIsRejectedByCheckConstraint() {
        String schema = migratedSchema();
        UUID user = insertUser(schema, "check");

        assertThatThrownBy(() -> jdbc.update("UPDATE " + schema + ".users SET status = 'BANNED' WHERE id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tokenHashIsUniqueAndTokensCascadeWithTheirUser() {
        String schema = migratedSchema();
        UUID user = insertUser(schema, "cascade");
        String hash = "a".repeat(64);
        insertToken(schema, user, hash);

        assertThatThrownBy(() -> insertToken(schema, user, hash))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM " + schema + ".users WHERE id = ?", user);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".verification_tokens", Long.class))
                .isZero();
    }

    @Test
    void tokenTypeIsRestrictedByCheckConstraint() {
        String schema = migratedSchema();
        UUID user = insertUser(schema, "type");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO " + schema + ".verification_tokens(user_id, token_hash, type, expires_at) "
                        + "VALUES (?, ?, 'PASSWORD_RESET', ?)",
                user, "b".repeat(64), Timestamp.from(Instant.now().plusSeconds(3600))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser(String schema, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".users(id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                id, "t004a-" + label + "-" + id + "@example.test", "not-a-real-hash", "T-004A " + label);
        return id;
    }

    private void insertToken(String schema, UUID userId, String hash) {
        jdbc.update("INSERT INTO " + schema + ".verification_tokens(user_id, token_hash, type, expires_at) "
                        + "VALUES (?, ?, 'EMAIL_VERIFICATION', ?)",
                userId, hash, Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    private String status(String schema, UUID userId) {
        return jdbc.queryForObject("SELECT status FROM " + schema + ".users WHERE id = ?", String.class, userId);
    }

    private String migratedSchema() {
        String schema = newSchema();
        migration(schema, null).migrate();
        return schema;
    }

    private Flyway migration(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private String newSchema() {
        return "auth_t004a_" + UUID.randomUUID().toString().replace("-", "");
    }
}
