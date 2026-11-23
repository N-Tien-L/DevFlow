package io.devflow.ai.internal.ci;

import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.llm.AiMalformedOutputException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates CI summary output from LLM (T-031). Enforces exactly 3 markdown bullet points
 * and word limit under the configured threshold (default 100 words).
 */
@Component
public class CiSummaryValidator {

    public static final int DEFAULT_MAX_WORDS = 100;

    private final int maxWords;

    @Autowired
    public CiSummaryValidator(AiProperties properties) {
        this(properties != null ? properties.getCiSummary().getMaxSummaryWords() : DEFAULT_MAX_WORDS);
    }

    public CiSummaryValidator(int maxWords) {
        this.maxWords = maxWords > 0 ? maxWords : DEFAULT_MAX_WORDS;
    }

    /**
     * Validates and normalizes the raw output.
     *
     * @return normalized 3-bullet markdown summary
     * @throws AiMalformedOutputException if output does not conform to rules
     */
    public String validate(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            throw new AiMalformedOutputException("CI summary answer is blank");
        }

        List<String> bullets = rawOutput.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("- ") || line.startsWith("* "))
                .map(line -> line.startsWith("* ") ? "- " + line.substring(2).strip() : line)
                .toList();

        if (bullets.size() != 3) {
            throw new AiMalformedOutputException(
                    "CI summary must contain exactly 3 bullet points, found " + bullets.size());
        }

        String normalized = String.join("\n", bullets);
        int words = countWords(normalized);
        if (words > maxWords) {
            throw new AiMalformedOutputException(
                    "CI summary exceeds word limit of " + maxWords + " words (actual: " + words + ")");
        }

        return normalized;
    }

    public static int countWords(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
