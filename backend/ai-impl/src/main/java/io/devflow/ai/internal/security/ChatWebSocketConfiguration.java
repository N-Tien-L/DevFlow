package io.devflow.ai.internal.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Installs the chat destination guard on the shared STOMP broker's inbound channel (T-034). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 102)
public class ChatWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final ChatStompAuthorizationInterceptor interceptor;

    public ChatWebSocketConfiguration(ChatStompAuthorizationInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor);
    }
}
