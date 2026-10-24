package io.devflow.auth.internal;

import io.devflow.auth.api.CreatePersonalAccessTokenRequest;
import io.devflow.auth.api.CreatedPersonalAccessTokenResponse;
import io.devflow.auth.api.PersonalAccessTokenSummary;
import io.devflow.auth.internal.entity.PersonalAccessTokenEntity;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.exception.InvalidFieldException;
import io.devflow.auth.internal.exception.ResourceNotFoundException;
import io.devflow.auth.internal.exception.ConflictException;
import io.devflow.auth.internal.pat.PersonalAccessTokenCodec;
import io.devflow.auth.internal.pat.PersonalAccessTokenProperties;
import io.devflow.auth.internal.repository.PersonalAccessTokenRepository;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.security.RateLimitProperties;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.InputSanitizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal access tokens for MCP clients (T-038): create (shown once), list and revoke for the owner,
 * and authentication of tokens presented to the MCP endpoints.
 */
@Service
public class PersonalAccessTokenService {

    static final int MAX_PREFIX_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(PersonalAccessTokenService.class);

    private final PersonalAccessTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PersonalAccessTokenCodec codec;
    private final PersonalAccessTokenProperties properties;
    private final RateLimiterService rateLimiterService;
    private final AuthMetrics metrics;
    private final Clock clock;

    public PersonalAccessTokenService(
            PersonalAccessTokenRepository tokenRepository,
            UserRepository userRepository,
            PersonalAccessTokenCodec codec,
            PersonalAccessTokenProperties properties,
            RateLimiterService rateLimiterService,
            AuthMetrics metrics,
            Clock clock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.codec = codec;
        this.properties = properties;
        this.rateLimiterService = rateLimiterService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Creates a token for the caller. The response is the only place the raw token ever appears.
     * The caller's user row is locked so concurrent requests cannot exceed the per-user quota.
     */
    @Transactional
    public CreatedPersonalAccessTokenResponse create(UUID actorId, CreatePersonalAccessTokenRequest request) {
        rateLimiterService.checkRateLimit(RateLimitProperties.PAT_CREATE, actorId.toString());

        String name = InputSanitizer.sanitizeDisplayName(request.name());
        if (name.isEmpty()) {
            throw new InvalidFieldException("name", "Token name must contain visible characters");
        }

        Instant now = clock.instant();
        UserEntity owner = userRepository.findByIdForUpdate(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
        if (tokenRepository.countLiveTokens(actorId, now) >= properties.getMaxActivePerUser()) {
            metrics.personalAccessTokenCreated("limit_reached");
            throw new ConflictException("TOKEN_LIMIT_REACHED",
                    "You can have at most " + properties.getMaxActivePerUser()
                            + " active access tokens. Revoke one before creating another.");
        }

        PersonalAccessTokenCodec.GeneratedToken generated = uniqueToken();
        Instant expiresAt = request.expiresInDays() == null
                ? null
                : now.plus(Duration.ofDays(request.expiresInDays()));
        PersonalAccessTokenEntity token = tokenRepository.saveAndFlush(new PersonalAccessTokenEntity(
                owner, name, generated.publicPrefix(), generated.hash(), expiresAt));

        metrics.personalAccessTokenCreated("success");
        log.info("User {} created access token {} ({})", actorId, token.getId(), token.getTokenPrefix());
        return new CreatedPersonalAccessTokenResponse(token.getId(), token.getName(), generated.raw(),
                token.getTokenPrefix(), token.getExpiresAt(), token.getCreatedAt());
    }

    /**
     * Lists the caller's unrevoked tokens (expired ones flagged), newest first.
     */
    @Transactional(readOnly = true)
    public List<PersonalAccessTokenSummary> list(UUID actorId) {
        rateLimiterService.checkRateLimit(RateLimitProperties.PAT_MANAGE, actorId.toString());
        Instant now = clock.instant();
        return tokenRepository.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(actorId).stream()
                .map(token -> new PersonalAccessTokenSummary(token.getId(), token.getName(), token.getTokenPrefix(),
                        token.getExpiresAt(), token.getLastUsedAt(), token.getCreatedAt(), token.isExpired(now)))
                .toList();
    }

    /**
     * Revokes one of the caller's tokens; effective for the very next MCP request.
     *
     * @throws ResourceNotFoundException the token does not exist, belongs to another user or is already revoked
     */
    @Transactional
    public void revoke(UUID actorId, UUID tokenId) {
        rateLimiterService.checkRateLimit(RateLimitProperties.PAT_MANAGE, actorId.toString());
        if (tokenRepository.revoke(tokenId, actorId, clock.instant()) == 0) {
            metrics.personalAccessTokenRevoked("not_found");
            throw new ResourceNotFoundException("Access token not found.");
        }
        metrics.personalAccessTokenRevoked("success");
        log.info("User {} revoked access token {}", actorId, tokenId);
    }

    /**
     * Resolves a presented token to its owner. Every failure yields an empty result (the caller answers 401
     * without saying why) and consumes the client IP's failure budget, so tokens cannot be brute-forced.
     *
     * @throws io.devflow.auth.internal.exception.RateLimitExceededException the token or the IP is over its limit
     */
    @Transactional
    public Optional<AuthenticatedToken> authenticate(String rawToken, String clientIp) {
        Optional<String> prefix = codec.publicPrefix(rawToken);
        if (prefix.isEmpty()) {
            return fail("malformed", clientIp);
        }
        Optional<PersonalAccessTokenEntity> found = tokenRepository.findByTokenPrefix(prefix.get());
        if (found.isEmpty() || !codec.matches(rawToken, found.get().getTokenHash())) {
            return fail("invalid", clientIp);
        }

        PersonalAccessTokenEntity token = found.get();
        Instant now = clock.instant();
        if (token.isRevoked()) {
            return fail("revoked", clientIp);
        }
        if (token.isExpired(now)) {
            return fail("expired", clientIp);
        }
        UserEntity user = token.getUser();
        if (!user.isActive()) {
            return fail("inactive_user", clientIp);
        }

        try {
            rateLimiterService.checkRateLimit(RateLimitProperties.PAT_USE, token.getId().toString());
        } catch (RuntimeException e) {
            metrics.personalAccessTokenAuth("rate_limited");
            throw e;
        }

        Instant staleBefore = now.minus(properties.getLastUsedUpdateInterval());
        if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(staleBefore)) {
            tokenRepository.touchLastUsed(token.getId(), now, staleBefore);
        }
        metrics.personalAccessTokenAuth("success");
        return Optional.of(new AuthenticatedToken(user.getId(), user.getEmail(), token.getId()));
    }

    private Optional<AuthenticatedToken> fail(String reason, String clientIp) {
        metrics.personalAccessTokenAuth(reason);
        log.debug("Rejected access token on MCP request: {}", reason);
        rateLimiterService.checkRateLimit(RateLimitProperties.PAT_AUTH_FAILURE, clientIp);
        return Optional.empty();
    }

    private PersonalAccessTokenCodec.GeneratedToken uniqueToken() {
        for (int attempt = 0; attempt < MAX_PREFIX_ATTEMPTS; attempt++) {
            PersonalAccessTokenCodec.GeneratedToken generated = codec.generate();
            if (!tokenRepository.existsByTokenPrefix(generated.publicPrefix())) {
                return generated;
            }
        }
        // 48-bit random lookup keys: reaching this means the RNG is broken, not bad luck.
        throw new IllegalStateException("Could not allocate a unique access token prefix");
    }

    /**
     * Identity resolved from a valid personal access token.
     */
    public record AuthenticatedToken(UUID userId, String email, UUID tokenId) {
    }
}
