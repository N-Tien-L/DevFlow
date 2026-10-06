package io.devflow.auth.internal;

import io.devflow.auth.api.AuthResponse;
import io.devflow.auth.api.LoginRequest;
import io.devflow.auth.api.RefreshTokenRequest;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.api.UserSummary;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.entity.WorkspaceRole;
import io.devflow.auth.internal.exception.EmailAlreadyExistsException;
import io.devflow.auth.internal.exception.InvalidTokenException;
import io.devflow.auth.internal.exception.ResourceNotFoundException;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.JwtProperties;
import io.devflow.auth.internal.security.JwtTokenProvider;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                jwtProperties);
    }

    private UserEntity createTestUser() {
        UserEntity user = new UserEntity(email, hashedPassword, fullName);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    @Nested
    @DisplayName("register()")
    class RegisterTests {

        @Test
        @DisplayName("Should successfully register a new user and return AuthResponse")
        void register_success() {
            RegisterRequest request = new RegisterRequest(email, rawPassword, fullName);
            UserEntity user = createTestUser();

            when(userRepository.existsByEmail(email)).thenReturn(false);
            when(passwordEncoder.encode(rawPassword)).thenReturn(hashedPassword);
            when(userRepository.save(any(UserEntity.class))).thenReturn(user);
            when(jwtProperties.getAccessTokenExpirationMs()).thenReturn(3600000L);
            when(jwtTokenProvider.generateAccessToken(eq(userId), eq(email))).thenReturn("mock-access-token");
            when(jwtTokenProvider.generateRefreshToken(eq(userId), eq(email))).thenReturn("mock-refresh-token");

            AuthResponse response = authService.register(request);

            assertThat(response).isNotNull();
            assertThat(response.accessToken()).isEqualTo("mock-access-token");
            assertThat(response.refreshToken()).isEqualTo("mock-refresh-token");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(3600L);
            assertThat(response.user().email()).isEqualTo(email);
            assertThat(response.user().fullName()).isEqualTo(fullName);

            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("Should throw EmailAlreadyExistsException when email is already registered")
        void register_duplicateEmail_throwsException() {
            RegisterRequest request = new RegisterRequest(email, rawPassword, fullName);
            when(userRepository.existsByEmail(email)).thenReturn(true);

            assertThatThrownBy(() -> authService.register(request))
                    .isInstanceOf(EmailAlreadyExistsException.class)
                    .hasMessageContaining(email);

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("login()")
    class LoginTests {

        @Test
        @DisplayName("Should authenticate and return AuthResponse on valid credentials")
        void login_success() {
            LoginRequest request = new LoginRequest(email, rawPassword);
            UserEntity user = createTestUser();

            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(rawPassword, hashedPassword)).thenReturn(true);
            when(jwtProperties.getAccessTokenExpirationMs()).thenReturn(3600000L);
            when(jwtTokenProvider.generateAccessToken(userId, email)).thenReturn("access-token-123");
            when(jwtTokenProvider.generateRefreshToken(userId, email)).thenReturn("refresh-token-123");

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
    }

    @Nested
    @DisplayName("refreshToken()")
    class RefreshTokenTests {

        @Test
        @DisplayName("Should issue new tokens when refresh token is valid and type is refresh")
        void refreshToken_success() {
            String refreshToken = "valid-refresh-token";
            RefreshTokenRequest request = new RefreshTokenRequest(refreshToken);
            UserEntity user = createTestUser();

            when(jwtTokenProvider.validateToken(refreshToken)).thenReturn(true);
            when(jwtTokenProvider.getTokenTypeFromToken(refreshToken)).thenReturn("refresh");
            when(jwtTokenProvider.getUserIdFromToken(refreshToken)).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(jwtProperties.getAccessTokenExpirationMs()).thenReturn(3600000L);
            when(jwtTokenProvider.generateAccessToken(userId, email)).thenReturn("new-access-token");
            when(jwtTokenProvider.generateRefreshToken(userId, email)).thenReturn("new-refresh-token");

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
