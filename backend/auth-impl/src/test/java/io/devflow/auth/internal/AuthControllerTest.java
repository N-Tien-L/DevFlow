package io.devflow.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.api.LoginRequest;
import io.devflow.auth.api.RefreshTokenRequest;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.api.ResendVerificationRequest;
import io.devflow.auth.api.VerifyEmailRequest;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.UserStatus;
import io.devflow.auth.internal.entity.VerificationTokenEntity;
import io.devflow.auth.internal.entity.VerificationTokenType;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.entity.WorkspaceRole;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.VerificationTokenRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.verification.VerificationEmailRequestedEvent;
import io.devflow.auth.internal.verification.VerificationTokenGenerator;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = TestAuthApplication.class)
@AutoConfigureMockMvc
@RecordApplicationEvents
@DisplayName("AuthController & RFC 7807 Integration Tests")
class AuthControllerTest {

    private static final String STRONG_PASSWORD = "StrongP4ssword!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationTokenRepository tokenRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private VerificationTokenGenerator tokenGenerator;

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void cleanUp() {
        tokenRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
        rateLimiterService.clearAll();
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    }

    private UserEntity saveActiveUser(String email, String rawPassword, String fullName) {
        UserEntity user = new UserEntity(email, passwordEncoder.encode(rawPassword), fullName);
        user.activate(Instant.now());
        return userRepository.save(user);
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions postJsonFrom(String path, Object body, String clientIp) throws Exception {
        return mockMvc.perform(post(path)
                .with(req -> {
                    req.setRemoteAddr(clientIp);
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Raw tokens only exist in the published event (the database holds the hash). */
    private String lastIssuedRawToken() {
        List<VerificationEmailRequestedEvent> events =
                applicationEvents.stream(VerificationEmailRequestedEvent.class).toList();
        assertThat(events).isNotEmpty();
        return events.get(events.size() - 1).rawToken();
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class RegisterEndpointTests {

        @Test
        @DisplayName("TC-01: Valid registration returns 201 with PENDING_VERIFICATION and no tokens, then emails a link")
        void register_validRequest_returns201() throws Exception {
            RegisterRequest request = new RegisterRequest("New.User@DevFlow.io", STRONG_PASSWORD, "Alex Mercer");

            postJson("/api/v1/auth/register", request)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email", is("new.user@devflow.io")))
                    .andExpect(jsonPath("$.status", is("PENDING_VERIFICATION")))
                    .andExpect(jsonPath("$.message", containsString("verify")))
                    .andExpect(jsonPath("$.accessToken").doesNotExist())
                    .andExpect(jsonPath("$.refreshToken").doesNotExist());

            UserEntity saved = userRepository.findByEmail("new.user@devflow.io").orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
            assertThat(saved.getPasswordHash()).isNotEqualTo(STRONG_PASSWORD);

            String rawToken = lastIssuedRawToken();
            VerificationTokenEntity token = tokenRepository.findByTokenHash(tokenGenerator.hash(rawToken)).orElseThrow();
            assertThat(token.getUser().getId()).isEqualTo(saved.getId());

            ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
            verify(mailSender, timeout(5000)).send(mail.capture());
            assertThat(mail.getValue().getAllRecipients()[0].toString()).isEqualTo("new.user@devflow.io");
            assertThat(mail.getValue().getSubject()).contains("Verify");
        }

        @Test
        @DisplayName("TC-02: Duplicate email returns 409 Conflict ProblemDetail")
        void register_duplicateEmail_returns409() throws Exception {
            saveActiveUser("existing@devflow.io", "Password123!", "Existing");

            RegisterRequest request = new RegisterRequest("EXISTING@devflow.io", STRONG_PASSWORD, "Duplicate Person");

            postJson("/api/v1/auth/register", request)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title", is("Conflict")))
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.detail", containsString("already exists")));
        }

        @Test
        @DisplayName("TC-03 & TC-04 & TC-05: Validation failure returns 400 Bad Request ProblemDetail with field errors")
        void register_invalidInputs_returns400WithErrors() throws Exception {
            RegisterRequest request = new RegisterRequest("invalid-email", "short", "");

            postJson("/api/v1/auth/register", request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title", is("Validation Failed")))
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.errors.email", notNullValue()))
                    .andExpect(jsonPath("$.errors.password", notNullValue()))
                    .andExpect(jsonPath("$.errors.fullName", notNullValue()));
        }

        @Test
        @DisplayName("Weak password without upper/lower/digit mix is rejected")
        void register_weakPassword_returns400() throws Exception {
            postJson("/api/v1/auth/register", new RegisterRequest("weak@devflow.io", "alllowercase", "Weak"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.password", containsString("uppercase")));
        }

        @Test
        @DisplayName("Disposable email domain returns 400 with code DISPOSABLE_EMAIL and creates no account")
        void register_disposableEmail_returns400() throws Exception {
            postJson("/api/v1/auth/register",
                    new RegisterRequest("bot@Mailinator.com", STRONG_PASSWORD, "Bot"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("DISPOSABLE_EMAIL")))
                    .andExpect(jsonPath("$.errors.email", containsString("Disposable")));

            assertThat(userRepository.findByEmail("bot@mailinator.com")).isEmpty();
        }

        @Test
        @DisplayName("Oversized Turnstile token is rejected by Bean Validation")
        void register_oversizedTurnstileToken_returns400() throws Exception {
            postJson("/api/v1/auth/register",
                    new RegisterRequest("token@devflow.io", STRONG_PASSWORD, "Token", "x".repeat(2049)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.turnstileToken", notNullValue()));
        }

        @Test
        @DisplayName("Malformed JSON body returns 400 ProblemDetail instead of 500")
        void register_malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.detail", is("Request body is missing or malformed")));
        }

        @Test
        @DisplayName("Registration is limited to 3 requests per 10 minutes per IP")
        void register_rateLimitPerIp_returns429() throws Exception {
            for (int i = 0; i < 3; i++) {
                postJsonFrom("/api/v1/auth/register",
                        new RegisterRequest("burst" + i + "@devflow.io", STRONG_PASSWORD, "Burst"), "10.0.0.7")
                        .andExpect(status().isCreated());
            }

            postJsonFrom("/api/v1/auth/register",
                    new RegisterRequest("burst3@devflow.io", STRONG_PASSWORD, "Burst"), "10.0.0.7")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        }

        @Test
        @DisplayName("A spoofed X-Forwarded-For header does not reset the per-IP limit")
        void register_spoofedForwardedFor_doesNotBypassLimit() throws Exception {
            for (int i = 0; i < 4; i++) {
                int attempt = i;
                ResultActions result = mockMvc.perform(post("/api/v1/auth/register")
                        .with(req -> {
                            req.setRemoteAddr("10.0.0.8");
                            return req;
                        })
                        .header("X-Forwarded-For", "203.0.113." + attempt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("spoof" + attempt + "@devflow.io", STRONG_PASSWORD, "Spoof"))));
                if (attempt < 3) {
                    result.andExpect(status().isCreated());
                } else {
                    result.andExpect(status().isTooManyRequests());
                }
            }
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/verify-email")
    class VerifyEmailEndpointTests {

        @Test
        @DisplayName("Full flow: register -> login blocked (403) -> verify (200) -> login succeeds")
        void verify_fullFlow() throws Exception {
            postJson("/api/v1/auth/register", new RegisterRequest("flow@devflow.io", STRONG_PASSWORD, "Flow User"))
                    .andExpect(status().isCreated());

            postJson("/api/v1/auth/login", new LoginRequest("flow@devflow.io", STRONG_PASSWORD))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("EMAIL_NOT_VERIFIED")));

            postJson("/api/v1/auth/verify-email", new VerifyEmailRequest(lastIssuedRawToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message", containsString("verified")));

            UserEntity user = userRepository.findByEmail("flow@devflow.io").orElseThrow();
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getEmailVerifiedAt()).isNotNull();

            postJson("/api/v1/auth/login", new LoginRequest("flow@devflow.io", STRONG_PASSWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken", notNullValue()));
        }

        @Test
        @DisplayName("A token can only be used once")
        void verify_reusedToken_returns400() throws Exception {
            postJson("/api/v1/auth/register", new RegisterRequest("once@devflow.io", STRONG_PASSWORD, "Once"))
                    .andExpect(status().isCreated());
            String rawToken = lastIssuedRawToken();

            postJson("/api/v1/auth/verify-email", new VerifyEmailRequest(rawToken)).andExpect(status().isOk());
            postJson("/api/v1/auth/verify-email", new VerifyEmailRequest(rawToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VERIFICATION_TOKEN_INVALID")));
        }

        @Test
        @DisplayName("Expired token returns 410 Gone and leaves the account pending")
        void verify_expiredToken_returns410() throws Exception {
            UserEntity user = userRepository.save(
                    new UserEntity("expired@devflow.io", passwordEncoder.encode(STRONG_PASSWORD), "Expired"));
            String rawToken = tokenGenerator.generateRawToken();
            tokenRepository.save(new VerificationTokenEntity(user, tokenGenerator.hash(rawToken),
                    VerificationTokenType.EMAIL_VERIFICATION, Instant.now().minusSeconds(60)));

            postJson("/api/v1/auth/verify-email", new VerifyEmailRequest(rawToken))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.status", is(410)))
                    .andExpect(jsonPath("$.code", is("VERIFICATION_TOKEN_EXPIRED")));

            assertThat(userRepository.findById(user.getId()).orElseThrow().isActive()).isFalse();
        }

        @Test
        @DisplayName("Unknown token returns 400; blank token returns validation error")
        void verify_unknownOrBlankToken_returns400() throws Exception {
            postJson("/api/v1/auth/verify-email", new VerifyEmailRequest("not-a-real-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VERIFICATION_TOKEN_INVALID")));

            postJson("/api/v1/auth/verify-email", new VerifyEmailRequest(""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.token", notNullValue()));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/resend-verification")
    class ResendVerificationEndpointTests {

        @Test
        @DisplayName("Pending account gets a fresh token and the previous one stops working")
        void resend_pendingAccount_rotatesToken() throws Exception {
            postJson("/api/v1/auth/register", new RegisterRequest("resend@devflow.io", STRONG_PASSWORD, "Resend"))
                    .andExpect(status().isCreated());
            String firstToken = lastIssuedRawToken();

            postJson("/api/v1/auth/resend-verification", new ResendVerificationRequest("resend@devflow.io"))
                    .andExpect(status().isAccepted());
            String secondToken = lastIssuedRawToken();
            assertThat(secondToken).isNotEqualTo(firstToken);

            postJson("/api/v1/auth/verify-email", new VerifyEmailRequest(firstToken))
                    .andExpect(status().isBadRequest());
            postJson("/api/v1/auth/verify-email", new VerifyEmailRequest(secondToken))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Unknown and already-active emails get the identical 202 response (no enumeration)")
        void resend_unknownOrActive_sameResponse() throws Exception {
            saveActiveUser("active@devflow.io", STRONG_PASSWORD, "Active");

            String unknown = postJson("/api/v1/auth/resend-verification", new ResendVerificationRequest("ghost@devflow.io"))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String active = postJson("/api/v1/auth/resend-verification", new ResendVerificationRequest("active@devflow.io"))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();

            assertThat(active).isEqualTo(unknown);
            assertThat(applicationEvents.stream(VerificationEmailRequestedEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("Resend is limited to 3 per hour per email address, even across IPs")
        void resend_rateLimitPerEmail_returns429() throws Exception {
            ResendVerificationRequest request = new ResendVerificationRequest("victim@devflow.io");
            for (int i = 0; i < 3; i++) {
                postJsonFrom("/api/v1/auth/resend-verification", request, "10.1.0." + i)
                        .andExpect(status().isAccepted());
            }

            postJsonFrom("/api/v1/auth/resend-verification", request, "10.1.0.99")
                    .andExpect(status().isTooManyRequests());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginEndpointTests {

        @Test
        @DisplayName("TC-06: Valid login credentials return 200 OK with tokens")
        void login_validCredentials_returns200() throws Exception {
            saveActiveUser("login.user@devflow.io", "CorrectPassword123!", "Login User");

            LoginRequest request = new LoginRequest("login.user@devflow.io", "CorrectPassword123!");

            postJson("/api/v1/auth/login", request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken", notNullValue()))
                    .andExpect(jsonPath("$.refreshToken", notNullValue()))
                    .andExpect(jsonPath("$.user.email", is("login.user@devflow.io")));
        }

        @Test
        @DisplayName("TC-07 & TC-08: Bad credentials return 401 Unauthorized ProblemDetail without leaking stack traces")
        void login_invalidCredentials_returns401() throws Exception {
            LoginRequest request = new LoginRequest("nonexistent@devflow.io", "WrongPassword");

            postJson("/api/v1/auth/login", request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title", is("Unauthorized")))
                    .andExpect(jsonPath("$.status", is(401)))
                    .andExpect(jsonPath("$.detail", is("Invalid email or password")))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        }

        @Test
        @DisplayName("Unverified account with wrong password gets 401, not 403 (state not disclosed)")
        void login_unverifiedWrongPassword_returns401() throws Exception {
            userRepository.save(new UserEntity("pending@devflow.io", passwordEncoder.encode(STRONG_PASSWORD), "Pending"));

            postJson("/api/v1/auth/login", new LoginRequest("pending@devflow.io", "WrongPassword1"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("TC-09: Missing fields return 400 Bad Request ProblemDetail")
        void login_blankFields_returns400() throws Exception {
            LoginRequest request = new LoginRequest("", "");

            postJson("/api/v1/auth/login", request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.errors.email", notNullValue()))
                    .andExpect(jsonPath("$.errors.password", notNullValue()));
        }

        @Test
        @DisplayName("TC-10: Rate limiting blocks after 5 requests with 429 Too Many Requests and Retry-After header")
        void login_rateLimitExceeded_returns429() throws Exception {
            LoginRequest request = new LoginRequest("attacker@devflow.io", "GuessPassword");
            String clientIp = "192.168.1.100";

            // First 5 attempts consumed (they return 401 since user doesn't exist)
            for (int i = 0; i < 5; i++) {
                postJsonFrom("/api/v1/auth/login", request, clientIp)
                        .andExpect(status().isUnauthorized());
            }

            // 6th attempt must be rejected by RateLimitingInterceptor with HTTP 429
            postJsonFrom("/api/v1/auth/login", request, clientIp)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                    .andExpect(jsonPath("$.title", is("Too Many Requests")))
                    .andExpect(jsonPath("$.status", is(429)))
                    .andExpect(jsonPath("$.detail", containsString("Rate limit exceeded")));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/refresh")
    class RefreshEndpointTests {

        @Test
        @DisplayName("TC-11: Valid refresh token issues new tokens with 200 OK")
        void refresh_validToken_returns200() throws Exception {
            UserEntity user = saveActiveUser("refresh.user@devflow.io", "Pass12345!", "Refresh User");

            String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail());
            RefreshTokenRequest request = new RefreshTokenRequest(refreshToken);

            postJson("/api/v1/auth/refresh", request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken", notNullValue()))
                    .andExpect(jsonPath("$.refreshToken", notNullValue()))
                    .andExpect(jsonPath("$.user.email", is("refresh.user@devflow.io")));
        }

        @Test
        @DisplayName("TC-12: Malformed or expired refresh token returns 401 Unauthorized ProblemDetail")
        void refresh_invalidToken_returns401() throws Exception {
            RefreshTokenRequest request = new RefreshTokenRequest("invalid.jwt.token");

            postJson("/api/v1/auth/refresh", request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title", is("Unauthorized")))
                    .andExpect(jsonPath("$.status", is(401)))
                    .andExpect(jsonPath("$.detail", containsString("Invalid or expired refresh token")));
        }

        @Test
        @DisplayName("TC-13: Access token passed to refresh endpoint returns 401 Unauthorized")
        void refresh_accessTokenUsedAsRefresh_returns401() throws Exception {
            UserEntity user = saveActiveUser("access.type@devflow.io", "Pass12345!", "Access Type");

            String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
            RefreshTokenRequest request = new RefreshTokenRequest(accessToken);

            postJson("/api/v1/auth/refresh", request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status", is(401)))
                    .andExpect(jsonPath("$.detail", containsString("not a refresh token")));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/auth/me")
    class MeEndpointTests {

        @Test
        @DisplayName("TC-15: Authenticated request returns current UserSummary")
        void me_authenticated_returns200() throws Exception {
            UserEntity user = saveActiveUser("me.user@devflow.io", "Pass12345!", "Me User");

            String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());

            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(user.getId().toString())))
                    .andExpect(jsonPath("$.email", is("me.user@devflow.io")))
                    .andExpect(jsonPath("$.fullName", is("Me User")));
        }

        @Test
        @DisplayName("TC-16: Unauthenticated request returns 401 Unauthorized ProblemDetail")
        void me_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status", is(401)))
                    .andExpect(jsonPath("$.title", is("Unauthorized")));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/auth/workspaces")
    class WorkspacesEndpointTests {

        @Test
        @DisplayName("TC-18: Authenticated user returns joined workspaces with roles")
        void workspaces_authenticatedWithMemberships_returns200() throws Exception {
            UserEntity user = saveActiveUser("workspace.user@devflow.io", "Pass12345!", "WS User");

            WorkspaceEntity ws = new WorkspaceEntity("Core Platform", "core-platform", user);
            ws = workspaceRepository.save(ws);

            WorkspaceMemberEntity member = new WorkspaceMemberEntity(ws, user, WorkspaceRole.OWNER);
            workspaceMemberRepository.save(member);

            String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());

            mockMvc.perform(get("/api/v1/auth/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name", is("Core Platform")))
                    .andExpect(jsonPath("$[0].slug", is("core-platform")))
                    .andExpect(jsonPath("$[0].role", is("OWNER")));
        }

        @Test
        @DisplayName("TC-19: Authenticated user with no memberships returns empty list")
        void workspaces_authenticatedNoMemberships_returnsEmptyList() throws Exception {
            UserEntity user = saveActiveUser("lonely.user@devflow.io", "Pass12345!", "Lonely User");

            String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());

            mockMvc.perform(get("/api/v1/auth/workspaces")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("TC-20: Unauthenticated request to /workspaces returns 401 Unauthorized")
        void workspaces_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/auth/workspaces"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status", is(401)));
        }
    }
}
