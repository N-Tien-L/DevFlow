package io.devflow.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.api.LoginRequest;
import io.devflow.auth.api.RefreshTokenRequest;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.entity.WorkspaceRole;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.auth.internal.security.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = TestAuthApplication.class)
@AutoConfigureMockMvc
@DisplayName("AuthController & RFC 7807 Integration Tests")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

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

    @BeforeEach
    void cleanUp() {
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
        rateLimiterService.clearAll();
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class RegisterEndpointTests {

        @Test
        @DisplayName("TC-01: Valid registration returns 201 Created with JWT tokens and user summary")
        void register_validRequest_returns201() throws Exception {
            RegisterRequest request = new RegisterRequest("new.user@devflow.io", "StrongP@ssw0rd!", "Alex Mercer");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessToken", notNullValue()))
                    .andExpect(jsonPath("$.refreshToken", notNullValue()))
                    .andExpect(jsonPath("$.tokenType", is("Bearer")))
                    .andExpect(jsonPath("$.user.email", is("new.user@devflow.io")))
                    .andExpect(jsonPath("$.user.fullName", is("Alex Mercer")));
        }

        @Test
        @DisplayName("TC-02: Duplicate email returns 409 Conflict ProblemDetail")
        void register_duplicateEmail_returns409() throws Exception {
            UserEntity existing = new UserEntity("existing@devflow.io", passwordEncoder.encode("Password123!"), "Existing");
            userRepository.save(existing);

            RegisterRequest request = new RegisterRequest("existing@devflow.io", "StrongP@ssw0rd!", "Duplicate Person");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title", is("Conflict")))
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.detail", containsString("already exists")));
        }

        @Test
        @DisplayName("TC-03 & TC-04 & TC-05: Validation failure returns 400 Bad Request ProblemDetail with field errors")
        void register_invalidInputs_returns400WithErrors() throws Exception {
            RegisterRequest request = new RegisterRequest("invalid-email", "short", "");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title", is("Validation Failed")))
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.errors.email", notNullValue()))
                    .andExpect(jsonPath("$.errors.password", notNullValue()))
                    .andExpect(jsonPath("$.errors.fullName", notNullValue()));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginEndpointTests {

        @Test
        @DisplayName("TC-06: Valid login credentials return 200 OK with tokens")
        void login_validCredentials_returns200() throws Exception {
            UserEntity user = new UserEntity("login.user@devflow.io", passwordEncoder.encode("CorrectPassword123!"), "Login User");
            userRepository.save(user);

            LoginRequest request = new LoginRequest("login.user@devflow.io", "CorrectPassword123!");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken", notNullValue()))
                    .andExpect(jsonPath("$.refreshToken", notNullValue()))
                    .andExpect(jsonPath("$.user.email", is("login.user@devflow.io")));
        }

        @Test
        @DisplayName("TC-07 & TC-08: Bad credentials return 401 Unauthorized ProblemDetail without leaking stack traces")
        void login_invalidCredentials_returns401() throws Exception {
            LoginRequest request = new LoginRequest("nonexistent@devflow.io", "WrongPassword");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title", is("Unauthorized")))
                    .andExpect(jsonPath("$.status", is(401)))
                    .andExpect(jsonPath("$.detail", is("Invalid email or password")))
                    .andExpect(jsonPath("$.stackTrace").doesNotExist());
        }

        @Test
        @DisplayName("TC-09: Missing fields return 400 Bad Request ProblemDetail")
        void login_blankFields_returns400() throws Exception {
            LoginRequest request = new LoginRequest("", "");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
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
                mockMvc.perform(post("/api/v1/auth/login")
                                .with(req -> {
                                    req.setRemoteAddr(clientIp);
                                    return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isUnauthorized());
            }

            // 6th attempt must be rejected by RateLimitingInterceptor with HTTP 429
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(clientIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
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
            UserEntity user = new UserEntity("refresh.user@devflow.io", passwordEncoder.encode("Pass12345!"), "Refresh User");
            user = userRepository.save(user);

            String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail());
            RefreshTokenRequest request = new RefreshTokenRequest(refreshToken);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken", notNullValue()))
                    .andExpect(jsonPath("$.refreshToken", notNullValue()))
                    .andExpect(jsonPath("$.user.email", is("refresh.user@devflow.io")));
        }

        @Test
        @DisplayName("TC-12: Malformed or expired refresh token returns 401 Unauthorized ProblemDetail")
        void refresh_invalidToken_returns401() throws Exception {
            RefreshTokenRequest request = new RefreshTokenRequest("invalid.jwt.token");

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title", is("Unauthorized")))
                    .andExpect(jsonPath("$.status", is(401)))
                    .andExpect(jsonPath("$.detail", containsString("Invalid or expired refresh token")));
        }

        @Test
        @DisplayName("TC-13: Access token passed to refresh endpoint returns 401 Unauthorized")
        void refresh_accessTokenUsedAsRefresh_returns401() throws Exception {
            UserEntity user = new UserEntity("access.type@devflow.io", passwordEncoder.encode("Pass12345!"), "Access Type");
            user = userRepository.save(user);

            String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
            RefreshTokenRequest request = new RefreshTokenRequest(accessToken);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
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
            UserEntity user = new UserEntity("me.user@devflow.io", passwordEncoder.encode("Pass12345!"), "Me User");
            user = userRepository.save(user);

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
            UserEntity user = new UserEntity("workspace.user@devflow.io", passwordEncoder.encode("Pass12345!"), "WS User");
            user = userRepository.save(user);

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
            UserEntity user = new UserEntity("lonely.user@devflow.io", passwordEncoder.encode("Pass12345!"), "Lonely User");
            user = userRepository.save(user);

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
