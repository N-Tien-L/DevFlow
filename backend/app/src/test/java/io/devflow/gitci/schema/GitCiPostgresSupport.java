package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Isolated schemas on an ephemeral PostgreSQL container; never uses the local Compose database. */
@Testcontainers
abstract class GitCiPostgresSupport {
    static final List<String> TABLES = List.of("repositories", "git_commits", "pull_requests", "ci_pipelines", "ci_failures");
    static final List<String> MIGRATIONS = List.of("V1__init_schema.sql", "V2__board_schema.sql",
            "V3__board_audit_events.sql", "V4__task_audit_events.sql",
            "V5__task_version_for_optimistic_updates.sql", "V6__gitci_schema.sql");
    static final String SHA = "a".repeat(40);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow_t020").withUsername("devflow").withPassword("test-only");

    String schema;
    JdbcTemplate jdbc;

    boolean migrateBeforeEach() { return true; }

    @BeforeEach
    void prepareSchema() {
        schema = "t020_" + UUID.randomUUID().toString().replace("-", "");
        jdbc = jdbc(schema);
        if (migrateBeforeEach()) migration(null).migrate();
    }

    @AfterEach
    void removeTestSchema() {
        if (schema != null && schema.matches("t020_[0-9a-f]{32}")) {
            jdbc("public").execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /**
     * These tests pin the Git/CI contract at V6. Later modules add migrations after it (e.g. Auth V7),
     * so the classpath migration stops at V6 unless a test asks for an earlier target.
     */
    static final String GITCI_SCHEMA_VERSION = "6";

    Flyway migration(String target) {
        return migrationAt("classpath:db/migration", target == null ? GITCI_SCHEMA_VERSION : target);
    }

    Flyway migrationAt(String location, String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations(location).baselineOnMigrate(false).cleanDisabled(true)
                .lockRetryCount(15);
        if (target != null) config.target(target);
        return config.load();
    }

    JdbcTemplate jdbc(String targetSchema) {
        if (!targetSchema.matches("public|t020_[0-9a-f]{32}")) throw new IllegalArgumentException("Unsafe test schema");
        var ds = new DriverManagerDataSource(schemaUrl(POSTGRES.getJdbcUrl(), targetSchema),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var result = new JdbcTemplate(ds);
        result.setQueryTimeout(15);
        return result;
    }

    static String schemaUrl(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    Connection connection() throws SQLException {
        var result = jdbc.getDataSource().getConnection();
        try (var statement = result.createStatement()) {
            statement.execute("SET statement_timeout = '15s'");
            statement.execute("SET lock_timeout = '10s'");
        }
        return result;
    }

    Map<String, Object> row(String table) {
        var result = new LinkedHashMap<String, Object>();
        switch (table) {
            case "repositories" -> {
                result.put("workspace_id", UUID.randomUUID());
                result.put("provider", "GITHUB");
                result.put("remote_repo_id", UUID.randomUUID().toString());
                result.put("name", "Synthetic repo");
                result.put("repository_url", "https://github.com/example/synthetic");
                result.put("webhook_secret_ref", "config:github-test");
            }
            case "git_commits" -> {
                result.put("repository_id", insert("repositories", row("repositories")));
                result.put("sha", SHA);
                result.put("message", "");
                result.put("committed_at", Timestamp.from(Instant.parse("2026-10-15T01:00:00Z")));
            }
            case "pull_requests" -> {
                result.put("repository_id", insert("repositories", row("repositories")));
                result.put("pr_number", 12);
                result.put("title", "Synthetic PR");
                result.put("source_branch", "feature/T-020-schema");
                result.put("target_branch", "develop");
                result.put("status", "OPEN");
                result.put("pr_url", "https://github.com/example/synthetic/pull/12");
            }
            case "ci_pipelines" -> {
                result.put("repository_id", insert("repositories", row("repositories")));
                result.put("pipeline_id", "100");
                result.put("commit_sha", SHA);
                result.put("branch", "develop");
                result.put("status", "FAILED");
            }
            case "ci_failures" -> {
                result.put("pipeline_id", insert("ci_pipelines", row("ci_pipelines")));
                result.put("failure_key", "job:101");
                result.put("stage_name", "test");
                result.put("job_name", "unit-tests");
            }
            default -> throw new IllegalArgumentException("Unknown test table");
        }
        return result;
    }

    UUID insert(String table, Map<String, Object> values) {
        String sql = insertSql(table, values) + " RETURNING id";
        return jdbc.queryForObject(sql, UUID.class, values.values().toArray());
    }

    static String insertSql(String table, Map<String, Object> values) {
        identifier(table);
        values.keySet().forEach(GitCiPostgresSupport::identifier);
        return "INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")";
    }

    void update(String table, UUID id, String column, Object value) {
        identifier(table);
        identifier(column);
        jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", value, id);
    }

    long count(String table) {
        identifier(table);
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    static void identifier(String value) {
        if (!value.matches("[a-z_]+")) throw new IllegalArgumentException("Unsafe test identifier");
    }

    static void sqlState(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable failure = catchThrowable(action);
        var states = new ArrayList<String>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) states.add(sql.getSQLState());
        }
        assertThat(states).as("SQLSTATE, without exposing bound payloads").contains(expected);
    }

    static void copyMigrations(Path directory, int count) throws IOException {
        for (String name : MIGRATIONS.subList(0, count)) {
            try (var input = GitCiPostgresSupport.class.getResourceAsStream("/db/migration/" + name)) {
                if (input == null) throw new IOException("Missing migration " + name);
                Files.copy(input, directory.resolve(name));
            }
        }
    }
}
