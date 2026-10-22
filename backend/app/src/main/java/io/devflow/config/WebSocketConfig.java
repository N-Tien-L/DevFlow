package io.devflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import java.net.URI;
import java.util.List;

/**
 * Shared STOMP-over-WebSocket transport (endpoint /ws) backing the two real-time paths
 * in ARCHITECTURE.md Section 5: the chat WebSocket gateway (handled by the AI module)
 * and live board sync (handled by the Board module). This class only wires the shared
 * broker — message handling lives in the owning modules.
 *
 * <p>Conventions: clients SEND to /app/*, SUBSCRIBE to /topic/*.
 *
 * <p>Board topic authorization and STOMP JWT authentication are installed by their owning
 * modules through ordered {@link WebSocketMessageBrokerConfigurer} implementations.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final List<String> allowedOrigins;
    private final ThreadPoolTaskScheduler heartbeatScheduler;

    public WebSocketConfig(
            @Value("${devflow.websocket.allowed-origins:http://localhost:5173,http://localhost:3000}")
                    List<String> allowedOrigins,
            ThreadPoolTaskScheduler heartbeatScheduler) {
        this.allowedOrigins = allowedOrigins.stream().map(String::trim).filter(StringUtils::hasText).toList();
        this.heartbeatScheduler = heartbeatScheduler;
        if (this.allowedOrigins.isEmpty() || this.allowedOrigins.contains("*")) {
            throw new IllegalStateException("Configure explicit WebSocket origins; wildcard origins are not allowed");
        }
        for (String origin : this.allowedOrigins) {
            URI uri = URI.create(origin);
            if (!List.of("http", "https").contains(uri.getScheme()) || !StringUtils.hasText(uri.getHost())
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                throw new IllegalStateException("Invalid WebSocket origin configuration");
            }
        }
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(4).maxPoolSize(8).queueCapacity(500);
    }

    @Override
    public void configureClientOutboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(4).maxPoolSize(8).queueCapacity(500);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024)
                .setSendTimeLimit(5_000)
                .setSendBufferSizeLimit(256 * 1024);
    }
}
