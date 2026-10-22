package io.devflow.auth.internal;

import io.devflow.auth.api.MessageResponse;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.api.RegisterResponse;
import io.devflow.auth.api.ResendVerificationRequest;
import io.devflow.auth.api.VerifyEmailRequest;
import io.devflow.auth.internal.antiabuse.EmailDomainValidator;
import io.devflow.auth.internal.antiabuse.TurnstileService;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.VerificationTokenEntity;
import io.devflow.auth.internal.entity.VerificationTokenType;
import io.devflow.auth.internal.exception.EmailAlreadyExistsException;
import io.devflow.auth.internal.exception.InvalidFieldException;
import io.devflow.auth.internal.exception.InvalidVerificationTokenException;
import io.devflow.auth.internal.exception.VerificationTokenExpiredException;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.VerificationTokenRepository;
import io.devflow.auth.internal.security.RateLimitProperties;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.InputSanitizer;
import io.devflow.auth.internal.support.LogMasking;
import io.devflow.auth.internal.verification.VerificationEmailRequestedEvent;
import io.devflow.auth.internal.verification.VerificationProperties;
import io.devflow.auth.internal.verification.VerificationTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and email verification workflow (T-004A), guarded by anti-abuse checks (T-004B).
 *
 * <p>New accounts start as {@code PENDING_VERIFICATION}. A single-use token (stored as a SHA-256 digest)
 * is emailed after the transaction commits; confirming it activates the account.
 */
@Service
public class RegistrationService {

    static final String REGISTERED_MESSAGE =
            "Registration successful. Please check your email to verify your account.";
    static final String VERIFIED_MESSAGE = "Email verified successfully. You can now sign in.";
    static final String RESEND_MESSAGE =
            "If an unverified account exists for this email, a new verification link has been sent.";

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final VerificationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationTokenGenerator tokenGenerator;
    private final VerificationProperties properties;
    private final RateLimiterService rateLimiterService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthMetrics metrics;
    private final TurnstileService turnstileService;
    private final EmailDomainValidator emailDomainValidator;
    private final Clock clock;

    public RegistrationService(
            UserRepository userRepository,
            VerificationTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            VerificationTokenGenerator tokenGenerator,
            VerificationProperties properties,
            RateLimiterService rateLimiterService,
            ApplicationEventPublisher eventPublisher,
            AuthMetrics metrics,
            TurnstileService turnstileService,
            EmailDomainValidator emailDomainValidator,
            Clock clock) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.rateLimiterService = rateLimiterService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.turnstileService = turnstileService;
        this.emailDomainValidator = emailDomainValidator;
        this.clock = clock;
    }

    /**
     * Creates a {@code PENDING_VERIFICATION} account and schedules the verification email.
     *
     * <p>The Turnstile challenge runs first so bots learn nothing about which emails are registered,
     * then the email domain is screened (disposable providers, missing MX) before any database access.
     *
     * @param clientIp caller IP as resolved by the servlet container, forwarded to Siteverify
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request, String clientIp) {
        turnstileService.verify(request.turnstileToken(), clientIp);

        String email = InputSanitizer.normalizeEmail(request.email());
        String fullName = InputSanitizer.sanitizeDisplayName(request.fullName());
        if (fullName.isEmpty()) {
            throw new InvalidFieldException("fullName", "Full name must contain visible characters");
        }
        emailDomainValidator.validate(email);

        if (userRepository.existsByEmail(email)) {
            metrics.registration("duplicate_email");
            throw new EmailAlreadyExistsException();
        }

        UserEntity user;
        try {
            user = userRepository.saveAndFlush(
                    new UserEntity(email, passwordEncoder.encode(request.password()), fullName));
        } catch (DataIntegrityViolationException e) {
            // Concurrent registration of the same email won the unique constraint race.
            metrics.registration("duplicate_email");
            throw new EmailAlreadyExistsException();
        }

        issueToken(user);
        metrics.registration("success");
        log.info("Registered user {} ({}) pending email verification", user.getId(), LogMasking.maskEmail(email));

        return new RegisterResponse(email, user.getStatus().name(), REGISTERED_MESSAGE);
    }

    /**
     * Consumes a verification token and activates the owning account.
     *
     * @throws InvalidVerificationTokenException token unknown or already used (HTTP 400)
     * @throws VerificationTokenExpiredException token genuine but expired (HTTP 410)
     */
    @Transactional
    public MessageResponse verifyEmail(VerifyEmailRequest request) {
        Instant now = clock.instant();
        VerificationTokenEntity token = tokenRepository.findByTokenHash(tokenGenerator.hash(request.token().trim()))
                .filter(t -> t.getType() == VerificationTokenType.EMAIL_VERIFICATION)
                .orElseThrow(() -> {
                    metrics.verification("invalid");
                    return new InvalidVerificationTokenException();
                });

        if (token.isUsed()) {
            metrics.verification("invalid");
            throw new InvalidVerificationTokenException();
        }
        if (token.isExpired(now)) {
            metrics.verification("expired");
            throw new VerificationTokenExpiredException();
        }

        UUID userId = token.getUser().getId();
        // Conditional update: only one of several concurrent requests can consume the token.
        if (tokenRepository.markUsed(token.getId(), now) == 0) {
            metrics.verification("invalid");
            throw new InvalidVerificationTokenException();
        }

        UserEntity user = userRepository.findById(userId).orElseThrow(InvalidVerificationTokenException::new);
        if (!user.isActive()) {
            user.activate(now);
        }

        metrics.verification("success");
        log.info("User {} verified email and is now active", userId);
        return new MessageResponse(VERIFIED_MESSAGE);
    }

    /**
     * Re-issues a verification email. The response is identical whether or not the account exists or
     * is already verified, so this endpoint cannot be used to enumerate registered emails.
     */
    @Transactional
    public MessageResponse resendVerification(ResendVerificationRequest request) {
        String email = InputSanitizer.normalizeEmail(request.email());
        // Per-address limit on top of the per-IP interceptor: stops mail-bombing one inbox from many IPs.
        rateLimiterService.checkRateLimit(RateLimitProperties.RESEND_VERIFICATION_PER_EMAIL, email);

        Optional<UserEntity> pendingUser = userRepository.findByEmail(email).filter(u -> !u.isActive());
        if (pendingUser.isPresent()) {
            UserEntity user = pendingUser.get();
            tokenRepository.invalidateActiveTokens(user.getId(), VerificationTokenType.EMAIL_VERIFICATION,
                    clock.instant());
            issueToken(user);
            metrics.resendVerification("sent");
            log.info("Re-issued verification token for user {}", user.getId());
        } else {
            metrics.resendVerification("skipped");
            log.debug("Resend requested for {} with no pending account", LogMasking.maskEmail(email));
        }

        return new MessageResponse(RESEND_MESSAGE);
    }

    private void issueToken(UserEntity user) {
        String rawToken = tokenGenerator.generateRawToken();
        Instant expiresAt = clock.instant().plus(properties.getTokenTtl());
        tokenRepository.save(new VerificationTokenEntity(
                user, tokenGenerator.hash(rawToken), VerificationTokenType.EMAIL_VERIFICATION, expiresAt));
        eventPublisher.publishEvent(new VerificationEmailRequestedEvent(
                user.getId(), user.getEmail(), user.getFullName(), rawToken, expiresAt));
    }
}
