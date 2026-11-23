package io.devflow.ai.internal.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.devflow.ai.internal.llm.AiMalformedOutputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CiSummaryValidator Unit Tests")
class CiSummaryValidatorTest {

    private final CiSummaryValidator validator = new CiSummaryValidator(100);

    @Test
    void acceptsValid3BulletSummaryUnderWordLimit() {
        String answer = """
                - Build failed due to missing dependency in build.gradle.
                - Error occurred during compilation of auth-impl module.
                - Add the required dependency and re-run gradle check.
                """;

        String validated = validator.validate(answer);

        assertThat(validated.lines().count()).isEqualTo(3);
        assertThat(validated).startsWith("- Build failed due to");
    }

    @Test
    void normalizesAsteriskBulletsToDashBullets() {
        String answer = """
                * Build failed due to missing dependency in build.gradle.
                * Error occurred during compilation of auth-impl module.
                * Add the required dependency and re-run gradle check.
                """;

        String validated = validator.validate(answer);

        assertThat(validated).isEqualTo("""
                - Build failed due to missing dependency in build.gradle.
                - Error occurred during compilation of auth-impl module.
                - Add the required dependency and re-run gradle check.
                """.strip());
    }

    @Test
    void rejectsBlankAnswer() {
        assertThatThrownBy(() -> validator.validate("   \n  "))
                .isInstanceOf(AiMalformedOutputException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsOutputWithTwoBullets() {
        String answer = """
                - Root cause: syntax error in main.go
                - Failed step: go test ./...
                """;

        assertThatThrownBy(() -> validator.validate(answer))
                .isInstanceOf(AiMalformedOutputException.class)
                .hasMessageContaining("exactly 3 bullet points, found 2");
    }

    @Test
    void rejectsOutputWithFourBullets() {
        String answer = """
                - Root cause: syntax error in main.go
                - Failed step: go test ./...
                - File: main.go line 42
                - Suggested fix: remove extra parenthesis
                """;

        assertThatThrownBy(() -> validator.validate(answer))
                .isInstanceOf(AiMalformedOutputException.class)
                .hasMessageContaining("exactly 3 bullet points, found 4");
    }

    @Test
    void rejectsOutputExceedingWordLimit() {
        StringBuilder longBullet = new StringBuilder("- Root cause: ");
        for (int i = 0; i < 110; i++) {
            longBullet.append("word").append(i).append(" ");
        }
        String answer = longBullet + "\n- Step: build\n- Fix: retry";

        assertThatThrownBy(() -> validator.validate(answer))
                .isInstanceOf(AiMalformedOutputException.class)
                .hasMessageContaining("exceeds word limit");
    }
}
