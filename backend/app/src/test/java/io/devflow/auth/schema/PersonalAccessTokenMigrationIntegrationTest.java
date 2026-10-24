package io.devflow.auth.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** Verifies the T-038 personal_access_tokens migration on PostgreSQL. */
@Testcontainers
class PersonalAccessTokenMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

    private String schema;
    private UUID user;

    @BeforeEach
    void migrate() {
        schema = "auth_t038_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".users(id, email, password_hash, full_name) VALUES (?, ?, 'x', 'T-038')",
                user, "t038-" + user + "@example.test");
    }

    @Test
    void prefixAndHashAreUnique() {
        insert("dfp_aaaaaaaaaaaa", "a".repeat(64), "first");

        assertThatThrownBy(() -> insert("dfp_aaaaaaaaaaaa", "b".repeat(64), "same prefix"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("dfp_bbbbbbbbbbbb", "a".repeat(64), "same hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintsGuardNamePrefixAndExpiry() {
        assertThatThrownBy(() -> insert("dfp_cccccccccccc", "c".repeat(64), "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("ghp_cccccccccccc", "c".repeat(64), "foreign prefix"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + schema + ".personal_access_tokens"
                        + "(user_id, name, token_prefix, token_hash, expires_at) "
                        + "VALUES (?, 'past', 'dfp_dddddddddddd', ?, now() - interval '1 day')",
                user, "d".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tokensAreDeletedWithTheirUser() {
        insert("dfp_eeeeeeeeeeee", "e".repeat(64), "cascade");

        jdbc.update("DELETE FROM " + schema + ".users WHERE id = ?", user);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".personal_access_tokens", Long.class))
                .isZero();
    }

    private void insert(String prefix, String hash, String name) {
        jdbc.update("INSERT INTO " + schema + ".personal_access_tokens(user_id, name, token_prefix, token_hash) "
                + "VALUES (?, ?, ?, ?)", user, name, prefix, hash);
    }
}
