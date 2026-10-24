package io.devflow.ai.internal.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * LLM client settings for the AI service module (T-030). Bound to prefix {@code devflow.ai}.
 *
 * <p>The chat model name comes from {@code AI_MODEL_CHAT}; {@code none} (or a missing
 * {@code OPENAI_API_KEY}) selects the deterministic fallback model so the app boots and CI
 * runs without a provider key.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "devflow.ai")
public class AiProperties {

    @Valid
    private final Chat chat = new Chat();

    @Valid
    private final OpenAi openai = new OpenAi();

    @Valid
    private final Resilience resilience = new Resilience();

    @Valid
    private final RateLimit rateLimit = new RateLimit();

    @Valid
    private final Breakdown breakdown = new Breakdown();

    public Chat getChat() {
        return chat;
    }

    public OpenAi getOpenai() {
        return openai;
    }

    public Resilience getResilience() {
        return resilience;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Breakdown getBreakdown() {
        return breakdown;
    }

    /** Per-user token bucket shared by all AI features (each call consumes one token). */
    public static class RateLimit {

        @Min(1)
        private long capacity = 10;

        @NotNull
        private Duration period = Duration.ofMinutes(10);

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }
    }

    /** Task breakdown proposals (T-032). */
    public static class Breakdown {

        /** Completion-token cap; a 5-subtask answer with checklists needs more than the chat default. */
        @Min(256)
        @Max(16384)
        private int maxOutputTokens = 2048;

        /** Model calls per request when the answer is malformed or invalid (1 = no second try). */
        @Min(1)
        @Max(3)
        private int maxOutputAttempts = 2;

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public int getMaxOutputAttempts() {
            return maxOutputAttempts;
        }

        public void setMaxOutputAttempts(int maxOutputAttempts) {
            this.maxOutputAttempts = maxOutputAttempts;
        }
    }

    public static class Chat {

        /** Provider model id, or {@code none} to force the deterministic fallback. */
        private String model = "gpt-4o-mini";

        /** Upper bound on completion tokens per call (cost and latency guard). */
        @Min(1)
        @Max(16384)
        private int maxTokens = 1024;

        @DecimalMin("0.0")
        @DecimalMax("2.0")
        private double temperature = 0.2;

        /** Maximum combined system + user prompt length in characters; longer prompts are rejected. */
        @Min(1)
        @Max(500_000)
        private int maxPromptChars = 16_000;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxPromptChars() {
            return maxPromptChars;
        }

        public void setMaxPromptChars(int maxPromptChars) {
            this.maxPromptChars = maxPromptChars;
        }
    }

    public static class OpenAi {

        /** Secret; never logged. Blank selects the deterministic fallback. */
        private String apiKey = "";

        /** OpenAI or an OpenAI-compatible endpoint (tests point this at a local stub). */
        @NotBlank
        private String baseUrl = "https://api.openai.com";

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(5);

        @NotNull
        private Duration readTimeout = Duration.ofSeconds(20);

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /**
     * Retry and circuit breaker for provider calls. Configured here rather than under
     * {@code resilience4j.*} because which failures are transient is decided in code.
     */
    public static class Resilience {

        /** Total attempts including the first call; only transient failures are retried. */
        @Min(1)
        @Max(5)
        private int maxAttempts = 3;

        @NotNull
        private Duration initialBackoff = Duration.ofMillis(500);

        @DecimalMin("1.0")
        private double backoffMultiplier = 2.0;

        @Min(1)
        private int slidingWindowSize = 20;

        @Min(1)
        private int minimumNumberOfCalls = 10;

        @DecimalMin("1.0")
        @DecimalMax("100.0")
        private float failureRateThreshold = 50;

        @NotNull
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);

        @Min(1)
        private int permittedNumberOfCallsInHalfOpenState = 3;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }
    }
}
