package io.devflow.ai.internal.breakdown;

import static io.devflow.ai.api.TaskBreakdownProposal.MAX_CHECKLIST_ITEMS;
import static io.devflow.ai.api.TaskBreakdownProposal.MAX_CHECKLIST_ITEM_LENGTH;
import static io.devflow.ai.api.TaskBreakdownProposal.MAX_DESCRIPTION_LENGTH;
import static io.devflow.ai.api.TaskBreakdownProposal.MAX_SUBTASKS;
import static io.devflow.ai.api.TaskBreakdownProposal.MAX_TITLE_LENGTH;
import static io.devflow.ai.api.TaskBreakdownProposal.MIN_SUBTASKS;

import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.api.TaskBreakdownProposal.ProposedTask;
import io.devflow.ai.internal.llm.AiMalformedOutputException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks a model answer against the proposal contract and returns a normalized copy: control characters
 * removed, whitespace trimmed (and collapsed in single-line fields). Anything outside the limits rejects
 * the whole answer; nothing is truncated or partially returned. Rejection messages name the rule only.
 */
final class TaskBreakdownValidator {

    // Control characters except tab and newline; also strips CR so line endings are uniform.
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private TaskBreakdownValidator() {
    }

    static TaskBreakdownProposal normalize(TaskBreakdownProposal proposal) {
        List<ProposedTask> subtasks = proposal.subtasks();
        if (subtasks == null || subtasks.size() < MIN_SUBTASKS || subtasks.size() > MAX_SUBTASKS) {
            throw new AiMalformedOutputException("subtask count outside " + MIN_SUBTASKS + "-" + MAX_SUBTASKS);
        }
        List<ProposedTask> normalized = new ArrayList<>(subtasks.size());
        for (ProposedTask subtask : subtasks) {
            if (subtask == null) {
                throw new AiMalformedOutputException("null subtask");
            }
            normalized.add(new ProposedTask(
                    singleLine(subtask.title(), MAX_TITLE_LENGTH, "title"),
                    text(subtask.description(), MAX_DESCRIPTION_LENGTH, "description"),
                    checklist(subtask.checklist())));
        }
        return new TaskBreakdownProposal(normalized);
    }

    private static List<String> checklist(List<String> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_CHECKLIST_ITEMS) {
            throw new AiMalformedOutputException("checklist size outside 1-" + MAX_CHECKLIST_ITEMS);
        }
        List<String> normalized = new ArrayList<>(items.size());
        for (String item : items) {
            normalized.add(singleLine(item, MAX_CHECKLIST_ITEM_LENGTH, "checklist item"));
        }
        return normalized;
    }

    private static String singleLine(String value, int maxLength, String field) {
        String cleaned = value == null ? "" : WHITESPACE_RUN.matcher(CONTROL.matcher(value).replaceAll(""))
                .replaceAll(" ").strip();
        return requireLength(cleaned, maxLength, field);
    }

    private static String text(String value, int maxLength, String field) {
        String cleaned = value == null ? "" : CONTROL.matcher(value).replaceAll("").strip();
        return requireLength(cleaned, maxLength, field);
    }

    private static String requireLength(String value, int maxLength, String field) {
        if (value.isEmpty() || value.length() > maxLength) {
            throw new AiMalformedOutputException(field + " length outside 1-" + maxLength);
        }
        return value;
    }
}
