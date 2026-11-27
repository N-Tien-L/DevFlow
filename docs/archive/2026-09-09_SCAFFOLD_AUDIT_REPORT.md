# DevFlow Scaffold Audit Report

**Date:** 2026-09-09  
**Source docs:** `docs/PRODUCT_SPEC.md`, `docs/ARCHITECTURE.md`  
**Build verification:**
- Backend: `./gradlew clean build` → **BUILD SUCCESSFUL** (45 actionable tasks executed, smoke test `contextLoads` passed).
- Module boundary check: `:verifyModuleBoundaries` task executed during `check` with 0 violations.
- Frontend: `npm run build` (`tsc -b && vite build`) → **Built in ~1.0s**, `npm run lint` → **Passed (0 warnings, 0 errors)**.
- Docker Compose config: `docker compose config` → **Validated successfully** (all 5 services and 2 volumes configured).

---

## 1. Project Structure

```
DevFlow/
├── .env.example
├── .gitignore
├── docker-compose.yml
├── docs/
│   ├── PRODUCT_SPEC.md
│   └── ARCHITECTURE.md
├── infra/
│   ├── grafana/provisioning/datasources/prometheus.yml
│   └── prometheus/prometheus.yml
├── backend/
│   ├── settings.gradle.kts          (12 includes: app, common, 5×-api, 5×-impl)
│   ├── build.gradle.kts             (root: Java 17 toolchain, verifyModuleBoundaries)
│   ├── gradle.properties
│   ├── gradlew / gradlew.bat
│   ├── gradle/wrapper/
│   ├── Dockerfile                    (multi-stage: gradle:8.14-jdk17 → temurin:17-jre)
│   ├── common/                       (shared kernel)
│   │   ├── build.gradle.kts
│   │   └── src/main/java/io/devflow/common/
│   │       ├── event/                (DevFlowEvent, EventTypes, 8 typed events)
│   │       ├── entity/BaseEntity.java
│   │       └── config/JpaAuditingConfig.java
│   ├── auth-api/                     (AuthApi, UserSummary)
│   ├── auth-impl/                    (AuthService, AuthController, SecurityConfig, AuthHealthController)
│   ├── board-api/                    (BoardApi, TaskSummary)
│   ├── board-impl/                   (BoardService, BoardController, GitActivityEventListener, BoardHealthController)
│   ├── gitci-api/                    (GitCiApi, CiFailureInfo)
│   ├── gitci-impl/                   (GitCiService, GitWebhookController, GitCiHealthController)
│   ├── ai-api/                       (AiServiceApi, TaskBreakdownProposal)
│   ├── ai-impl/                      (AiService, ChatGatewayController, McpToolConfig, AiEventListeners, AiHealthController)
│   ├── notification-api/             (NotificationApi)
│   ├── notification-impl/            (NotificationService, NotificationEventListeners, NotificationController, NotificationHealthController)
│   └── app/
│       ├── build.gradle.kts          (depends on all -impl modules)
│       └── src/
│           ├── main/java/io/devflow/
│           │   ├── DevFlowApplication.java
│           │   └── config/WebSocketConfig.java
│           ├── main/resources/application.yml
│           ├── test/java/io/devflow/DevFlowApplicationTests.java
│           └── test/resources/application.yml  (H2 + dummy OpenAI key)
└── frontend/
    ├── .gitignore
    ├── .oxlintrc.json
    ├── package.json                  (react 19, react-router-dom 7, tailwindcss 4, vite 6)
    ├── package-lock.json
    ├── eslint.config.js              (ESLint 9 flat config for react-hooks & typescript-eslint)
    ├── vite.config.ts                (proxy: /api, /ws, /sse, /mcp, /actuator → :8080)
    ├── tsconfig.json / tsconfig.app.json / tsconfig.node.json
    ├── Dockerfile                    (multi-stage: node:22-alpine → nginx:alpine)
    ├── nginx.conf                    (SPA + reverse proxy to backend)
    ├── index.html
    ├── README.md
    ├── public/
    │   ├── favicon.svg
    │   └── icons.svg
    └── src/
        ├── main.tsx
        ├── index.css                 (tailwind import)
        ├── App.tsx                   (Routes: * → BoardPage, ChatPanel sidebar)
        ├── assets/hero.png
        ├── pages/BoardPage.tsx
        ├── pages/LoginPage.tsx
        ├── components/ChatPanel.tsx
        └── types/route.ts
```

---

## 2. Module Boundary Enforcement

**File:** `backend/build.gradle.kts:46-69`

The `verifyModuleBoundaries` task runs on every `check` (and thus every `build`). It iterates all non-`app` subprojects and scans their `ProjectDependency` instances. If any project depends on a path ending in `-impl` (except `app`), the build fails with:

```
Module boundary violation: ':board-impl' depends on ':auth-impl'.
Depend on its -api project instead (ARCHITECTURE.md Section 10).
```

**Dependency rules enforced:**

| Project | Allowed dependencies |
|---------|---------------------|
| `:common` | nothing |
| `:X-api` | `:common` only |
| `:X-impl` | `:common`, `:X-api`, other modules' `-api` projects |
| `:app` | everything (bootstrap exception) |

**Note:** The task uses a deprecated API (`dep.dependencyProject`) which emits a warning on Gradle 8.14. This is cosmetic; the enforcement works correctly.

---

## 3. Event Bus Contract

**File:** `backend/common/src/main/java/io/devflow/common/event/`

All 8 events from `ARCHITECTURE.md` Section 4 are implemented as `record` types implementing `DevFlowEvent`:

| Event class | `eventType()` constant | Publisher module | Subscriber modules |
|-------------|----------------------|------------------|-------------------|
| `TaskCreatedEvent` | `task.created` | board | ai, notification |
| `TaskStatusChangedEvent` | `task.status_changed` | board, gitci | notification, ai |
| `GitCommitLinkedEvent` | `git.commit_linked` | gitci | board |
| `GitPrOpenedEvent` | `git.pr_opened` | gitci | board, notification |
| `GitPrMergedEvent` | `git.pr_merged` | gitci | board, notification |
| `CiFailureDetectedEvent` | `ci.failure_detected` | gitci | ai |
| `CiFailureSummarizedEvent` | `ci.failure_summarized` | ai | board, notification |
| `RiskDeadlineFlaggedEvent` | `risk.deadline_flagged` | ai | notification |

Each event record contains: `eventType()`, `occurredAt()` (Instant), plus domain-specific fields matching the architecture spec.

**Event listener wiring (scaffolded):**

- `board-impl/GitActivityEventListener` → listens to `GitCommitLinkedEvent`, `GitPrOpenedEvent`, `GitPrMergedEvent`
- `ai-impl/AiEventListeners` → listens to `CiFailureDetectedEvent`, `TaskCreatedEvent`, `TaskStatusChangedEvent`
- `notification-impl/NotificationEventListeners` → listens to 6 subscribed events (`TaskCreatedEvent`, `TaskStatusChangedEvent`, `GitPrOpenedEvent`, `GitPrMergedEvent`, `CiFailureSummarizedEvent`, `RiskDeadlineFlaggedEvent`)

---

## 4. Backend Build Files

### `backend/common/build.gradle.kts`

```kotlin
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api("org.springframework:spring-context")
    api("org.springframework.data:spring-data-jpa")
    api("jakarta.persistence:jakarta.persistence-api")
    api("jakarta.annotation:jakarta.annotation-api")
}
```

### `backend/auth-impl/build.gradle.kts` (representative of standard -impl modules)

```kotlin
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(project(":common"))
    api(project(":auth-api"))
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-security")
}
```

### `backend/ai-impl/build.gradle.kts` (specialized with Spring AI & MCP Starter)

```kotlin
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    api(platform("org.springframework.ai:spring-ai-bom:1.0.0"))
    api(project(":common"))
    api(project(":ai-api"))
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-websocket")
    api("org.springframework.ai:spring-ai-starter-model-openai")
    api("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
}
```

### `backend/app/build.gradle.kts`

```kotlin
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    implementation(project(":common"))
    implementation(project(":auth-impl"))
    implementation(project(":board-impl"))
    implementation(project(":gitci-impl"))
    implementation(project(":ai-impl"))
    implementation(project(":notification-impl"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}
```

---

## 5. Configuration

### Production (`backend/app/src/main/resources/application.yml`)

```yaml
spring:
  application.name: devflow
  datasource.url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/devflow}
  datasource.username: ${SPRING_DATASOURCE_USERNAME:devflow}
  datasource.password: ${SPRING_DATASOURCE_PASSWORD:devflow}
  jpa.hibernate.ddl-auto: update
  jpa.open-in-view: false
  ai.model.chat: ${AI_MODEL_CHAT:none}
  ai.openai.api-key: ${OPENAI_API_KEY:}
  ai.mcp.server.name: devflow
  ai.mcp.server.version: 0.0.1-SNAPSHOT
server.port: ${SERVER_PORT:8080}
management.endpoints.web.exposure.include: health,info,prometheus
management.endpoint.health.show-details: always
logging.level.root: info
```

### Test (`backend/app/src/test/resources/application.yml`)

```yaml
spring:
  datasource.url: jdbc:h2:mem:devflow-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
  datasource.driver-class-name: org.h2.Driver
  datasource.username: sa
  datasource.password:
  jpa.hibernate.ddl-auto: create-drop
  ai.model.chat: none
  ai.openai.api-key: test-dummy-key-not-real
  ai.mcp.server.name: devflow
  ai.mcp.server.version: 0.0.1-SNAPSHOT
```

**Note:** The test config uses a dummy OpenAI key (`test-dummy-key-not-real`) because Spring AI's `OpenAiAudioSpeechAutoConfiguration` requires a non-empty key at context load time. Production uses the `OPENAI_API_KEY` environment variable.

---

## 6. WebSocket Configuration

**File:** `backend/app/src/main/java/io/devflow/config/WebSocketConfig.java`

- STOMP endpoint: `/ws` (allows all origins)
- Simple broker: `/topic`
- App destination prefix: `/app`
- Conventions: clients SEND to `/app/*`, SUBSCRIBE to `/topic/*`

---

## 7. Frontend

### Dependencies (`frontend/package.json`)

- **Runtime:** `react 19.1.0`, `react-dom 19.1.0`, `react-router-dom 7.6.0`, `clsx 2.1.1`
- **Dev:** `vite 6.3.5`, `tailwindcss 4.1.7`, `@tailwindcss/vite 4.1.7`, `typescript 5.8.3`, `eslint 9.25.0`, `typescript-eslint 8.30.1`

### Vite Proxy (`frontend/vite.config.ts`)

```typescript
proxy: {
  '/api': 'http://localhost:8080',
  '/ws': { target: 'ws://localhost:8080', ws: true },
  '/sse': 'http://localhost:8080',
  '/mcp': 'http://localhost:8080',
  '/actuator': 'http://localhost:8080',
}
```

### Routing (`frontend/src/App.tsx`)

- `*` → `BoardPage` (catch-all)
- `ChatPanel` renders as floating overlay/sidebar on every route

### Nginx (`frontend/nginx.conf`)

- `/` → SPA static files with `try_files $uri $uri/ /index.html`
- `/api/` → `http://backend:8080`
- `/actuator/` → `http://backend:8080`
- `/ws` → WebSocket upgrade proxy to `http://backend:8080`
- `/sse` → proxy with `proxy_buffering off; proxy_cache off`
- `/mcp/` → `http://backend:8080`

---

## 8. Docker

### `backend/Dockerfile`

- **Build:** `gradle:8.14-jdk17` → runs `gradle :app:bootJar --no-daemon`
- **Runtime:** `eclipse-temurin:17-jre` with curl for healthcheck
- Entrypoint: `java -jar app.jar`

### `frontend/Dockerfile`

- **Build:** `node:22-alpine` → `npm ci && npm run build`
- **Runtime:** `nginx:alpine` serving `dist/` with custom `nginx.conf`

### `docker-compose.yml`

5 services: `db` (postgres:17-alpine), `backend`, `frontend` (nginx), `prometheus` (v2.54.1), `grafana` (11.2.2).

- Healthchecks on db (`pg_isready`) and backend (`curl /actuator/health`)
- Named volumes: `pgdata`, `grafana-data`
- `backend` depends on `db` with `condition: service_healthy`
- Network validated with `docker compose config`

---

## 9. Observability

### Prometheus (`infra/prometheus/prometheus.yml`)

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: devflow-backend
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["backend:8080"]
```

### Grafana (`infra/grafana/provisioning/datasources/prometheus.yml`)

- Auto-provisioned Prometheus datasource pointing to `http://prometheus:9090`
- Admin credentials: admin/admin

---

## 10. Module API Interfaces (Verified from Code)

### `auth-api/src/main/java/io/devflow/auth/api/AuthApi.java`

```java
public interface AuthApi {
    /** Looks up a user by id. Used by other modules to resolve assignees/authors. */
    Optional<UserSummary> findUser(UUID userId);

    /** Checks workspace membership — the building block of permission checks. */
    boolean isWorkspaceMember(UUID userId, UUID workspaceId);
}
```

### `board-api/src/main/java/io/devflow/board/api/BoardApi.java`

```java
public interface BoardApi {
    /** All tasks of a project — input for conversational project Q&A (PRODUCT_SPEC 5.2b). */
    List<TaskSummary> findTasksByProject(UUID projectId);

    /** Single task lookup, e.g. to attach a CI failure summary (PRODUCT_SPEC 5.2a). */
    Optional<TaskSummary> findTask(UUID taskId);
}
```

### `gitci-api/src/main/java/io/devflow/gitci/api/GitCiApi.java`

```java
public interface GitCiApi {
    /** Looks up a recorded CI failure by pipeline id. */
    Optional<CiFailureInfo> findFailure(String pipelineId);
}
```

### `ai-api/src/main/java/io/devflow/ai/api/AiServiceApi.java`

```java
public interface AiServiceApi {
    String answerProjectQuery(UUID projectId, String question);
    TaskBreakdownProposal proposeTaskBreakdown(String description);
    String summarizeCiFailure(String pipelineId);
}
```

### `notification-api/src/main/java/io/devflow/notification/api/NotificationApi.java`

```java
public interface NotificationApi {
    /** Delivers a notification to a single user (in-app first; push is a later option). */
    void sendToUser(UUID userId, String subject, String body);
}
```

---

## 11. Health Endpoints (Verified from Code)

Every `-impl` module exposes a health controller under the `/api/v1/` prefix (which aligns with the reverse proxy in Vite/Nginx):

| Module | Health Endpoint Path | Payload Returned |
|--------|---------------------|------------------|
| auth-impl | `/api/v1/auth/health` | `{"module":"auth","status":"UP"}` |
| board-impl | `/api/v1/boards/health` | `{"module":"board","status":"UP"}` |
| gitci-impl | `/api/v1/gitci/health` | `{"module":"gitci","status":"UP"}` |
| ai-impl | `/api/v1/ai/health` | `{"module":"ai","status":"UP"}` |
| notification-impl | `/api/v1/notifications/health` | `{"module":"notification","status":"UP"}` |
| app (Actuator) | `/actuator/health` | Standard Spring Boot health JSON with details |

---

## 12. What Is Scaffolded (Skeletons Only)

| Area | Status | What exists |
|------|--------|-------------|
| Gradle multi-module structure | Done | 12 projects, wrapper (8.14), root build with module boundary enforcement |
| common events | Done | 8 typed records matching ARCHITECTURE.md Section 4 |
| common BaseEntity | Done | `@MappedSuperclass` with `id`, `createdAt`, `updatedAt` |
| common JpaAuditingConfig | Done | `@EnableJpaAuditing` |
| 5 × api interfaces | Done | Public interfaces in `-api` modules |
| 5 × impl services | Done | Stub services (return defaults or throw scaffold UnsupportedOperationException) |
| 5 × impl controllers | Done | Stub REST endpoints (return 501 NOT_IMPLEMENTED JSON) |
| 5 × impl health | Done | Health endpoints per module under `/api/v1/*/health` |
| Event listeners | Done | 3 listener classes wiring events to stub handlers |
| AI MCP tools | Done | `McpToolConfig` with `@Tool` methods `queryProject` and `lookupCiFailure` |
| AI chat gateway | Done | `ChatGatewayController` (STOMP `/app/chat` → `/topic/chat`) |
| Security config | Done | Permits `/actuator/**`, `/api/v1/*/health`, `/ws/**`, `/sse`, `/mcp/**`; other endpoints require auth |
| WebSocket broker | Done | STOMP `/ws` with `/topic` broker and `/app` prefix |
| App bootstrap | Done | `@SpringBootApplication`, test with in-memory H2 |
| Frontend routing | Done | `react-router-dom` with `BoardPage` + `ChatPanel` |
| Frontend pages | Done | `BoardPage` (scaffold grid), `LoginPage` (stub) |
| Frontend build & lint | Done | TypeScript compiles (`tsc -b`), Vite produces `dist/`, ESLint 9 configured and passing |
| Dockerfiles | Done | Multi-stage for backend and frontend |
| docker-compose | Done | 5 services with healthchecks, volumes, validated with `docker compose config` |
| Prometheus | Done | Scrapes `/actuator/prometheus` |
| Grafana | Done | Auto-provisions Prometheus datasource |

---

## 13. What Is NOT Implemented (All Business Logic)

| Area | Status |
|------|--------|
| JPA entities (User, Workspace, Board, Task, Column, etc.) | Not done |
| JPA repositories | Not done |
| Database migrations (Flyway) | Not done |
| Real service business logic | All services return stubs/defaults |
| OAuth2 / Google sign-in | Not done |
| Task CRUD operations | Not done |
| Board CRUD operations | Not done |
| Git webhook signature verification | Not done |
| GitHub API integration | Not done |
| CI webhook parsing (GitHub Actions, GitLab CI) | Not done |
| Real Spring AI chat integration | Returns "not configured" stub |
| MCP tool implementations | Return stubs |
| WebSocket message handling (card moves, live sync) | Not done |
| Notification delivery (email, WebSocket push) | Not done |
| Frontend API calls / data fetching | Not done |
| Frontend state management | Not done |
| Frontend board drag-and-drop | Not done |
| Frontend AI chat UI | Not done |
| Unit tests (beyond contextLoads) | Not done |
| Integration tests | Not done |
| CI/CD pipeline config | Not done |

---

## 14. Known Issues / Technical Debt

1. **Gradle deprecation warning:** `dep.dependencyProject` in `verifyModuleBoundaries` is deprecated in Gradle 9.0. Cosmetic only for Gradle 8.x.
2. **Local Java environment:** Ensure `JAVA_HOME` points to a valid Java 17 installation (e.g. `C:\Program Files\Java\jdk-17`) without trailing spaces or incompatible preview versions (like early access JDK 25).
3. **Test uses dummy OpenAI key:** Required because `OpenAiAudioSpeechAutoConfiguration` validates key presence at startup. Should be replaced with proper auto-configuration exclusion or `@ConditionalOnProperty` in production.
4. **`ddl-auto: update`** in production config — should be replaced with Flyway/Liquibase before production deployment.
5. **No Flyway/Liquibase** dependency or migration files.
6. **`spring.ai.model.chat` defaults to `none`** — app boots without AI, but all AI endpoints return stubs.
7. **Git ignore cleanliness:** Both root `.gitignore` and `frontend/.gitignore` cleanly exclude all build artifacts (`dist/`, `build/`, `.gradle/`, `node_modules/`, `.env`).
