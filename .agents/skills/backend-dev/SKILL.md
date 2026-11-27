---
name: backend-dev
description: DevFlow backend rules and commands (Java 17, Spring Boot 3.4 modular monolith, Gradle multi-project, PostgreSQL 17, Flyway). Use before adding or changing anything under backend/ - REST endpoints, JPA entities, Flyway migrations, cross-module DevFlowEvents, listeners, tests - or when running Gradle tasks. Covers module boundaries, the event patterns this codebase actually uses, migration numbering and the fast test commands. Generic Spring knowledge is assumed; this skill holds only what is specific to DevFlow.
---

# DevFlow backend

Read `docs/ARCHITECTURE.md` Sections 4 (event catalog), 5 (table ownership) and 10 (boundaries) before changing a contract. Generic Spring Boot practice is not repeated here.

## Commands (run in `backend/`)

| Goal | Command |
|---|---|
| Boundary check only (seconds) | `./gradlew :verifyModuleBoundaries` |
| One module's tests | `./gradlew :board-impl:test` |
| One test class / method | `./gradlew :app:test --tests 'io.devflow.auth.api.PersonalAccessTokenApiIntegrationTest'` (append `.methodName` for one method) |
| Full gate before a PR | `./gradlew check` (compile, all tests, boundaries; Testcontainers needs Docker running) |
| Run locally | `./gradlew :app:bootRun` (needs `docker compose up -d db`) |

Windows: if Gradle says `JAVA_HOME` is invalid, set `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"`.
Failed test reports: `backend/<module>/build/reports/tests/test/index.html`.

## Module layout

| Project | Package | May depend on |
|---|---|---|
| `:common` | `io.devflow.common.{event,entity,security,config}` | nothing |
| `:<m>-api` | `io.devflow.<m>.api` (DTO records, public contracts) | `:common` |
| `:<m>-impl` | `io.devflow.<m>.internal.*` (entities, repositories, services, controllers, listeners) | `:common`, own `-api`, other modules' `-api` |
| `:app` | `io.devflow.config`, `application.yml`, **all Flyway migrations**, cross-module integration tests | everything |

Modules: `auth`, `board`, `gitci`, `ai`, `notification`. `:verifyModuleBoundaries` only inspects Gradle dependencies; importing another module's `internal` package fails compilation because the dependency is not there. Never "fix" that by adding an `-impl` dependency.

## Cross-module communication: the patterns in use

All events implement the `DevFlowEvent` **interface** (`eventType()`, `occurredAt()`) in `io.devflow.common.event`. They are `final` classes with validated constructor fields; there is no `super(source, ...)`. Register the wire name in `EventTypes` (`domain.event_past_tense`) and add the event to ARCHITECTURE.md Section 4.

1. **Fire-and-forget after commit** - the default. Listeners use `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` (add `fallbackExecution = true` when the publisher may run outside a transaction). Side effects such as mail run on a dedicated executor (see `auth.internal.support.AuthMailExecutor`).
2. **Durable outbox** - Git and CI events must survive a crash between commit and delivery: `gitci.internal.GitDomainEventOutbox` and `CiFailureEventOutbox` write rows in the same transaction and a worker publishes them. Reuse this for any event whose loss would corrupt another module's state.
3. **Synchronous request/decision** - when the caller needs an answer before writing (e.g. `WorkspaceMembershipCheckRequestedEvent`, `TaskReferencesResolveRequestedEvent`): the owning module's plain `@EventListener` records exactly one decision on the event, and the caller **fails closed** on PENDING or multiple decisions. Never make these listeners async.
4. **Read-only query through `-api`**: inject `AuthApi`, `BoardApi`, `GitCiApi`, `NotificationApi` or `AiServiceApi` from the provider's `-api` project, never its implementation class.

## Data and migrations

- Migrations live only in `backend/app/src/main/resources/db/migration/V<n>__<snake_case>.sql`.
- **Never edit a migration that is on `origin/develop`**: Flyway checksums break every database. A PreToolUse hook blocks it.
- Number new migrations `max(origin/develop) + 1` after `git fetch`. With parallel branches the merge owner renumbers on collision, so keep the number out of code and docs.
- Each table belongs to one module (ARCHITECTURE.md Section 5). Reference foreign-module rows by plain `UUID` columns, never `@ManyToOne` across modules.
- Constraints (`NOT NULL`, unique, FK within the module) mirror business rules. A new `NOT NULL` column needs a default in SQL **and** in the entity: app tests run on H2 with `ddl-auto: create-drop`, so a missing entity default breaks raw-SQL tests (PR #25 incident).

## Tests: two databases

- `app/src/test/resources/application.yml` uses **H2 `MODE=PostgreSQL`, Flyway disabled**. API integration tests (`io.devflow.<m>.api.*IntegrationTest`) run here with MockMvc.
- Schema and migration tests (`io.devflow.<m>.schema.*`) use **Testcontainers PostgreSQL 17** with Flyway on. Anything Postgres-specific (partial indexes, `jsonb`, constraint behaviour) must be covered there.
- Tests never call real providers: the test config forces the deterministic fallback `ChatModel` and dummy keys.

## Web layer conventions

- Routes are under `/api/v1/<module>`.
- Errors are RFC 7807 `ProblemDetail` from a **per-module** `@RestControllerAdvice` (e.g. `BoardApiControllerAdvice`, `NotificationExceptionHandler`, auth's `GlobalExceptionHandler`). Add handlers to your module's advice; never leak exception messages or stack traces. The frontend reads `code` and field errors from the body (`frontend/src/api/problemDetail.ts`), so keep codes stable.
- Every mutation checks workspace membership or ownership (IDOR) and goes through the module's rate limiter (`BoardRateLimiter`, `NotificationRateLimiter`, `AiRateLimiter`).
- Metrics use Micrometer (`AiMetrics`, `BoardApiMetricsInterceptor`); each module exposes a health controller.
- External calls (GitHub, LLM, mail) need explicit timeouts, retries and a circuit breaker (Resilience4j); see `ai.internal.llm.AiResilience` and `gitci.internal.GitHubActionsReadClient`.

## Done means

AGENTS.md Section 4 checklist passes, and `./gradlew check` is green locally.
