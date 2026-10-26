package io.devflow.notification.internal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 101)
public class NotificationWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final NotificationStompAuthorizationInterceptor interceptor;
    public NotificationWebSocketConfiguration(NotificationStompAuthorizationInterceptor interceptor) {
        this.interceptor = interceptor;
    }
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor);
    }
}
