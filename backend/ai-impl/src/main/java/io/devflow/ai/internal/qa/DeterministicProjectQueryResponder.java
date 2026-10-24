package io.devflow.ai.internal.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.devflow.ai.internal.llm.DeterministicResponder;
import io.devflow.ai.internal.qa.ProjectSnapshot.AssigneeLoad;
import io.devflow.ai.internal.qa.ProjectSnapshot.Summary;
import io.devflow.ai.internal.qa.ProjectSnapshot.TaskEntry;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Offline answer for project Q&amp;A, read from the same data block the real model gets and held to the same
 * grounding rule. It answers only questions it understands completely, in English or Vietnamese:
 * <ul>
 *   <li>how many tasks are left, for the project or for one person ("Bob", or "I"), from the summary counts;</li>
 *   <li>who is on task X, with X matched against the listed titles.</li>
 * </ul>
 * Every word of the question must be explained by one of these shapes. A word it does not know, such as a
 * time range ("last week"), a label, a priority or a column name, is a condition it cannot apply, so the
 * answer is {@link ProjectQueryPrompts#NO_INFORMATION} rather than a count that ignores it. Task titles are
 * only ever compared, never obeyed.
 */
@Component
class DeterministicProjectQueryResponder implements DeterministicResponder {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final int MIN_TITLE_MATCH_CHARS = 3;
    private static final int MIN_NAME_PART_CHARS = 2;

    // Matched after normalize(): lower case, Vietnamese diacritics removed, punctuation as spaces.
    private static final List<String> COUNT_PHRASES = List.of("how many", "number of", "count", "bao nhieu",
            "tong so", "so luong");
    private static final Set<String> TASK_WORDS = words("task tasks card cards ticket tickets viec");
    /** Every word a supported count question may contain besides a person. */
    private static final Set<String> COUNT_VOCABULARY = words(
            "how many much number of count total what s is are there still currently now right yet"
                    + " task tasks card cards ticket tickets in on the this project board workspace a an"
                    + " left remaining remain open not done unfinished incomplete outstanding pending"
                    + " completed finished complete does do has have assigned to for with by"
                    + " con bao nhieu tong so luong viec cong chua xong hoan thanh da lai"
                    + " co trong du an nay cua dang duoc giao cho la hien tai");
    /** Every word a supported "who is on task X" question may contain besides the title. */
    private static final Set<String> WHO_VOCABULARY = words(
            "who whom s is are working works work on assigned to the task card ticket owns owner of doing"
                    + " handling handles responsible for assignee currently now right"
                    + " ai dang lam phu trach nhan viec cong giao cho duoc la nguoi cua hien tai");
    private static final Set<String> WHO_TRIGGERS = words("who whom owner owns assignee responsible ai");
    /** The person asking. "my" and Vietnamese "minh" are left out: they can also mean "our". */
    private static final Set<String> SELF = words("i me toi");
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
            String question = normalize(blocks.group(3));
            return Optional.of(whoIsOnTask(data, question)
                    .or(() -> howManyLeft(data.summary(), question))
                    .orElse(ProjectQueryPrompts.NO_INFORMATION));
        } catch (JsonProcessingException unreadable) {
            return Optional.of(ProjectQueryPrompts.NO_INFORMATION);
        }
    }

    private static Optional<String> whoIsOnTask(ProjectSnapshot data, String question) {
        String padded = " " + question + " ";
        Optional<TaskEntry> best = data.tasks().stream()
                .filter(task -> normalize(task.title()).length() >= MIN_TITLE_MATCH_CHARS
                        && padded.contains(" " + normalize(task.title()) + " "))
                .max(Comparator.comparingInt(task -> normalize(task.title()).length()));
        if (best.isEmpty()) {
            return Optional.empty();
        }
        String title = normalize(best.get().title());
        List<String> rest = tokens(padded.replace(" " + title + " ", " "));
        if (rest.stream().noneMatch(WHO_TRIGGERS::contains) || !WHO_VOCABULARY.containsAll(rest)) {
            return Optional.empty();
        }
        // Two listed tasks with this title but different people: there is no single answer.
        long people = data.tasks().stream().filter(task -> normalize(task.title()).equals(title))
                .map(task -> String.valueOf(task.assignee())).distinct().count();
        return people == 1 ? Optional.of(assignee(best.get())) : Optional.empty();
    }

    private static Optional<String> howManyLeft(Summary summary, String question) {
        String padded = " " + question + " ";
        if (COUNT_PHRASES.stream().noneMatch(phrase -> padded.contains(" " + phrase + " "))
                || tokens(question).stream().noneMatch(TASK_WORDS::contains)) {
            return Optional.empty();
        }
        List<AssigneeLoad> named = summary.assignees().stream()
                .filter(person -> !ProjectSnapshotBuilder.UNKNOWN_MEMBER.equals(person.name()))
                .sorted(Comparator.comparingInt((AssigneeLoad person) -> normalize(person.name()).length()).reversed())
                .toList();
        List<AssigneeLoad> mentioned = new ArrayList<>();
        String rest = padded;
        for (AssigneeLoad person : named) {
            String fullName = normalize(person.name());
            if (!fullName.isEmpty() && !COUNT_VOCABULARY.contains(fullName) && rest.contains(" " + fullName + " ")) {
                mentioned.add(person);
                rest = rest.replace(" " + fullName + " ", " ");
            }
        }
        boolean self = false;
        for (String token : tokens(rest)) {
            if (SELF.contains(token)) {
                self = true;
            } else if (!COUNT_VOCABULARY.contains(token)) {
                // A first or last name alone counts only if it points to exactly one person and nobody left out
                // of the summary could share it. Any other unknown word is a condition this cannot apply.
                List<AssigneeLoad> owners = summary.otherAssignees() > 0 || token.length() < MIN_NAME_PART_CHARS
                        ? List.of()
                        : named.stream().filter(person -> tokens(normalize(person.name())).contains(token)).toList();
                if (owners.size() != 1) {
                    return Optional.empty();
                }
                if (!mentioned.contains(owners.get(0))) {
                    mentioned.add(owners.get(0));
                }
            }
        }
        if (self) {
            Optional<AssigneeLoad> asker = named.stream().filter(AssigneeLoad::asker).findFirst();
            if (asker.isEmpty()) {
                return summary.otherAssignees() == 0 && mentioned.isEmpty()
                        ? Optional.of("You have no tasks assigned in this project.") : Optional.empty();
            }
            if (!mentioned.isEmpty() && !mentioned.equals(List.of(asker.get()))) {
                return Optional.empty();
            }
            mentioned = List.of(asker.get());
        }
        if (mentioned.size() > 1) {
            return Optional.empty();
        }
        return Optional.of(mentioned.isEmpty() ? projectCount(summary) : personCount(mentioned.get(0)));
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

    private static String personCount(AssigneeLoad person) {
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

    private static List<String> tokens(String normalized) {
        return Arrays.stream(normalized.strip().split(" ")).filter(token -> !token.isEmpty()).toList();
    }

    private static Set<String> words(String list) {
        return Arrays.stream(list.split(" ")).collect(Collectors.toUnmodifiableSet());
    }
}
