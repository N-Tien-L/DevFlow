package io.devflow.ai.internal.support;

import io.devflow.common.security.AuthenticatedActor;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Puts an authenticated {@link AuthenticatedActor} into the security context, as the Auth module's JWT
 * filter does. ai-impl slice tests have no filter chain, and MockMvc runs on the calling thread.
 */
public final class TestActors {

    private TestActors() {
    }

    public static void signIn(UUID userId) {
        AuthenticatedActor actor = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    public static void signOut() {
        SecurityContextHolder.clearContext();
    }
}
