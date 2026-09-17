# DevFlow — AI Agent Workspace Guide

> Welcome to **DevFlow**! This document is the primary entry point for AI agents (Antigravity, Cursor, Claude Code, etc.) working in this codebase. Read this file first to understand the architecture, boundaries, conventions, and operational commands.

---

## 1. Project Overview

**DevFlow** is an intelligent developer coordination platform designed for software teams. It integrates Kanban board management, Git commit/PR tracking, CI pipeline failure triage, automated AI risk assessment, and smart developer notifications into a single cohesive workspace.

- **Backend:** Modular Monolith in Java 17, Spring Boot 3.4.5, Gradle multi-project build, PostgreSQL 17, Spring Event Bus.
- **Frontend:** Single Page Application (SPA) in React 19, TypeScript 5.8, Vite 6, TailwindCSS 4, React Router 7.
- **Infrastructure:** Docker Compose with PostgreSQL, Prometheus, and Grafana.

---

## 2. Mandatory Core Documentation

Before designing new features, modifying architecture, or adding integrations, you **must consult**:

1. [docs/ARCHITECTURE.md](file:///docs/ARCHITECTURE.md) (EN) / [docs/ARCHITECTURE_VI.md](file:///docs/ARCHITECTURE_VI.md) (VI) — The authoritative system architecture:
   - Section 2: Modular Monolith architecture & dependency layout
   - Section 4: Domain Event Catalog (`DevFlowEvent` contract & matrix)
   - Section 5: Database schema & module table ownership
   - Section 10: Module boundary enforcement rules
2. [docs/PRODUCT_SPEC.md](file:///docs/PRODUCT_SPEC.md) (EN) / [docs/PRODUCT_SPEC_VI.md](file:///docs/PRODUCT_SPEC_VI.md) (VI) — Functional specifications, user personas, use cases, and acceptance criteria.
3. [docs/IMPLEMENTATION_PLAN.md](file:///docs/IMPLEMENTATION_PLAN.md) (EN) / [docs/IMPLEMENTATION_PLAN_VI.md](file:///docs/IMPLEMENTATION_PLAN_VI.md) (VI) — Master implementation plan, 5-phase rollout roadmap, sprint milestones, and ticket breakdown.
4. [Trello Management Skill](file:///.agents/skills/trello-management/SKILL.md) — Project tasks, board lists, and card workflows.

---

## 3. Strict Module Boundary Rules (Zero-Tolerance)

DevFlow enforces clean separation of concerns at compile/build time:

- **Module Structure:** Every business domain consists of two subprojects:
  - `<module>-api`: Public interface and DTOs. **Only depends on `:common`**.
  - `<module>-impl`: Internal logic, JPA entities, controllers, and services. Depends on `:common`, its own `-api`, and foreign `-api` modules.
- **No `impl -> impl` dependencies:** Under NO circumstances may one module depend on or import classes from another module's `-impl` package (`io.devflow.<module>.internal.*`).
- **Cross-module communication:** Must ONLY occur via the Spring Event Bus (`ApplicationEventPublisher`) using events extending `DevFlowEvent` in `:common`.
- **Validation:** Always verify boundaries with `./gradlew :verifyModuleBoundaries`.

---

## 4. Skills & Rules Index

### Custom Skills (`.agents/skills/`)
| Skill | Purpose | Key File / Entrypoint |
|---|---|---|
| [`trello-management`](file:///.agents/skills/trello-management/SKILL.md) | Manage Trello boards, sprint lists, cards, checklists | `node .agents/skills/trello-management/scripts/trello.js` |
| [`backend-dev`](file:///.agents/skills/backend-dev/SKILL.md) | Gradle commands, module creation, event publishing, JPA/REST patterns | `./gradlew` |
| [`frontend-dev`](file:///.agents/skills/frontend-dev/SKILL.md) | Vite commands, React components, Tailwind styling, API/WS integration | `npm run dev` in `frontend/` |
| [`docker-local`](file:///.agents/skills/docker-local/SKILL.md) | Manage containers, inspect logs, PostgreSQL CLI, Prometheus/Grafana | `docker compose` |

### Project Rules (`.agents/rules/`)
| Rule | Scope |
|---|---|
| [`coding-conventions.md`](file:///.agents/rules/coding-conventions.md) | Naming patterns, package organization, REST standards, React component rules |
| [`git-workflow.md`](file:///.agents/rules/git-workflow.md) | Branch naming (`feature/T-XXX-...`), commit messages (Conventional Commits), PR guidelines |
| [`module-boundaries.md`](file:///.agents/rules/module-boundaries.md) | Dependency rules, event-driven isolation, forbidden patterns |
| [`response-formatting.md`](file:///.agents/rules/response-formatting.md) | Chat output formatting (no Mermaid/KaTeX in chat, DevFlow ASCII diagrams, link format) |

---

## 5. Common Operational Commands

### Backend
```bash
# Verify architecture module boundaries and run all tests
cd backend
# (If on Windows and JAVA_HOME issue occurs: $env:JAVA_HOME = "C:\Program Files\Java\jdk-17")
./gradlew check

# Run boundary verification task only
./gradlew :verifyModuleBoundaries

# Run Spring Boot backend locally
./gradlew :app:bootRun
```

### Frontend
```bash
cd frontend
# Install dependencies
npm install

# Start Vite dev server (proxies /api to http://localhost:8080)
npm run dev

# Run TypeScript check & build
npm run build

# Run linter
npm run lint
```

### Docker Compose
```bash
# Start all services (db, backend, frontend, prometheus, grafana) in background
docker compose up -d

# Check status of containers
docker compose ps

# View backend logs in real-time
docker compose logs -f backend

# Stop all containers
docker compose down
```

---

## 6. Service & Port Matrix

| Service | Port | Healthcheck / URL |
|---|---|---|
| **Backend API** | `8080` | `http://localhost:8080/actuator/health` |
| **Frontend UI** | `3000` (Docker) / `5173` (Vite) | `http://localhost:3000` or `http://localhost:5173` |
| **PostgreSQL** | `5432` | `devflow:devflow@localhost:5432/devflow` |
| **Prometheus** | `9090` | `http://localhost:9090` |
| **Grafana** | `3001` | `http://localhost:3001` (admin / admin) |

---

## 7. Working on a Task Workflow

When instructed to work on a task:
1. **Find Card & Ticket ID:** Use `trello-management` skill to find the card details and acceptance criteria (e.g., `T-010`).
2. **Review Architecture:** Check `docs/ARCHITECTURE.md` to see which modules and events are affected.
3. **Follow Git Convention:** Follow `.agents/rules/git-workflow.md` for branch naming (`feature/T-<id>-<description>`).
4. **Implement Code:** Follow `.agents/rules/coding-conventions.md` and `.agents/rules/module-boundaries.md`.
5. **Verify:** Run `./gradlew check` and frontend `npm run build` to ensure no compile, test, or boundary errors.
6. **Update Trello:** Move card to `In Progress` when starting and update checklist items as you make progress.
