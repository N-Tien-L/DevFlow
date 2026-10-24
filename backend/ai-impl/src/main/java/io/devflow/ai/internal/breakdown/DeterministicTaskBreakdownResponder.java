package io.devflow.ai.internal.breakdown;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.api.TaskBreakdownProposal.ProposedTask;
import io.devflow.ai.internal.llm.DeterministicResponder;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Offline answer for task breakdown: a fixed, generic three-step plan in the structured-output JSON shape,
 * so the fallback model yields a proposal that passes {@link TaskBreakdownValidator}.
 */
@Component
class DeterministicTaskBreakdownResponder implements DeterministicResponder {

    static final TaskBreakdownProposal PROPOSAL = new TaskBreakdownProposal(List.of(
            new ProposedTask("Clarify scope and acceptance criteria",
                    "Review the requirement with the team and agree on what done means.",
                    List.of("List open questions", "Write acceptance criteria", "Confirm scope with the requester")),
            new ProposedTask("Implement the change",
                    "Build the agreed behaviour in small, reviewable steps.",
                    List.of("Create a feature branch", "Implement the core logic", "Handle edge cases and errors")),
            new ProposedTask("Test and release",
                    "Verify the change against the acceptance criteria and ship it.",
                    List.of("Add automated tests", "Request code review", "Deploy and verify"))));

    private final String json;

    DeterministicTaskBreakdownResponder() {
        try {
            this.json = new ObjectMapper().writeValueAsString(PROPOSAL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<String> respond(Prompt prompt) {
        SystemMessage system = prompt.getSystemMessage();
        boolean breakdown = system != null && system.getText() != null
                && system.getText().startsWith(TaskBreakdownPrompts.ROLE_LINE);
        return breakdown ? Optional.of(json) : Optional.empty();
    }
}
