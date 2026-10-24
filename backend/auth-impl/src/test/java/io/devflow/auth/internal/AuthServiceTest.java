package io.devflow.auth.internal;

import io.devflow.auth.api.AuthResponse;
import io.devflow.auth.api.LoginRequest;
import io.devflow.auth.api.RefreshTokenRequest;
import io.devflow.auth.api.UserSummary;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.entity.WorkspaceRole;
import io.devflow.auth.internal.exception.AccountNotVerifiedException;
import io.devflow.auth.internal.exception.InvalidTokenException;
import io.devflow.auth.internal.exception.ResourceNotFoundException;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.JwtProperties;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.auth.internal.support.AuthMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private AuthMetrics metrics;

    private AuthService authService;

    private final UUID userId = UUID.randomUUID();
    private final String email = "developer@devflow.io";
    private final String rawPassword = "SecurePassword123!";
    private final String hashedPassword = "$2a$10$hashedpassword";
    private final String fullName = "Dev Flow";

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                workspaceRepository,
                workspaceMemberRepository,
                passwordEncoder,
                jwtTokenProvider,
                jwtProperties,
                metrics);
    }

    /** New accounts start as PENDING_VERIFICATION. */
    private UserEntity createTestUser() {
        UserEntity user = new UserEntity(email, hashedPassword, fullName);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private UserEntity createActiveTestUser() {
        UserEntity user = createTestUser();
        user.activate(Instant.now());
        return user;
    }

    @Nested
    @DisplayName("login()")
    class LoginTests {

        @Test
        @DisplayName("Should authenticate and return AuthResponse on valid credentials")
        void login_success() {
            LoginRequest request = new LoginRequest(email, rawPassword);
            UserEntity user = createActiveTestUser();

            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(rawPassword, hashedPassword)).thenReturn(true);
            when(jwtProperties.getAccessTokenExpirationMs()).thenReturn(3600000L);
            when(jwtTokenProvider.generateAccessToken(userId, email, 0)).thenReturn("access-token-123");
            when(jwtTokenProvider.generateRefreshToken(userId, email, 0)).thenReturn("refresh-token-123");

            AuthResponse response = authService.login(request);

            assertThat(response).isNotNull();
            assertThat(response.accessToken()).isEqualTo("access-token-123");
            assertThat(response.refreshToken()).isEqualTo("refresh-token-123");
            assertThat(response.user().id()).isEqualTo(userId);
        }

        @Test
        @DisplayName("Should throw BadCredentialsException when email not found")
        void login_userNotFound_throwsException() {
            LoginRequest request = new LoginRequest(email, rawPassword);
            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Invalid email or password");
        }

        @Test
        @DisplayName("Should throw BadCredentialsException when password does not match")
        void login_wrongPassword_throwsException() {
            LoginRequest request = new LoginRequest(email, "WrongPassword");
            UserEntity user = createTestUser();

            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("WrongPassword", hashedPassword)).thenReturn(false);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Invalid email or password");
        }

        @Test
        @DisplayName("Should throw AccountNotVerifiedException when password matches but email is unverified")
        void login_unverifiedAccount_throwsAccountNotVerified() {
            LoginRequest request = new LoginRequest(email, rawPassword);
            UserEntity user = createTestUser();

            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(rawPassword, hashedPassword)).thenReturn(true);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(AccountNotVerifiedException.class);
            verify(metrics).loginBlocked("unverified");
            verifyNoInteractions(jwtTokenProvider);
        }

        @Test
        @DisplayName("Should not reveal verification state when password is wrong for an unverified account")
        void login_unverifiedAccountWrongPassword_throwsBadCredentials() {
            LoginRequest request = new LoginRequest(email, "WrongPassword");
            UserEntity user = createTestUser();

            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("WrongPassword", hashedPassword)).thenReturn(false);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BadCredentialsException.class);
        }

        @Test
        @DisplayName("Should normalise email case and whitespace before lookup")
        void login_normalisesEmail() {
            LoginRequest request = new LoginRequest("  Developer@DevFlow.IO ", rawPassword);
            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request)).isInstanceOf(BadCredentialsException.class);
            verify(userRepository).findByEmail(email);
        }
    }

    @Nested
    @DisplayName("refreshToken()")
    class RefreshTokenTests {

        @Test
        @DisplayName("Should issue new tokens when refresh token is valid and type is refresh")
        void refreshToken_success() {
            String refreshToken = "valid-refresh-token";
            RefreshTokenRequest request = new RefreshTokenRequest(refreshToken);
            UserEntity user = createActiveTestUser();

            when(jwtTokenProvider.validateToken(refreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenTypeFromToken(refreshToken)).thenReturn("refresh");
            when(jwtTokenProvider.getUserIdFromToken(refreshToken)).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(jwtProperties.getAccessTokenExpirationMs()).thenReturn(3600000L);
            when(jwtTokenProvider.generateAccessToken(userId, email, 0)).thenReturn("new-access-token");
            when(jwtTokenProvider.generateRefreshToken(userId, email, 0)).thenReturn("new-refresh-token");

            AuthResponse response = authService.refreshToken(request);

            assertThat(response).isNotNull();
            assertThat(response.accessToken()).isEqualTo("new-access-token");
            assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        }

        @Test
        @DisplayName("Should throw InvalidTokenException when token signature is invalid or expired")
        void refreshToken_invalidSignature_throwsException() {
            RefreshTokenRequest request = new RefreshTokenRequest("corrupted-token");
            when(jwtTokenProvider.validateToken("corrupted-token")).thenReturn(false);

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessageContaining("Invalid or expired refresh token");
        }

        @Test
        @DisplayName("Should throw InvalidTokenException when token type is access instead of refresh")
        void refreshToken_wrongTokenType_throwsException() {
            String token = "access-token-used-as-refresh";
            RefreshTokenRequest request = new RefreshTokenRequest(token);

            when(jwtTokenProvider.validateToken(token)).thenReturn(true);
            when(jwtTokenProvider.getTokenTypeFromToken(token)).thenReturn("access");

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessageContaining("Supplied token is not a refresh token");
        }

        @Test
        @DisplayName("Should refuse to refresh tokens for an account that is not active")
        void refreshToken_unverifiedAccount_throwsAccountNotVerified() {
            String token = "valid-refresh-token";
            RefreshTokenRequest request = new RefreshTokenRequest(token);

            when(jwtTokenProvider.validateToken(token)).thenReturn(true);
            when(jwtTokenProvider.getTokenTypeFromToken(token)).thenReturn("refresh");
            when(jwtTokenProvider.getUserIdFromToken(token)).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(createTestUser()));

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(AccountNotVerifiedException.class);
        }

        @Test
        @DisplayName("Should reject a refresh token minted before a password change, even within the same second")
        void refreshToken_staleTokenVersion_throwsInvalidToken() {
            String token = "refresh-token-before-reset";
            RefreshTokenRequest request = new RefreshTokenRequest(token);
            UserEntity user = createActiveTestUser();
            // Same instant as the token's issue time: only the version tells the two apart.
            user.changePassword("$2a$10$newhash", Instant.now());

            when(jwtTokenProvider.validateToken(token)).thenReturn(true);
            when(jwtTokenProvider.getTokenTypeFromToken(token)).thenReturn("refresh");
            when(jwtTokenProvider.getUserIdFromToken(token)).thenReturn(userId);
            when(jwtTokenProvider.getTokenVersionFromToken(token)).thenReturn(0);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessageContaining("revoked");
            verify(jwtTokenProvider, never()).generateRefreshToken(any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("getCurrentUser() & getUserWorkspaces()")
    class UserAndWorkspaceTests {

        @Test
        @DisplayName("Should return UserSummary when user exists")
        void getCurrentUser_success() {
            UserEntity user = createTestUser();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            UserSummary summary = authService.getCurrentUser(userId);

            assertThat(summary).isNotNull();
            assertThat(summary.id()).isEqualTo(userId);
            assertThat(summary.email()).isEqualTo(email);
            assertThat(summary.fullName()).isEqualTo(fullName);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when user does not exist")
        void getCurrentUser_notFound_throwsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.getCurrentUser(userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(userId.toString());
        }

        @Test
        @DisplayName("Should return list of WorkspaceSummary for current user")
        void getUserWorkspaces_success() {
            UserEntity user = createTestUser();
            WorkspaceEntity workspace = new WorkspaceEntity("Engineering", "engineering", user);
            UUID workspaceId = UUID.randomUUID();
            ReflectionTestUtils.setField(workspace, "id", workspaceId);

            WorkspaceMemberEntity member = new WorkspaceMemberEntity(workspace, user, WorkspaceRole.OWNER);

            when(workspaceMemberRepository.findAllByUserId(userId)).thenReturn(List.of(member));

            List<WorkspaceSummary> workspaces = authService.getUserWorkspaces(userId);

            assertThat(workspaces).hasSize(1);
            assertThat(workspaces.get(0).id()).isEqualTo(workspaceId);
            assertThat(workspaces.get(0).name()).isEqualTo("Engineering");
            assertThat(workspaces.get(0).slug()).isEqualTo("engineering");
            assertThat(workspaces.get(0).role()).isEqualTo("OWNER");
        }
    }

    @Nested
    @DisplayName("AuthApi Contract Implementation")
    class AuthApiContractTests {

        @Test
        @DisplayName("findUser should return UserSummary when user found")
        void findUser_found() {
            UserEntity user = createTestUser();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            Optional<UserSummary> result = authService.findUser(userId);

            assertThat(result).isPresent();
            assertThat(result.get().email()).isEqualTo(email);
        }

        @Test
        @DisplayName("findUser should return empty when user not found")
        void findUser_notFound() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            Optional<UserSummary> result = authService.findUser(userId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("isWorkspaceMember should delegate to repository")
        void isWorkspaceMember_delegates() {
            UUID workspaceId = UUID.randomUUID();
            when(workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(true);

            boolean isMember = authService.isWorkspaceMember(userId, workspaceId);

            assertThat(isMember).isTrue();
            verify(workspaceMemberRepository).existsByWorkspaceIdAndUserId(workspaceId, userId);
        }
    }
}
