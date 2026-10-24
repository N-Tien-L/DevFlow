package io.devflow.auth.internal;

import io.devflow.auth.api.AcceptInvitationRequest;
import io.devflow.auth.api.CreateInvitationRequest;
import io.devflow.auth.api.CreateWorkspaceRequest;
import io.devflow.auth.api.TransferWorkspaceOwnershipRequest;
import io.devflow.auth.api.WorkspaceInvitationSummary;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.internal.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
 * REST API for workspace creation, member invitations (T-007) and ownership transfer (T-009). All endpoints
 * require a JWT; each is rate limited per user inside the service layer.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceInvitationService invitationService;

    public WorkspaceController(WorkspaceService workspaceService, WorkspaceInvitationService invitationService) {
        this.workspaceService = workspaceService;
        this.invitationService = invitationService;
    }

    /**
     * Creates a workspace; the caller becomes its {@code OWNER}.
     */
    @PostMapping("/workspaces")
    public ResponseEntity<WorkspaceSummary> createWorkspace(
            @AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody CreateWorkspaceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workspaceService.createWorkspace(principal.getId(), request));
    }

    /**
     * Hands the workspace to another member; the caller becomes {@code ADMIN}. Owner only.
     */
    @PostMapping("/workspaces/{workspaceId}/transfer-ownership")
    public ResponseEntity<WorkspaceSummary> transferOwnership(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @Valid @RequestBody TransferWorkspaceOwnershipRequest request) {
        return ResponseEntity.ok(workspaceService.transferOwnership(principal.getId(), workspaceId, request));
    }

    /**
     * Invites an email address to the workspace. Owners and admins only.
     */
    @PostMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<WorkspaceInvitationSummary> invite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @Valid @RequestBody CreateInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.invite(principal.getId(), workspaceId, request));
    }

    /**
     * Lists pending invitations of the workspace. Owners and admins only.
     */
    @GetMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<List<WorkspaceInvitationSummary>> listPendingInvitations(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable("workspaceId") UUID workspaceId) {
        return ResponseEntity.ok(invitationService.listPending(principal.getId(), workspaceId));
    }

    /**
     * Revokes a pending invitation. Owners and admins only.
     */
    @DeleteMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<Void> revokeInvitation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("invitationId") UUID invitationId) {
        invitationService.revoke(principal.getId(), workspaceId, invitationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Accepts an invitation with the token from the invitation email. The signed-in account's email
     * must match the invited address.
     */
    @PostMapping("/invitations/accept")
    public ResponseEntity<WorkspaceSummary> acceptInvitation(
            @AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody AcceptInvitationRequest request) {
        return ResponseEntity.ok(invitationService.accept(principal.getId(), request));
    }
}
