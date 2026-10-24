package io.devflow.ai.internal.breakdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.api.TaskBreakdownProposal.ProposedTask;
import io.devflow.ai.internal.llm.AiMalformedOutputException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskBreakdownValidator: proposal contract and normalization")
class TaskBreakdownValidatorTest {

    @Test
    void acceptsAndNormalizesAValidProposal() {
        TaskBreakdownProposal normalized = TaskBreakdownValidator.normalize(proposal(
                new ProposedTask("  Add   login\tform\u0007 ", "  Build the form.\r\nValidate input.  ",
                        List.of("  Create  component ", "Wire API")),
                task("Second"), task("Third")));

        ProposedTask first = normalized.subtasks().get(0);
        assertThat(first.title()).isEqualTo("Add login form");
        assertThat(first.description()).isEqualTo("Build the form.\nValidate input.");
        assertThat(first.checklist()).containsExactly("Create component", "Wire API");
        assertThat(normalized.subtasks()).hasSize(3);
    }

    @Test
    void acceptsTheBoundsExactly() {
        String title = "t".repeat(TaskBreakdownProposal.MAX_TITLE_LENGTH);
        String description = "d".repeat(TaskBreakdownProposal.MAX_DESCRIPTION_LENGTH);
        List<String> checklist = Collections.nCopies(TaskBreakdownProposal.MAX_CHECKLIST_ITEMS,
                "c".repeat(TaskBreakdownProposal.MAX_CHECKLIST_ITEM_LENGTH));
        ProposedTask max = new ProposedTask(title, description, checklist);

        assertThat(TaskBreakdownValidator.normalize(proposal(max, max, max, max, max)).subtasks()).hasSize(5);
    }

    @Test
    void rejectsSubtaskCountOutsideThreeToFive() {
        assertRejected(proposal(task("A"), task("B")));
        assertRejected(proposal(task("A"), task("B"), task("C"), task("D"), task("E"), task("F")));
        assertRejected(new TaskBreakdownProposal(null));
        assertRejected(new TaskBreakdownProposal(Arrays.asList(task("A"), null, task("C"))));
    }

    @Test
    void rejectsMissingOrOversizedFields() {
        assertRejected(proposal(new ProposedTask("   ", "desc", List.of("step")), task("B"), task("C")));
        assertRejected(proposal(new ProposedTask(null, "desc", List.of("step")), task("B"), task("C")));
        assertRejected(proposal(new ProposedTask("t".repeat(101), "desc", List.of("step")), task("B"), task("C")));
        assertRejected(proposal(new ProposedTask("Title", "", List.of("step")), task("B"), task("C")));
        assertRejected(proposal(new ProposedTask("Title", "d".repeat(501), List.of("step")), task("B"), task("C")));
    }

    @Test
    void rejectsInvalidChecklists() {
        assertRejected(proposal(new ProposedTask("Title", "desc", null), task("B"), task("C")));
        assertRejected(proposal(new ProposedTask("Title", "desc", List.of()), task("B"), task("C")));
        assertRejected(proposal(new ProposedTask("Title", "desc", Collections.nCopies(7, "step")),
                task("B"), task("C")));
        assertRejected(proposal(new ProposedTask("Title", "desc", List.of("ok", " ")), task("B"), task("C")));
        assertRejected(proposal(new ProposedTask("Title", "desc", Arrays.asList("ok", null)), task("B"), task("C")));
        assertRejected(proposal(new ProposedTask("Title", "desc", List.of("c".repeat(121))), task("B"), task("C")));
    }

    @Test
    void deterministicFallbackProposalPassesValidation() {
        assertThat(TaskBreakdownValidator.normalize(DeterministicTaskBreakdownResponder.PROPOSAL))
                .isEqualTo(DeterministicTaskBreakdownResponder.PROPOSAL);
    }

    private static void assertRejected(TaskBreakdownProposal proposal) {
        assertThatThrownBy(() -> TaskBreakdownValidator.normalize(proposal))
                .isInstanceOf(AiMalformedOutputException.class);
    }

    private static TaskBreakdownProposal proposal(ProposedTask... tasks) {
        return new TaskBreakdownProposal(new ArrayList<>(List.of(tasks)));
    }

    private static ProposedTask task(String title) {
        return new ProposedTask(title, "Do the " + title + " part.", List.of("Step one", "Step two"));
    }
}
