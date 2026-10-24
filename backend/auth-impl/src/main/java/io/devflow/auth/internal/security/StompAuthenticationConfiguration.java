package io.devflow.auth.internal.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * Installs authentication before Board and framework message authorization interceptors, and the session registry
 * that lets a password reset close a user's open sockets.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class StompAuthenticationConfiguration implements WebSocketMessageBrokerConfigurer {

    private final StompAuthenticationInterceptor authenticationInterceptor;
    private final StompSessionRegistry sessionRegistry;

    public StompAuthenticationConfiguration(StompAuthenticationInterceptor authenticationInterceptor,
                                            StompSessionRegistry sessionRegistry) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor);
    }

    /**
     * Tracks open sessions so a password reset can close them.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessionRegistry);
    }
}
