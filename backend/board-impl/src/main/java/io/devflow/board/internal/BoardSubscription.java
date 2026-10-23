package io.devflow.board.internal;

import java.util.UUID;

/** A board scope resolved from Board storage after a membership decision. */
record BoardSubscription(UUID boardId, UUID workspaceId) {
}
