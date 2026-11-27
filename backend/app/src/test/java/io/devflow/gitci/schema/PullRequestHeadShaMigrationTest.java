package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies V20 pull_requests.head_sha migration preserves V19 rows and enforces SHA constraints. */
class PullRequestHeadShaMigrationTest extends GitCiPostgresSupport {

    @Override
    boolean migrateBeforeEach() {
        return false;
    }

    @Test
    void upgradesV19ToV20PreservingLegacyPrsAndEnforcingHeadShaConstraint() {
        migration("19").migrate();
        UUID legacyPr = insert("pull_requests", row("pull_requests"));

        migration("20").migrate();

        assertThat(jdbc.queryForObject("SELECT head_sha FROM pull_requests WHERE id=?", String.class, legacyPr))
                .isNull();

        // Valid SHA-1 and SHA-256 accepted
        assertThat(jdbc.update("UPDATE pull_requests SET head_sha=? WHERE id=?", "a".repeat(40), legacyPr)).isOne();
        assertThat(jdbc.update("UPDATE pull_requests SET head_sha=? WHERE id=?", "f".repeat(64), legacyPr)).isOne();
        assertThat(jdbc.update("UPDATE pull_requests SET head_sha=NULL WHERE id=?", legacyPr)).isOne();

        // Invalid / all-zero / uppercase SHA rejected with CHECK violation (23514)
        sqlState("23514", () -> jdbc.update("UPDATE pull_requests SET head_sha=? WHERE id=?", "0".repeat(40), legacyPr));
        sqlState("23514", () -> jdbc.update("UPDATE pull_requests SET head_sha=? WHERE id=?", "A".repeat(40), legacyPr));
        sqlState("23514", () -> jdbc.update("UPDATE pull_requests SET head_sha=? WHERE id=?", "not-a-sha", legacyPr));

        // Partial index exists on pull_requests(repository_id, head_sha)
        Integer indexCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename = 'pull_requests'
                  AND indexname = 'idx_pull_requests_repo_head_sha'
                """, Integer.class);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    void migratesCleanDatabaseFromV1ThroughV20() {
        migration("20").migrate();
        UUID prId = insert("pull_requests", row("pull_requests"));
        assertThat(jdbc.update("UPDATE pull_requests SET head_sha=? WHERE id=?", "b".repeat(40), prId)).isOne();
    }
}
