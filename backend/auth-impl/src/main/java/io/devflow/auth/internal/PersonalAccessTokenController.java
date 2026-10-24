package io.devflow.auth.internal;

import io.devflow.auth.api.CreatePersonalAccessTokenRequest;
import io.devflow.auth.api.CreatedPersonalAccessTokenResponse;
import io.devflow.auth.api.PersonalAccessTokenSummary;
import io.devflow.auth.internal.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for managing personal access tokens used by MCP clients (T-038). Requires a JWT: a personal access
 * token cannot manage tokens. Each user only ever sees and revokes their own tokens.
 */
@RestController
@RequestMapping("/api/v1/auth/tokens")
public class PersonalAccessTokenController {

    private final PersonalAccessTokenService tokenService;

    public PersonalAccessTokenController(PersonalAccessTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Creates a token. The raw token is in this response only and is never retrievable again.
     */
    @PostMapping
    public ResponseEntity<CreatedPersonalAccessTokenResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreatePersonalAccessTokenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(tokenService.create(principal.getId(), request));
    }

    /**
     * Lists the caller's tokens that have not been revoked.
     */
    @GetMapping
    public ResponseEntity<List<PersonalAccessTokenSummary>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(tokenService.list(principal.getId()));
    }

    /**
     * Revokes one of the caller's tokens.
     */
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable("tokenId") UUID tokenId) {
        tokenService.revoke(principal.getId(), tokenId);
        return ResponseEntity.noContent().build();
    }
}
