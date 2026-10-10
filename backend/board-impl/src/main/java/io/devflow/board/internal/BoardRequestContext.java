package io.devflow.board.internal;

import java.util.UUID;
import org.slf4j.MDC;

final class BoardRequestContext {

    static final String CORRELATION_ID_KEY = "correlationId";
    static final String USER_ID_KEY = "userId";
    private static final String CLIENT_IP_KEY = "boardClientIp";
    static final String REQUEST_STARTED_AT_KEY = BoardRequestContext.class.getName() + ".startedAt";

    private BoardRequestContext() {
    }

    static UUID correlationId() {
        String value = MDC.get(CORRELATION_ID_KEY);
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // Replace invalid client correlation values with a server-generated UUID.
            }
        }
        return UUID.randomUUID();
    }

    static String clientIp() {
        String value = MDC.get(CLIENT_IP_KEY);
        return value == null || value.isBlank() ? "unknown" : value;
    }

    static String clientIpKey() {
        return CLIENT_IP_KEY;
    }
}
