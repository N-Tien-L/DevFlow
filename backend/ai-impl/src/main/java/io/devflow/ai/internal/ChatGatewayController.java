package io.devflow.ai.internal;

import io.devflow.ai.api.CancelChatRequest;
import io.devflow.ai.api.ChatMessageRequest;
import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

/**
 * Chat WebSocket gateway (STOMP over /ws) — the thin STOMP adapter into the shared
 * AI capabilities (ARCHITECTURE.md Section 5, PRODUCT_SPEC Section 5.2b).
 *
 * <p>Receives chat requests sent to {@code /app/chat} and {@code /app/chat/cancel}.
 * Strict per-user isolation: all replies are pushed exclusively to the authenticated caller's
 * private queue {@code /user/queue/chat} (never broadcasted to {@code /topic/chat}).
 */
@Controller
public class ChatGatewayController {

    private static final Logger log = LoggerFactory.getLogger(ChatGatewayController.class);

    private final ChatStreamService chatStreamService;

    public ChatGatewayController(ChatStreamService chatStreamService) {
        this.chatStreamService = chatStreamService;
    }

    /**
     * Handles real-time chat messages from the web client.
     */
    @MessageMapping("/chat")
    public void chat(@Payload ChatMessageRequest request, Principal principal) {
        UUID userId = extractUserId(principal);
        if (userId == null) {
            log.warn("Rejected chat frame: unauthenticated or invalid principal");
            return;
        }
        chatStreamService.handleChat(userId, request);
    }

    /**
     * Handles client request to cancel an active streaming conversation.
     */
    @MessageMapping("/chat/cancel")
    public void cancel(@Payload CancelChatRequest request, Principal principal) {
        UUID userId = extractUserId(principal);
        if (userId == null || request == null) {
            return;
        }
        chatStreamService.cancel(userId, request.conversationId());
    }

    private static UUID extractUserId(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            log.warn("Principal name is not a valid UUID name={}", principal.getName());
            return null;
        }
    }
}
