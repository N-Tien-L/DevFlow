package io.devflow.board.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned board topic message. It contains no task body or persistence entity. */
public record BoardMutationMessage(
        int schemaVersion,
        UUID eventId,
        BoardMutationType type,
        UUID boardId,
        Instant occurredAt,
        UUID correlationId,
        UUID actorId,
        BoardMutationData data) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public BoardMutationMessage {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported board mutation schema version");
        }
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(boardId, "boardId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(data, "data must not be null");
    }
}
