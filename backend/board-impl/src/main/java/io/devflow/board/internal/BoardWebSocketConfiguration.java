package io.devflow.board.internal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Installs Board topic authorization around the shared STOMP broker. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class BoardWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final BoardStompAuthorizationInterceptor inboundAuthorization;
    private final BoardOutboundAuthorizationInterceptor outboundAuthorization;

    public BoardWebSocketConfiguration(
            BoardStompAuthorizationInterceptor inboundAuthorization,
            BoardOutboundAuthorizationInterceptor outboundAuthorization) {
        this.inboundAuthorization = inboundAuthorization;
        this.outboundAuthorization = outboundAuthorization;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(inboundAuthorization);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors((ExecutorChannelInterceptor) outboundAuthorization);
    }
}
