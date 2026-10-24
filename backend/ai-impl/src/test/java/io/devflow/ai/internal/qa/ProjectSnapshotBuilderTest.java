package io.devflow.ai.internal.qa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.ai.internal.config.AiProperties;
import io.devflow.board.api.TaskSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProjectSnapshotBuilder: exact counts, bounded task list")
class ProjectSnapshotBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final Map<UUID, String> NAMES = Map.of(ALICE, "Alice", BOB, "Bob");

    private final ObjectMapper json = new ObjectMapper();
    private final AiProperties.Qa limits = new AiProperties.Qa();

    @Test
    void countsByStatusAssigneeAndDueDate() throws Exception {
        List<TaskSummary> tasks = List.of(
                task("Done long ago", "DONE", ALICE, TODAY.minusDays(30)),
                task("Overdue", "IN_PROGRESS", ALICE, TODAY.minusDays(1)),
                task("Due today", "TODO", BOB, TODAY),
                task("Due in a week", "IN_REVIEW", BOB, TODAY.plusDays(7)),
                task("Due later", "TODO", null, TODAY.plusDays(8)));

        JsonNode summary = build(tasks, 100_000).path("summary");

        assertThat(summary.path("totalTasks").asInt()).isEqualTo(5);
        assertThat(summary.path("notDone").asInt()).isEqualTo(4);
        assertThat(summary.path("done").asInt()).isEqualTo(1);
        assertThat(summary.path("byStatus").toString())
                .isEqualTo("{\"TODO\":2,\"IN_PROGRESS\":1,\"IN_REVIEW\":1,\"DONE\":1}");
        // A finished task past its due date is not overdue.
        assertThat(summary.path("overdueNotDone").asInt()).isEqualTo(1);
        assertThat(summary.path("dueWithin7DaysNotDone").asInt()).isEqualTo(2);
        assertThat(summary.path("unassignedNotDone").asInt()).isEqualTo(1);
        assertThat(summary.path("assignees").toString()).isEqualTo(
                "[{\"name\":\"Bob\",\"asker\":false,\"notDone\":2,\"done\":0,\"overdue\":0},"
                        + "{\"name\":\"Alice\",\"asker\":true,\"notDone\":1,\"done\":1,\"overdue\":1}]");
    }

    @Test
    void listsUnfinishedTasksFirstInBoardOrder() throws Exception {
        List<TaskSummary> tasks = List.of(task("A", "DONE", null, null), task("B", "TODO", null, null),
                task("C", "DONE", null, null), task("D", "IN_REVIEW", null, null));

        JsonNode data = build(tasks, 100_000);

        assertThat(data.path("tasks").findValuesAsText("title")).containsExactly("B", "D", "A", "C");
    }

    @Test
    void cleansTitlesAndNamesToOneBoundedLine() throws Exception {
        limits.setMaxTitleChars(20);
        UUID unnamed = UUID.randomUUID();
        List<TaskSummary> tasks = List.of(
                task("Line one\r\nline\ttwo\u0007 and a much longer tail", "TODO", unnamed, null),
                task("   ", "TODO", null, null));

        JsonNode data = build(tasks, 100_000);

        assertThat(data.path("tasks").get(0).path("title").asText()).isEqualTo("Line one line two a\u2026");
        assertThat(data.path("tasks").get(1).path("title").asText()).isEqualTo("(untitled)");
        assertThat(data.path("tasks").get(0).path("assignee").asText())
                .isEqualTo(ProjectSnapshotBuilder.UNKNOWN_MEMBER);
    }

    @Test
    void stopsListingAtTheTaskLimitButCountsEveryTask() throws Exception {
        limits.setMaxTasks(3);

        JsonNode summary = build(manyTasks(10), 100_000).path("summary");

        assertThat(summary.path("totalTasks").asInt()).isEqualTo(10);
        assertThat(summary.path("tasksListed").asInt()).isEqualTo(3);
        assertThat(summary.path("tasksNotListed").asInt()).isEqualTo(7);
    }

    @Test
    void staysWithinTheCharacterBudget() {
        for (int budget : new int[] {600, 1_000, 2_345, 5_000}) {
            ProjectSnapshotBuilder.Built built = new ProjectSnapshotBuilder(limits)
                    .build(manyTasks(200), NAMES::get, ALICE, TODAY, budget);

            assertThat(built.json().length()).isLessThanOrEqualTo(budget);
            assertThat(built.totalTasks()).isEqualTo(200);
            assertThat(built.tasksListed()).isLessThan(200);
        }
    }

    @Test
    void capsNamedAssigneesAndCountsTheRest() throws Exception {
        limits.setMaxAssignees(1);
        List<TaskSummary> tasks = List.of(task("A", "TODO", ALICE, null), task("B", "TODO", BOB, null),
                task("C", "TODO", BOB, null));

        JsonNode summary = build(tasks, 100_000).path("summary");

        assertThat(summary.path("assignees").findValuesAsText("name")).containsExactly("Bob");
        assertThat(summary.path("otherAssignees").asInt()).isEqualTo(1);
    }

    private JsonNode build(List<TaskSummary> tasks, int budget) throws Exception {
        return json.readTree(new ProjectSnapshotBuilder(limits).build(tasks, NAMES::get, ALICE, TODAY, budget).json());
    }

    private static List<TaskSummary> manyTasks(int count) {
        return IntStream.range(0, count).mapToObj(i -> task("Task " + i, "TODO", BOB, null)).toList();
    }

    private static TaskSummary task(String title, String status, UUID assignee, LocalDate due) {
        return new TaskSummary(UUID.randomUUID(), PROJECT, title, status, assignee, due);
    }
}
