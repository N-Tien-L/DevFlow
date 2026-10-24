package io.devflow.ai.internal.llm;

/**
 * Result of one structured LLM call.
 *
 * @param entity           the converted and validated answer
 * @param model            model id that produced it
 * @param promptTokens     provider-reported (or estimated, for the fallback) prompt tokens
 * @param completionTokens provider-reported (or estimated) completion tokens
 */
public record AiStructuredCompletion<T>(T entity, String model, int promptTokens, int completionTokens) {
}
