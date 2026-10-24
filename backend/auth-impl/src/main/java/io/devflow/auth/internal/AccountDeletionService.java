package io.devflow.auth.internal;

import io.devflow.auth.api.DeleteAccountRequest;
import io.devflow.auth.internal.entity.AccountDeletionAuditEntity;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.WorkspaceEntity;
import io.devflow.auth.internal.exception.InvalidTokenException;
import io.devflow.auth.internal.exception.OwnershipTransferRequiredException;
import io.devflow.auth.internal.exception.ReauthenticationFailedException;
import io.devflow.auth.internal.repository.AccountDeletionAuditRepository;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.repository.WorkspaceInvitationRepository;
import io.devflow.auth.internal.repository.WorkspaceMemberRepository;
import io.devflow.auth.internal.repository.WorkspaceRepository;
import io.devflow.auth.internal.security.RateLimitProperties;
import io.devflow.auth.internal.security.RateLimiterService;
import io.devflow.auth.internal.security.SessionRevocationService;
import io.devflow.auth.internal.security.StompSessionRegistry;
import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.LogMasking;
import io.devflow.common.event.UserAccountDeletedEvent;
import io.devflow.common.event.WorkspaceDeletedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account erasure (GDPR right to erasure, T-009).
 *
 * <p>In one transaction: re-checks the password, refuses while the user still owns a workspace with other
 * members, deletes the workspaces the user is alone in, deletes invitations addressed to the user's email,
 * deletes the user (memberships, verification tokens and personal access tokens cascade) and writes a PII-free
 * audit row. {@code workspace.deleted} and {@code user.account_deleted} are published so the other modules
 * remove or anonymise their data once this commits.
 *
 * <p>Sessions: with the user row gone, refresh and personal access tokens no longer authenticate, and
 * {@link SessionRevocationService} treats access tokens of a missing user as revoked. After commit the cached
 * token version is dropped and the user's open STOMP sockets on this instance are closed.
 *
 * <p>Lock order is users, then workspaces, then their member/invitation rows, as in the other Auth writes.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final AccountDeletionAuditRepository auditRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiterService rateLimiterService;
    private final SessionRevocationService sessionRevocationService;
    private final StompSessionRegistry stompSessionRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthMetrics metrics;
    private final Clock clock;

    public AccountDeletionService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceInvitationRepository invitationRepository,
            AccountDeletionAuditRepository auditRepository,
            PasswordEncoder passwordEncoder,
            RateLimiterService rateLimiterService,
            SessionRevocationService sessionRevocationService,
            StompSessionRegistry stompSessionRegistry,
            ApplicationEventPublisher eventPublisher,
            AuthMetrics metrics,
            Clock clock) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.invitationRepository = invitationRepository;
        this.auditRepository = auditRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiterService = rateLimiterService;
        this.sessionRevocationService = sessionRevocationService;
        this.stompSessionRegistry = stompSessionRegistry;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Erases the caller's account.
     *
     * @param clientIp caller IP; only its truncated form is stored
     * @throws ReauthenticationFailedException    wrong password (HTTP 403)
     * @throws OwnershipTransferRequiredException still owns workspaces with other members (HTTP 409)
     */
    @Transactional
    public void deleteAccount(UUID actorId, DeleteAccountRequest request, String clientIp) {
        // Per user, so it also caps password guessing through this endpoint.
        rateLimiterService.checkRateLimit(RateLimitProperties.ACCOUNT_DELETE, actorId.toString());

        UserEntity user = userRepository.findByIdForUpdate(actorId)
                .orElseThrow(() -> new InvalidTokenException("This account no longer exists."));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            metrics.accountDeleted("reauthentication_failed");
            throw new ReauthenticationFailedException();
        }

        List<UUID> soleWorkspaces = new ArrayList<>();
        List<OwnershipTransferRequiredException.BlockingWorkspace> blocking = new ArrayList<>();
        for (WorkspaceEntity workspace : workspaceRepository.findAllByOwnerIdForUpdate(actorId)) {
            long otherMembers = workspaceMemberRepository.countByWorkspaceIdAndUserIdNot(workspace.getId(), actorId);
            if (otherMembers == 0) {
                soleWorkspaces.add(workspace.getId());
            } else {
                blocking.add(new OwnershipTransferRequiredException.BlockingWorkspace(
                        workspace.getId(), workspace.getName(), otherMembers));
            }
        }
        if (!blocking.isEmpty()) {
            metrics.accountDeleted("ownership_transfer_required");
            throw new OwnershipTransferRequiredException(blocking);
        }

        String email = user.getEmail();
        if (!soleWorkspaces.isEmpty()) {
            workspaceRepository.deleteAllByIdIn(soleWorkspaces);
        }
        int invitations = invitationRepository.deleteAllByEmail(email);
        userRepository.deleteAccount(actorId);

        Instant now = clock.instant();
        auditRepository.save(new AccountDeletionAuditEntity(actorId, now, LogMasking.maskIp(clientIp),
                soleWorkspaces.size()));
        signOutAfterCommit(actorId);
        // Delivered to the other modules after commit (they listen AFTER_COMMIT).
        soleWorkspaces.forEach(workspaceId -> eventPublisher.publishEvent(new WorkspaceDeletedEvent(workspaceId, now)));
        eventPublisher.publishEvent(new UserAccountDeletedEvent(actorId, now));

        metrics.accountDeleted("success");
        log.info("User {} erased their account ({} sole-owned workspaces and {} invitations deleted)",
                actorId, soleWorkspaces.size(), invitations);
    }

    private void signOutAfterCommit(UUID userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionRevocationService.evict(userId);
                int closed = stompSessionRegistry.closeSessions(userId);
                if (closed > 0) {
                    log.info("Closed {} open STOMP sessions of deleted user {}", closed, userId);
                }
            }
        });
    }
}
