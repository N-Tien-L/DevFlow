package io.devflow.ai.internal.llm;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Offline {@link ChatModel} used when no provider is configured ({@code AI_MODEL_CHAT=none} or no
 * {@code OPENAI_API_KEY}). It sits behind the same {@code ChatClient} as the real provider, so callers
 * need no special casing, and always returns the same text so CI and offline demos are reproducible.
 * Token usage is a rough chars/4 estimate so metrics stay populated.
 */
public final class DeterministicChatModel implements ChatModel {

    public static final String MODEL = "devflow-deterministic";
    public static final String RESPONSE_TEXT = "DevFlow AI is running in offline mode: no AI provider is configured, "
            + "so this is a fixed placeholder response.";

    @Override
    public ChatResponse call(Prompt prompt) {
        int promptChars = prompt.getInstructions().stream()
                .map(Message::getText)
                .mapToInt(text -> text == null ? 0 : text.length())
                .sum();
        Generation generation = new Generation(new AssistantMessage(RESPONSE_TEXT),
                ChatGenerationMetadata.builder().finishReason("STOP").build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(MODEL)
                .usage(new DefaultUsage(estimateTokens(promptChars), estimateTokens(RESPONSE_TEXT.length())))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().model(MODEL).build();
    }

    private static int estimateTokens(int chars) {
        return (chars + 3) / 4;
    }
}
