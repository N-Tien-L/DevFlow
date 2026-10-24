package io.devflow.ai.internal.support;

import io.devflow.board.api.BoardApi;
import io.devflow.board.api.TaskSummary;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Stands in for the Board module's {@link BoardApi} in ai-impl slice tests: tasks per project from memory,
 * counting reads so tests can prove no board data is read before the membership check passes.
 */
@Component
public class TestBoardDirectory implements BoardApi {

    private final Map<UUID, List<TaskSummary>> tasksByProject = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeException> failures = new ConcurrentHashMap<>();
    private final AtomicInteger projectReads = new AtomicInteger();

    public void setTasks(UUID projectId, List<TaskSummary> tasks) {
        tasksByProject.put(projectId, List.copyOf(tasks));
    }

    public void failReads(UUID projectId, RuntimeException failure) {
        failures.put(projectId, failure);
    }

    public int projectReads() {
        return projectReads.get();
    }

    @Override
    public List<TaskSummary> findTasksByProject(UUID projectId) {
        projectReads.incrementAndGet();
        RuntimeException failure = failures.get(projectId);
        if (failure != null) {
            throw failure;
        }
        return tasksByProject.getOrDefault(projectId, List.of());
    }

    @Override
    public Optional<TaskSummary> findTask(UUID taskId) {
        return tasksByProject.values().stream().flatMap(List::stream)
                .filter(task -> task.taskId().equals(taskId)).findFirst();
    }
}
