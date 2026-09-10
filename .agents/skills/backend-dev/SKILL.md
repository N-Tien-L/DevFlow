---
name: backend-dev
description: Guidance and step-by-step recipes for developing the DevFlow Spring Boot 3.4 backend, creating REST endpoints, defining domain events, JPA entities, and enforcing module boundaries.
---

# Backend Development Skill — DevFlow

This skill provides practical guidance, code templates, and commands for building and maintaining the DevFlow backend.

---

## 1. Quick Reference & System Stack

- **Java Version:** Java 17 LTS (configured in `build.gradle.kts` toolchain)
- **Framework:** Spring Boot 3.4.5
- **Build Tool:** Gradle with Kotlin DSL (`gradlew` wrapper in `backend/`)
- **Database:** PostgreSQL 17 (`devflow` database on port 5432)
- **Default Port:** `8080` (Local dev server)
- **Actuator Healthcheck:** `http://localhost:8080/actuator/health`

---

## 2. Standard Gradle Commands

Run these commands inside the `backend/` directory (or specify `-p backend` from the root):
*(Note on Windows: if `JAVA_HOME` reports invalid directory, set `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` in PowerShell)*

```bash
# Verify architecture module boundaries (instant check)
./gradlew :verifyModuleBoundaries

# Run full checks (boundary check + compiler + unit tests)
./gradlew check

# Run the backend locally
./gradlew :app:bootRun

# Run tests for a specific module
./gradlew :board-impl:test

# Clean and rebuild all projects
./gradlew clean build
```

---

## 3. Module Layout & Responsibilities

| Subproject | Responsibility | Package | Allowed Dependencies |
|---|---|---|---|
| `:common` | Kernel: `DevFlowEvent`, `EventTypes`, base config | `io.devflow.common.*` | None |
| `:<module>-api` | DTOs, public interface, exceptions | `io.devflow.<module>.api.*` | `:common` |
| `:<module>-impl` | JPA entities, Repositories, Services, REST Controllers, Event Listeners | `io.devflow.<module>.internal.*` | `:common`, `:<module>-api`, foreign `-api` |
| `:app` | Spring Boot main runner, profiles, security config | `io.devflow.app` | All projects |

---

## 4. Development Recipes

### Recipe 1: Implementing a New REST Endpoint

To add a new REST API endpoint to a module (e.g., `board`):

#### 1. Define Request/Response DTOs in `<module>-api`
```java
// backend/board-api/src/main/java/io/devflow/board/api/dto/CreateTaskRequest.java
package io.devflow.board.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateTaskRequest(
    @NotNull UUID columnId,
    @NotBlank String title,
    String description,
    UUID assigneeId
) {}
```

#### 2. Create Service & Repository in `<module>-impl`
```java
// backend/board-impl/src/main/java/io/devflow/board/internal/service/TaskService.java
package io.devflow.board.internal.service;

import io.devflow.board.api.dto.CreateTaskRequest;
import io.devflow.board.api.dto.TaskResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TaskService {
    // Inject repository & event publisher
}
```

#### 3. Create REST Controller in `<module>-impl`
```java
// backend/board-impl/src/main/java/io/devflow/board/internal/controller/TaskController.java
package io.devflow.board.internal.controller;

import io.devflow.board.api.dto.CreateTaskRequest;
import io.devflow.board.api.dto.TaskResponse;
import io.devflow.board.internal.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse createTask(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.createTask(request);
    }
}
```

---

### Recipe 2: Publishing and Consuming Domain Events

#### 1. Declare Event Type Constant in `:common`
Add the event name in `io.devflow.common.event.EventTypes`:
```java
public static final String TASK_ARCHIVED = "task.archived";
```

#### 2. Define the Event Class in `:common`
```java
package io.devflow.common.event;

import java.util.UUID;

public class TaskArchivedEvent extends DevFlowEvent {
    private final UUID taskId;
    private final UUID boardId;

    public TaskArchivedEvent(Object source, UUID taskId, UUID boardId) {
        super(source, EventTypes.TASK_ARCHIVED);
        this.taskId = taskId;
        this.boardId = boardId;
    }

    public UUID getTaskId() { return taskId; }
    public UUID getBoardId() { return boardId; }
}
```

#### 3. Publish Event in Producer Module
```java
@Service
public class TaskService {
    private final ApplicationEventPublisher eventPublisher;

    public TaskService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void archiveTask(UUID taskId, UUID boardId) {
        // Business logic...
        eventPublisher.publishEvent(new TaskArchivedEvent(this, taskId, boardId));
    }
}
```

#### 4. Consume Event in Another Module (`notification-impl`, `ai-impl`, etc.)
```java
@Component
public class TaskArchivedNotificationListener {

    @EventListener
    public void handleTaskArchived(TaskArchivedEvent event) {
        // React to event without direct dependency on board-impl
    }
}
```

---

### Recipe 3: Database & JPA Best Practices

1. **UUID Primary Keys:** Use `UUID` generated via `UUID.randomUUID()` or `@GeneratedValue(strategy = GenerationType.UUID)`.
2. **Auditing:** Include `createdAt` and `updatedAt` timestamps on all entities.
3. **Foreign Keys Across Modules:** Never use `@ManyToOne` or `@JoinColumn` targeting an entity outside your module. Store plain `UUID otherId;`.
4. **Transactions:** Mark service mutation methods with `@Transactional`.

---

## 5. Pre-Commit Checklist for Backend

- [ ] All new packages adhere to `io.devflow.<module>.api` or `io.devflow.<module>.internal`.
- [ ] No cross-module `*-impl` dependencies added in `build.gradle.kts`.
- [ ] `./gradlew :verifyModuleBoundaries` exits with 0.
- [ ] `./gradlew check` passes all unit and integration tests.
