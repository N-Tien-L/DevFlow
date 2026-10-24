package io.devflow.auth.internal;

import io.devflow.auth.api.AcceptInvitationRequest;
import io.devflow.auth.api.CreateInvitationRequest;
import io.devflow.auth.api.WorkspaceInvitationSummary;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.internal.entity.InvitationStatus;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceInvitationEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.entity.WorkspaceRole;
import io.devflow.auth.internal.exception.InvalidInvitationException;
import io.devflow.auth.internal.exception.InvitationEmailMismatchException;
import io.devflow.auth.internal.exception.InvitationExpiredException;
import io.devflow.auth.internal.exception.ResourceNotFoundException;
import io.devflow.auth.internal.exception.ConflictException;
import io.devflow.auth.internal.exception.WorkspacePermissionDeniedException;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.WorkspaceInvitationRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.RateLimitProperties;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.InputSanitizer;
import io.devflow.auth.internal.support.LogMasking;
import io.devflow.auth.internal.verification.VerificationTokenGenerator;
import io.devflow.auth.internal.workspace.InvitationEmailRequestedEvent;
import io.devflow.auth.internal.workspace.WorkspaceProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace member invitations (T-007): invite by email, list and revoke pending invitations, accept.
 *
 * <p>Invitation tokens follow the verification-token scheme of T-004A: 256-bit random, stored as a SHA-256
 * digest, single use, expiring. Inviting behaves identically whether or not the email belongs to an account,
 * so the endpoint cannot be used to discover registered addresses.
 */
@Service
public class WorkspaceInvitationService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInvitationService.class);
    private static final String MANAGE_DENIED = "Only workspace owners and admins can manage invitations.";

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final VerificationTokenGenerator tokenGenerator;
    private final WorkspaceProperties properties;
    private final RateLimiterService rateLimiterService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthMetrics metrics;
    private final Clock clock;

    public WorkspaceInvitationService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceInvitationRepository invitationRepository,
            VerificationTokenGenerator tokenGenerator,
            WorkspaceProperties properties,
            RateLimiterService rateLimiterService,
            ApplicationEventPublisher eventPublisher,
            AuthMetrics metrics,
            Clock clock) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.invitationRepository = invitationRepository;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.rateLimiterService = rateLimiterService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Invites an email address to a workspace and schedules the invitation email.
     *
     * <p>Only {@code OWNER}/{@code ADMIN} members may invite, and only the {@code OWNER} may invite admins.
     * The workspace row is locked so the pending quota and the one-open-invitation-per-email rule hold
     * under concurrency (the partial unique index backs the latter).
     */
    @Transactional
    public WorkspaceInvitationSummary invite(UUID actorId, UUID workspaceId, CreateInvitationRequest request) {
        rateLimiterService.checkRateLimit(RateLimitProperties.WORKSPACE_INVITE, actorId.toString());
        WorkspaceRole actorRole = requireManager(actorId, workspaceId);
        WorkspaceRole role = request.role() == null
                ? WorkspaceRole.MEMBER
                : WorkspaceRole.valueOf(request.role().toUpperCase(Locale.ROOT));
        if (role == WorkspaceRole.ADMIN && actorRole != WorkspaceRole.OWNER) {
            metrics.workspaceInvitation("forbidden");
            throw new WorkspacePermissionDeniedException("Only the workspace owner can invite admins.");
        }

        String email = InputSanitizer.normalizeEmail(request.email());
        Instant now = clock.instant();
        WorkspaceEntity workspace = workspaceRepository.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new WorkspacePermissionDeniedException(MANAGE_DENIED));

        Optional<WorkspaceInvitationEntity> open =
                invitationRepository.findByWorkspaceIdAndEmailAndStatus(workspaceId, email, InvitationStatus.PENDING);
        if (open.isPresent()) {
            if (!open.get().isExpired(now)) {
                metrics.workspaceInvitation("already_pending");
                throw new ConflictException("INVITATION_ALREADY_PENDING",
                        "An invitation is already pending for this email. Revoke it to send a new one.");
            }
            // An expired invitation still occupies the open-invitation slot until purged; replace it.
            invitationRepository.delete(open.get());
            invitationRepository.flush();
        }
        if (invitationRepository.countByWorkspaceIdAndStatusAndExpiresAtAfter(
                workspaceId, InvitationStatus.PENDING, now) >= properties.getMaxPendingInvitations()) {
            metrics.workspaceInvitation("limit_reached");
            throw new ConflictException("INVITATION_LIMIT_REACHED",
                    "This workspace has too many pending invitations. Revoke some before inviting more.");
        }

        String rawToken = tokenGenerator.generateRawToken();
        Instant expiresAt = now.plus(properties.getInvitationTtl());
        WorkspaceInvitationEntity invitation = invitationRepository.saveAndFlush(new WorkspaceInvitationEntity(
                workspace, email, role, tokenGenerator.hash(rawToken), actorId, expiresAt));

        String inviterName = userRepository.findById(actorId).map(UserEntity::getFullName).orElse("A DevFlow user");
        eventPublisher.publishEvent(new InvitationEmailRequestedEvent(invitation.getId(), email, inviterName,
                workspace.getName(), role.name(), rawToken, expiresAt));

        metrics.workspaceInvitation("created");
        log.info("User {} invited {} to workspace {} as {} (invitation {})",
                actorId, LogMasking.maskEmail(email), workspaceId, role, invitation.getId());
        return toSummary(invitation, workspaceId);
    }

    /**
     * Lists open (pending, unexpired) invitations of a workspace, newest first. Owners and admins only.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceInvitationSummary> listPending(UUID actorId, UUID workspaceId) {
        rateLimiterService.checkRateLimit(RateLimitProperties.WORKSPACE_INVITATION_MANAGE, actorId.toString());
        requireManager(actorId, workspaceId);
        return invitationRepository.findAllByWorkspaceIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        workspaceId, InvitationStatus.PENDING, clock.instant())
                .stream()
                .map(invitation -> toSummary(invitation, workspaceId))
                .toList();
    }

    /**
     * Revokes a pending invitation of the workspace. Owners and admins only.
     *
     * @throws ResourceNotFoundException the invitation does not exist in this workspace or is no longer pending
     */
    @Transactional
    public void revoke(UUID actorId, UUID workspaceId, UUID invitationId) {
        rateLimiterService.checkRateLimit(RateLimitProperties.WORKSPACE_INVITATION_MANAGE, actorId.toString());
        requireManager(actorId, workspaceId);
        if (invitationRepository.markRevoked(invitationId, workspaceId, clock.instant()) == 0) {
            metrics.workspaceInvitationRevoke("not_found");
            throw new ResourceNotFoundException("Pending invitation not found.");
        }
        metrics.workspaceInvitationRevoke("success");
        log.info("User {} revoked invitation {} of workspace {}", actorId, invitationId, workspaceId);
    }

    /**
     * Accepts an invitation on behalf of the signed-in user, whose email must match the invited address.
     * Accepting into a workspace the user already belongs to consumes the invitation and keeps the current role.
     *
     * @throws InvalidInvitationException       token unknown, revoked or already used (HTTP 400)
     * @throws InvitationEmailMismatchException signed in with a different email (HTTP 403)
     * @throws InvitationExpiredException       token genuine but expired (HTTP 410)
     */
    @Transactional
    public WorkspaceSummary accept(UUID actorId, AcceptInvitationRequest request) {
        rateLimiterService.checkRateLimit(RateLimitProperties.INVITATION_ACCEPT, actorId.toString());
        Instant now = clock.instant();

        WorkspaceInvitationEntity invitation = invitationRepository
                .findByTokenHash(tokenGenerator.hash(request.token().trim()))
                .filter(WorkspaceInvitationEntity::isPending)
                .orElseThrow(() -> {
                    metrics.workspaceInvitationAccept("invalid");
                    return new InvalidInvitationException();
                });
        // Lock order users -> workspaces -> invitation/member rows, as in account deletion (T-009): the reverse
        // could deadlock with the owner deleting their account, or with this user deleting theirs.
        UserEntity user = userRepository.findByIdForUpdate(actorId).orElseThrow(InvalidInvitationException::new);
        if (!user.getEmail().equalsIgnoreCase(invitation.getEmail())) {
            metrics.workspaceInvitationAccept("email_mismatch");
            throw new InvitationEmailMismatchException();
        }
        if (invitation.isExpired(now)) {
            metrics.workspaceInvitationAccept("expired");
            throw new InvitationExpiredException();
        }

        WorkspaceEntity workspace = workspaceRepository.findByIdForUpdate(invitation.getWorkspace().getId())
                .orElseThrow(InvalidInvitationException::new);
        WorkspaceRole invitedRole = invitation.getRole();
        // Conditional update: only one of several concurrent requests can consume the invitation.
        if (invitationRepository.markAccepted(invitation.getId(), actorId, now) == 0) {
            metrics.workspaceInvitationAccept("invalid");
            throw new InvalidInvitationException();
        }

        Optional<WorkspaceMemberEntity> existing =
                workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), actorId);
        WorkspaceRole role;
        if (existing.isPresent()) {
            role = existing.get().getRole();
            metrics.workspaceInvitationAccept("already_member");
        } else {
            workspaceMemberRepository.save(new WorkspaceMemberEntity(
                    workspaceRepository.getReferenceById(workspace.getId()),
                    userRepository.getReferenceById(actorId),
                    invitedRole));
            role = invitedRole;
            metrics.workspaceInvitationAccept("success");
        }

        log.info("User {} accepted invitation {} and is {} of workspace {}",
                actorId, invitation.getId(), role, workspace.getId());
        return new WorkspaceSummary(workspace.getId(), workspace.getName(), workspace.getSlug(), role.name(),
                workspace.getCreatedAt());
    }

    /**
     * Returns the caller's role when it may manage invitations. Non-members (including callers probing a
     * workspace id that does not exist) get the same 403 as plain members, so ids cannot be enumerated.
     */
    private WorkspaceRole requireManager(UUID actorId, UUID workspaceId) {
        WorkspaceRole role = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, actorId)
                .map(WorkspaceMemberEntity::getRole)
                .orElse(null);
        if (role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
            metrics.workspaceInvitation("forbidden");
            throw new WorkspacePermissionDeniedException(MANAGE_DENIED);
        }
        return role;
    }

    private static WorkspaceInvitationSummary toSummary(WorkspaceInvitationEntity invitation, UUID workspaceId) {
        return new WorkspaceInvitationSummary(invitation.getId(), workspaceId, invitation.getEmail(),
                invitation.getRole().name(), invitation.getStatus().name(), invitation.getInvitedBy(),
                invitation.getExpiresAt(), invitation.getCreatedAt());
    }
}
