package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reports DB-only timings; these are measurements, not an API performance guarantee. */
class GitCiQueryPlanIntegrationTest extends GitCiPostgresSupport {
    @Test
    @DisplayName("PF01-PF06: realistic dataset, query plans, pagination, concurrent reads, rollout and bounded text churn")
    void recordsQueryPlansAndPerformanceEvidence() throws Exception {
        seedDataset();
        var report = new StringBuilder("T-020 PostgreSQL evidence\n");
        report.append("time=").append(java.time.Instant.now()).append('\n');
        report.append("image=").append(POSTGRES.getDockerImageName()).append(" imageId=")
                .append(POSTGRES.getContainerInfo().getImageId()).append('\n');
        report.append("java=").append(System.getProperty("java.version")).append(" os=")
                .append(System.getProperty("os.name")).append(" cores=").append(Runtime.getRuntime().availableProcessors())
                .append(" maxJvmHeapBytes=").append(Runtime.getRuntime().maxMemory()).append('\n');
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            report.append("hostMemoryBytes=").append(os.getTotalMemorySize()).append('\n');
        }
        report.append(jdbc.queryForObject("SELECT version()", String.class)).append('\n');
        report.append("shared_buffers=").append(jdbc.queryForObject("SHOW shared_buffers", String.class))
                .append(" max_connections=").append(jdbc.queryForObject("SHOW max_connections", String.class)).append('\n');
        report.append("dataset: repositories=50 commits=50000 PR=5000 attempts=10000 failures=10000; warm cache; pool=20\n");
        UUID repo = jdbc.queryForObject("SELECT md5('r1')::uuid", UUID.class);
        UUID task = jdbc.queryForObject("SELECT md5('task1')::uuid", UUID.class);
        UUID workspace = UUID.fromString("10000000-0000-0000-0000-000000000001");
        String sha = "0".repeat(39) + "1";
        var queries = List.of(
                new Query("commit-key", "SELECT id FROM git_commits WHERE repository_id=? AND sha=?", List.of(repo, sha)),
                new Query("pr-key", "SELECT id FROM pull_requests WHERE repository_id=? AND pr_number=?", List.of(repo, 1)),
                new Query("run-key", "SELECT id FROM ci_pipelines WHERE repository_id=? AND pipeline_id=? AND run_attempt=1", List.of(repo, "1")),
                new Query("task-commits", "SELECT c.id FROM git_commits c JOIN repositories r ON r.id=c.repository_id WHERE r.workspace_id=? AND c.task_id=? LIMIT 25", List.of(workspace, task)),
                new Query("task-pr", "SELECT p.id FROM pull_requests p JOIN repositories r ON r.id=p.repository_id WHERE r.workspace_id=? AND p.task_id=? LIMIT 25", List.of(workspace, task)),
                new Query("task-failures", "SELECT f.id FROM ci_failures f JOIN ci_pipelines p ON p.id=f.pipeline_id JOIN repositories r ON r.id=p.repository_id WHERE r.workspace_id=? AND f.related_task_id=? LIMIT 25", List.of(workspace, task)),
                new Query("repo-status", "SELECT id FROM ci_pipelines WHERE repository_id=? AND status='FAILED' LIMIT 25", List.of(repo)),
                new Query("repo-commit", "SELECT id FROM ci_pipelines WHERE repository_id=? AND commit_sha=?", List.of(repo, sha)));
        for (Query query : queries) {
            String plan = String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + query.sql(), String.class, query.args().toArray()));
            report.append("\nPLAN ").append(query.name()).append('\n').append(plan).append('\n');
            if (query.name().endsWith("-key")) assertThat(plan).contains("Index");
            assertThat(jdbc.queryForList(query.sql(), query.args().toArray())).isNotEmpty();
        }

        // Every row has the same created_at in this INSERT, exercising the UUID tie breaker.
        var expected = jdbc.queryForList("SELECT id FROM git_commits WHERE repository_id=? ORDER BY created_at,id", UUID.class, repo);
        var actual = new ArrayList<UUID>();
        for (int offset = 0; offset < expected.size(); offset += 37) {
            actual.addAll(jdbc.queryForList("SELECT id FROM git_commits WHERE repository_id=? ORDER BY created_at,id LIMIT 37 OFFSET ?", UUID.class, repo, offset));
        }
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();

        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(schemaUrl(POSTGRES.getJdbcUrl(), schema));
            pool.setUsername(POSTGRES.getUsername()); pool.setPassword(POSTGRES.getPassword());
            pool.setMaximumPoolSize(20); pool.setMinimumIdle(20); pool.setConnectionTimeout(10000);
            var warm = new ArrayList<Connection>();
            for (int i = 0; i < 20; i++) warm.add(pool.getConnection());
            for (var connection : warm) connection.close();
            var executor = Executors.newFixedThreadPool(20);
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<List<Double>>>();
            try {
                for (int worker = 0; worker < 20; worker++) {
                    int workerId = worker;
                    futures.add(executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        var samples = new ArrayList<Double>();
                        for (int n = 0; n < 16; n++) {
                            Query query = queries.get((n + workerId) % queries.size());
                            long begin = System.nanoTime();
                            try (var connection = pool.getConnection(); var statement = connection.prepareStatement(query.sql())) {
                                statement.setQueryTimeout(15);
                                for (int a = 0; a < query.args().size(); a++) statement.setObject(a + 1, query.args().get(a));
                                try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
                            }
                            samples.add((System.nanoTime() - begin) / 1_000_000.0);
                        }
                        return samples;
                    }));
                }
                start.countDown();
                var samples = new ArrayList<Double>();
                for (var future : futures) samples.addAll(future.get(45, TimeUnit.SECONDS));
                Collections.sort(samples);
                double p50 = samples.get(samples.size() / 2), p95 = samples.get((int) Math.ceil(samples.size() * .95) - 1);
                report.append("\nDB_ONLY samples=").append(samples.size()).append(" p50_ms=").append(p50)
                        .append(" p95_ms=").append(p95).append(" candidate_budget_100ms=").append(p95 < 100 ? "PASS" : "FAIL")
                        .append(" (includes pool checkout, excludes pool startup; host-dependent, not API SLA)\n");
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            } finally { executor.shutdownNow(); }
        }

        String activeSchema = schema;
        String cloneSchema = "t020_" + UUID.randomUUID().toString().replace("-", "");
        try {
            schema = cloneSchema;
            migration("5").migrate();
            var clone = jdbc(cloneSchema);
            UUID board = UUID.randomUUID(), column = UUID.randomUUID();
            clone.update("INSERT INTO boards(id,workspace_id,name) VALUES (?,?,'Legacy fixture')", board, workspace);
            clone.update("INSERT INTO columns(id,board_id,name,position,status_category) VALUES (?,?,'Todo',0,'TODO')", column, board);
            clone.update("INSERT INTO tasks(column_id,title,position,version) SELECT ?,'Legacy task '||g,g,7 FROM generate_series(1,5000) g", column);
            long begin = System.nanoTime();
            migration(null).migrate();
            double millis = (System.nanoTime() - begin) / 1_000_000.0;
            assertThat(clone.queryForObject("SELECT count(*) FROM tasks WHERE version=7", Long.class)).isEqualTo(5000);
            report.append("V5_TO_V6 legacyTasks=5000 duration_ms=").append(millis)
                    .append(" candidate_budget_60000ms=").append(millis < 60000 ? "PASS" : "FAIL").append('\n');
        } finally {
            schema = activeSchema;
            if (cloneSchema.matches("t020_[0-9a-f]{32}")) jdbc("public").execute("DROP SCHEMA IF EXISTS " + cloneSchema + " CASCADE");
        }

        for (int i = 0; i < 20; i++) {
            var values = row("git_commits"); values.put("repository_id", repo);
            values.put("sha", "f".repeat(32) + String.format("%08x", i)); values.put("message", "é".repeat(32768));
            UUID id = insert("git_commits", values);
            jdbc.update("DELETE FROM git_commits WHERE id=?", id);
        }
        long otherRepoCommits = jdbc.queryForObject("SELECT count(*) FROM git_commits WHERE repository_id <> ?", Long.class, repo);
        long begin = System.nanoTime();
        jdbc.update("DELETE FROM repositories WHERE id=?", repo);
        report.append("CASCADE repositoryWith1000Commits duration_ms=").append((System.nanoTime() - begin) / 1_000_000.0).append('\n');
        assertThat(count("git_commits")).isEqualTo(otherRepoCommits);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failures f LEFT JOIN ci_pipelines p ON p.id=f.pipeline_id WHERE p.id IS NULL", Long.class)).isZero();
        for (String table : TABLES) report.append("size_bytes ").append(table).append('=').append(jdbc.queryForObject("SELECT pg_total_relation_size(?::regclass)", Long.class, table)).append('\n');
        Path evidence = Path.of("build", "reports", "gitci", "t020-query-evidence.txt");
        Files.createDirectories(evidence.getParent()); Files.writeString(evidence, report.toString());
        System.out.println("T020 performance/query evidence: " + evidence.toAbsolutePath());
    }

    private void seedDataset() {
        jdbc.execute("""
                INSERT INTO repositories(id,workspace_id,provider,remote_repo_id,name,repository_url,webhook_secret_ref)
                SELECT md5('r'||g)::uuid,
                CASE WHEN g<=25 THEN '10000000-0000-0000-0000-000000000001'::uuid
                ELSE '10000000-0000-0000-0000-000000000002'::uuid END,
                'GITHUB',g::text,'Repo '||g,'https://github.com/example/repo-'||g,'config:test'
                FROM generate_series(1,50) g
                """);
        jdbc.execute("""
                INSERT INTO git_commits(id,repository_id,sha,message,task_id,committed_at)
                SELECT md5('c'||g)::uuid,md5('r'||((g-1)%50+1))::uuid,lpad(to_hex(g),40,'0'),
                'Synthetic commit',md5('task'||((g-1)%1000+1))::uuid,CURRENT_TIMESTAMP
                FROM generate_series(1,50000) g
                """);
        jdbc.execute("""
                INSERT INTO pull_requests(repository_id,pr_number,title,source_branch,target_branch,status,task_id,pr_url)
                SELECT md5('r'||((g-1)%50+1))::uuid,(g-1)/50+1,'Synthetic PR','feature/test','develop',
                CASE WHEN g%3=0 THEN 'MERGED' ELSE 'OPEN' END,md5('task'||((g-1)%1000+1))::uuid,
                'https://github.com/example/repo/pull/'||g FROM generate_series(1,5000) g
                """);
        jdbc.execute("""
                INSERT INTO ci_pipelines(id,repository_id,pipeline_id,commit_sha,branch,status)
                SELECT md5('p'||g)::uuid,md5('r'||((g-1)%50+1))::uuid,g::text,lpad(to_hex(g),40,'0'),
                'develop',CASE WHEN g<=5000 THEN 'FAILED' ELSE 'SUCCESS' END FROM generate_series(1,10000) g
                """);
        jdbc.execute("""
                INSERT INTO ci_failures(pipeline_id,failure_key,stage_name,job_name,related_task_id)
                SELECT md5('p'||((g-1)%5000+1))::uuid,'job:'||g,'test','Synthetic job',
                md5('task'||((g-1)%1000+1))::uuid FROM generate_series(1,10000) g
                """);
        for (String table : TABLES) jdbc.execute("ANALYZE " + table);
        assertThat(count("git_commits")).isEqualTo(50000);
        assertThat(count("ci_failures")).isEqualTo(10000);
    }

    private record Query(String name, String sql, List<Object> args) {}
}
