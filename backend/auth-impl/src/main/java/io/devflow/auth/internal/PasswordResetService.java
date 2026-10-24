package io.devflow.auth.internal;

import io.devflow.auth.api.ForgotPasswordRequest;
import io.devflow.auth.api.MessageResponse;
import io.devflow.auth.api.ResetPasswordRequest;
import io.devflow.auth.internal.antiabuse.TurnstileService;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.VerificationTokenEntity;
import io.devflow.auth.internal.entity.VerificationTokenType;
import io.devflow.auth.internal.exception.PasswordResetTokenExpiredException;
import io.devflow.auth.internal.exception.PasswordResetTokenInvalidException;
import io.devflow.auth.internal.passwordreset.PasswordChangedEmailRequestedEvent;
import io.devflow.auth.internal.passwordreset.PasswordResetEmailRequestedEvent;
import io.devflow.auth.internal.passwordreset.PasswordResetProperties;
import io.devflow.auth.internal.repository.PersonalAccessTokenRepository;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.VerificationTokenRepository;
import io.devflow.auth.internal.security.RateLimitProperties;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.security.SessionRevocationService;
import io.devflow.auth.internal.security.StompSessionRegistry;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.InputSanitizer;
import io.devflow.auth.internal.support.LogMasking;
import io.devflow.auth.internal.verification.VerificationTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Forgot password and password reset (T-008).
 *
 * <p>Reset tokens reuse {@code verification_tokens} (type {@code PASSWORD_RESET}): 256-bit random, stored as a
 * SHA-256 digest, single use, short-lived, and issuing one invalidates the previous ones. A successful reset signs
 * the user out everywhere: the token version is bumped so every earlier JWT is rejected (see
 * {@link SessionRevocationService}), open STOMP sockets are closed and personal access tokens are revoked.
 */
@Service
public class PasswordResetService {

    static final String FORGOT_MESSAGE =
            "If an account exists for this email, a password reset link has been sent.";
    static final String RESET_MESSAGE = "Your password has been reset. You can now sign in with your new password.";

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final VerificationTokenRepository tokenRepository;
    private final PersonalAccessTokenRepository accessTokenRepository;
    private final VerificationTokenGenerator tokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetProperties properties;
    private final RateLimiterService rateLimiterService;
    private final TurnstileService turnstileService;
    private final SessionRevocationService sessionRevocationService;
    private final StompSessionRegistry stompSessionRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthMetrics metrics;
    private final Clock clock;

    public PasswordResetService(
            UserRepository userRepository,
            VerificationTokenRepository tokenRepository,
            PersonalAccessTokenRepository accessTokenRepository,
            VerificationTokenGenerator tokenGenerator,
            PasswordEncoder passwordEncoder,
            PasswordResetProperties properties,
            RateLimiterService rateLimiterService,
            TurnstileService turnstileService,
            SessionRevocationService sessionRevocationService,
            StompSessionRegistry stompSessionRegistry,
            ApplicationEventPublisher eventPublisher,
            AuthMetrics metrics,
            Clock clock) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.accessTokenRepository = accessTokenRepository;
        this.tokenGenerator = tokenGenerator;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.rateLimiterService = rateLimiterService;
        this.turnstileService = turnstileService;
        this.sessionRevocationService = sessionRevocationService;
        this.stompSessionRegistry = stompSessionRegistry;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Emails a reset link when the address belongs to an active account. The response, and the work done on the
     * request thread, are the same either way: the email itself is sent asynchronously after commit.
     *
     * @param clientIp caller IP as resolved by the servlet container, forwarded to Turnstile Siteverify
     */
    @Transactional
    public MessageResponse forgotPassword(ForgotPasswordRequest request, String clientIp) {
        turnstileService.verify(request.turnstileToken(), clientIp);

        String email = InputSanitizer.normalizeEmail(request.email());
        // Per-address limit on top of the per-IP interceptor: stops mail-bombing one inbox from many IPs.
        rateLimiterService.checkRateLimit(RateLimitProperties.FORGOT_PASSWORD_PER_EMAIL, email);

        // Generated whether or not an account exists, so both paths do the same work before the lookup result.
        String rawToken = tokenGenerator.generateRawToken();
        String tokenHash = tokenGenerator.hash(rawToken);

        // The user row lock serialises concurrent requests for one account, so exactly one link stays open
        // (backed by the uq_verification_tokens_open_password_reset partial unique index).
        Optional<UserEntity> account = userRepository.findByEmail(email).filter(UserEntity::isActive)
                .flatMap(found -> userRepository.findByIdForUpdate(found.getId()));
        if (account.isPresent()) {
            UserEntity user = account.get();
            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.getTokenTtl());
            tokenRepository.invalidateActiveTokens(user.getId(), VerificationTokenType.PASSWORD_RESET, now);
            tokenRepository.save(new VerificationTokenEntity(
                    user, tokenHash, VerificationTokenType.PASSWORD_RESET, expiresAt));
            eventPublisher.publishEvent(new PasswordResetEmailRequestedEvent(
                    user.getId(), user.getEmail(), user.getFullName(), rawToken, expiresAt));
            metrics.passwordReset("requested");
            log.info("Issued password reset token for user {}", user.getId());
        } else {
            metrics.passwordReset("skipped");
            log.debug("Password reset requested for {} with no active account", LogMasking.maskEmail(email));
        }
        return new MessageResponse(FORGOT_MESSAGE);
    }

    /**
     * Consumes a reset token and sets the new password.
     *
     * @throws PasswordResetTokenInvalidException token unknown, used or superseded (HTTP 400)
     * @throws PasswordResetTokenExpiredException token genuine but expired (HTTP 410)
     */
    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        Instant now = clock.instant();
        VerificationTokenEntity token = tokenRepository.findByTokenHash(tokenGenerator.hash(request.token().trim()))
                .filter(t -> t.getType() == VerificationTokenType.PASSWORD_RESET)
                .orElseThrow(() -> invalid("invalid"));
        if (token.isUsed()) {
            throw invalid("invalid");
        }
        if (token.isExpired(now)) {
            metrics.passwordReset("expired");
            throw new PasswordResetTokenExpiredException();
        }

        UUID userId = token.getUser().getId();
        // Conditional update: only one of several concurrent requests can consume the token.
        if (tokenRepository.markUsed(token.getId(), now) == 0) {
            throw invalid("invalid");
        }
        UserEntity user = userRepository.findById(userId).filter(UserEntity::isActive)
                .orElseThrow(() -> invalid("invalid"));

        user.changePassword(passwordEncoder.encode(request.newPassword()), now);
        userRepository.saveAndFlush(user);
        tokenRepository.invalidateActiveTokens(userId, VerificationTokenType.PASSWORD_RESET, now);
        int revokedAccessTokens = accessTokenRepository.revokeAllForUser(userId, now);
        signOutEverywhereAfterCommit(userId);
        eventPublisher.publishEvent(new PasswordChangedEmailRequestedEvent(
                userId, user.getEmail(), user.getFullName(), now));

        metrics.passwordReset("success");
        log.info("User {} reset their password; sessions signed out and {} access tokens revoked",
                userId, revokedAccessTokens);
        return new MessageResponse(RESET_MESSAGE);
    }

    private PasswordResetTokenInvalidException invalid(String result) {
        metrics.passwordReset(result);
        return new PasswordResetTokenInvalidException();
    }

    /**
     * Once the new token version is committed: drop this instance's cached version and close the user's open
     * STOMP sockets here (other instances catch up through the cache TTL and the SUBSCRIBE / SEND check).
     */
    private void signOutEverywhereAfterCommit(UUID userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionRevocationService.evict(userId);
                int closed = stompSessionRegistry.closeSessions(userId);
                if (closed > 0) {
                    log.info("Closed {} open STOMP sessions of user {} after password reset", closed, userId);
                }
            }
        });
    }
}
