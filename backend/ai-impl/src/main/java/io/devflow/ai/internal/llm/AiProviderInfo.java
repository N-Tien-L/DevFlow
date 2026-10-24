package io.devflow.ai.internal.llm;

import io.devflow.ai.internal.config.AiProperties;
import org.springframework.util.StringUtils;

/**
 * Which chat provider backs the shared {@code ChatClient}, resolved once at startup.
 *
 * @param provider       {@code openai} or {@code deterministic}
 * @param model          model id sent to the provider (or the fallback's fixed id)
 * @param fallbackReason why the deterministic model was chosen; {@code null} for a real provider
 */
public record AiProviderInfo(String provider, String model, String fallbackReason) {

    public static final String OPENAI = "openai";
    public static final String DETERMINISTIC = "deterministic";
    static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";

    public boolean isDeterministic() {
        return DETERMINISTIC.equals(provider);
    }

    /**
     * {@code AI_MODEL_CHAT=none} or no API key selects the fallback. The legacy value
     * {@code openai} (provider name, pre-T-030) maps to the default model.
     */
    public static AiProviderInfo resolve(AiProperties properties) {
        String model = StringUtils.hasText(properties.getChat().getModel())
                ? properties.getChat().getModel().trim() : "";
        if (model.isEmpty() || "none".equalsIgnoreCase(model)) {
            return new AiProviderInfo(DETERMINISTIC, DeterministicChatModel.MODEL, "model_disabled");
        }
        if (!StringUtils.hasText(properties.getOpenai().getApiKey())) {
            return new AiProviderInfo(DETERMINISTIC, DeterministicChatModel.MODEL, "api_key_missing");
        }
        if (OPENAI.equalsIgnoreCase(model)) {
            model = DEFAULT_OPENAI_MODEL;
        }
        return new AiProviderInfo(OPENAI, model, null);
    }
}
