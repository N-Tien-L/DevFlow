package io.devflow.auth.internal.verification;

import io.devflow.auth.internal.entity.UserStatus;
import io.devflow.auth.internal.pat.PersonalAccessTokenProperties;
import io.devflow.auth.internal.repository.PersonalAccessTokenRepository;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.VerificationTokenRepository;
import io.devflow.auth.internal.repository.WorkspaceInvitationRepository;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.workspace.WorkspaceProperties;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly purge of stale verification tokens, abandoned (never verified) sign-ups, expired or revoked
 * workspace invitations and expired or revoked personal access tokens. All deletes are idempotent, so running on several instances at once is harmless.
 */
@Component
public class VerificationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(VerificationCleanupJob.class);

    private final VerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final PersonalAccessTokenRepository accessTokenRepository;
    private final VerificationProperties properties;
    private final WorkspaceProperties workspaceProperties;
    private final PersonalAccessTokenProperties accessTokenProperties;
    private final AuthMetrics metrics;
    private final Clock clock;

    public VerificationCleanupJob(
            VerificationTokenRepository tokenRepository,
            UserRepository userRepository,
            WorkspaceInvitationRepository invitationRepository,
            PersonalAccessTokenRepository accessTokenRepository,
            VerificationProperties properties,
            WorkspaceProperties workspaceProperties,
            PersonalAccessTokenProperties accessTokenProperties,
            AuthMetrics metrics,
            Clock clock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.accessTokenRepository = accessTokenRepository;
        this.properties = properties;
        this.workspaceProperties = workspaceProperties;
        this.accessTokenProperties = accessTokenProperties;
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
        int invitations = invitationRepository.deleteStaleInvitations(
                now.minus(workspaceProperties.getInvitationRetention()));
        int accessTokens = accessTokenRepository.deleteStaleTokens(now.minus(accessTokenProperties.getRetention()));

        metrics.purged("verification_token", tokens);
        metrics.purged("unverified_user", users);
        metrics.purged("workspace_invitation", invitations);
        metrics.purged("personal_access_token", accessTokens);
        if (tokens > 0 || users > 0 || invitations > 0 || accessTokens > 0) {
            log.info("Auth cleanup removed {} stale tokens, {} unverified accounts, {} stale invitations and "
                    + "{} stale access tokens", tokens, users, invitations, accessTokens);
        }
    }
}
