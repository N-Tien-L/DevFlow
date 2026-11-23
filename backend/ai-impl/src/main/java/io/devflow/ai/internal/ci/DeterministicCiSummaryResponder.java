package io.devflow.ai.internal.ci;

import io.devflow.ai.internal.llm.DeterministicResponder;
import java.util.Optional;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Offline answer for CI failure summarization when running without an OpenAI key (T-031).
 * Returns a fixed, realistic 3-bullet summary under 100 words so that tests and offline mode
 * pass {@link CiSummaryValidator}.
 */
@Component
public class DeterministicCiSummaryResponder implements DeterministicResponder {

    static final String SUMMARY = """
            - CI pipeline failed during build or test execution.
            - Check the compilation errors and failing assertions in the log.
            - Fix the reported issues in the failing files and retry the build.
            """.strip();

    @Override
    public Optional<String> respond(Prompt prompt) {
        SystemMessage system = prompt.getSystemMessage();
        boolean matches = system != null && system.getText() != null
                && system.getText().startsWith(CiSummaryPrompts.ROLE_LINE);
        return matches ? Optional.of(SUMMARY) : Optional.empty();
    }
}
