package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitCiConcurrencyIntegrationTest extends GitCiPostgresSupport {
    @ParameterizedTest(name = "CC01: concurrent duplicate {0}")
    @ValueSource(strings = {"repositories", "git_commits", "pull_requests", "ci_pipelines", "ci_failures"})
    void rejectsConcurrentDuplicateBusinessKeys(String table) throws Exception {
        var values = row(table);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        long before = count(table);
        try {
            var a = executor.submit(() -> insertAfterSignal(table, values, start));
            var b = executor.submit(() -> insertAfterSignal(table, values, start));
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "23505");
            assertThat(count(table)).isEqualTo(before + 1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    @DisplayName("CC02: independent inserts in the same repository both commit")
    void permitsIndependentKeys() throws Exception {
        var first = row("git_commits");
        var second = new LinkedHashMap<>(first); second.put("sha", "b".repeat(40));
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> insertAfterSignal("git_commits", first, start));
            var b = executor.submit(() -> insertAfterSignal("git_commits", second, start));
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsOnly("OK");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM git_commits WHERE repository_id = ?", Long.class, first.get("repository_id"))).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    @Test
    @DisplayName("CC03/CC04: failed graph rolls back, savepoint permits recovery")
    void preservesTransactionAtomicityAndSavepoints() throws Exception {
        var run = row("ci_pipelines");
        long beforeRuns = count("ci_pipelines"), beforeFailures = count("ci_failures");
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            UUID pipeline = insert(connection, "ci_pipelines", run);
            var failure = new LinkedHashMap<String, Object>();
            failure.put("pipeline_id", pipeline); failure.put("failure_key", "job:1");
            failure.put("stage_name", "test"); failure.put("job_name", "tests");
            insert(connection, "ci_failures", failure);
            failure.put("failure_key", "");
            sqlState("23514", () -> insert(connection, "ci_failures", failure));
            connection.rollback();
        }
        assertThat(count("ci_pipelines")).isEqualTo(beforeRuns);
        assertThat(count("ci_failures")).isEqualTo(beforeFailures);
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            var savepoint = connection.setSavepoint();
            var invalid = new LinkedHashMap<>(run); invalid.put("run_attempt", 0);
            sqlState("23514", () -> insert(connection, "ci_pipelines", invalid));
            connection.rollback(savepoint);
            insert(connection, "ci_pipelines", run);
            connection.commit();
        }
        assertThat(count("ci_pipelines")).isEqualTo(beforeRuns + 1);
    }

    @ParameterizedTest(name = "CC05: delete/insert race, deleteFirst={0}")
    @ValueSource(booleans = {true, false})
    void neverLeavesOrphansWhenDeleteRacesInsert(boolean deleteFirst) throws Exception {
        var values = row("git_commits");
        UUID repo = (UUID) values.get("repository_id");
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            if (deleteFirst) {
                try (var statement = connection.prepareStatement("DELETE FROM repositories WHERE id = ?")) {
                    statement.setObject(1, repo); statement.executeUpdate();
                }
                var result = executor.submit(() -> insertAfterSignal("git_commits", values, new CountDownLatch(0)));
                connection.commit();
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo("23503");
            } else {
                insert(connection, "git_commits", values);
                var result = executor.submit(() -> jdbc.update("DELETE FROM repositories WHERE id = ?", repo));
                connection.commit();
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM git_commits WHERE repository_id = ?", Long.class, repo)).isZero();
        } finally { executor.shutdownNow(); }
    }

    @Test
    @DisplayName("CC06: tenant-scoped sample queries keep repeated remote IDs/SHA/PR numbers separate")
    void confinesSampleQueriesToTheirWorkspace() {
        UUID firstWorkspace = UUID.randomUUID(), secondWorkspace = UUID.randomUUID(), task = UUID.randomUUID();
        var expected = new ArrayList<UUID>();
        for (UUID workspace : List.of(firstWorkspace, secondWorkspace)) {
            var repo = row("repositories"); repo.put("workspace_id", workspace); repo.put("remote_repo_id", "same-remote");
            UUID repoId = insert("repositories", repo);
            var commit = row("git_commits"); commit.put("repository_id", repoId); commit.put("task_id", task);
            expected.add(insert("git_commits", commit));
            var pr = row("pull_requests"); pr.put("repository_id", repoId); pr.put("task_id", task); insert("pull_requests", pr);
            var run = row("ci_pipelines"); run.put("repository_id", repoId); UUID runId = insert("ci_pipelines", run);
            var failure = row("ci_failures"); failure.put("pipeline_id", runId); failure.put("related_task_id", task); insert("ci_failures", failure);
        }
        assertThat(jdbc.queryForList("SELECT c.id FROM git_commits c JOIN repositories r ON r.id=c.repository_id WHERE r.workspace_id=? AND c.task_id=?",
                UUID.class, firstWorkspace, task)).containsExactly(expected.get(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pull_requests p JOIN repositories r ON r.id=p.repository_id WHERE r.workspace_id=? AND p.task_id=?",
                Long.class, secondWorkspace, task)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ci_failures f JOIN ci_pipelines p ON p.id=f.pipeline_id
                JOIN repositories r ON r.id=p.repository_id WHERE r.workspace_id=? AND f.related_task_id=?
                """, Long.class, firstWorkspace, task)).isEqualTo(1);
    }

    private String insertAfterSignal(String table, Map<String, Object> values, CountDownLatch start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try (var connection = connection()) {
            insert(connection, table, values);
            return "OK";
        } catch (SQLException failure) { return failure.getSQLState(); }
    }

    private static UUID insert(Connection connection, String table, Map<String, Object> values) throws SQLException {
        try (var statement = connection.prepareStatement(insertSql(table, values) + " RETURNING id")) {
            int index = 1;
            for (Object value : values.values()) statement.setObject(index++, value);
            try (var result = statement.executeQuery()) { result.next(); return result.getObject(1, UUID.class); }
        }
    }
}
