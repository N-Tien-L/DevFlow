package io.devflow.ai.internal.llm;

/**
 * Result of one LLM call.
 *
 * @param text             the model's answer
 * @param model            model id that produced it
 * @param promptTokens     provider-reported (or estimated, for the fallback) prompt tokens
 * @param completionTokens provider-reported (or estimated) completion tokens
 */
public record AiCompletion(String text, String model, int promptTokens, int completionTokens) {
}
