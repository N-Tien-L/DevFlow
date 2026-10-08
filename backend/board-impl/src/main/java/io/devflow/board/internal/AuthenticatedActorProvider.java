package io.devflow.board.internal;

import io.devflow.common.security.AuthenticatedActor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;
import org.slf4j.MDC;

/** Resolves the trusted user id from the authenticated Spring Security principal. */
@Component
public class AuthenticatedActorProvider {

    public UUID requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)
                || actor.userId() == null) {
            throw BoardApiException.authenticationRequired();
        }
        UUID userId = actor.userId();
        MDC.put(BoardRequestContext.USER_ID_KEY, userId.toString());
        return userId;
    }
}
