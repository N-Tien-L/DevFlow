package io.devflow.ai.internal.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.devflow.ai.internal.llm.DeterministicResponder;
import io.devflow.ai.internal.qa.ProjectSnapshot.Summary;
import io.devflow.ai.internal.qa.ProjectSnapshot.TaskEntry;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Offline answer for project Q&amp;A, read from the same data block the real model gets and held to the same
 * grounding rule: it answers only "how many tasks are left" (from the summary counts) and "who is on task X"
 * (X matched against listed titles), in English or Vietnamese; anything else gets
 * {@link ProjectQueryPrompts#NO_INFORMATION}. Task titles are only ever compared, never obeyed.
 */
@Component
class DeterministicProjectQueryResponder implements DeterministicResponder {

    // Matched on lower-case text with Vietnamese diacritics removed.
    private static final Pattern WHO = Pattern.compile(
            "\\b(who|whom|assignee|assigned|owner|owns|ai|phu trach|nguoi lam)\\b");
    private static final Pattern COUNT = Pattern.compile(
            "how many|remaining|\\bleft\\b|not done|unfinished|\\bopen\\b|bao nhieu|chua xong|chua hoan thanh|con lai");
    // A count question must be about tasks: "how many people live here?" gets no task count.
    private static final Pattern TASKS = Pattern.compile("\\b(tasks?|cards?|tickets?|viec)\\b");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final int MIN_TITLE_MATCH_CHARS = 3;
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
            return Optional.of(answer(data, fold(blocks.group(3))));
        } catch (JsonProcessingException unreadable) {
            return Optional.of(ProjectQueryPrompts.NO_INFORMATION);
        }
    }

    private static String answer(ProjectSnapshot data, String question) {
        if (WHO.matcher(question).find()) {
            Optional<TaskEntry> task = data.tasks().stream()
                    .filter(entry -> fold(entry.title()).length() >= MIN_TITLE_MATCH_CHARS
                            && question.contains(fold(entry.title())))
                    .max(Comparator.comparingInt(entry -> entry.title().length()));
            if (task.isPresent()) {
                return assignee(task.get());
            }
        }
        if (COUNT.matcher(question).find() && TASKS.matcher(question).find()) {
            return count(data.summary());
        }
        return ProjectQueryPrompts.NO_INFORMATION;
    }

    private static String assignee(TaskEntry task) {
        String status = STATUS_LABELS.getOrDefault(task.status(), task.status());
        if (task.assignee() == null) {
            return "\"" + task.title() + "\" is not assigned to anyone (status: " + status + ").";
        }
        return "\"" + task.title() + "\" is assigned to " + task.assignee() + " (status: " + status + ").";
    }

    private static String count(Summary summary) {
        Map<String, Integer> byStatus = summary.byStatus();
        return summary.notDone() + " of " + summary.totalTasks() + " tasks are not done yet (to do: "
                + byStatus.getOrDefault("TODO", 0) + ", in progress: " + byStatus.getOrDefault("IN_PROGRESS", 0)
                + ", in review: " + byStatus.getOrDefault("IN_REVIEW", 0) + "; done: " + summary.done() + ").";
    }

    private static String fold(String text) {
        String decomposed = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed).replaceAll("").replace('\u0111', 'd').replace('\u0110', 'D')
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
