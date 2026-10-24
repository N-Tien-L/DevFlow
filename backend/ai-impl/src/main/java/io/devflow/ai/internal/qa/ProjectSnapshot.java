package io.devflow.ai.internal.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * The project data block given to the model, serialized as JSON so task titles and names are escaped
 * strings and cannot change the block's structure. Counts in {@link Summary} are computed in code over
 * every task; {@code tasks} may be a partial list.
 */
record ProjectSnapshot(Summary summary, List<TaskEntry> tasks) {

    /** Own mapper: the block's shape must not change with the application's Jackson settings. */
    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param today          UTC date the overdue and due-soon counts refer to
     * @param byStatus       tasks per status category, in board order (TODO, IN_PROGRESS, IN_REVIEW, DONE)
     * @param tasksNotListed tasks counted here but missing from {@code tasks} (finished ones are left out first)
     * @param otherAssignees people with tasks who are not in {@code assignees}
     */
    record Summary(String today, int totalTasks, int tasksListed, int tasksNotListed, Map<String, Integer> byStatus,
            int notDone, int done, int unassignedNotDone, int overdueNotDone, int dueWithin7DaysNotDone,
            List<AssigneeLoad> assignees, int otherAssignees) {
    }

    /** @param asker true for the person asking the question */
    record AssigneeLoad(String name, boolean asker, int notDone, int done, int overdue) {
    }

    /**
     * @param assignee display name, {@code null} when unassigned
     * @param due      ISO date, {@code null} when there is no due date
     */
    record TaskEntry(String title, String status, String assignee, String due, boolean overdue) {
    }
}
