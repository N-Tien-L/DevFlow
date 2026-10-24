package io.devflow.auth.internal;

import io.devflow.auth.api.AuthApi;
import io.devflow.auth.api.AuthResponse;
import io.devflow.auth.api.LoginRequest;
import io.devflow.auth.api.RefreshTokenRequest;
import io.devflow.auth.api.UserSummary;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.api.WorkspaceMemberPageResponse;
import io.devflow.auth.api.WorkspaceMemberSummary;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.exception.AccountNotVerifiedException;
import io.devflow.auth.internal.exception.InvalidTokenException;
import io.devflow.auth.internal.exception.ResourceNotFoundException;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.JwtProperties;
import io.devflow.auth.internal.security.JwtTokenProvider;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.InputSanitizer;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;

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
    private final AuthMetrics metrics;

    public AuthService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties,
            AuthMetrics metrics) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.metrics = metrics;
    }

    /**
     * Authenticates user credentials via email and password, returning JWT access and refresh tokens.
     * The password is checked before the account status so that the verification state of an email
     * is only disclosed to someone who knows its password.
     */
    public AuthResponse login(LoginRequest request) {
        String email = InputSanitizer.normalizeEmail(request.email());
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isActive()) {
            metrics.loginBlocked("unverified");
            throw new AccountNotVerifiedException();
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

        if (!user.isActive()) {
            throw new AccountNotVerifiedException();
        }
        if (jwtTokenProvider.getTokenVersionFromToken(token) != user.getTokenVersion()) {
            throw new InvalidTokenException("Refresh token was revoked by a password change. Please sign in again.");
        }

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

    public WorkspaceMemberPageResponse getWorkspaceMembers(UUID actorId, UUID workspaceId, String query,
            int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (query != null && query.codePointCount(0, query.length()) > 100))
            throw new io.devflow.auth.internal.exception.InvalidWorkspaceMemberQueryException();
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, actorId)) {
            throw new ResourceNotFoundException("Workspace not found.");
        }
        String normalizedQuery = StringUtils.hasText(query) ? query.trim() : "";
        Page<WorkspaceMemberEntity> result = workspaceMemberRepository
                .findAllByWorkspace_IdAndUser_FullNameContainingIgnoreCaseOrderByUser_FullNameAscUser_IdAsc(
                        workspaceId, normalizedQuery, PageRequest.of(page, size));
        return new WorkspaceMemberPageResponse(
                result.getContent().stream().map(member -> new WorkspaceMemberSummary(
                        member.getUser().getId(), member.getUser().getFullName(), member.getUser().getAvatarUrl()))
                        .toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
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
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(),
                user.getTokenVersion());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail(),
                user.getTokenVersion());
        long expiresIn = jwtProperties.getAccessTokenExpirationMs() / 1000;
        UserSummary summary = toUserSummary(user);
        return AuthResponse.of(accessToken, refreshToken, expiresIn, summary);
    }

    private UserSummary toUserSummary(UserEntity user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getAvatarUrl());
    }
}
