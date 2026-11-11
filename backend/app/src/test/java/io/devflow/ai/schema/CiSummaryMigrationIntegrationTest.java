package io.devflow.ai.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the T-031 ai_ci_summaries migration on PostgreSQL 17 (schema, constraints, indexes).
 */
@Testcontainers
@DisplayName("V13 ai_ci_summaries Migration Integration Test")
class CiSummaryMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

    private String schema;

    @BeforeEach
    void migrate() {
        schema = "ai_t031_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
    }

    @Test
    void insertsValidSummaryWithDefaults() {
        UUID id = UUID.randomUUID();
        String summaryContent = "- Root cause\n- Step\n- Fix";
        jdbc.update("INSERT INTO " + schema + ".ai_ci_summaries "
                + "(id, pipeline_id, repo, commit_sha, summary_markdown, source) "
                + "VALUES (?, 'pipe-001', 'devflow/repo', 'commit-sha-1', ?, 'LLM')",
                id, summaryContent);

        String summary = jdbc.queryForObject(
                "SELECT summary_markdown FROM " + schema + ".ai_ci_summaries WHERE pipeline_id = 'pipe-001'",
                String.class);
        assertThat(summary).isEqualTo(summaryContent);
    }

    @Test
    void enforcesUniquePipelineIdConstraint() {
        jdbc.update("INSERT INTO " + schema + ".ai_ci_summaries "
                + "(pipeline_id, repo, commit_sha, summary_markdown, source) "
                + "VALUES ('pipe-dup', 'repo', 'sha', 'summary 1', 'LLM')");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + schema + ".ai_ci_summaries "
                + "(pipeline_id, repo, commit_sha, summary_markdown, source) "
                + "VALUES ('pipe-dup', 'repo', 'sha', 'summary 2', 'FALLBACK')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesCheckConstraintOnSource() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + schema + ".ai_ci_summaries "
                + "(pipeline_id, repo, commit_sha, summary_markdown, source) "
                + "VALUES ('pipe-bad-source', 'repo', 'sha', 'summary', 'INVALID_SOURCE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
