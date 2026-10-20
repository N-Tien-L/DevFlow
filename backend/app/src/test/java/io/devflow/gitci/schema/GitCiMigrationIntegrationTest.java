package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GitCiMigrationIntegrationTest extends GitCiPostgresSupport {
    @Override boolean migrateBeforeEach() { return false; }

    @Test
    @DisplayName("MG01/MG04/MG05: fresh migration, restart no-op, immutable history")
    void migratesFreshDatabaseExactlyOnceAndValidatesChecksums() {
        assertThat(migration(null).migrate().migrationsExecuted).isEqualTo(6);
        var before = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank");
        assertThat(before).hasSize(6).allSatisfy(r -> assertThat(r.get("checksum")).isNotNull());
        for (String table : TABLES) assertThat(count(table)).isZero();
        UUID repo = insert("repositories", row("repositories"));
        assertThat(migration(null).migrate().migrationsExecuted).isZero();
        migration(null).validate();
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT id FROM repositories", UUID.class)).isEqualTo(repo);
    }

    @ParameterizedTest(name = "MG02/MG03: preserve all legacy values from V{0}")
    @ValueSource(strings = {"2", "5"})
    void upgradesLegacyDataWithoutChangingIt(String version) {
        migration(version).migrate();
        seedLegacy(version.equals("5"));
        var before = legacySnapshot(version.equals("5"));
        var history = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank");
        migration(null).migrate();
        assertThat(legacySnapshot(version.equals("5"))).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank"))
                .containsAll(history);
        assertThat(jdbc.queryForObject("SELECT version FROM tasks", Long.class)).isEqualTo(version.equals("5") ? 7L : 0L);
        migration(null).validate();
    }

    @Test
    @DisplayName("MG06: duplicate migration version is rejected")
    void rejectsDuplicateVersion(@TempDir Path directory) throws Exception {
        copyMigrations(directory, 6);
        Files.copy(directory.resolve(MIGRATIONS.get(5)), directory.resolve("V6__duplicate.sql"));
        assertThatThrownBy(() -> migrationAt("filesystem:" + directory, null).migrate()).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = ?", Long.class, schema))
                .isZero();
    }

    @Test
    @DisplayName("MG07: applied checksum drift is rejected")
    void rejectsChecksumDrift() {
        migration(null).migrate();
        jdbc.update("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '6'");
        assertThatThrownBy(() -> migration(null).validate()).isInstanceOf(FlywayException.class);
    }

    @Test
    @DisplayName("MG08: preexisting schema collision fails without changing old data")
    void rejectsTableCollision() {
        migration("5").migrate();
        seedLegacy(true);
        var before = legacySnapshot(true);
        jdbc.execute("CREATE TABLE repositories (legacy_value INTEGER)");
        jdbc.update("INSERT INTO repositories VALUES (42)");
        assertThatThrownBy(() -> migration(null).migrate()).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT legacy_value FROM repositories", Integer.class)).isEqualTo(42);
        assertThat(legacySnapshot(true)).isEqualTo(before);
        assertNoV6Success();
    }

    @Test
    @DisplayName("MG09/MG10: failed DDL rolls back and corrected isolated copy can migrate")
    void rollsBackFailedMigrationAndRetriesCorrectedCopy(@TempDir Path directory) throws Exception {
        migration("5").migrate();
        seedLegacy(true);
        var before = legacySnapshot(true);
        copyMigrations(directory, 6);
        Path v6 = directory.resolve(MIGRATIONS.get(5));
        String original = Files.readString(v6);
        Files.writeString(v6, original + "\nSELECT 1 / 0;\n");
        assertThatThrownBy(() -> migrationAt("filesystem:" + directory, null).migrate()).isInstanceOf(FlywayException.class);
        for (String table : TABLES) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?)", String.class, schema + "." + table)).isNull();
        }
        assertThat(legacySnapshot(true)).isEqualTo(before);
        assertNoV6Success();
        Files.writeString(v6, original);
        assertThat(migrationAt("filesystem:" + directory, null).migrate().migrationsExecuted).isEqualTo(1);
        migration(null).validate();
    }

    @Test
    @DisplayName("MG11: untracked nonempty schema fails when auto-baseline is disabled")
    void rejectsUntrackedSchema() {
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE untracked_data (value INTEGER)");
        jdbc.update("INSERT INTO untracked_data VALUES (42)");
        assertThatThrownBy(() -> migration(null).migrate()).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT value FROM untracked_data", Integer.class)).isEqualTo(42);
    }

    @Test
    @DisplayName("MG12: simultaneous migrators apply each version once")
    void serializesConcurrentMigrators() throws Exception {
        jdbc.execute("CREATE SCHEMA " + schema);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return migration(null).migrate(); });
            var b = executor.submit(() -> { start.await(); return migration(null).migrate(); });
            start.countDown();
            assertThat(a.get(45, TimeUnit.SECONDS).migrationsExecuted + b.get(45, TimeUnit.SECONDS).migrationsExecuted).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Long.class)).isEqualTo(6);
            migration(null).validate();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("MG13: insufficient migration privileges do not report success")
    void rejectsMigrationWithoutCreatePrivileges() {
        migration("5").migrate();
        String role = "t020role" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'test-only'");
        try {
            jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
            var restricted = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), role, "test-only")
                    .schemas(schema).defaultSchema(schema).baselineOnMigrate(false).load();
            assertThatThrownBy(restricted::migrate).isInstanceOf(FlywayException.class);
            assertNoV6Success();
        } finally {
            jdbc.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + schema + " FROM " + role);
            jdbc.execute("REVOKE ALL ON SCHEMA " + schema + " FROM " + role);
            jdbc.execute("DROP ROLE " + role);
        }
    }

    @Test
    @DisplayName("MG14: restore V5 backup into an isolated clone and upgrade the clone")
    void restoresAndUpgradesBackupClone() throws Exception {
        migration("5").migrate();
        seedLegacy(true);
        var before = legacySnapshot(true);
        String clone = "t020_clone_" + UUID.randomUUID().toString().replace("-", "");
        String dump = "/tmp/" + clone + ".dump";
        assertThat(POSTGRES.execInContainer("pg_dump", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "--schema=" + schema, "-Fc", "-f", dump).getExitCode()).isZero();
        jdbc("public").execute("CREATE DATABASE " + clone);
        try {
            assertThat(POSTGRES.execInContainer("pg_restore", "-U", POSTGRES.getUsername(), "-d", clone,
                    "--exit-on-error", "--no-owner", dump).getExitCode()).isZero();
            String cloneUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + clone);
            var cloneJdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl(cloneUrl, schema),
                    POSTGRES.getUsername(), POSTGRES.getPassword()));
            assertThat(legacySnapshot(cloneJdbc, true)).isEqualTo(before);
            Flyway.configure().dataSource(cloneUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).baselineOnMigrate(false)
                    .target(GITCI_SCHEMA_VERSION).load().migrate();
            assertThat(cloneJdbc.queryForObject("SELECT version FROM tasks", Long.class)).isEqualTo(7);
            assertThat(cloneJdbc.queryForObject("SELECT count(*) FROM repositories", Long.class)).isZero();
            assertThat(legacySnapshot(cloneJdbc, true)).isEqualTo(before);
            assertThat(legacySnapshot(true)).isEqualTo(before);
            assertNoV6Success();
        } finally {
            jdbc("public").execute("DROP DATABASE " + clone + " WITH (FORCE)");
        }
    }

    private void assertNoV6Success() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success", Long.class))
                .isZero();
    }

    private void seedLegacy(boolean withAudit) {
        UUID user = UUID.randomUUID(), workspace = UUID.randomUUID(), board = UUID.randomUUID();
        UUID column = UUID.randomUUID(), task = UUID.randomUUID(), tag = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name) VALUES (?,?,?,?)",
                user, "synthetic@example.test", "not-a-real-password", "Synthetic User");
        jdbc.update("INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,?,?,?)", workspace, "Test", "t020", user);
        jdbc.update("INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,?)", workspace, user, "OWNER");
        jdbc.update("INSERT INTO boards(id,workspace_id,name) VALUES (?,?,?)", board, workspace, "Board giữ nguyên");
        jdbc.update("INSERT INTO columns(id,board_id,name,position,status_category) VALUES (?,?,?,?,?)", column, board, "Todo", 0, "TODO");
        jdbc.update("INSERT INTO tasks(id,column_id,title,position,assignee_id) VALUES (?,?,?,?,?)", task, column, "Task tiếng Việt", 0, user);
        jdbc.update("INSERT INTO task_comments(task_id,author_id,content) VALUES (?,?,?)", task, user, "Comment");
        jdbc.update("INSERT INTO task_tags(id,board_id,name,color) VALUES (?,?,?,?)", tag, board, "Label", "blue");
        jdbc.update("INSERT INTO task_tag_mappings(task_id,tag_id) VALUES (?,?)", task, tag);
        if (withAudit) {
            jdbc.update("UPDATE tasks SET version = 7 WHERE id = ?", task);
            jdbc.update("""
                    INSERT INTO board_audit_events(actor_id,action,resource_type,resource_id,workspace_id,
                    correlation_id,changed_fields,source_column_id,destination_column_id)
                    VALUES (?,'MOVE','TASK',?,?,?,'position',?,?)
                    """, user, task, workspace, UUID.randomUUID(), column, column);
        }
    }

    private Map<String, List<String>> legacySnapshot(boolean withAudit) {
        return legacySnapshot(jdbc, withAudit);
    }

    private Map<String, List<String>> legacySnapshot(JdbcTemplate source, boolean withAudit) {
        var result = new LinkedHashMap<String, List<String>>();
        var tables = new java.util.ArrayList<>(List.of("users", "workspaces", "workspace_members", "boards", "columns",
                "tasks", "task_comments", "task_tags", "task_tag_mappings"));
        if (withAudit) tables.add("board_audit_events");
        for (String table : tables) {
            result.put(table, source.queryForList("SELECT (to_jsonb(t) - 'version')::text FROM " + table
                    + " t ORDER BY (to_jsonb(t) - 'version')::text", String.class));
        }
        return result;
    }
}
