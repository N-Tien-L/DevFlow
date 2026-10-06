package io.devflow.board.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the V1 -> V2 rollout on an isolated PostgreSQL schema. */
@Testcontainers
class KanbanMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    @Test
    void upgradesExistingV1DataToV2ExactlyOnce() {
        String schema = "kanban_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway v1 = migration(schema, MigrationVersion.fromVersion("1"));
        v1.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".users(id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                userId, "t010-" + userId + "@example.test", "not-a-real-password", "T-010 Test User");
        jdbc.update("INSERT INTO " + schema + ".workspaces(id, name, slug, owner_id) VALUES (?, ?, ?, ?)",
                workspaceId, "T-010 Workspace", "t010-" + workspaceId, userId);
        jdbc.update("INSERT INTO " + schema + ".workspace_members(workspace_id, user_id, role) VALUES (?, ?, ?)",
                workspaceId, userId, "OWNER");
        long usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".users", Long.class);
        long workspacesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".workspaces", Long.class);
        long membersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".workspace_members", Long.class);

        Flyway all = migration(schema, null);
        all.migrate();
        all.migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".users", Long.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".workspaces", Long.class))
                .isEqualTo(workspacesBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".workspace_members", Long.class))
                .isEqualTo(membersBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".boards", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE version = '2' AND success", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT checksum FROM " + schema + ".flyway_schema_history WHERE version = '2'", Integer.class))
                .isNotNull();
    }

    @Test
    void validationRejectsAChangedAppliedMigrationChecksum() {
        String schema = newSchema();
        Flyway flyway = migration(schema, null);
        flyway.migrate();
        JdbcTemplate jdbc = jdbc(schema);
        jdbc.update("UPDATE " + schema + ".flyway_schema_history SET checksum = checksum + 1 WHERE version = '2'");

        assertThatThrownBy(flyway::validate).isInstanceOf(FlywayException.class);
    }

    @Test
    void failedV2MigrationRollsBackItsDdlAndKeepsV1(@TempDir Path location) throws Exception {
        String schema = newSchema();
        copyProductionMigration(location, "V1__init_schema.sql");
        Files.writeString(location.resolve("V2__board_schema.sql"), """
                CREATE TABLE t010_should_rollback (id INTEGER PRIMARY KEY);
                SELECT 1 / 0;
                """);
        migration(schema, MigrationVersion.fromVersion("1")).migrate();
        Flyway flyway = migrationAt(schema, "filesystem:" + location, null);

        FlywayException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                flyway::migrate, FlywayException.class);

        assertThat(failure).isNotNull();
        assertThat(jdbc(schema).queryForObject("SELECT to_regclass(?)", String.class,
                schema + ".t010_should_rollback")).isNull();
        assertThat(jdbc(schema).queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE version = '1' AND success",
                Long.class)).isEqualTo(1);
        assertThat(jdbc(schema).queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE version = '2' AND success",
                Long.class)).isZero();
    }

    @Test
    void conflictingPreexistingBoardTableFailsWithoutSkippingTheCollision() {
        String schema = newSchema();
        migration(schema, MigrationVersion.fromVersion("1")).migrate();
        jdbc(schema).execute("CREATE TABLE " + schema + ".boards (legacy_id INTEGER)");

        assertThatThrownBy(migration(schema, null)::migrate).isInstanceOf(FlywayException.class);
        assertThat(jdbc(schema).queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE version = '2' AND success",
                Long.class)).isZero();
        assertThat(jdbc(schema).queryForObject("SELECT COUNT(*) FROM " + schema + ".boards", Long.class)).isZero();
    }

    @Test
    void nonemptySchemaWithoutFlywayHistoryIsRejectedWhenAutoBaselineIsDisabled() {
        String schema = newSchema();
        JdbcTemplate jdbc = jdbc(schema);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE " + schema + ".untracked_data (id INTEGER)");
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .baselineOnMigrate(false)
                .load();

        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Long.class, schema, "untracked_data")).isEqualTo(1);
    }

    @Test
    void duplicateVersionedMigrationResourcesAreRejected(@TempDir Path location) throws Exception {
        String schema = newSchema();
        copyProductionMigration(location, "V1__init_schema.sql");
        copyMigration(location, "V1__init_schema.sql", "V1__duplicate.sql");
        Flyway flyway = migrationAt(schema, "filesystem:" + location, null);

        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
        assertThat(jdbc(schema).queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Long.class, schema, "users")).isZero();
    }

    private Flyway migration(String schema, MigrationVersion target) {
        return migrationAt(schema, "classpath:db/migration", target);
    }

    private Flyway migrationAt(String schema, String location, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location)
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private String newSchema() {
        return "kanban_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void copyProductionMigration(Path target, String name) throws IOException {
        copyMigration(target, name, name);
    }

    private void copyMigration(Path target, String sourceName, String targetName) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("db/migration/" + sourceName)) {
            if (input == null) {
                throw new IllegalStateException("Production migration not found: " + sourceName);
            }
            Files.copy(input, target.resolve(targetName));
        }
    }
}
