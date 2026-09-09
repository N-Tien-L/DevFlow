package io.devflow.notification.internal;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the Notification module (ARCHITECTURE.md Section 5) — listing and
 * acknowledging in-app notifications. Stubs only.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1 — Notifications).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    @GetMapping
    public ResponseEntity<Map<String, String>> listNotifications() {
        // TODO(PRODUCT_SPEC 5.1): return the caller's notifications, newest first.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of(
                        "todo", "listNotifications — not implemented yet",
                        "spec", "PRODUCT_SPEC.md Section 5.1"));
    }
}
