# Module Boundaries & Architecture Rules — DevFlow

This rule enforces the modular monolith boundaries documented in `docs/ARCHITECTURE.md` (Sections 2, 4, and 10). It is enforced both by policy and automated build-time tasks.

---

## 1. Core Principles

DevFlow is organized as a **Modular Monolith**. It runs as a single deployable application (`app`) while enforcing strict isolation between business modules:

- **Modules:** `auth`, `board`, `gitci`, `ai`, `notification`
- **Shared Kernel:** `common`
- **Runner:** `app`

The primary goal of this architecture is to keep domain boundaries clean so that services could easily be extracted into standalone microservices in the future if scale demands it, without rewriting core business logic.

---

## 2. Dependency Matrix (Build-time Enforced)

The Gradle build strictly checks these dependency rules:

| Subproject | Allowed Dependencies | Prohibited Dependencies |
|---|---|---|
| `:common` | None | Any module |
| `:<module>-api` | `:common` only | Any `-impl`, other `-api`, `:app` |
| `:<module>-impl` | `:common`, its own `:<module>-api`, other modules' `:<module>-api` | **ANY other module's `-impl`** |
| `:app` | All `-api` and `-impl` projects | None |

> [!WARNING]
> Adding an `implementation(project(":<module>-impl"))` dependency into another module's `build.gradle.kts` will cause `./gradlew :verifyModuleBoundaries` and `./gradlew check` to immediately FAIL the build.

---

## 3. Package & Import Isolation Rules

1. **No Internal Package Cross-Imports:**
   - Code in `io.devflow.board.internal.*` MUST NEVER import classes from `io.devflow.gitci.internal.*` or `io.devflow.auth.internal.*`.
   - Any class placed in an `.internal.` package is strictly private to that Gradle module.

2. **No Cross-Module Database/JPA Sharing:**
   - A module must NEVER inject or use a `Repository` belonging to another module.
   - A module must NEVER declare JPA `@ManyToOne`, `@OneToMany`, or foreign keys referencing an `@Entity` in another module.
   - Entity references across modules must be stored as raw IDs (`UUID userId`, `UUID boardId`, `UUID taskId`).

3. **Table Ownership:**
   - Each module strictly owns its respective database tables:
     - `auth`: `users`, `refresh_tokens`
     - `board`: `boards`, `columns`, `tasks`, `task_history`
     - `gitci`: `git_repositories`, `git_commits`, `git_pull_requests`, `ci_runs`, `ci_failures`
     - `ai`: `ai_analysis_reports`, `ai_risk_flags`
     - `notification`: `notifications`, `user_notification_preferences`

---

## 4. Cross-Module Communication

All inter-module interactions must follow one of two patterns:

### Pattern A: Asynchronous / Event-Driven (Standard & Preferred)
- Cross-module coordination is achieved via Spring's `ApplicationEventPublisher`.
- Events are defined in `:common` (`io.devflow.common.event.*`) and inherit from `DevFlowEvent`.
- The publisher module fires the event:
  ```java
  eventPublisher.publishEvent(new TaskCreatedEvent(this, taskId, boardId, title, assigneeId));
  ```
- The consumer module listens via `@EventListener`:
  ```java
  @Component
  public class NotificationEventListener {
      @EventListener
      public void onTaskCreated(TaskCreatedEvent event) {
          // Send notification...
      }
  }
  ```

### Pattern B: Synchronous In-Process API (Read-only Queries)
- When immediate return values are required (e.g. verifying user permissions), the consumer module calls an interface exposed in the provider's `-api` module:
  - Provider: `io.devflow.auth.api.AuthApi` in `auth-api`
  - Implementation: `io.devflow.auth.internal.service.AuthApiImpl` in `auth-impl` (registered as a Spring bean)
  - Consumer: Injects `AuthApi` (from `auth-api`), never `AuthApiImpl`.

---

## 5. Verification Commands

Before committing any backend code changes, run:

```bash
# In backend/ directory:
./gradlew :verifyModuleBoundaries
./gradlew check
```

If a violation is reported, you must refactor the interaction to use the module's `-api` interface or publish a domain event through `common`.
