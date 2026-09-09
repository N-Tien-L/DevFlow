package io.devflow.notification.internal;

import io.devflow.notification.api.NotificationApi;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Notification module core service. Owns DELIVERY only (ARCHITECTURE.md Section 3):
 * in-app notifications over the shared WebSocket gateway first, push later. It never
 * decides what counts as notifiable — that decision arrives via events from the owning
 * modules (Section 4).
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1 — Notifications): notify on new assignment, new
 * comment, approaching due date. Persist notifications so the web client can list them.
 */
@Service
public class NotificationService implements NotificationApi {

    @Override
    public void sendToUser(UUID userId, String subject, String body) {
        // TODO(PRODUCT_SPEC 5.1): persist + push over /topic/user-{id} via SimpMessagingTemplate.
        throw new UnsupportedOperationException("Not implemented yet — scaffold stub");
    }
}
