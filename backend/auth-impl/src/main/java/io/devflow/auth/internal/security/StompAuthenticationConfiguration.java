package io.devflow.auth.internal.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Installs authentication before Board and framework message authorization interceptors. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class StompAuthenticationConfiguration implements WebSocketMessageBrokerConfigurer {

    private final StompAuthenticationInterceptor authenticationInterceptor;

    public StompAuthenticationConfiguration(StompAuthenticationInterceptor authenticationInterceptor) {
        this.authenticationInterceptor = authenticationInterceptor;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor);
    }
}
