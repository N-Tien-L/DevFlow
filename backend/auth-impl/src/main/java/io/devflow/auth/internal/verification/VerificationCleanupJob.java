package io.devflow.auth.internal.verification;

import io.devflow.auth.internal.entity.UserStatus;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.VerificationTokenRepository;
import io.devflow.auth.internal.support.AuthMetrics;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly purge of stale verification tokens and abandoned (never verified) sign-ups.
 * Both deletes are idempotent, so running on several instances at once is harmless.
 */
@Component
public class VerificationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(VerificationCleanupJob.class);

    private final VerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final VerificationProperties properties;
    private final AuthMetrics metrics;
    private final Clock clock;

    public VerificationCleanupJob(
            VerificationTokenRepository tokenRepository,
            UserRepository userRepository,
            VerificationProperties properties,
            AuthMetrics metrics,
            Clock clock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(cron = "${devflow.verification.purge-cron:0 0 3 * * *}", zone = "UTC")
    @Transactional
    public void purge() {
        Instant now = clock.instant();
        int tokens = tokenRepository.deleteStaleTokens(now.minus(properties.getTokenRetention()));
        int users = userRepository.deleteByStatusAndCreatedAtBefore(
                UserStatus.PENDING_VERIFICATION, now.minus(properties.getUnverifiedAccountRetention()));

        metrics.purged("verification_token", tokens);
        metrics.purged("unverified_user", users);
        if (tokens > 0 || users > 0) {
            log.info("Verification cleanup removed {} stale tokens and {} unverified accounts", tokens, users);
        }
    }
}
