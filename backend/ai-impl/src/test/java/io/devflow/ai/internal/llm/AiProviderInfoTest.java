package io.devflow.ai.internal.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.devflow.ai.internal.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AiProviderInfo: provider selection from AI_MODEL_CHAT and OPENAI_API_KEY")
class AiProviderInfoTest {

    @Test
    void usesOpenAiWithConfiguredModelWhenKeyPresent() {
        AiProviderInfo info = AiProviderInfo.resolve(properties(" gpt-4.1-mini ", "sk-test"));

        assertThat(info.provider()).isEqualTo(AiProviderInfo.OPENAI);
        assertThat(info.model()).isEqualTo("gpt-4.1-mini");
        assertThat(info.fallbackReason()).isNull();
    }

    @Test
    void defaultsToGpt4oMini() {
        AiProviderInfo info = AiProviderInfo.resolve(properties(new AiProperties().getChat().getModel(), "sk-test"));

        assertThat(info.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void modelNoneSelectsDeterministicFallbackEvenWithKey() {
        AiProviderInfo info = AiProviderInfo.resolve(properties("NONE", "sk-test"));

        assertThat(info.isDeterministic()).isTrue();
        assertThat(info.model()).isEqualTo(DeterministicChatModel.MODEL);
        assertThat(info.fallbackReason()).isEqualTo("model_disabled");
    }

    @Test
    void missingKeySelectsDeterministicFallback() {
        AiProviderInfo info = AiProviderInfo.resolve(properties("gpt-4o-mini", "  "));

        assertThat(info.isDeterministic()).isTrue();
        assertThat(info.fallbackReason()).isEqualTo("api_key_missing");
    }

    @Test
    void legacyProviderValueMapsToDefaultModel() {
        AiProviderInfo info = AiProviderInfo.resolve(properties("openai", "sk-test"));

        assertThat(info.provider()).isEqualTo(AiProviderInfo.OPENAI);
        assertThat(info.model()).isEqualTo("gpt-4o-mini");
    }

    private static AiProperties properties(String model, String apiKey) {
        AiProperties properties = new AiProperties();
        properties.getChat().setModel(model);
        properties.getOpenai().setApiKey(apiKey);
        return properties;
    }
}
