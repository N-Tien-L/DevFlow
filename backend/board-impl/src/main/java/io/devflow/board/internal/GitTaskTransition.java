package io.devflow.board.internal;

import io.devflow.board.internal.entity.StatusCategory;
import io.devflow.common.event.EventTypes;
import java.time.Instant;
import java.util.UUID;

/** Immutable adapter snapshot; Git strings are fingerprinted, never logged or persisted. */
record GitTaskTransition(UUID eventId, UUID repositoryId, UUID workspaceId, UUID taskId,
        String eventType, Instant occurredAt, String objectKey, String repo, String author, UUID actorId) {
    StatusCategory target() {
        return switch (eventType) {
            case EventTypes.GIT_COMMIT_LINKED -> StatusCategory.IN_PROGRESS;
            case EventTypes.GIT_PR_OPENED -> StatusCategory.IN_REVIEW;
            case EventTypes.GIT_PR_MERGED -> StatusCategory.DONE;
            default -> null;
        };
    }

    static boolean advances(StatusCategory current, StatusCategory target) {
        return rank(target) > rank(current);
    }

    private static int rank(StatusCategory value) {
        return switch (value) {
            case TODO -> 0;
            case IN_PROGRESS -> 1;
            case IN_REVIEW -> 2;
            case DONE -> 3;
        };
    }
}
