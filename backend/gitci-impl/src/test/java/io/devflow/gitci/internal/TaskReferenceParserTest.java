package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TaskReferenceParserTest {
    private final TaskReferenceParser parser = new TaskReferenceParser();
    private final GitReferenceSourcePolicy policy = new GitReferenceSourcePolicy(parser);

    @ParameterizedTest
    @ValueSource(strings = {"feat(auth): T-003 setup jwt", "fix #T-003", "feature/T-003-board", "(T-003)", "Việt: 🎯 T-003!"})
    void acceptsConvention(String input) { assertThat(parser.parse(input)).containsExactly("T-003"); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "no task", "T-000", "T-0000", "T-01", "T-10000", "T-00345", "AT-003", "T-003abc",
            "foo_T-003", "##T-003", "t-003", "T–003", "T- 003", "T-００３", "éT-003", "T-003中"})
    void rejectsPartialOrInvalidTokens(String input) { assertThat(parser.parse(input)).isEmpty(); }

    @Test void canonicalizesAndPreservesOrder() {
        assertThat(parser.parse("T-003\nT-0003 T-001 T-9999 T-003")).containsExactly("T-003", "T-001", "T-9999");
    }
    @Test void enforcesByteAndUniqueKeyBoundsWithoutTruncation() {
        assertThat(parser.parse("a".repeat(65530) + " T-003")).containsExactly("T-003");
        assertThatThrownBy(() -> parser.parse("a".repeat(65531) + " T-003")).isInstanceOf(WebhookFailure.class);
        assertThat(parser.parse("é".repeat(32765) + " T-003")).containsExactly("T-003");
        assertThatThrownBy(() -> parser.parse("é".repeat(32766) + " T-003")).isInstanceOf(WebhookFailure.class);
        String twenty = IntStream.rangeClosed(1,20).mapToObj(n -> String.format("T-%03d", n)).collect(java.util.stream.Collectors.joining(" "));
        assertThat(parser.parse(twenty)).hasSize(20);
        assertThatThrownBy(() -> parser.parse(twenty + " T-021")).isInstanceOf(WebhookFailure.class);
    }
    @Test void boundedNearMatchesAndConcurrentRequests() {
        org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(2), () -> parser.parse("TT-00000 ".repeat(7000)));
        IntStream.range(0,200).parallel().forEach(i -> {
            String key = i % 2 == 0 ? "T-001" : "T-002";
            assertThat(parser.parse(key)).containsExactly(key);
        });
    }
    @Test void directReferencesTakePrecedenceWithoutResolvingExistence() {
        assertThat(policy.select("T-999", "feature/T-002", GitReferenceSourcePolicy.Source.MESSAGE).keys()).containsExactly("T-999");
        var fallback = policy.select("no code", "feature/T-002", GitReferenceSourcePolicy.Source.MESSAGE);
        assertThat(fallback.keys()).containsExactly("T-002");
        assertThat(fallback.source()).isEqualTo(GitReferenceSourcePolicy.Source.BRANCH);
        assertThat(policy.select("T-001", "feature/T-002", GitReferenceSourcePolicy.Source.TITLE).keys()).containsExactly("T-001");
        assertThat(policy.select("no code", "feature/T-002", GitReferenceSourcePolicy.Source.TITLE).keys()).containsExactly("T-002");
        assertThat(policy.select("title", "a".repeat(255), GitReferenceSourcePolicy.Source.TITLE).keys()).isEmpty();
        assertThat(policy.select("a".repeat(255), "branch", GitReferenceSourcePolicy.Source.TITLE).keys()).isEmpty();
        assertThatThrownBy(() -> policy.select("title", "a".repeat(256), GitReferenceSourcePolicy.Source.TITLE)).isInstanceOf(WebhookFailure.class);
        assertThatThrownBy(() -> policy.select("a".repeat(256), "branch", GitReferenceSourcePolicy.Source.TITLE)).isInstanceOf(WebhookFailure.class);
    }
}
