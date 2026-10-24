package io.devflow.ai.internal.llm;

import java.util.Optional;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A feature's fixed answer for {@link DeterministicChatModel}, so offline mode returns output that the
 * feature's own parsing and validation accept (e.g. a well-formed task breakdown).
 */
public interface DeterministicResponder {

    /** @return the answer for prompts this feature recognizes, or empty to let other responders try */
    Optional<String> respond(Prompt prompt);
}
