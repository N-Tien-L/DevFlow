package io.devflow.ai.api;

import java.util.List;

/**
 * An AI-proposed breakdown of a requirement/error description into subtasks. Users review
 * and edit the proposal before anything is created on the board (PRODUCT_SPEC.md
 * Section 5.2b — "editable before creation").
 */
public record TaskBreakdownProposal(String rawDescription, List<ProposedTask> proposedTasks) {

    public record ProposedTask(String title, String description) {
    }
}
