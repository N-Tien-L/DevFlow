package io.devflow.ai.internal.security;

import io.devflow.ai.internal.exception.AiApiException;
import io.devflow.common.security.AuthenticatedActor;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the trusted user id from the authenticated principal (set by the Auth module's JWT filter)
 * through the shared {@link AuthenticatedActor} contract; never from request data.
 */
@Component
public class AiActorProvider {

    public UUID requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)
                || actor.userId() == null) {
            throw AiApiException.authenticationRequired();
        }
        UUID userId = actor.userId();
        MDC.put("userId", userId.toString());
        return userId;
    }
}
