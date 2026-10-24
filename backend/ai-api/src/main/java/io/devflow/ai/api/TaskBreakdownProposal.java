package io.devflow.ai.api;

import java.util.List;

/**
 * An AI-proposed breakdown of a requirement into 3-5 subtasks. It is only a proposal: users review and
 * edit it before anything is created on the board, and the AI module never creates tasks itself
 * (PRODUCT_SPEC.md Section 5.2b — "editable before creation").
 *
 * <p>Also the structured-output target the LLM fills in, so it carries only fields the model produces.
 */
public record TaskBreakdownProposal(List<ProposedTask> subtasks) {

    public static final int MIN_SUBTASKS = 3;
    public static final int MAX_SUBTASKS = 5;
    public static final int MAX_TITLE_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_CHECKLIST_ITEMS = 6;
    public static final int MAX_CHECKLIST_ITEM_LENGTH = 120;

    // stream().toList(): unmodifiable but null-tolerant, so a model answer with null entries reaches
    // validation instead of failing inside deserialization.
    public TaskBreakdownProposal {
        subtasks = subtasks == null ? null : subtasks.stream().toList();
    }

    /**
     * @param title       short imperative title, at most {@value #MAX_TITLE_LENGTH} characters
     * @param description 1-3 sentences, at most {@value #MAX_DESCRIPTION_LENGTH} characters
     * @param checklist   1-{@value #MAX_CHECKLIST_ITEMS} concrete steps, each at most
     *                    {@value #MAX_CHECKLIST_ITEM_LENGTH} characters
     */
    public record ProposedTask(String title, String description, List<String> checklist) {

        public ProposedTask {
            checklist = checklist == null ? null : checklist.stream().toList();
        }
    }
}
