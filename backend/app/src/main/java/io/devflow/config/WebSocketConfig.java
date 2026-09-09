package io.devflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Shared STOMP-over-WebSocket transport (endpoint /ws) backing the two real-time paths
 * in ARCHITECTURE.md Section 5: the chat WebSocket gateway (handled by the AI module)
 * and live board sync (handled by the Board module). This class only wires the shared
 * broker — message handling lives in the owning modules.
 *
 * <p>Conventions: clients SEND to /app/*, SUBSCRIBE to /topic/*.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1 — Real-time sync): push card moves to all viewers of
 * the same board. TODO(PRODUCT_SPEC Section 5.2b — Live sync with the board): push chat
 * actions (e.g. AI-created tasks) to the board the user is viewing.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
