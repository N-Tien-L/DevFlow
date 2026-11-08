package io.devflow.gitci.internal;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Stateless, bounded parser. Git text never supplies a user identity or a tenant. */
@Component
final class TaskReferenceParser {
    private static final Pattern REFERENCE = Pattern.compile("(?<![\\p{L}\\p{N}_#])#?T-([0-9]{3,4})(?![\\p{L}\\p{N}_])");
    List<String> parse(String input) {
        if (input == null) return List.of();
        if (input.length() > 65536 || input.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new WebhookFailure(422, "GIT_TEXT_LIMIT");
        var keys = new LinkedHashSet<String>();
        var matcher = REFERENCE.matcher(input);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (number == 0) continue;
            keys.add("T-" + (number < 100 ? (number < 10 ? "00" : "0") : "") + number);
            if (keys.size() > 20) throw new WebhookFailure(422, "GIT_REFERENCE_LIMIT");
        }
        return List.copyOf(keys);
    }
}
