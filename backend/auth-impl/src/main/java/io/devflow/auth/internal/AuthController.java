package io.devflow.auth.internal;

import io.devflow.auth.api.AuthResponse;
import io.devflow.auth.api.LoginRequest;
import io.devflow.auth.api.RefreshTokenRequest;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.api.UserSummary;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.internal.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for user registration, authentication, token refresh,
 * user profile retrieval, and workspace membership queries.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Registers a new user account.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticates credentials and issues JWT access and refresh tokens.
     * Rate limited by {@link io.devflow.auth.internal.security.RateLimitingInterceptor}.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Issues a new access token using a valid refresh token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the profile summary of the currently authenticated user.
     */
    @GetMapping("/me")
    public ResponseEntity<UserSummary> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        UserSummary summary = authService.getCurrentUser(principal.getId());
        return ResponseEntity.ok(summary);
    }

    /**
     * Retrieves the list of workspaces that the current user belongs to.
     */
    @GetMapping("/workspaces")
    public ResponseEntity<List<WorkspaceSummary>> getUserWorkspaces(@AuthenticationPrincipal UserPrincipal principal) {
        List<WorkspaceSummary> workspaces = authService.getUserWorkspaces(principal.getId());
        return ResponseEntity.ok(workspaces);
    }

    // Stubs for future workspace creation/invitation endpoints
    @PostMapping("/workspaces")
    public ResponseEntity<Map<String, String>> createWorkspace() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("todo", "createWorkspace — not implemented yet", "spec", "PRODUCT_SPEC.md Section 5.1"));
    }

    @PostMapping("/workspaces/invitations")
    public ResponseEntity<Map<String, String>> inviteMember() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("todo", "inviteMember — not implemented yet", "spec", "PRODUCT_SPEC.md Section 5.1"));
    }
}
