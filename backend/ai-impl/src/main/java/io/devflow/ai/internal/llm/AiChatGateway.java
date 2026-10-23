package io.devflow.ai.internal.llm;

import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.exception.AiPromptRejectedException;
import io.devflow.ai.internal.exception.AiProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;

/**
 * The only path from AI features to the LLM (T-030). Every call goes through the shared
 * {@link ChatClient} and gets the same guards: prompt length limit, Resilience4j retry (transient
 * failures only) around a circuit breaker, and request/latency/token metrics.
 *
 * <p>Prompts and answers are never logged: they can contain user content, CI logs or secrets.
 */
@Service
public class AiChatGateway {

    static final String PROMPT_EMPTY = "AI_PROMPT_EMPTY";
    static final String PROMPT_TOO_LARGE = "AI_PROMPT_TOO_LARGE";

    private static final Logger log = LoggerFactory.getLogger(AiChatGateway.class);

    private final ChatClient chatClient;
    private final AiResilience resilience;
    private final AiMetrics metrics;
    private final AiProperties properties;
    private final AiProviderInfo providerInfo;

    public AiChatGateway(ChatClient chatClient, AiResilience resilience, AiMetrics metrics,
            AiProperties properties, AiProviderInfo providerInfo) {
        this.chatClient = chatClient;
        this.resilience = resilience;
        this.metrics = metrics;
        this.properties = properties;
        this.providerInfo = providerInfo;
    }

    /**
     * @param systemPrompt instructions; may be {@code null}
     * @param userPrompt   the content to answer; required
     * @throws AiPromptRejectedException     prompt empty or over {@code devflow.ai.chat.max-prompt-chars}
     * @throws AiProviderUnavailableException provider failed after retries, or the circuit is open
     */
    public AiCompletion complete(AiFeature feature, String systemPrompt, String userPrompt) {
        Objects.requireNonNull(feature, "feature");
        Timer.Sample sample = metrics.start();
        String result = "error";
        try {
            validate(systemPrompt, userPrompt);
            Supplier<AiCompletion> call = () -> invoke(systemPrompt, userPrompt);
            Supplier<AiCompletion> guarded = Retry.decorateSupplier(resilience.retry(),
                    CircuitBreaker.decorateSupplier(resilience.circuitBreaker(), call));
            AiCompletion completion = guarded.get();
            metrics.tokens(feature, completion.promptTokens(), completion.completionTokens());
            result = "success";
            return completion;
        } catch (AiPromptRejectedException rejected) {
            result = "rejected";
            throw rejected;
        } catch (CallNotPermittedException open) {
            result = "circuit_open";
            log.warn("AI provider call skipped: circuit open feature={} provider={}",
                    feature.tag(), providerInfo.provider());
            throw unavailable(open);
        } catch (RuntimeException failure) {
            log.error("AI provider call failed feature={} provider={} model={} exceptionType={} httpStatus={}",
                    feature.tag(), providerInfo.provider(), providerInfo.model(),
                    failure.getClass().getSimpleName(), httpStatus(failure));
            throw unavailable(failure);
        } finally {
            metrics.request(feature, result, sample);
        }
    }

    private AiCompletion invoke(String systemPrompt, String userPrompt) {
        ChatClient.ChatClientRequestSpec request = chatClient.prompt();
        if (StringUtils.hasText(systemPrompt)) {
            request = request.system(systemPrompt);
        }
        ChatResponse response = request.user(userPrompt).call().chatResponse();
        if (response == null || response.getResult() == null
                || !StringUtils.hasText(response.getResult().getOutput().getText())) {
            // Inside the circuit breaker: repeated empty answers count as provider failures.
            throw new IllegalStateException("AI provider returned an empty completion");
        }
        Usage usage = response.getMetadata().getUsage();
        String model = StringUtils.hasText(response.getMetadata().getModel())
                ? response.getMetadata().getModel() : providerInfo.model();
        return new AiCompletion(response.getResult().getOutput().getText(), model,
                tokens(usage == null ? null : usage.getPromptTokens()),
                tokens(usage == null ? null : usage.getCompletionTokens()));
    }

    private void validate(String systemPrompt, String userPrompt) {
        if (!StringUtils.hasText(userPrompt)) {
            throw new AiPromptRejectedException(PROMPT_EMPTY, "The request for the AI assistant is empty.");
        }
        long length = (long) userPrompt.length() + (systemPrompt == null ? 0 : systemPrompt.length());
        if (length > properties.getChat().getMaxPromptChars()) {
            throw new AiPromptRejectedException(PROMPT_TOO_LARGE, "The request is too large for the AI assistant.");
        }
    }

    private AiProviderUnavailableException unavailable(Throwable cause) {
        long retryAfter = Math.max(1, properties.getResilience().getWaitDurationInOpenState().toSeconds());
        return new AiProviderUnavailableException(retryAfter, cause);
    }

    /**
     * Retry predicate: provider 408/429/5xx, timeouts and connection failures. A read timeout usually
     * arrives as {@link ResourceAccessException}, but {@code JdkClientHttpRequestFactory} enforces it by
     * cancelling the response future and, depending on timing, the raw {@link CancellationException}
     * escapes unwrapped (seen on CI).
     */
    static boolean isTransient(Throwable failure) {
        return failure instanceof TransientAiException || failure instanceof ResourceAccessException
                || failure instanceof CancellationException;
    }

    private static Object httpStatus(Throwable failure) {
        if (failure instanceof ProviderResponseErrorHandler.TransientProviderException transientFailure) {
            return transientFailure.status();
        }
        if (failure instanceof ProviderResponseErrorHandler.NonTransientProviderException rejected) {
            return rejected.status();
        }
        return "n/a";
    }

    private static int tokens(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
