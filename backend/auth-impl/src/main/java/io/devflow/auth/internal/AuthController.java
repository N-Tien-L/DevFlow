package io.devflow.auth.internal;

import io.devflow.auth.api.AuthResponse;
import io.devflow.auth.api.LoginRequest;
import io.devflow.auth.api.MessageResponse;
import io.devflow.auth.api.RefreshTokenRequest;
import io.devflow.auth.api.RegisterRequest;
import io.devflow.auth.api.RegisterResponse;
import io.devflow.auth.api.ResendVerificationRequest;
import io.devflow.auth.api.UserSummary;
import io.devflow.auth.api.VerifyEmailRequest;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.api.WorkspaceMemberPageResponse;
import io.devflow.auth.internal.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API controller for user registration, authentication, token refresh,
 * user profile retrieval, and workspace membership queries.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RegistrationService registrationService;

    public AuthController(AuthService authService, RegistrationService registrationService) {
        this.authService = authService;
        this.registrationService = registrationService;
    }

    /**
     * Registers a new account in {@code PENDING_VERIFICATION} state and emails a verification link.
     * No tokens are issued until the email is verified. Requires a Turnstile token when bot protection is on.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        RegisterResponse response = registrationService.register(request, servletRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Confirms the token from the verification email and activates the account.
     */
    @PostMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(registrationService.verifyEmail(request));
    }

    /**
     * Re-sends the verification email. Always answers 202 to avoid disclosing which emails are registered.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(registrationService.resendVerification(request));
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

    @GetMapping("/workspaces/{workspaceId}/members")
    public ResponseEntity<WorkspaceMemberPageResponse> getWorkspaceMembers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(authService.getWorkspaceMembers(principal.getId(), workspaceId, q, page, size));
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
