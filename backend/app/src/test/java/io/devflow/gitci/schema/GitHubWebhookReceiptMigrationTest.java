package io.devflow.gitci.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class GitHubWebhookReceiptMigrationTest extends GitCiPostgresSupport {
    @Test
    void enforcesDeliveryDigestAndStatusConstraints() {
        migration("13").migrate();
        UUID repository = insert("repositories", row("repositories"));
        String sql = "INSERT INTO github_webhook_receipts "
                + "(repository_id, delivery_id, payload_sha256, event_name, status) VALUES (?, ?, ?, 'ping', 'PROCESSING')";
        assertThat(jdbc.update(sql, repository, "delivery-1", "a".repeat(64))).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(sql, repository, "delivery-1", "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, repository, "delivery-2", "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, repository, "delivery-3", "BAD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE github_webhook_receipts SET status = 'COMPLETED'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
