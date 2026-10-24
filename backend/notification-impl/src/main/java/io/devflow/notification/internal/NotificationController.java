package io.devflow.notification.internal;

import io.devflow.common.security.AuthenticatedActor;
import io.devflow.notification.api.NotificationPage;
import io.devflow.notification.api.NotificationResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;
    private final NotificationRateLimiter limiter;

    public NotificationController(NotificationService service, NotificationRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @GetMapping
    public NotificationPage list(Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(userId(authentication), page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication authentication) {
        return Map.of("count", service.unreadCount(userId(authentication)));
    }

    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(Authentication authentication, @PathVariable UUID id) {
        return service.markRead(userId(authentication), id);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(Authentication authentication) {
        return Map.of("updated", service.markAllRead(userId(authentication)));
    }

    private UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor) || actor.userId() == null) {
            throw new NotificationException(401, "Authentication required.");
        }
        limiter.check(actor.userId());
        return actor.userId();
    }
}
