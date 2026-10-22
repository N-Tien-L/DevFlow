package io.devflow.board.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Applies the shared per-actor quota before task writes.
 *
 * <p>Task mutations are authenticated (JWT) and authorized by workspace membership in the service layer,
 * so they do not require a Turnstile challenge; bot protection belongs on public auth forms.
 */
@Component
public class TaskMutationRequestGuard {

    private final AuthenticatedActorProvider actorProvider;
    private final BoardRateLimiter rateLimiter;

    public TaskMutationRequestGuard(AuthenticatedActorProvider actorProvider, BoardRateLimiter rateLimiter) {
        this.actorProvider = actorProvider;
        this.rateLimiter = rateLimiter;
    }

    /** Call once from each authenticated create/update/delete/move request, before opening a DB transaction. */
    public void authorizeMutation() {
        UUID actorId = actorProvider.requireUserId();
        rateLimiter.check(actorId);
    }
}
