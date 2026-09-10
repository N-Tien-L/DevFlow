# Coding Conventions — DevFlow

This document defines the coding standards, patterns, and style conventions for both Backend (Spring Boot / Java) and Frontend (React / TypeScript) in the DevFlow repository.

---

## 1. Backend (Java / Spring Boot)

### 1.1 Technology Baseline
- **Java:** Version 17 LTS
- **Framework:** Spring Boot 3.4.5
- **Build Tool:** Gradle with Kotlin DSL (`build.gradle.kts`)
- **Database:** PostgreSQL 17 via Spring Data JPA / Hibernate

### 1.2 Package & File Structure
Every functional module follows the split pattern: `<module>-api` and `<module>-impl`.
Base package: `io.devflow.<module>`

#### API Subproject (`<module>-api`)
```
io.devflow.<module>.api
├── dto/             # Shared request/response data transfer objects
├── model/           # Public domain representations / enums
└── <Module>Api.java # Public interface if module exposes synchronous Java API
```
*Rule:* Only interfaces, records, DTOs, enums, and exceptions may live in `-api`. Never add JPA `@Entity`, Spring `@Service`, or database logic here.

#### Implementation Subproject (`<module>-impl`)
```
io.devflow.<module>.internal
├── controller/      # Spring MVC REST Controllers (@RestController)
├── service/         # Business logic services (@Service)
├── entity/          # JPA entities (@Entity, @Table)
├── repository/      # Spring Data repositories (@Repository)
├── listener/        # Event listeners (@EventListener)
└── config/          # Spring @Configuration classes specific to module
```
*Rule:* All classes in `<module>-impl` belong to the `internal` package and are considered private to that module.

### 1.3 Naming Conventions
- **Controllers:** `<Resource>Controller.java` (e.g., `BoardController.java`, `TaskController.java`)
- **Services:** `<Resource>Service.java` or `<Action>Service.java`
- **Entities:** Singular noun with JPA annotations (e.g., `Board.java`, `Task.java`, `Notification.java`)
- **DTOs:** `<Action><Resource>Request.java` / `<Resource>Response.java` (e.g., `CreateTaskRequest.java`, `TaskSummaryResponse.java`). Use Java `record` for immutable DTOs whenever possible.
- **Repositories:** `<Entity>Repository.java` extending `JpaRepository<Entity, UUID>` or `CrudRepository`.

### 1.4 REST API Guidelines
- **Base URI:** `/api/v1/<resource>`
- **HTTP Methods:**
  - `GET /api/v1/<resources>`: Retrieve collections (supports pagination/filtering)
  - `GET /api/v1/<resources>/{id}`: Retrieve single entity
  - `POST /api/v1/<resources>`: Create new resource (HTTP 201 Created)
  - `PUT /api/v1/<resources>/{id}`: Replace or full update
  - `PATCH /api/v1/<resources>/{id}`: Partial update
  - `DELETE /api/v1/<resources>/{id}`: Soft or hard delete (HTTP 204 No Content)
- **ID Type:** Use `UUID` as primary identifiers for all entities and API paths (`@PathVariable UUID id`).
- **Validation:** Use `jakarta.validation.constraints.*` (`@NotNull`, `@NotBlank`, `@Size`) on request DTOs, validated with `@Valid` on controller methods.
- **Error Responses:** Return RFC 7807 `ProblemDetail` or consistent JSON errors via `@ControllerAdvice`.

### 1.5 Domain Events
- Cross-module communication must use domain events published via Spring's `ApplicationEventPublisher`.
- All event classes must extend `io.devflow.common.event.DevFlowEvent`.
- Event type string must be declared in `io.devflow.common.event.EventTypes`.
- Event naming format: `<domain>.<action_past_tense>` (e.g., `task.created`, `task.status_changed`, `git.pr_merged`, `ci.failure_detected`).
- Listeners: Use `@EventListener` (or `@TransactionalEventListener(phase = AFTER_COMMIT)`) in the consumer module's `internal.listener` package.

---

## 2. Frontend (React / TypeScript)

### 2.1 Technology Baseline
- **Framework:** React 19
- **Language:** TypeScript 5.8 (Strict mode enabled)
- **Bundler:** Vite 6
- **Routing:** React Router 7
- **Styling:** TailwindCSS 4, `clsx` for dynamic classes

### 2.2 Directory Structure
```
frontend/src/
├── api/             # API client methods and fetch wrappers
├── components/      # Reusable UI components
│   ├── common/      # Generic buttons, modals, badges, inputs
│   └── layout/      # Navbar, Sidebar, PageContainer
├── hooks/           # Custom React hooks (e.g., useBoard, useWebSocket)
├── pages/           # Route-level page components (e.g., BoardPage.tsx)
├── types/           # TypeScript interfaces and domain type definitions
├── styles/          # Global styles, Tailwind imports
├── App.tsx          # Application root with router configuration
└── main.tsx         # Vite entry point
```

### 2.3 Component Conventions
- **File Naming:** PascalCase for component files (e.g., `TaskCard.tsx`, `BoardColumn.tsx`).
- **Component Declaration:** Functional components with explicit typing.
  ```tsx
  interface TaskCardProps {
    task: TaskSummary;
    onStatusChange: (taskId: string, newStatus: TaskStatus) => void;
  }

  export const TaskCard: React.FC<TaskCardProps> = ({ task, onStatusChange }) => {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h3 className="font-semibold text-slate-900">{task.title}</h3>
      </div>
    );
  };
  ```
- **Styling:** Use utility classes from TailwindCSS v4. Prefer clean, modern slate/neutral dark-and-light friendly palettes. Use `clsx` for conditional classes. Avoid inline style objects.

### 2.4 API Integration
- Always use relative paths starting with `/api/v1/...` for HTTP requests.
- Vite dev server is configured to proxy `/api` requests to backend at `http://localhost:8080`. Do not hardcode `http://localhost:8080` in frontend components.
- WebSocket connections should target `/ws` (proxied by Vite or Nginx in production).
- Extract data fetching logic into hooks or service modules in `src/api/`. Do not embed raw `fetch` calls directly inside presentational components.
