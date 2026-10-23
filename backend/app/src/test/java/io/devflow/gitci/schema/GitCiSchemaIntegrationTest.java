package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitCiSchemaIntegrationTest extends GitCiPostgresSupport {
    @Test
    @DisplayName("SC01/SC03/SC04/SC05/SC06: metadata, internal-only cascades and scoped business keys")
    void matchesSchemaContract() {
        var columns = Map.of(
                "repositories", "id workspace_id provider remote_repo_id name repository_url webhook_secret_ref created_at updated_at",
                "git_commits", "id repository_id sha message author_name author_email task_id committed_at created_at updated_at",
                "pull_requests", "id repository_id pr_number title source_branch target_branch status task_id pr_url provider_updated_at created_at updated_at",
                "ci_pipelines", "id repository_id pipeline_id run_attempt commit_sha branch status provider_status provider_conclusion started_at finished_at provider_updated_at created_at updated_at",
                "ci_failures", "id pipeline_id failure_key stage_name job_name error_summary raw_log_snippet raw_log_reference ai_summary related_task_id created_at updated_at");
        columns.forEach((table, expected) -> assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position
                """, String.class, schema, table)).containsExactly(expected.split(" ")));
        var varcharLengths = Map.of(
                "repositories", Map.of("provider", 50, "remote_repo_id", 100, "name", 255, "repository_url", 2048, "webhook_secret_ref", 255),
                "git_commits", Map.of("sha", 64, "author_name", 255, "author_email", 255),
                "pull_requests", Map.of("title", 255, "source_branch", 255, "target_branch", 255, "status", 50, "pr_url", 512),
                "ci_pipelines", Map.of("pipeline_id", 100, "commit_sha", 64, "branch", 255, "status", 50, "provider_status", 50, "provider_conclusion", 50),
                "ci_failures", Map.of("failure_key", 200, "stage_name", 100, "job_name", 100, "raw_log_reference", 2048));
        var nullable = Map.of("repositories", List.<String>of(),
                "git_commits", List.of("author_name", "author_email", "task_id"),
                "pull_requests", List.of("task_id", "provider_updated_at"),
                "ci_pipelines", List.of("provider_status", "provider_conclusion", "started_at", "finished_at", "provider_updated_at"),
                "ci_failures", List.of("error_summary", "raw_log_snippet", "raw_log_reference", "ai_summary", "related_task_id"));
        for (String table : TABLES) {
            for (var field : jdbc.queryForList("""
                    SELECT column_name,data_type,character_maximum_length,is_nullable FROM information_schema.columns
                    WHERE table_schema=? AND table_name=?
                    """, schema, table)) {
                String column = (String) field.get("column_name");
                String expectedType;
                if (varcharLengths.get(table).containsKey(column)) {
                    expectedType = "character varying";
                    assertThat(((Number) field.get("character_maximum_length")).intValue()).isEqualTo(varcharLengths.get(table).get(column));
                } else if (column.endsWith("_at")) expectedType = "timestamp with time zone";
                else if (List.of("message", "error_summary", "raw_log_snippet", "ai_summary").contains(column)) expectedType = "text";
                else if (List.of("pr_number", "run_attempt").contains(column)) expectedType = "integer";
                else expectedType = "uuid";
                assertThat(field.get("data_type")).as(table + "." + column).isEqualTo(expectedType);
                assertThat(field.get("is_nullable")).isEqualTo(nullable.get(table).contains(column) ? "YES" : "NO");
            }
        }
        for (String table : TABLES) {
            var types = jdbc.queryForMap("""
                    SELECT data_type, column_default FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = ? AND column_name = 'id'
                    """, schema, table);
            assertThat(types.get("data_type")).isEqualTo("uuid");
            assertThat(types.get("column_default").toString()).contains("gen_random_uuid()");
            var timestamps = jdbc.queryForList("""
                    SELECT data_type, column_default FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = ? AND column_name IN ('created_at','updated_at')
                    """, schema, table);
            assertThat(timestamps).hasSize(2).allSatisfy(t -> {
                assertThat(t.get("data_type")).isEqualTo("timestamp with time zone");
                assertThat(t.get("column_default").toString()).containsIgnoringCase("CURRENT_TIMESTAMP");
            });
        }
        List<String> unique = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        for (String table : TABLES) {
            unique.addAll(jdbc.queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'u'",
                    String.class, table));
            foreign.addAll(jdbc.queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'f'",
                    String.class, table));
        }
        assertThat(unique).containsExactlyInAnyOrder("UNIQUE (workspace_id, provider, remote_repo_id)",
                "UNIQUE (repository_id, sha)", "UNIQUE (repository_id, pr_number)",
                "UNIQUE (repository_id, pipeline_id, run_attempt)", "UNIQUE (pipeline_id, failure_key)");
        assertThat(foreign).hasSize(4).allSatisfy(fk -> {
            assertThat(fk).contains("ON DELETE CASCADE");
            assertThat(fk).containsAnyOf("REFERENCES repositories(id)", "REFERENCES ci_pipelines(id)");
        });
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ?", String.class, schema))
                .contains("idx_git_commits_task_id", "idx_pull_requests_task_id", "idx_pull_requests_repo_status",
                        "idx_ci_pipelines_repo_status", "idx_ci_pipelines_repo_commit", "idx_ci_failures_related_task")
                .doesNotContain("uq_git_commits_sha", "idx_git_commits_repo", "idx_ci_failures_pipeline");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND NOT i.indisvalid
                """, Long.class, schema)).isZero();
    }

    @ParameterizedTest(name = "SC02/SC07: defaults, duplicate PK and every NOT NULL in {0}")
    @ValueSource(strings = {"repositories", "git_commits", "pull_requests", "ci_pipelines", "ci_failures"})
    void enforcesDefaultsPrimaryKeyAndEveryNotNull(String table) {
        var values = row(table);
        UUID id = insert(table, values);
        var timestamps = jdbc.queryForMap("SELECT created_at, updated_at FROM " + table + " WHERE id = ?", id);
        assertThat(timestamps.values()).doesNotContainNull();
        var duplicate = new LinkedHashMap<>(values);
        duplicate.put("id", id);
        sqlState("23505", () -> insert(table, duplicate));
        for (String column : jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND is_nullable = 'NO'
                """, String.class, schema, table)) {
            sqlState("23502", () -> update(table, id, column, null));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id)).isEqualTo(1);
    }

    @TestFactory
    Stream<DynamicTest> varcharBoundariesAndNullableFields() {
        var cases = new ArrayList<DynamicTest>();
        for (String table : TABLES) {
            var fields = jdbc.queryForList("""
                    SELECT column_name, character_maximum_length, is_nullable FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = ? AND data_type = 'character varying'
                    """, schema, table);
            for (var field : fields) {
                String column = (String) field.get("column_name");
                int max = ((Number) field.get("character_maximum_length")).intValue();
                cases.add(DynamicTest.dynamicTest("IV10 " + table + "." + column, () -> {
                    UUID id = insert(table, row(table));
                    // Enum columns cannot contain n arbitrary characters; their semantic boundaries are tested separately.
                    if (!List.of("provider", "status").contains(column)) {
                        String value = List.of("sha", "commit_sha").contains(column) ? "a".repeat(max) : "é".repeat(max);
                        update(table, id, column, value);
                    }
                    sqlState("22001", () -> update(table, id, column, "é".repeat(max + 1)));
                    if (field.get("is_nullable").equals("YES")) {
                        update(table, id, column, null);
                        assertThat(jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id)).isNull();
                    }
                }));
            }
        }
        return cases.stream();
    }

    @TestFactory
    Stream<DynamicTest> rejectsBlankRequiredNamesAndOptionalAuthors() {
        var fields = Map.of("repositories", "remote_repo_id name repository_url webhook_secret_ref",
                "git_commits", "author_name author_email", "pull_requests", "title source_branch target_branch pr_url",
                "ci_pipelines", "pipeline_id branch", "ci_failures", "failure_key stage_name job_name");
        return fields.entrySet().stream().flatMap(entry -> Stream.of(entry.getValue().split(" ")).map(column ->
                DynamicTest.dynamicTest("IV09 " + entry.getKey() + "." + column, () -> {
                    UUID id = insert(entry.getKey(), row(entry.getKey()));
                    for (String blank : List.of("", "   ")) sqlState("23514", () -> update(entry.getKey(), id, column, blank));
                })));
    }

    @TestFactory
    Stream<DynamicTest> enforcesUtf8ByteLimitsForEveryBoundedText() {
        record TextField(String table, String column, int bytes) {}
        return Stream.of(new TextField("git_commits", "message", 65536), new TextField("ci_failures", "error_summary", 8192),
                new TextField("ci_failures", "raw_log_snippet", 16384), new TextField("ci_failures", "ai_summary", 16384))
                .map(field -> DynamicTest.dynamicTest("IV11 " + field.column(), () -> {
                    UUID id = insert(field.table(), row(field.table()));
                    String at = "é".repeat(field.bytes() / 2);
                    update(field.table(), id, field.column(), at.substring(1) + "x");
                    update(field.table(), id, field.column(), at);
                    assertThat(jdbc.queryForObject("SELECT octet_length(" + field.column() + ") FROM " + field.table()
                            + " WHERE id = ?", Integer.class, id)).isEqualTo(field.bytes());
                    sqlState("23514", () -> update(field.table(), id, field.column(), at + "x"));
                }));
    }

    @ParameterizedTest(name = "IV06 canonical SHA in {0}")
    @ValueSource(strings = {"git_commits", "ci_pipelines"})
    void validatesCommitHashes(String table) {
        String column = table.equals("git_commits") ? "sha" : "commit_sha";
        UUID id = insert(table, row(table));
        for (String valid : List.of("b".repeat(40), "c".repeat(64))) update(table, id, column, valid);
        for (String invalid : List.of("a".repeat(39), "a".repeat(41), "a".repeat(63), "g".repeat(40), "A".repeat(40), "0".repeat(40), "0".repeat(64))) {
            sqlState("23514", () -> update(table, id, column, invalid));
        }
        sqlState("22001", () -> update(table, id, column, "a".repeat(65)));
    }

    @Test
    @DisplayName("IV07/IV08/RT09: integer bounds, exact enums and independent provider state")
    void validatesNumbersAndEnums() {
        for (var pair : Map.of("pull_requests", "pr_number", "ci_pipelines", "run_attempt").entrySet()) {
            UUID id = insert(pair.getKey(), row(pair.getKey()));
            for (int valid : List.of(1, Integer.MAX_VALUE)) update(pair.getKey(), id, pair.getValue(), valid);
            for (int bad : List.of(-1, 0)) sqlState("23514", () -> update(pair.getKey(), id, pair.getValue(), bad));
            sqlState("22003", () -> update(pair.getKey(), id, pair.getValue(), (long) Integer.MAX_VALUE + 1));
        }
        var enums = Map.of("repositories", List.of("GITHUB", "GITLAB"), "pull_requests", List.of("OPEN", "CLOSED", "MERGED"),
                "ci_pipelines", List.of("PENDING", "RUNNING", "SUCCESS", "FAILED", "CANCELLED", "SKIPPED", "TIMED_OUT",
                        "ACTION_REQUIRED", "NEUTRAL", "STALE", "UNKNOWN"));
        enums.forEach((table, values) -> {
            UUID id = insert(table, row(table));
            String column = table.equals("repositories") ? "provider" : "status";
            for (String value : values) update(table, id, column, value);
            for (String bad : List.of("", "made-up", values.get(0).toLowerCase())) sqlState("23514", () -> update(table, id, column, bad));
        });
        UUID pipeline = insert("ci_pipelines", row("ci_pipelines"));
        assertThat(jdbc.queryForObject("SELECT run_attempt FROM ci_pipelines WHERE id = ?", Integer.class, pipeline)).isEqualTo(1);
        update("ci_pipelines", pipeline, "provider_status", "completed");
        update("ci_pipelines", pipeline, "provider_conclusion", "new-conclusion");
        assertThat(jdbc.queryForObject("SELECT status FROM ci_pipelines WHERE id = ?", String.class, pipeline)).isEqualTo("FAILED");
    }

    @ParameterizedTest(name = "IV01-IV05 scoped uniqueness in {0}")
    @ValueSource(strings = {"repositories", "git_commits", "pull_requests", "ci_pipelines", "ci_failures"})
    void scopesBusinessKeysCorrectly(String table) {
        var first = row(table);
        insert(table, first);
        sqlState("23505", () -> insert(table, first));
        var other = new LinkedHashMap<>(first);
        switch (table) {
            case "repositories" -> {
                other.put("workspace_id", UUID.randomUUID());
                insert(table, other);
                other.put("workspace_id", first.get("workspace_id"));
                other.put("provider", "GITLAB");
            }
            case "ci_failures" -> other.put("pipeline_id", insert("ci_pipelines", row("ci_pipelines")));
            default -> other.put("repository_id", insert("repositories", row("repositories")));
        }
        insert(table, other);
        if (table.equals("ci_pipelines")) {
            var retry = new LinkedHashMap<>(first);
            retry.put("run_attempt", 2);
            insert(table, retry);
            retry.put("pipeline_id", "101");
            insert(table, retry);
        }
    }

    @Test
    @DisplayName("SC08/IV13: invalid UUIDs fail and only internal parent IDs have foreign keys")
    void rejectsOrphanInternalChildrenButAcceptsSoftReferences() {
        for (String table : TABLES.subList(1, TABLES.size())) {
            var values = row(table);
            values.put(table.equals("ci_failures") ? "pipeline_id" : "repository_id", UUID.randomUUID());
            sqlState("23503", () -> insert(table, values));
        }
        UUID commit = insert("git_commits", row("git_commits"));
        update("git_commits", commit, "task_id", UUID.randomUUID());
        sqlState("22P02", () -> jdbc.update("UPDATE git_commits SET task_id = ?::uuid WHERE id = ?", "invalid-uuid", commit));
        update("git_commits", commit, "task_id", null);
    }

    @Test
    @DisplayName("RT01/RT02/RT03/RT04/RT05/RT06/IV14: unicode, parameters, mutable links and explicit audit")
    void preservesContentAndLinksWithoutInterpretingPayloads() {
        var values = row("repositories");
        values.put("name", "Kho tiếng Việt 🧪");
        UUID repo = insert("repositories", values);
        var commit = row("git_commits");
        commit.put("repository_id", repo);
        commit.put("message", "Xin chào 🧪\n`code` <script>alert(1)</script> '; DROP TABLE tasks; --");
        UUID commitId = insert("git_commits", commit);
        assertThat(jdbc.queryForObject("SELECT message FROM git_commits WHERE id = ?", String.class, commitId)).isEqualTo(commit.get("message"));
        assertThat(jdbc.queryForMap("SELECT author_name,author_email,task_id FROM git_commits WHERE id = ?", commitId).values())
                .containsOnlyNulls();
        UUID task = UUID.randomUUID();
        update("git_commits", commitId, "task_id", task);
        assertThat(jdbc.queryForObject("SELECT task_id FROM git_commits WHERE id = ?", UUID.class, commitId)).isEqualTo(task);
        update("git_commits", commitId, "task_id", null);
        Timestamp before = jdbc.queryForObject("SELECT updated_at FROM repositories WHERE id = ?", Timestamp.class, repo);
        update("repositories", repo, "name", "Renamed");
        update("repositories", repo, "repository_url", "https://github.com/example/renamed");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM repositories WHERE id = ?", Timestamp.class, repo)).isEqualTo(before);
        Timestamp changed = Timestamp.from(Instant.parse("2026-10-16T01:00:00Z"));
        update("repositories", repo, "updated_at", changed);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM repositories WHERE id = ?", Timestamp.class, repo)).isEqualTo(changed);
        var pr = row("pull_requests");
        pr.put("repository_id", repo);
        UUID prId = insert("pull_requests", pr);
        for (String status : List.of("CLOSED", "OPEN", "MERGED")) update("pull_requests", prId, "status", status);
        assertThat(jdbc.queryForObject("SELECT id FROM pull_requests WHERE repository_id = ? AND pr_number = 12", UUID.class, repo)).isEqualTo(prId);
        assertThat(jdbc.queryForObject("SELECT to_regclass('tasks') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT repository_id FROM git_commits WHERE id = ?", UUID.class, commitId)).isEqualTo(repo);
    }

    @Test
    @DisplayName("RT07/RT08/RT10/RT11: workflow before push, reruns and nullable failure data")
    void recordsRunsBeforeCommitsAndKeepsAttemptHistory() {
        var run = row("ci_pipelines");
        UUID first = insert("ci_pipelines", run);
        var retry = new LinkedHashMap<>(run);
        retry.put("run_attempt", 2);
        UUID second = insert("ci_pipelines", retry);
        retry.put("pipeline_id", "101");
        insert("ci_pipelines", retry);
        assertThat(count("git_commits")).isZero();
        var commit = row("git_commits");
        commit.put("repository_id", run.get("repository_id"));
        insert("git_commits", commit);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ci_pipelines p JOIN git_commits c
                ON c.repository_id = p.repository_id AND c.sha = p.commit_sha
                """, Long.class)).isEqualTo(3);
        var failure = row("ci_failures");
        failure.put("pipeline_id", first);
        UUID id = insert("ci_failures", failure);
        for (String field : List.of("error_summary", "raw_log_snippet", "raw_log_reference", "ai_summary", "related_task_id")) {
            assertThat(jdbc.queryForObject("SELECT " + field + " FROM ci_failures WHERE id = ?", Object.class, id)).isNull();
        }
        update("ci_failures", id, "raw_log_snippet", "Synthetic redacted log");
        update("ci_failures", id, "raw_log_reference", "object:synthetic-log");
        update("ci_failures", id, "ai_summary", "Synthetic summary");
        update("ci_failures", id, "related_task_id", UUID.randomUUID());
        failure.put("failure_key", "job:102");
        insert("ci_failures", failure);
        failure.put("pipeline_id", second);
        insert("ci_failures", failure);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failures WHERE pipeline_id = ?", Long.class, first)).isEqualTo(2);
    }

    @Test
    @DisplayName("IV12/RT12: timestamp order and timezone-independent instants")
    void validatesTimeOrderAndPreservesInstantsAcrossTimezones() throws Exception {
        UUID run = insert("ci_pipelines", row("ci_pipelines"));
        Timestamp time = Timestamp.from(Instant.parse("2026-10-15T01:00:00Z"));
        update("ci_pipelines", run, "finished_at", time); // NULL start is allowed.
        update("ci_pipelines", run, "started_at", time); // Equality is allowed.
        sqlState("23514", () -> update("ci_pipelines", run, "finished_at", Timestamp.from(time.toInstant().minusSeconds(1))));
        update("ci_pipelines", run, "finished_at", Timestamp.from(time.toInstant().plusSeconds(1)));
        update("ci_pipelines", run, "finished_at", null); // Start without finish is allowed.
        var values = row("git_commits");
        Instant future = Instant.parse("2030-01-01T01:00:00.123456Z");
        values.put("committed_at", Timestamp.from(future));
        UUID commit = insert("git_commits", values);
        try (var connection = connection()) {
            for (String zone : List.of("UTC", "Asia/Saigon")) {
                try (var statement = connection.createStatement()) { statement.execute("SET TIME ZONE '" + zone + "'"); }
                try (var statement = connection.prepareStatement("SELECT committed_at FROM git_commits WHERE id = ?")) {
                    statement.setObject(1, commit);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getObject(1, OffsetDateTime.class).toInstant()).isEqualTo(future);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("DL01-DL05/DL07: local cascades, rollback, anonymization and independent repositories")
    void limitsCascadesToTheOwnedGraph() throws Exception {
        UUID repo = insert("repositories", row("repositories"));
        UUID other = insert("repositories", row("repositories"));
        var otherCommit = row("git_commits"); otherCommit.put("repository_id", other);
        UUID otherCommitId = insert("git_commits", otherCommit);
        var otherPr = row("pull_requests"); otherPr.put("repository_id", other);
        UUID otherPrId = insert("pull_requests", otherPr);
        var otherRun = row("ci_pipelines"); otherRun.put("repository_id", other);
        UUID otherRunId = insert("ci_pipelines", otherRun);
        var otherFailure = row("ci_failures"); otherFailure.put("pipeline_id", otherRunId);
        UUID otherFailureId = insert("ci_failures", otherFailure);
        var commit = row("git_commits"); commit.put("repository_id", repo);
        UUID commitId = insert("git_commits", commit);
        var pr = row("pull_requests"); pr.put("repository_id", repo);
        UUID prId = insert("pull_requests", pr);
        var run = row("ci_pipelines"); run.put("repository_id", repo);
        UUID runId = insert("ci_pipelines", run);
        var failure = row("ci_failures"); failure.put("pipeline_id", runId);
        UUID failureId = insert("ci_failures", failure);
        update("git_commits", commitId, "author_name", "Synthetic");
        update("git_commits", commitId, "author_email", "synthetic@example.test");
        update("git_commits", commitId, "author_name", null);
        update("git_commits", commitId, "author_email", null);
        update("ci_failures", failureId, "raw_log_snippet", null);
        update("ci_failures", failureId, "raw_log_reference", null);
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("DELETE FROM repositories WHERE id = ?")) {
                statement.setObject(1, repo); statement.executeUpdate();
            }
            connection.rollback();
        }
        assertExists("repositories", repo, true); assertExists("git_commits", commitId, true);
        assertExists("pull_requests", prId, true); assertExists("ci_pipelines", runId, true); assertExists("ci_failures", failureId, true);
        jdbc.update("DELETE FROM git_commits WHERE id = ?", commitId);
        jdbc.update("DELETE FROM pull_requests WHERE id = ?", prId);
        assertExists("ci_pipelines", runId, true);
        jdbc.update("DELETE FROM ci_failures WHERE id = ?", failureId);
        assertExists("ci_pipelines", runId, true);
        failureId = insert("ci_failures", failure);
        jdbc.update("DELETE FROM ci_pipelines WHERE id = ?", runId);
        assertExists("ci_failures", failureId, false); assertExists("repositories", repo, true);
        runId = insert("ci_pipelines", run); failure.put("pipeline_id", runId);
        failureId = insert("ci_failures", failure);
        commitId = insert("git_commits", commit); prId = insert("pull_requests", pr);
        jdbc.update("DELETE FROM repositories WHERE id = ?", repo);
        assertExists("git_commits", commitId, false); assertExists("pull_requests", prId, false);
        assertExists("ci_pipelines", runId, false); assertExists("ci_failures", failureId, false);
        assertExists("repositories", other, true);
        assertExists("git_commits", otherCommitId, true); assertExists("pull_requests", otherPrId, true);
        assertExists("ci_pipelines", otherRunId, true); assertExists("ci_failures", otherFailureId, true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Long.class)).isEqualTo(6);
    }

    @Test
    @DisplayName("DL06/DL08: soft task/workspace deletion does not cascade; secrets remain external")
    void preservesSoftReferencesAndStoresOnlySecretReferences() throws Exception {
        UUID user = UUID.randomUUID(), workspace = UUID.randomUUID(), board = UUID.randomUUID(), column = UUID.randomUUID(), task = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name) VALUES (?,'test@example.test','synthetic','Test')", user);
        jdbc.update("INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?,'Test','test',?)", workspace, user);
        jdbc.update("INSERT INTO boards(id,workspace_id,name) VALUES (?,?,'Test')", board, workspace);
        jdbc.update("INSERT INTO columns(id,board_id,name,position,status_category) VALUES (?,?,'Todo',0,'TODO')", column, board);
        jdbc.update("INSERT INTO tasks(id,column_id,title,position) VALUES (?,?,'Test',0)", task, column);
        var repo = row("repositories"); repo.put("workspace_id", workspace);
        UUID repoId = insert("repositories", repo);
        var commit = row("git_commits"); commit.put("repository_id", repoId); commit.put("task_id", task);
        UUID id = insert("git_commits", commit);
        jdbc.update("DELETE FROM tasks WHERE id = ?", task);
        jdbc.update("DELETE FROM workspaces WHERE id = ?", workspace);
        assertThat(jdbc.queryForObject("SELECT task_id FROM git_commits WHERE id = ?", UUID.class, id)).isEqualTo(task);
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM repositories WHERE id = ?", UUID.class, repoId)).isEqualTo(workspace);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND column_name = 'webhook_secret'", Long.class, schema)).isZero();
        assertThat(jdbc.queryForObject("SELECT webhook_secret_ref FROM repositories WHERE id = ?", String.class, repoId)).isEqualTo("config:github-test");
        try (var resource = getClass().getResourceAsStream("/db/migration/V6__gitci_schema.sql")) {
            assertThat(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("INSERT INTO", "CREATE TRIGGER");
        }
    }

    private void assertExists(String table, UUID id, boolean exists) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id)).isEqualTo(exists ? 1L : 0L);
    }
}
