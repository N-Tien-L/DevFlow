package io.devflow.common.event;

import java.util.UUID;

/**
 * Placeholder identity that replaces a deleted user in data other members still need, such as comment and audit
 * authors (T-009). Clients render {@link #ID} as {@link #DISPLAY_NAME}. It never matches a real account.
 */
public final class DeletedUser {

    /** Nil UUID: never generated for a real user. */
    public static final UUID ID = new UUID(0L, 0L);

    /** Label shown in place of a deleted author. */
    public static final String DISPLAY_NAME = "Ng\u01b0\u1eddi d\u00f9ng \u0111\u00e3 xo\u00e1"; // Vietnamese for "deleted user"

    private DeletedUser() {
    }
}
