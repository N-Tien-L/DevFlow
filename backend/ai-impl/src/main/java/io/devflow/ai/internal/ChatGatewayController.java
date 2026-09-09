package io.devflow.ai.internal;

import io.devflow.ai.api.AiServiceApi;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * Chat WebSocket gateway (STOMP over /ws) — one of the two thin adapters into the shared
 * AI service (ARCHITECTURE.md Section 5). Consumed by the web client's chat panel.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.2b — Conversational AI Assistant):
 * <ul>
 *   <li>Route chat messages to {@link AiServiceApi} (Q&amp;A, task breakdown)</li>
 *   <li>Live sync with the board: actions taken via chat (e.g. AI creates a task) must be
 *       pushed to board viewers immediately (also PRODUCT_SPEC 5.1 real-time sync)</li>
 * </ul>
 */
@Controller
public class ChatGatewayController {

    private final AiServiceApi aiService;

    public ChatGatewayController(AiServiceApi aiService) {
        this.aiService = aiService;
    }

    /** Placeholder echo of the Q&amp;A path. Clients send to /app/chat, receive on /topic/chat. */
    @MessageMapping("/chat")
    @SendTo("/topic/chat")
    public String chat(String message) {
        // TODO(PRODUCT_SPEC 5.2b): resolve the caller's project from the session instead of
        //   a nil UUID, and stream partial responses instead of a single reply.
        return aiService.answerProjectQuery(new UUID(0, 0), message);
    }
}
