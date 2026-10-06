package io.devflow.auth.internal;

import io.devflow.auth.api.AuthApi;
import io.devflow.auth.api.AuthResponse;
import io.devflow.auth.api.LoginRequest;
import io.devflow.auth.api.RefreshTokenRequest;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.api.UserSummary;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.exception.EmailAlreadyExistsException;
import io.devflow.auth.internal.exception.InvalidTokenException;
import io.devflow.auth.internal.exception.ResourceNotFoundException;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.JwtProperties;
import io.devflow.auth.internal.security.JwtTokenProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Auth module core service.
 * Manages user accounts, authentication tokens, workspace memberships,
 * and implements the inter-module {@link AuthApi} contract.
 */
@Service
@Transactional(readOnly = true)
public class AuthService implements AuthApi {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    public AuthService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Registers a new user account with hashed password and generates initial JWT tokens.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase().trim();
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        UserEntity user = new UserEntity(email, passwordHash, request.fullName().trim());
        user = userRepository.save(user);

        return createAuthResponse(user);
    }

    /**
     * Authenticates user credentials via email and password, returning JWT access and refresh tokens.
     */
    public AuthResponse login(LoginRequest request) {
        String email = request.email().toLowerCase().trim();
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return createAuthResponse(user);
    }

    /**
     * Issues a new access token using a valid, non-expired refresh token.
     */
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String token = request.refreshToken();
        if (!jwtTokenProvider.validateToken(token)) {
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        String tokenType = jwtTokenProvider.getTokenTypeFromToken(token);
        if (!"refresh".equalsIgnoreCase(tokenType)) {
            throw new InvalidTokenException("Supplied token is not a refresh token");
        }

        UUID userId = jwtTokenProvider.getUserIdFromToken(token);
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("User associated with token no longer exists"));

        return createAuthResponse(user);
    }

    /**
     * Retrieves user summary for the currently authenticated user.
     */
    public UserSummary getCurrentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(this::toUserSummary)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
    }

    /**
     * Retrieves all workspaces that the user is a member of, along with their roles.
     */
    public List<WorkspaceSummary> getUserWorkspaces(UUID userId) {
        List<WorkspaceMemberEntity> memberships = workspaceMemberRepository.findAllByUserId(userId);
        List<WorkspaceSummary> result = new ArrayList<>();

        for (WorkspaceMemberEntity membership : memberships) {
            WorkspaceEntity workspace = membership.getWorkspace();
            if (workspace != null) {
                result.add(new WorkspaceSummary(
                        workspace.getId(),
                        workspace.getName(),
                        workspace.getSlug(),
                        membership.getRole().name(),
                        workspace.getCreatedAt()
                ));
            }
        }
        return result;
    }

    // --- Inter-Module Contract (AuthApi) ---

    @Override
    public Optional<UserSummary> findUser(UUID userId) {
        return userRepository.findById(userId).map(this::toUserSummary);
    }

    @Override
    public boolean isWorkspaceMember(UUID userId, UUID workspaceId) {
        return workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId);
    }

    // --- Helper Methods ---

    private AuthResponse createAuthResponse(UserEntity user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail());
        long expiresIn = jwtProperties.getAccessTokenExpirationMs() / 1000;
        UserSummary summary = toUserSummary(user);
        return AuthResponse.of(accessToken, refreshToken, expiresIn, summary);
    }

    private UserSummary toUserSummary(UserEntity user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl());
    }
}
