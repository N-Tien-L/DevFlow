package io.devflow.ai.internal.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.devflow.ai.internal.llm.DeterministicResponder;
import io.devflow.ai.internal.qa.ProjectSnapshot.AssigneeLoad;
import io.devflow.ai.internal.qa.ProjectSnapshot.Summary;
import io.devflow.ai.internal.qa.ProjectSnapshot.TaskEntry;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Offline answer for project Q&amp;A, read from the same data block the real model gets. It is only a stand-in
 * for when no provider is configured, so it is a strict allowlist: the whole question (normalized) must match
 * one of a few fully defined English or Vietnamese patterns, otherwise the answer is
 * {@link ProjectQueryPrompts#NO_INFORMATION}. The patterns are:
 * <ul>
 *   <li>how many tasks are left in the project;</li>
 *   <li>how many tasks one person has ("Bob", "Bob Tran", "I"/"t&ocirc;i");</li>
 *   <li>who is working on task X, X being exactly one listed title.</li>
 * </ul>
 * Nothing else is answered: negations ("not assigned to", "except"), time ranges, labels, columns, several
 * people or tasks, or a name that matches nobody. A name that matches more than one person is reported as
 * ambiguous. Task titles are only ever compared, never obeyed.
 */
@Component
class DeterministicProjectQueryResponder implements DeterministicResponder {

    static final String AMBIGUOUS = "More than one person or task in this project's data matches that, "
            + "so I can't tell which one you mean.";

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final String TASKS = "(?:tasks|cards|tickets)";
    private static final String VI_TASKS = "(?:task|viec|cong viec)";
    private static final String LEFT = "(?:left|remaining|open|not done|unfinished)";
    private static final String VI_LEFT = "(?:chua xong|chua hoan thanh)";

    // Each pattern must match the whole normalized question (Matcher.matches()).
    private static final List<Pattern> PROJECT_COUNT = patterns(
            "how many " + TASKS + " are (?:still )?" + LEFT + "(?: in (?:this|the) project)?",
            "how many " + TASKS + " (?:still )?" + LEFT + "(?: in (?:this|the) project)?",
            "(?:du an )?con bao nhieu " + VI_TASKS + "(?: " + VI_LEFT + ")?(?: trong du an(?: nay)?)?",
            "bao nhieu " + VI_TASKS + " " + VI_LEFT + "(?: trong du an(?: nay)?)?");
    private static final List<Pattern> PERSON_COUNT = patterns(
            "how many " + TASKS + " (?:does|do) (?<who>.+?) have(?: " + LEFT + ")?",
            "how many " + TASKS + " are assigned to (?<who>.+)",
            "(?<who>.+?) con bao nhieu " + VI_TASKS + "(?: " + VI_LEFT + ")?",
            "(?:co )?bao nhieu " + VI_TASKS + " (?:dang )?(?:duoc )?giao cho (?<who>.+)",
            "so luong " + VI_TASKS + " cua (?<who>.+)");
    private static final List<Pattern> WHO_IS_ON_TASK = patterns(
            "who (?:is|s) (?:working on|assigned to|doing|handling|responsible for) (?:the )?(?:task |card )?(?<task>.+)",
            "who (?:owns|works on) (?:the )?(?:task |card )?(?<task>.+)",
            "ai (?:dang )?(?:lam|phu trach|nhan) (?:task |viec |cong viec |the )?(?<task>.+)");
    /** Words that turn a name capture into a compound or negated question. */
    private static final Pattern NOT_ONE_PERSON = Pattern.compile(
            "\\b(?:and|or|but|except|not|without|nor|va|hoac|nhung|ngoai|tru|khong|chua)\\b");
    private static final Set<String> SELF = Set.of("i", "me", "toi");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "TODO", "to do", "IN_PROGRESS", "in progress", "IN_REVIEW", "in review", "DONE", "done");

    @Override
    public Optional<String> respond(Prompt prompt) {
        SystemMessage system = prompt.getSystemMessage();
        if (system == null || system.getText() == null || !system.getText().startsWith(ProjectQueryPrompts.ROLE_LINE)) {
            return Optional.empty();
        }
        String user = prompt.getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.USER)
                .map(message -> message.getText() == null ? "" : message.getText())
                .reduce((first, last) -> last)
                .orElse("");
        Matcher blocks = ProjectQueryPrompts.BLOCKS.matcher(user);
        if (!blocks.find()) {
            return Optional.of(ProjectQueryPrompts.NO_INFORMATION);
        }
        try {
            ProjectSnapshot data = ProjectSnapshot.JSON.readValue(blocks.group(2), ProjectSnapshot.class);
            return Optional.of(answer(data, normalize(blocks.group(3))));
        } catch (JsonProcessingException unreadable) {
            return Optional.of(ProjectQueryPrompts.NO_INFORMATION);
        }
    }

    private static String answer(ProjectSnapshot data, String question) {
        if (PROJECT_COUNT.stream().anyMatch(pattern -> pattern.matcher(question).matches())) {
            return projectCount(data.summary());
        }
        Optional<String> person = capture(PERSON_COUNT, question, "who");
        if (person.isPresent()) {
            // "Bob and Alice", "Bob not", "ngoai Bob": not one person.
            return NOT_ONE_PERSON.matcher(person.get()).find()
                    ? ProjectQueryPrompts.NO_INFORMATION : personCount(data.summary(), person.get());
        }
        return capture(WHO_IS_ON_TASK, question, "task")
                .map(title -> whoIsOn(data.tasks(), title))
                .orElse(ProjectQueryPrompts.NO_INFORMATION);
    }

    private static String personCount(Summary summary, String who) {
        // Anyone not named in the summary, or named only as "Unknown member", might be the one meant.
        boolean everyoneNamed = summary.otherAssignees() == 0 && summary.assignees().stream()
                .noneMatch(person -> ProjectSnapshotBuilder.UNKNOWN_MEMBER.equals(person.name()));
        if (!everyoneNamed) {
            return ProjectQueryPrompts.NO_INFORMATION;
        }
        if (SELF.contains(who)) {
            return summary.assignees().stream().filter(AssigneeLoad::asker).findFirst()
                    .map(DeterministicProjectQueryResponder::describe)
                    .orElse("You have no tasks assigned in this project.");
        }
        List<AssigneeLoad> matches = summary.assignees().stream()
                .filter(person -> normalize(person.name()).equals(who)).toList();
        if (matches.isEmpty() && !who.contains(" ")) {
            // A first or last name on its own.
            matches = summary.assignees().stream()
                    .filter(person -> List.of(normalize(person.name()).split(" ")).contains(who)).toList();
        }
        return switch (matches.size()) {
            case 0 -> ProjectQueryPrompts.NO_INFORMATION;
            case 1 -> describe(matches.get(0));
            default -> AMBIGUOUS;
        };
    }

    /** The title must be exactly one listed task's title, so "X and Y" or "X last week" match nothing. */
    private static String whoIsOn(List<TaskEntry> tasks, String title) {
        List<TaskEntry> matches = tasks.stream().filter(task -> normalize(task.title()).equals(title)).toList();
        return switch (matches.size()) {
            case 0 -> ProjectQueryPrompts.NO_INFORMATION;
            case 1 -> assignee(matches.get(0));
            default -> AMBIGUOUS;
        };
    }

    /** The named group of the first pattern that matches the whole question. */
    private static Optional<String> capture(List<Pattern> patterns, String question, String group) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(question);
            if (matcher.matches()) {
                return Optional.of(matcher.group(group));
            }
        }
        return Optional.empty();
    }

    private static String assignee(TaskEntry task) {
        String status = STATUS_LABELS.getOrDefault(task.status(), task.status());
        if (task.assignee() == null) {
            return "\"" + task.title() + "\" is not assigned to anyone (status: " + status + ").";
        }
        return "\"" + task.title() + "\" is assigned to " + task.assignee() + " (status: " + status + ").";
    }

    private static String projectCount(Summary summary) {
        Map<String, Integer> byStatus = summary.byStatus();
        return summary.notDone() + " of " + summary.totalTasks() + " tasks are not done yet (to do: "
                + byStatus.getOrDefault("TODO", 0) + ", in progress: " + byStatus.getOrDefault("IN_PROGRESS", 0)
                + ", in review: " + byStatus.getOrDefault("IN_REVIEW", 0) + "; done: " + summary.done() + ").";
    }

    private static String describe(AssigneeLoad person) {
        return person.name() + " has " + person.notDone() + (person.notDone() == 1 ? " task" : " tasks")
                + " not done yet and " + person.done() + " done.";
    }

    /** Lower case, Vietnamese diacritics removed, every run of other characters as one space. */
    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        String folded = DIACRITICS.matcher(decomposed).replaceAll("").replace('\u0111', 'd').replace('\u0110', 'D')
                .toLowerCase(Locale.ROOT);
        return NON_WORD.matcher(folded).replaceAll(" ").strip();
    }

    private static List<Pattern> patterns(String... regexes) {
        return java.util.Arrays.stream(regexes).map(Pattern::compile).toList();
    }
}
