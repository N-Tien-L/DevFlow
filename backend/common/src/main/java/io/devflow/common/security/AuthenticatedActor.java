package io.devflow.common.security;

import java.util.UUID;

/** Stable identity contract exposed by the authenticated principal to application modules. */
public interface AuthenticatedActor {

    UUID userId();
}
