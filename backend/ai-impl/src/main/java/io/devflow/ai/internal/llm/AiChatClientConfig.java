package io.devflow.ai.internal.llm;

import io.devflow.ai.internal.config.AiProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * Wires the single shared {@link ChatClient} (T-030). Exactly one {@link ChatModel} bean exists: OpenAI
 * when a model and {@code OPENAI_API_KEY} are configured, otherwise {@link DeterministicChatModel}.
 *
 * <p>The OpenAI model is built here instead of by Spring AI's auto-config (disabled with
 * {@code spring.ai.model.chat=none}) so that: HTTP calls get explicit connect/read timeouts; Spring AI's
 * built-in retry (10 attempts, logs response bodies) is replaced by one Resilience4j retry + circuit
 * breaker in {@link AiChatGateway}; and error messages never carry provider response bodies.
 */
@Configuration
public class AiChatClientConfig {

    static final String RESILIENCE_NAME = "aiChat";

    private static final Logger log = LoggerFactory.getLogger(AiChatClientConfig.class);
    private static final RetryTemplate NO_RETRY = RetryTemplate.builder().maxAttempts(1).build();

    @Bean
    AiProviderInfo aiProviderInfo(AiProperties properties) {
        AiProviderInfo info = AiProviderInfo.resolve(properties);
        if (info.isDeterministic()) {
            log.warn("AI chat provider: deterministic fallback (reason={}). Set AI_MODEL_CHAT and OPENAI_API_KEY "
                    + "to enable the LLM.", info.fallbackReason());
        } else {
            log.info("AI chat provider: {} model={}", info.provider(), info.model());
        }
        return info;
    }

    @Bean
    ChatModel devflowChatModel(AiProperties properties, AiProviderInfo info,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        if (info.isDeterministic()) {
            return new DeterministicChatModel();
        }
        AiProperties.OpenAi openai = properties.getOpenai();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(openai.getBaseUrl())
                .apiKey(openai.getApiKey())
                .restClientBuilder(restClientBuilder.getIfAvailable(RestClient::builder)
                        .requestFactory(requestFactory(openai)))
                .responseErrorHandler(new ProviderResponseErrorHandler())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(info.model())
                .maxTokens(properties.getChat().getMaxTokens())
                .temperature(properties.getChat().getTemperature())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(NO_RETRY)
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    @Bean
    ChatClient devflowChatClient(ChatModel chatModel, ObjectProvider<ChatClient.Builder> builder) {
        return builder.getIfAvailable(() -> ChatClient.builder(chatModel)).build();
    }

    @Bean
    AiResilience aiResilience(AiProperties properties, ObjectProvider<RetryRegistry> retryRegistry,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistry) {
        AiProperties.Resilience settings = properties.getResilience();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(settings.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        settings.getInitialBackoff(), settings.getBackoffMultiplier()))
                .retryOnException(AiChatGateway::isTransient)
                .build();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .failureRateThreshold(settings.getFailureRateThreshold())
                .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedNumberOfCallsInHalfOpenState())
                // A rejected request (bad prompt, auth) is not a provider outage.
                .ignoreExceptions(NonTransientAiException.class)
                .build();
        // Registered in the shared registries so resilience4j_* metrics are exported.
        Retry retry = retryRegistry.getIfAvailable(RetryRegistry::ofDefaults).retry(RESILIENCE_NAME, retryConfig);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.getIfAvailable(CircuitBreakerRegistry::ofDefaults)
                .circuitBreaker(RESILIENCE_NAME, circuitBreakerConfig);
        return new AiResilience(retry, circuitBreaker);
    }

    private static JdkClientHttpRequestFactory requestFactory(AiProperties.OpenAi openai) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(openai.getConnectTimeout()).build());
        factory.setReadTimeout(openai.getReadTimeout());
        return factory;
    }
}
