package io.devflow.auth.internal;

import io.devflow.auth.api.CreateWorkspaceRequest;
import io.devflow.auth.api.TransferWorkspaceOwnershipRequest;
import io.devflow.auth.api.WorkspaceSummary;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.entity.WorkspaceMemberEntity;
import io.devflow.auth.internal.entity.WorkspaceRole;
import io.devflow.auth.internal.exception.InvalidFieldException;
import io.devflow.auth.internal.exception.ResourceNotFoundException;
import io.devflow.auth.internal.exception.ConflictException;
import io.devflow.auth.internal.exception.WorkspacePermissionDeniedException;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.RateLimitProperties;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.InputSanitizer;
import io.devflow.auth.internal.support.WorkspaceSlugs;
import io.devflow.auth.internal.workspace.WorkspaceProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace creation (T-007) and ownership transfer (T-009). The creator becomes the workspace {@code OWNER}.
 */
@Service
public class WorkspaceService {

    static final int MAX_SLUG_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceProperties properties;
    private final RateLimiterService rateLimiterService;
    private final AuthMetrics metrics;

    public WorkspaceService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceProperties properties,
            RateLimiterService rateLimiterService,
            AuthMetrics metrics) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.properties = properties;
        this.rateLimiterService = rateLimiterService;
        this.metrics = metrics;
    }

    /**
     * Creates a workspace owned by the caller, subject to the per-user rate limit and ownership quota.
     * The caller's user row is locked for the duration, so concurrent requests cannot overshoot the quota.
     */
    @Transactional
    public WorkspaceSummary createWorkspace(UUID actorId, CreateWorkspaceRequest request) {
        rateLimiterService.checkRateLimit(RateLimitProperties.WORKSPACE_CREATE, actorId.toString());

        String name = InputSanitizer.sanitizeDisplayName(request.name());
        if (name.isEmpty()) {
            throw new InvalidFieldException("name", "Workspace name must contain visible characters");
        }

        UserEntity owner = userRepository.findByIdForUpdate(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
        if (workspaceRepository.countByOwnerId(actorId) >= properties.getMaxOwnedPerUser()) {
            metrics.workspaceCreated("limit_reached");
            throw new ConflictException("WORKSPACE_LIMIT_REACHED",
                    "You can own at most " + properties.getMaxOwnedPerUser() + " workspaces.");
        }

        WorkspaceEntity workspace = workspaceRepository.save(new WorkspaceEntity(name, uniqueSlug(name), owner));
        workspaceMemberRepository.save(new WorkspaceMemberEntity(workspace, owner, WorkspaceRole.OWNER));

        metrics.workspaceCreated("success");
        log.info("User {} created workspace {}", actorId, workspace.getId());
        return new WorkspaceSummary(workspace.getId(), workspace.getName(), workspace.getSlug(),
                WorkspaceRole.OWNER.name(), workspace.getCreatedAt());
    }

    /**
     * Hands the workspace to another member (T-009: lets an owner leave or delete their account). The new owner
     * becomes {@code OWNER} and the caller stays on as {@code ADMIN}.
     *
     * <p>Locks the new owner's user row, then the workspace (users before workspaces, as every Auth write does),
     * and only reads the workspace and memberships under those locks: an entity loaded before the lock would not
     * be refreshed by the locking query, letting two concurrent transfers by the same owner both succeed.
     *
     * @return the workspace as the caller now sees it (role {@code ADMIN})
     * @throws WorkspacePermissionDeniedException the caller is not the owner, or the workspace does not exist
     * @throws InvalidFieldException              the target is the caller or not a member of the workspace
     */
    @Transactional
    public WorkspaceSummary transferOwnership(UUID actorId, UUID workspaceId, TransferWorkspaceOwnershipRequest request) {
        rateLimiterService.checkRateLimit(RateLimitProperties.WORKSPACE_TRANSFER, actorId.toString());
        UUID newOwnerId = request.newOwnerId();
        // Scalar pre-check: loads no entity, so the workspace read under the lock below is the current row.
        requireOwner(actorId, workspaceRepository.findOwnerIdById(workspaceId).orElse(null));
        if (newOwnerId.equals(actorId)) {
            metrics.workspaceOwnershipTransferred("invalid_target");
            throw new InvalidFieldException("newOwnerId", "You already own this workspace.");
        }

        UserEntity newOwner = userRepository.findByIdForUpdate(newOwnerId).filter(UserEntity::isActive)
                .orElseThrow(() -> notAMember());
        WorkspaceEntity workspace = workspaceRepository.findByIdForUpdate(workspaceId).orElse(null);
        // Re-checked under the lock: a concurrent transfer may have moved the workspace already.
        requireOwner(actorId, workspace == null ? null : workspace.getOwner().getId());
        WorkspaceMemberEntity target = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, newOwnerId)
                .orElseThrow(() -> notAMember());
        WorkspaceMemberEntity previous = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, actorId)
                .orElseThrow(() -> new IllegalStateException("Workspace owner has no membership row"));

        workspace.setOwner(newOwner);
        target.setRole(WorkspaceRole.OWNER);
        previous.setRole(WorkspaceRole.ADMIN);

        metrics.workspaceOwnershipTransferred("success");
        log.info("User {} transferred ownership of workspace {} to user {}", actorId, workspaceId, newOwnerId);
        return new WorkspaceSummary(workspace.getId(), workspace.getName(), workspace.getSlug(),
                WorkspaceRole.ADMIN.name(), workspace.getCreatedAt());
    }

    /** Same answer for "not the owner" and "no such workspace", so ids cannot be enumerated. */
    private void requireOwner(UUID actorId, UUID ownerId) {
        if (!actorId.equals(ownerId)) {
            metrics.workspaceOwnershipTransferred("forbidden");
            throw new WorkspacePermissionDeniedException("Only the workspace owner can transfer ownership.");
        }
    }

    private InvalidFieldException notAMember() {
        metrics.workspaceOwnershipTransferred("invalid_target");
        return new InvalidFieldException("newOwnerId", "The new owner must be an active member of this workspace.");
    }

    private String uniqueSlug(String name) {
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String slug = WorkspaceSlugs.generate(name);
            if (!workspaceRepository.existsBySlug(slug)) {
                return slug;
            }
        }
        // 36^6 random suffixes per base: reaching this means something is badly wrong, not bad luck.
        throw new IllegalStateException("Could not allocate a unique workspace slug");
    }
}
