package io.devflow.ai.internal.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.qa.ProjectSnapshot.AssigneeLoad;
import io.devflow.ai.internal.qa.ProjectSnapshot.Summary;
import io.devflow.ai.internal.qa.ProjectSnapshot.TaskEntry;
import io.devflow.board.api.ColumnStatusCategory;
import io.devflow.board.api.TaskSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns a project's tasks into the JSON data block for the model: exact counts over all tasks, then as
 * many tasks as fit the limits, unfinished first in board order. Titles and names are cleaned to one
 * line and cut to length; nothing else about a task (description, comments) is sent.
 */
final class ProjectSnapshotBuilder {

    static final String UNKNOWN_MEMBER = "Unknown member";
    static final int MAX_NAME_CHARS = 80;
    private static final String DONE = ColumnStatusCategory.DONE.name();
    private static final int DUE_SOON_DAYS = 7;
    // Control characters (newlines included) and whitespace runs collapse to one space.
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final AiProperties.Qa limits;

    ProjectSnapshotBuilder(AiProperties.Qa limits) {
        this.limits = limits;
    }

    /** The data block and how many tasks it lists. */
    record Built(String json, int totalTasks, int tasksListed) {
    }

    /**
     * @param names    display name per assignee id; a blank or missing name shows as {@value #UNKNOWN_MEMBER}
     * @param maxChars character budget for the JSON, on top of {@code devflow.ai.qa.max-context-chars};
     *                 the summary is always kept
     */
    Built build(List<TaskSummary> tasks, Function<UUID, String> names, UUID askerId, LocalDate today, int maxChars) {
        List<TaskSummary> ordered = new ArrayList<>(tasks);
        // Stable sort: board display order is kept within each group.
        ordered.sort(Comparator.comparingInt(task -> isDone(task) ? 1 : 0));
        Map<UUID, String> nameCache = new HashMap<>();
        Function<UUID, String> name = id -> id == null ? null
                : nameCache.computeIfAbsent(id, key -> oneLine(names.apply(key), MAX_NAME_CHARS, UNKNOWN_MEMBER));

        int budget = Math.min(maxChars, limits.getMaxContextChars());
        List<TaskEntry> entries = new ArrayList<>();
        int used = json(snapshot(ordered, entries, name, askerId, today)).length();
        for (TaskSummary task : ordered) {
            if (entries.size() >= limits.getMaxTasks()) {
                break;
            }
            TaskEntry entry = entry(task, name, today);
            int cost = json(entry).length() + 1;
            if (used + cost > budget) {
                break;
            }
            entries.add(entry);
            used += cost;
        }
        // Summary counts grow by a digit or two once tasks are listed; drop tasks until it fits.
        String json = json(snapshot(ordered, entries, name, askerId, today));
        while (json.length() > budget && !entries.isEmpty()) {
            entries.remove(entries.size() - 1);
            json = json(snapshot(ordered, entries, name, askerId, today));
        }
        return new Built(json, ordered.size(), entries.size());
    }

    private ProjectSnapshot snapshot(List<TaskSummary> tasks, List<TaskEntry> listed, Function<UUID, String> name,
            UUID askerId, LocalDate today) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (ColumnStatusCategory category : ColumnStatusCategory.values()) {
            byStatus.put(category.name(), 0);
        }
        // Per assignee: {not done, done, overdue}, in order of first appearance.
        Map<UUID, int[]> loads = new LinkedHashMap<>();
        int done = 0;
        int unassignedNotDone = 0;
        int overdueNotDone = 0;
        int dueSoonNotDone = 0;
        for (TaskSummary task : tasks) {
            byStatus.merge(statusOf(task), 1, Integer::sum);
            boolean finished = isDone(task);
            boolean overdue = isOverdue(task, today);
            if (finished) {
                done++;
            } else {
                if (task.assigneeId() == null) {
                    unassignedNotDone++;
                }
                if (overdue) {
                    overdueNotDone++;
                }
                if (task.dueDate() != null && !task.dueDate().isBefore(today)
                        && !task.dueDate().isAfter(today.plusDays(DUE_SOON_DAYS))) {
                    dueSoonNotDone++;
                }
            }
            if (task.assigneeId() != null) {
                int[] load = loads.computeIfAbsent(task.assigneeId(), ignored -> new int[3]);
                load[finished ? 1 : 0]++;
                if (overdue) {
                    load[2]++;
                }
            }
        }
        List<AssigneeLoad> assignees = loads.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<UUID, int[]> load) -> load.getValue()[0]).reversed())
                .limit(limits.getMaxAssignees())
                .map(load -> new AssigneeLoad(name.apply(load.getKey()), load.getKey().equals(askerId),
                        load.getValue()[0], load.getValue()[1], load.getValue()[2]))
                .toList();
        Summary summary = new Summary(today.toString(), tasks.size(), listed.size(), tasks.size() - listed.size(),
                byStatus, tasks.size() - done, done, unassignedNotDone, overdueNotDone, dueSoonNotDone, assignees,
                loads.size() - assignees.size());
        return new ProjectSnapshot(summary, List.copyOf(listed));
    }

    private TaskEntry entry(TaskSummary task, Function<UUID, String> name, LocalDate today) {
        return new TaskEntry(oneLine(task.title(), limits.getMaxTitleChars(), "(untitled)"), statusOf(task),
                name.apply(task.assigneeId()), task.dueDate() == null ? null : task.dueDate().toString(),
                isOverdue(task, today));
    }

    static String oneLine(String value, int maxChars, String whenBlank) {
        String cleaned = value == null ? ""
                : WHITESPACE_RUN.matcher(CONTROL.matcher(value).replaceAll(" ")).replaceAll(" ").strip();
        if (cleaned.isEmpty()) {
            return whenBlank;
        }
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars - 1).strip() + "\u2026";
    }

    private static String statusOf(TaskSummary task) {
        return task.status() == null ? "UNKNOWN" : task.status();
    }

    private static boolean isDone(TaskSummary task) {
        return DONE.equals(task.status());
    }

    private static boolean isOverdue(TaskSummary task, LocalDate today) {
        return !isDone(task) && task.dueDate() != null && task.dueDate().isBefore(today);
    }

    private String json(Object value) {
        try {
            return ProjectSnapshot.JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Project snapshot is not serializable", e);
        }
    }
}
