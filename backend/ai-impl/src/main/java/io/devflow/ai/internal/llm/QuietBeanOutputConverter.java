package io.devflow.ai.internal.llm;

import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * Spring AI's {@link BeanOutputConverter} (same JSON-schema format instructions and parsing), except that
 * an unparsable answer converts to {@code null} instead of a {@code RuntimeException}. That exception's
 * cause chain (Jackson messages) can quote answer text; returning {@code null} lets the gateway report a
 * content-free {@link AiMalformedOutputException}. {@code BeanOutputConverter} itself logs the raw answer
 * at ERROR on failure, so its logger is switched off in configuration.
 */
final class QuietBeanOutputConverter<T> implements StructuredOutputConverter<T> {

    private final BeanOutputConverter<T> delegate;

    QuietBeanOutputConverter(Class<T> type) {
        this.delegate = new BeanOutputConverter<>(type);
    }

    @Override
    public String getFormat() {
        return delegate.getFormat();
    }

    @Override
    public T convert(String text) {
        try {
            return delegate.convert(text);
        } catch (RuntimeException unparsable) {
            return null;
        }
    }
}
