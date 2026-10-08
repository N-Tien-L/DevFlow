package io.devflow.board.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Records low-cardinality request counts and latency for Board API routes. */
@Component
public class BoardApiMetricsInterceptor implements HandlerInterceptor {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public BoardApiMetricsInterceptor(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        Object startedAt = request.getAttribute(BoardRequestContext.REQUEST_STARTED_AT_KEY);
        if (registry == null || !(startedAt instanceof Long startNanos)) {
            return;
        }

        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern instanceof String value ? value : "unmatched";
        String method = request.getMethod();
        String status = Integer.toString(response.getStatus());
        registry.counter("devflow.board.api.requests", "method", method, "route", route, "status", status)
                .increment();
        Timer.builder("devflow.board.api.duration")
                .tag("method", method)
                .tag("route", route)
                .tag("status", status)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
