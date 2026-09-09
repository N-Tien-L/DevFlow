package io.devflow.notification.api;

import java.util.UUID;

/**
 * Public interface of the Notification module (ARCHITECTURE.md Section 3: Notification
 * owns delivery — in-app, push — and nothing else. Deciding WHAT is worth notifying
 * lives in the publishing modules, e.g. deadline-risk logic lives in the AI service).
 *
 * <p>Implementations live in {@code notification-impl} and are never imported directly.
 */
public interface NotificationApi {

    /** Delivers a notification to a single user (in-app first; push is a later option). */
    void sendToUser(UUID userId, String subject, String body);
}
