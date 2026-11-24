package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class CiFailureMigrationIntegrationTest extends GitCiPostgresSupport {
    @Override
    boolean migrateBeforeEach() {
        return false;
    }

    @Test
    void upgradesV18WithoutMarkingLegacyLogsReadyAndEnforcesNewInvariants() {
        migration("18").migrate();
        UUID pipeline = insert("ci_pipelines", row("ci_pipelines"));
        var legacy = row("ci_failures");
        legacy.put("pipeline_id", pipeline);
        legacy.put("raw_log_snippet", "legacy content");
        UUID failure = insert("ci_failures", legacy);
        migration("19").migrate();

        assertThat(jdbc.queryForMap("SELECT log_status, raw_log_snippet, log_event_id FROM ci_failures WHERE id=?", failure))
                .containsEntry("log_status", "LEGACY")
                .containsEntry("raw_log_snippet", "legacy content")
                .containsEntry("log_event_id", null);
        assertThat(jdbc.queryForObject("SELECT failure_event_enqueued FROM ci_pipelines WHERE id=?", Boolean.class, pipeline))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failure_event_outbox", Long.class)).isZero();

        // DB-03: nullable branch, blank rejected, 255 allowed, 256 rejected
        assertThat(jdbc.update("UPDATE ci_pipelines SET branch=NULL WHERE id=?", pipeline)).isOne();
        sqlState("23514", () -> jdbc.update("UPDATE ci_pipelines SET branch=E' \\t' WHERE id=?", pipeline));
        assertThat(jdbc.update("UPDATE ci_pipelines SET branch=? WHERE id=?", "b".repeat(255), pipeline)).isOne();
        sqlState("22001", () -> jdbc.update("UPDATE ci_pipelines SET branch=? WHERE id=?", "b".repeat(256), pipeline));

        // DB-02: ci_failures status, error code and READY invariants
        sqlState("23514", () -> jdbc.update("UPDATE ci_failures SET log_status='INVALID' WHERE id=?", failure));
        sqlState("23514", () -> jdbc.update("UPDATE ci_failures SET log_error_code='bad_code' WHERE id=?", failure));
        sqlState("23514", () -> jdbc.update("UPDATE ci_failures SET log_status='READY' WHERE id=?", failure));

        String snippet = "Error: synthetic failure é";
        int snippetBytes = snippet.getBytes(StandardCharsets.UTF_8).length;
        String digest = "a".repeat(64);
        Instant readyAt = Instant.parse("2026-11-24T00:00:00Z");
        Instant expiresAt = readyAt.plusSeconds(86400);
        UUID eventId = UUID.randomUUID();
        String ref = "ci-log:" + failure;

        // Wrong byte count -> rejected
        sqlState("23514", () -> jdbc.update("""
                UPDATE ci_failures SET log_status='READY', raw_log_snippet=?, raw_log_reference=?,
                    log_bytes=?, log_digest=?, log_ready_at=?, log_expires_at=?, log_event_id=?, log_error_code=NULL
                WHERE id=?
                """, snippet, ref, snippetBytes + 1, digest, Timestamp.from(readyAt), Timestamp.from(expiresAt), eventId, failure));
        // Wrong reference -> rejected
        sqlState("23514", () -> jdbc.update("""
                UPDATE ci_failures SET log_status='READY', raw_log_snippet=?, raw_log_reference=?,
                    log_bytes=?, log_digest=?, log_ready_at=?, log_expires_at=?, log_event_id=?, log_error_code=NULL
                WHERE id=?
                """, snippet, "ci-log:" + UUID.randomUUID(), snippetBytes, digest, Timestamp.from(readyAt), Timestamp.from(expiresAt), eventId, failure));
        // Invalid digest -> rejected
        sqlState("23514", () -> jdbc.update("""
                UPDATE ci_failures SET log_status='READY', raw_log_snippet=?, raw_log_reference=?,
                    log_bytes=?, log_digest=?, log_ready_at=?, log_expires_at=?, log_event_id=?, log_error_code=NULL
                WHERE id=?
                """, snippet, ref, snippetBytes, "not-sha256", Timestamp.from(readyAt), Timestamp.from(expiresAt), eventId, failure));
        // Expires at <= ready at -> rejected
        sqlState("23514", () -> jdbc.update("""
                UPDATE ci_failures SET log_status='READY', raw_log_snippet=?, raw_log_reference=?,
                    log_bytes=?, log_digest=?, log_ready_at=?, log_expires_at=?, log_event_id=?, log_error_code=NULL
                WHERE id=?
                """, snippet, ref, snippetBytes, digest, Timestamp.from(readyAt), Timestamp.from(readyAt), eventId, failure));
        // READY with error code -> rejected
        sqlState("23514", () -> jdbc.update("""
                UPDATE ci_failures SET log_status='READY', raw_log_snippet=?, raw_log_reference=?,
                    log_bytes=?, log_digest=?, log_ready_at=?, log_expires_at=?, log_event_id=?, log_error_code='SOME_ERR'
                WHERE id=?
                """, snippet, ref, snippetBytes, digest, Timestamp.from(readyAt), Timestamp.from(expiresAt), eventId, failure));

        // Valid READY update succeeds
        assertThat(jdbc.update("""
                UPDATE ci_failures SET log_status='READY', raw_log_snippet=?, raw_log_reference=?,
                    log_bytes=?, log_digest=?, log_ready_at=?, log_expires_at=?, log_event_id=?, log_error_code=NULL
                WHERE id=?
                """, snippet, ref, snippetBytes, digest, Timestamp.from(readyAt), Timestamp.from(expiresAt), eventId, failure))
                .isOne();

        // DB-02: ci_log_fetch_jobs invariants
        sqlState("23514", () -> jdbc.update(
                "INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id,status) VALUES (?,?,'PROCESSING')", pipeline, failure));
        sqlState("23514", () -> jdbc.update(
                "INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id,status,claim_token) VALUES (?,?,'PENDING',?)",
                pipeline, failure, UUID.randomUUID()));
        sqlState("23514", () -> jdbc.update(
                "INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id,attempts) VALUES (?,?,9)", pipeline, failure));
        sqlState("23514", () -> jdbc.update(
                "INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id,not_found_attempts) VALUES (?,?,4)", pipeline, failure));
        sqlState("23503", () -> jdbc.update(
                "INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id) VALUES (?,?)", pipeline, UUID.randomUUID()));

        assertThat(jdbc.update("INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id) VALUES (?,?)", pipeline, failure))
                .isOne();
        sqlState("23505", () -> jdbc.update(
                "INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id) VALUES (?,?)", pipeline, failure));

        // DB-02: ci_failure_event_outbox invariants
        UUID repoId = jdbc.queryForObject("SELECT repository_id FROM ci_pipelines WHERE id=?", UUID.class, pipeline);
        UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM repositories WHERE id=?", UUID.class, repoId);
        sqlState("23514", () -> jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,
                    provider_run_id,run_attempt,repo,commit_sha,occurred_at)
                VALUES (?,?,?,?,?,'0',1,'owner/repo',?,?)
                """, eventId, pipeline, failure, repoId, workspaceId, SHA, Timestamp.from(readyAt)));
        sqlState("23514", () -> jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,
                    provider_run_id,run_attempt,repo,commit_sha,occurred_at)
                VALUES (?,?,?,?,?,'100',0,'owner/repo',?,?)
                """, eventId, pipeline, failure, repoId, workspaceId, SHA, Timestamp.from(readyAt)));
        sqlState("23514", () -> jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,
                    provider_run_id,run_attempt,repo,commit_sha,occurred_at)
                VALUES (?,?,?,?,?,'100',1,'owner/repo',?,?)
                """, eventId, pipeline, failure, repoId, workspaceId, "0".repeat(40), Timestamp.from(readyAt)));
        sqlState("23514", () -> jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,
                    provider_run_id,run_attempt,repo,commit_sha,occurred_at,status)
                VALUES (?,?,?,?,?,'100',1,'owner/repo',?,?,'PUBLISHED')
                """, eventId, pipeline, failure, repoId, workspaceId, SHA, Timestamp.from(readyAt)));
        sqlState("23514", () -> jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,
                    provider_run_id,run_attempt,repo,commit_sha,occurred_at,status)
                VALUES (?,?,?,?,?,'100',1,'owner/repo',?,?,'PROCESSING')
                """, eventId, pipeline, failure, repoId, workspaceId, SHA, Timestamp.from(readyAt)));

        assertThat(jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,
                    provider_run_id,run_attempt,repo,commit_sha,occurred_at)
                VALUES (?,?,?,?,?,'100',1,'owner/repo',?,?)
                """, eventId, pipeline, failure, repoId, workspaceId, SHA, Timestamp.from(readyAt))).isOne();
        // Duplicate logical event for same pipeline attempt rejected
        sqlState("23505", () -> jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,
                    provider_run_id,run_attempt,repo,commit_sha,occurred_at)
                VALUES (?,?,?,?,?,'100',1,'owner/repo',?,?)
                """, UUID.randomUUID(), pipeline, failure, repoId, workspaceId, SHA, Timestamp.from(readyAt)));

        // DB-03: repository deletion cascades pipelines, failures, fetch jobs, and event outbox
        jdbc.update("DELETE FROM repositories WHERE id=?", repoId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_pipelines WHERE repository_id=?", Long.class, repoId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failures", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_log_fetch_jobs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_failure_event_outbox", Long.class)).isZero();
    }

    @Test
    void freshEmptyDatabaseMigratesToV19AndUsesPartialIndexesForClaimAndRetention() {
        migration("19").migrate();

        UUID repo = insert("repositories", row("repositories"));
        UUID workspace = jdbc.queryForObject("SELECT workspace_id FROM repositories WHERE id=?", UUID.class, repo);
        // Seed 2000 DONE jobs + 20 PENDING jobs + 2000 PUBLISHED outbox + 20 READY/expired failures
        jdbc.update("""
                INSERT INTO ci_pipelines(id,repository_id,pipeline_id,run_attempt,commit_sha,branch,status)
                SELECT md5('p'||g)::uuid, ?, g::text, 1, lpad(to_hex(g),40,'0'), NULL, 'FAILED'
                FROM generate_series(1, 2020) g
                """, repo);
        jdbc.execute("""
                INSERT INTO ci_failures(id,pipeline_id,failure_key,stage_name,job_name,log_status,
                    raw_log_snippet,raw_log_reference,log_bytes,log_digest,log_ready_at,log_expires_at,log_event_id)
                SELECT md5('f'||g)::uuid, md5('p'||g)::uuid, 'workflow-run', 'workflow', 'aggregate',
                    CASE WHEN g <= 2000 THEN 'EXPIRED' ELSE 'READY' END,
                    CASE WHEN g <= 2000 THEN NULL ELSE 'Error: synthetic' END,
                    'ci-log:' || md5('f'||g)::uuid::text,
                    CASE WHEN g <= 2000 THEN NULL ELSE octet_length('Error: synthetic') END,
                    CASE WHEN g <= 2000 THEN NULL ELSE repeat('a', 64) END,
                    now() - interval '10 days',
                    now() - interval '1 day',
                    md5('e'||g)::uuid
                FROM generate_series(1, 2020) g
                """);
        jdbc.execute("""
                INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id,status,next_attempt_at)
                SELECT md5('p'||g)::uuid, md5('f'||g)::uuid,
                    CASE WHEN g <= 2000 THEN 'DONE' ELSE 'PENDING' END,
                    now() - interval '1 minute'
                FROM generate_series(1, 2020) g
                """);
        jdbc.update("""
                INSERT INTO ci_failure_event_outbox(id,pipeline_id,failure_id,repository_id,workspace_id,
                    provider_run_id,run_attempt,repo,commit_sha,occurred_at,status,published_at)
                SELECT md5('e'||g)::uuid, md5('p'||g)::uuid, md5('f'||g)::uuid, ?, ?,
                    g::text, 1, 'owner/repo', lpad(to_hex(g),40,'0'), now() - interval '10 days',
                    CASE WHEN g <= 2000 THEN 'PUBLISHED' ELSE 'PENDING' END,
                    CASE WHEN g <= 2000 THEN now() - interval '9 days' ELSE NULL END
                FROM generate_series(1, 2020) g
                """, repo, workspace);
        jdbc.execute("ANALYZE ci_pipelines, ci_failures, ci_log_fetch_jobs, ci_failure_event_outbox");

        String claimPlan = String.join("\n", jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT j.pipeline_id FROM ci_log_fetch_jobs j JOIN ci_pipelines p ON p.id=j.pipeline_id
                WHERE j.attempts<8 AND ((j.status='PENDING' AND j.next_attempt_at<=CURRENT_TIMESTAMP)
                    OR (j.status='PROCESSING' AND j.lease_until<=CURRENT_TIMESTAMP))
                ORDER BY j.next_attempt_at,j.pipeline_id LIMIT 1 FOR UPDATE OF p SKIP LOCKED
                """, String.class));
        assertThat(claimPlan).contains("idx_ci_log_fetch_pending");

        String expiryPlan = String.join("\n", jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT f.id FROM ci_failures f WHERE f.log_status='READY'
                    AND f.log_expires_at<=CURRENT_TIMESTAMP AND NOT EXISTS (
                        SELECT 1 FROM ci_failure_event_outbox o WHERE o.id=f.log_event_id
                            AND o.status IN ('PENDING','PROCESSING'))
                ORDER BY f.log_expires_at,f.id LIMIT 100 FOR UPDATE OF f SKIP LOCKED
                """, String.class));
        assertThat(expiryPlan).contains("idx_ci_log_expiry");

        String outboxPurgePlan = String.join("\n", jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT id FROM ci_failure_event_outbox WHERE status='PUBLISHED'
                    AND published_at<=CURRENT_TIMESTAMP - (7 * INTERVAL '1 day')
                ORDER BY published_at,id LIMIT 100 FOR UPDATE SKIP LOCKED
                """, String.class));
        assertThat(outboxPurgePlan).contains("idx_ci_event_published");

        // Sanity: non-DataIntegrity check unused import guard
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ci_log_fetch_jobs(pipeline_id,failure_id) VALUES (?,?)",
                UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
