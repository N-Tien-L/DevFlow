package io.devflow.gitci.internal;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class GitReferenceSourcePolicy {
    enum Source { MESSAGE, TITLE, BRANCH }
    record References(List<String> keys, Source source) {
        References { keys = List.copyOf(keys); }
    }
    private final TaskReferenceParser parser;
    GitReferenceSourcePolicy(TaskReferenceParser parser) { this.parser = parser; }
    References select(String direct, String branch, Source directSource) {
        bounded(branch);
        if (directSource == Source.TITLE) bounded(direct);
        var primary = parser.parse(direct);
        // Validate every allowed source even if precedence excludes it from linking.
        var fallback = parser.parse(branch);
        return primary.isEmpty() ? new References(fallback, Source.BRANCH) : new References(primary, directSource);
    }
    static void bounded(String input) {
        if (input == null || input.isBlank() || input.indexOf('\0') >= 0) throw new WebhookFailure(400, "GIT_INVALID_FIELD");
        if (input.codePointCount(0, input.length()) > 255) throw new WebhookFailure(422, "GIT_TEXT_LIMIT");
    }
}
