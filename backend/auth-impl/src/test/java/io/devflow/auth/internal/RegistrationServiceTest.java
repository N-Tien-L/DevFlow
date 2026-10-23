package io.devflow.auth.internal;

import io.devflow.auth.api.MessageResponse;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.api.RegisterResponse;
import io.devflow.auth.api.ResendVerificationRequest;
import io.devflow.auth.api.VerifyEmailRequest;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.UserStatus;
import io.devflow.auth.internal.entity.VerificationTokenEntity;
import io.devflow.auth.internal.entity.VerificationTokenType;
import io.devflow.auth.internal.exception.EmailAlreadyExistsException;
import io.devflow.auth.internal.exception.InvalidFieldException;
import io.devflow.auth.internal.exception.InvalidVerificationTokenException;
import io.devflow.auth.internal.exception.RateLimitExceededException;
import io.devflow.auth.internal.exception.VerificationTokenExpiredException;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.VerificationTokenRepository;
import io.devflow.auth.internal.security.RateLimitProperties;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.verification.VerificationEmailRequestedEvent;
import io.devflow.auth.internal.verification.VerificationProperties;
import io.devflow.auth.internal.verification.VerificationTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistrationService Unit Tests")
class RegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private VerificationTokenRepository tokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AuthMetrics metrics;

    private final VerificationTokenGenerator tokenGenerator = new VerificationTokenGenerator();
    private final VerificationProperties properties = new VerificationProperties();
    private RegistrationService service;

    private final UUID userId = UUID.randomUUID();
    private final String email = "new.user@devflow.io";

    @BeforeEach
    void setUp() {
        service = new RegistrationService(
                userRepository,
                tokenRepository,
                passwordEncoder,
                tokenGenerator,
                properties,
                rateLimiterService,
                eventPublisher,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UserEntity pendingUser() {
        UserEntity user = new UserEntity(email, "$2a$10$hash", "Alex Mercer");
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private VerificationTokenEntity tokenFor(UserEntity user, String rawToken, Instant expiresAt) {
        VerificationTokenEntity token = new VerificationTokenEntity(
                user, tokenGenerator.hash(rawToken), VerificationTokenType.EMAIL_VERIFICATION, expiresAt);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        return token;
    }

    @Nested
    @DisplayName("register()")
    class RegisterTests {

        @Test
        @DisplayName("Creates a pending user, stores only the token hash and publishes the email event")
        void register_success() {
            when(userRepository.existsByEmail(email)).thenReturn(false);
            when(passwordEncoder.encode("StrongP4ssword")).thenReturn("$2a$10$hash");
            when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", userId);
                return saved;
            });

            RegisterResponse response = service.register(
                    new RegisterRequest("  New.User@DevFlow.io ", "StrongP4ssword", "  <b>Alex</b>   Mercer "));

            assertThat(response.email()).isEqualTo(email);
            assertThat(response.status()).isEqualTo("PENDING_VERIFICATION");

            ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).saveAndFlush(userCaptor.capture());
            assertThat(userCaptor.getValue().getEmail()).isEqualTo(email);
            assertThat(userCaptor.getValue().getFullName()).isEqualTo("Alex Mercer");
            assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);

            ArgumentCaptor<VerificationTokenEntity> tokenCaptor = ArgumentCaptor.forClass(VerificationTokenEntity.class);
            verify(tokenRepository).save(tokenCaptor.capture());
            ArgumentCaptor<VerificationEmailRequestedEvent> eventCaptor =
                    ArgumentCaptor.forClass(VerificationEmailRequestedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            VerificationTokenEntity storedToken = tokenCaptor.getValue();
            VerificationEmailRequestedEvent event = eventCaptor.getValue();
            assertThat(storedToken.getTokenHash()).isEqualTo(tokenGenerator.hash(event.rawToken()));
            assertThat(storedToken.getTokenHash()).isNotEqualTo(event.rawToken());
            assertThat(storedToken.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
            assertThat(event.email()).isEqualTo(email);
            assertThat(event.toString()).doesNotContain(event.rawToken());
            verify(metrics).registration("success");
        }

        @Test
        @DisplayName("Rejects a duplicate email with 409 without saving anything")
        void register_duplicateEmail() {
            when(userRepository.existsByEmail(email)).thenReturn(true);

            assertThatThrownBy(() -> service.register(new RegisterRequest(email, "StrongP4ssword", "Alex")))
                    .isInstanceOf(EmailAlreadyExistsException.class);

            verify(userRepository, never()).saveAndFlush(any());
            verifyNoInteractions(tokenRepository, eventPublisher);
        }

        @Test
        @DisplayName("Maps a concurrent unique-constraint violation to EmailAlreadyExistsException")
        void register_concurrentDuplicate() {
            when(userRepository.existsByEmail(email)).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_users_email"));

            assertThatThrownBy(() -> service.register(new RegisterRequest(email, "StrongP4ssword", "Alex")))
                    .isInstanceOf(EmailAlreadyExistsException.class);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Rejects a full name that is empty once HTML is stripped")
        void register_htmlOnlyName() {
            assertThatThrownBy(() -> service.register(
                    new RegisterRequest(email, "StrongP4ssword", "<script></script>")))
                    .isInstanceOf(InvalidFieldException.class)
                    .extracting("field").isEqualTo("fullName");
            verifyNoInteractions(userRepository);
        }
    }

    @Nested
    @DisplayName("verifyEmail()")
    class VerifyEmailTests {

        @Test
        @DisplayName("Consumes a valid token and activates the account")
        void verify_success() {
            UserEntity user = pendingUser();
            VerificationTokenEntity token = tokenFor(user, "raw-token", NOW.plus(Duration.ofHours(1)));
            when(tokenRepository.findByTokenHash(tokenGenerator.hash("raw-token"))).thenReturn(Optional.of(token));
            when(tokenRepository.markUsed(token.getId(), NOW)).thenReturn(1);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            MessageResponse response = service.verifyEmail(new VerifyEmailRequest("raw-token"));

            assertThat(response.message()).isEqualTo(RegistrationService.VERIFIED_MESSAGE);
            assertThat(user.isActive()).isTrue();
            assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW);
            verify(metrics).verification("success");
        }

        @Test
        @DisplayName("Rejects an unknown token with InvalidVerificationTokenException")
        void verify_unknownToken() {
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyEmail(new VerifyEmailRequest("nope")))
                    .isInstanceOf(InvalidVerificationTokenException.class);
        }

        @Test
        @DisplayName("Rejects an already used token")
        void verify_usedToken() {
            VerificationTokenEntity token = tokenFor(pendingUser(), "raw-token", NOW.plus(Duration.ofHours(1)));
            ReflectionTestUtils.setField(token, "usedAt", NOW.minusSeconds(60));
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.verifyEmail(new VerifyEmailRequest("raw-token")))
                    .isInstanceOf(InvalidVerificationTokenException.class);
            verify(tokenRepository, never()).markUsed(any(), any());
        }

        @Test
        @DisplayName("Rejects an expired token with VerificationTokenExpiredException")
        void verify_expiredToken() {
            UserEntity user = pendingUser();
            VerificationTokenEntity token = tokenFor(user, "raw-token", NOW);
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            assertThatThrownBy(() -> service.verifyEmail(new VerifyEmailRequest("raw-token")))
                    .isInstanceOf(VerificationTokenExpiredException.class);
            verify(tokenRepository, never()).markUsed(any(), any());
            assertThat(user.isActive()).isFalse();
            verify(metrics).verification("expired");
        }

        @Test
        @DisplayName("Loses gracefully when a concurrent request consumed the token first")
        void verify_concurrentConsumption() {
            UserEntity user = pendingUser();
            VerificationTokenEntity token = tokenFor(user, "raw-token", NOW.plus(Duration.ofHours(1)));
            when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(tokenRepository.markUsed(token.getId(), NOW)).thenReturn(0);

            assertThatThrownBy(() -> service.verifyEmail(new VerifyEmailRequest("raw-token")))
                    .isInstanceOf(InvalidVerificationTokenException.class);
            verify(userRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("resendVerification()")
    class ResendTests {

        @Test
        @DisplayName("Invalidates previous tokens and issues a new one for a pending account")
        void resend_pendingUser() {
            UserEntity user = pendingUser();
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

            MessageResponse response = service.resendVerification(new ResendVerificationRequest(" New.User@devflow.io"));

            assertThat(response.message()).isEqualTo(RegistrationService.RESEND_MESSAGE);
            verify(rateLimiterService).checkRateLimit(RateLimitProperties.RESEND_VERIFICATION_PER_EMAIL, email);
            verify(tokenRepository).invalidateActiveTokens(userId, VerificationTokenType.EMAIL_VERIFICATION, NOW);
            verify(tokenRepository).save(any(VerificationTokenEntity.class));
            verify(eventPublisher).publishEvent(any(VerificationEmailRequestedEvent.class));
        }

        @Test
        @DisplayName("Returns the same response and sends nothing for an already active account")
        void resend_activeUser() {
            UserEntity user = pendingUser();
            user.activate(NOW);
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

            MessageResponse response = service.resendVerification(new ResendVerificationRequest(email));

            assertThat(response.message()).isEqualTo(RegistrationService.RESEND_MESSAGE);
            verifyNoInteractions(tokenRepository, eventPublisher);
        }

        @Test
        @DisplayName("Returns the same response and sends nothing for an unknown email")
        void resend_unknownEmail() {
            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

            MessageResponse response = service.resendVerification(new ResendVerificationRequest(email));

            assertThat(response.message()).isEqualTo(RegistrationService.RESEND_MESSAGE);
            verifyNoInteractions(tokenRepository, eventPublisher);
        }

        @Test
        @DisplayName("Propagates the per-email rate limit before touching the database")
        void resend_rateLimited() {
            doThrow(new RateLimitExceededException("limited", 60))
                    .when(rateLimiterService).checkRateLimit(eq(RateLimitProperties.RESEND_VERIFICATION_PER_EMAIL), eq(email));

            assertThatThrownBy(() -> service.resendVerification(new ResendVerificationRequest(email)))
                    .isInstanceOf(RateLimitExceededException.class);
            verifyNoInteractions(userRepository, tokenRepository, eventPublisher);
        }
    }
}
