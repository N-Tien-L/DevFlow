package io.devflow.gitci.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes only old completed receipts, in bounded batches. */
@Component
class GitHubWebhookReceiptCleanup {
    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookReceiptCleanup.class);
    private final JdbcTemplate jdbc;
    private final GitHubWebhookProperties properties;

    GitHubWebhookReceiptCleanup(JdbcTemplate jdbc, GitHubWebhookProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(cron = "${devflow.gitci.github-webhook.cleanup-cron:0 0 4 * * *}", zone = "UTC")
    void clean() {
        if (!properties.isEnabled()) return;
        try {
            jdbc.update("DELETE FROM github_webhook_receipts WHERE id IN "
                    + "(SELECT id FROM github_webhook_receipts WHERE status = 'COMPLETED' "
                    + "AND created_at < CURRENT_TIMESTAMP - INTERVAL '7 days' "
                    + "ORDER BY created_at LIMIT 1000 FOR UPDATE SKIP LOCKED)");
        } catch (RuntimeException ex) {
            log.warn("GitHub webhook receipt cleanup failed");
        }
    }
}
