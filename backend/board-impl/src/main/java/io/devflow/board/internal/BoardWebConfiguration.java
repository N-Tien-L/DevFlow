package io.devflow.board.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BoardWebConfiguration implements WebMvcConfigurer {

    private final BoardApiMetricsInterceptor metricsInterceptor;

    public BoardWebConfiguration(BoardApiMetricsInterceptor metricsInterceptor) {
        this.metricsInterceptor = metricsInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(metricsInterceptor)
                .addPathPatterns("/api/v1/boards/**", "/api/v1/columns/**", "/api/v1/tasks/**",
                        "/api/v1/workspaces/*/boards");
    }
}
