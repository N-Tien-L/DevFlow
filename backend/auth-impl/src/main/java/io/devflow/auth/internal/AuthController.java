package io.devflow.auth.internal;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the Auth module (ARCHITECTURE.md Section 5). All endpoints are stubs —
 * no business logic yet.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1 — Accounts &amp; workspaces): sign up/login, create a
 * project/workspace, invite members.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register() {
        // TODO(PRODUCT_SPEC 5.1): create account, hash password, return session/token.
        return todo("register");
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login() {
        // TODO(PRODUCT_SPEC 5.1): verify credentials, return session/token.
        return todo("login");
    }

    @PostMapping("/workspaces")
    public ResponseEntity<Map<String, String>> createWorkspace() {
        // TODO(PRODUCT_SPEC 5.1): create workspace owned by the caller.
        return todo("createWorkspace");
    }

    @PostMapping("/workspaces/invitations")
    public ResponseEntity<Map<String, String>> inviteMember() {
        // TODO(PRODUCT_SPEC 5.1): invite a member to a workspace.
        return todo("inviteMember");
    }

    private ResponseEntity<Map<String, String>> todo(String endpoint) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of(
                        "todo", endpoint + " — not implemented yet",
                        "spec", "PRODUCT_SPEC.md Section 5.1"));
    }
}
