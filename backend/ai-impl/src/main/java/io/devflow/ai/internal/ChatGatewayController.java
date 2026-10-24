package io.devflow.ai.internal;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * Chat WebSocket gateway (STOMP over /ws) — one of the two thin adapters into the shared
 * AI service (ARCHITECTURE.md Section 5). Consumed by the web client's chat panel.
 *
 * <p>TODO(T-034, PRODUCT_SPEC Section 5.2b — Conversational AI Assistant):
 * <ul>
 *   <li>Route chat messages to {@link io.devflow.ai.api.AiServiceApi} (Q&amp;A, task breakdown) with the
 *       session's authenticated user and project, replying on the caller's own {@code /user/queue/chat}</li>
 *   <li>Live sync with the board: actions taken via chat (e.g. AI creates a task) must be
 *       pushed to board viewers immediately (also PRODUCT_SPEC 5.1 real-time sync)</li>
 * </ul>
 */
@Controller
public class ChatGatewayController {

    static final String NOT_AVAILABLE = "Chat over WebSocket is not available yet. "
            + "Ask project questions with POST /api/v1/ai/project-query.";

    /**
     * Placeholder until T-034. Project Q&amp;A needs an authenticated user and a project to check membership
     * against; this socket has neither yet, so it answers nothing from project data (and, on a shared
     * {@code /topic}, it must not).
     */
    @MessageMapping("/chat")
    @SendTo("/topic/chat")
    public String chat(String message) {
        return NOT_AVAILABLE;
    }
}
