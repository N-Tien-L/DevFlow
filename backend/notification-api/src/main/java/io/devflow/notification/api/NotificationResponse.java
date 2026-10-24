package io.devflow.notification.api;

import java.time.Instant;
import java.util.UUID;

/** Plain text content. Clients must render title/content as text, never as HTML. */
public record NotificationResponse(
        UUID id, UUID eventId, UUID workspaceId, String type, String title, String content,
        UUID referenceId, boolean isRead, Instant createdAt, Instant readAt) {
}
