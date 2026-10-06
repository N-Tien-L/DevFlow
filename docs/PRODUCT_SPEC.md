# DevFlow — Product & Feature Specification

> 🌐 **Language:** **English** (Authoritative Agent Product Specification) | [Bản Tiếng Việt (Dành cho Developer)](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/docs/PRODUCT_SPEC_VI.md)
>
> **Purpose of this document**
> This is the source-of-truth product spec for DevFlow. It exists so that both human contributors and AI coding agents (Cursor, Claude Code, etc.) share the same understanding of *what* the product is and *why* each feature exists, before any architecture or tech-stack decisions are made.
>
> **Status:** Idea & feature scope is locked. Architecture and tech stack are decided in [docs/ARCHITECTURE.md](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/docs/ARCHITECTURE.md) (or [docs/ARCHITECTURE_VI.md](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/docs/ARCHITECTURE_VI.md)).

---

## 1. Overview

**Tagline:** *"Trello for developers — one that understands your code, not just your tasks."*

DevFlow is a board/card-based project management tool (Trello-style) purpose-built for developers. Unlike general-purpose PM tools, DevFlow connects directly to real developer activity (Git, CI/CD) to keep task state accurate automatically, and layers an AI assistant on top to help users create, track, and understand project progress without repetitive manual work.

**Deployment Target:** DevFlow is built as a production-grade SaaS application — deployed on public cloud infrastructure (Render/AWS/GCP), accessible via a custom domain, serving real users with real traffic. The quality standard is commercial viability: every module must handle adversarial input, real-world failure modes, and operational concerns as if serving paying customers.

## 2. Problem Statement

- General PM tools (Trello, Jira) have no awareness of real code activity — users must manually update task status, which is frequently forgotten or delayed.
- Creating tasks and breaking down work from a requirement or bug report is repetitive and time-consuming.
- CI/CD failure logs and tracebacks are long and hard to parse quickly, slowing down debugging.
- Finding out project status (who's doing what, are we on track) usually requires manually filtering through multiple screens instead of just asking.
- Leaving the coding environment (Cursor, Claude Code, etc.) to check project status is a context-switch developers want to avoid.

## 3. Target Users

**Primary audience: developers, across team sizes.**

| Segment | Needs |
|---|---|
| Solo developer | Lightweight tool, no PM process overhead; benefits from AI auto-generating tasks and flagging overload/context-switching |
| Small team (e.g. student project team, small startup) | Tight progress tracking, coordination via Git; benefits from auto status updates from commits/PRs and CI/CD failure summaries |
| Large team / multiple teams | Needs cross-team visibility; benefits from detecting dependencies/conflicts between teams or modules |

Narrowing to developers specifically (instead of general PM audiences) is intentional — it lets us drop features irrelevant to this audience (complex approval workflows, form builders, non-technical integrations) and focus on what developers actually need.

## 4. Differentiators

- **vs. Trello:** AI-assisted task creation/breakdown; task status updates automatically from real code activity instead of manual drag-and-drop only.
- **vs. Jira:** Lighter weight, no enterprise approval/configuration overhead; tightly scoped to the code → PR → deploy workflow.
- **vs. GitHub Projects:** Adds a conversational AI layer and CI/CD failure summarization, not just an issue tracker view.
- **Most distinctive feature:** AI coding tools (Cursor, Claude Code) can query project info and CI/CD analysis directly from within the editor via an MCP server — no need to switch to a separate app.

## 5. Feature Design

Features are described at the product/value level (not implementation detail), grouped by category. Each feature is tagged with priority — see [Section 6](#6-mvp-scope--priority) for the full priority table.

### 5.1 Core PM Features (baseline, present in most PM tools)

- **Accounts & workspaces** — Production-grade registration flow (client-side validation, CAPTCHA/bot detection, server-side sanitization, duplicate & disposable email check, DNS MX verification, email verification with OTP/token, account activation workflow), secure login with JWT access/refresh tokens, profile management (GET /me), create a project/workspace, invite members with role-based access.
- **Trello-style board** — create status columns (to-do, in progress, done...), create/edit/delete cards, drag-and-drop between columns.
- **Task details** — assignee, due date, labels/priority, description, comments.
- **Activity history** — who changed what, and when.
- **Search & filter** — find tasks by name, filter by assignee/label/status.
- **Notifications** — notify on new assignment, new comment, or approaching due date.
- **Real-time sync** — multiple users viewing the same board see changes instantly.
- **Basic progress dashboard** — count of done/pending tasks, simple progress chart over time.

### 5.2 Developer-Specialized & AI Features

This is where DevFlow differentiates itself, built around three pillars: (1) real code/CI integration, (2) a conversational AI assistant, and (3) direct access for AI coding agents.

#### a) Git & CI/CD Integration
- **Task ↔ commit/PR linking** — when a branch or commit follows a naming convention referencing a task ID, the system automatically links them.
- **Automatic status updates** — a task moves to "In Progress" on first commit, "In Review" when a PR opens, "Done" when the PR merges — no manual update needed.
- **CI/CD failure summarization** — when a pipeline fails, the system reads the log and produces a short summary of the likely cause instead of requiring users to read a long traceback.
- **Failure clustering** — repeated CI failures with the same underlying cause are grouped into one issue instead of being reported as separate noise.
- **Failure-to-task/PR linking** — each failure is linked to the code change that likely caused it, making it clear who/what needs to act.

#### b) Conversational AI Assistant (in-app chat)
- **Natural-language project Q&A** — ask things like "how many tasks are left this sprint?" or "who owns the payments module?" instead of manually filtering screens.
- **Task creation & breakdown from description** — paste a requirement or an error log; AI proposes a breakdown into subtasks, editable before creation.
- **AI-assisted project setup** — for a new project, AI asks a few short questions then scaffolds an initial board structure and starter tasks.
- **Live sync with the board** — actions taken via chat (e.g. AI creates a task) are reflected immediately on the board the user is viewing, so users don't lose visual context while chatting.
- **Proactive deadline risk alerts** — based on actual completion pace vs. plan, proactively flag risk of slipping a deadline rather than only showing it passively.

#### c) AI Coding Agent Access (MCP Server)
- **Direct project queries from the coding environment** — AI coding tools (Cursor, Claude Code) can query project data without switching apps.
- **"What am I working on" queries** — a developer can ask, from inside the editor, "what's my current task, when's it due, anything I should know."
- **In-editor CI/CD failure lookup** — right after a push fails CI, ask the coding agent "is this failure related to what I just changed" without opening a separate dashboard.
- **Thin adapter, not a new system** — this exposes the same underlying AI Service capabilities used by the chat assistant; it is not a separate AI system.

#### d) Team-Size-Aware Behavior
- **Solo dev:** focus on avoiding fragmentation — e.g. flag when too many tasks are open at once.
- **Small team:** add deadline risk alerts, detect when one member is overloaded relative to others.
- **Large team / multi-team:** detect cross-team dependencies — one team's task blocking another's progress, or multiple teams touching the same shared code area.

## 6. MVP Scope & Priority

Legend: **MVP** = required for first shippable version · **Should-have** = add if time allows · **Later** = real value, but needs accumulated data or larger user scale than the project timeline allows.

### 6.1 Core PM Features

| Feature | Value | Priority |
|---|---|---|
| Accounts & workspace | Baseline requirement to use the product | MVP |
| Drag-and-drop board/cards | Core, familiar experience | MVP |
| Assignee, due date, labels | Basic work management | MVP |
| Real-time sync | Smooth team coordination | MVP |
| Basic notifications | Don't miss important updates | MVP |
| Comments & activity history | Discussion, audit trail | Should-have |
| Progress dashboard | Quick view of project health | Should-have |

### 6.2 Developer-Specialized & AI Features

| Feature | Value | Priority |
|---|---|---|
| Git-based task linking & auto status update | Removes manual updates, keeps data accurate | MVP |
| AI task creation/breakdown from description | Saves time on initial planning | MVP |
| CI/CD failure summarization | Faster debugging, no long log reading | MVP |
| Conversational project Q&A | Fast lookup without manual filtering | MVP |
| Chat-to-board live sync | Visual continuity while chatting | Should-have |
| Proactive deadline risk alerts | Act early instead of reactively | Should-have |
| MCP server for AI coding agents | Query project info from inside the editor | Should-have |
| Repeated-failure clustering | Reduces noise, surfaces real issues | Should-have |
| AI-assisted project setup | Faster project kickoff | Later |
| Effort estimation from history/code complexity | Better estimates over time | Later |
| Cross-team dependency detection | Useful once multiple teams/modules exist | Later |
| Automatic daily activity digest | Reduces standup overhead | Later |

**Why "Later" items are deferred:** they either need a meaningful amount of accumulated historical data to be reliable (effort estimation, dependency detection, daily digests), or only show clear value once there are multiple teams/users over a longer period than this project's timeline allows. Shipping them early with too little real data would produce results that are hard to justify convincingly.

## 7. Explicit Non-Goals

To keep scope tight for the target audience (developers), the following are intentionally **out of scope**:

- Complex multi-step approval/sign-off workflows
- Custom form builders
- Non-technical/business-user-oriented features (e.g. marketing/email campaign tooling)
- Enterprise-grade permission systems beyond basic role/member management
- Competing on breadth of integrations with general-purpose PM suites

## 8. Quality Standards & Non-Functional Requirements

DevFlow operates as a production SaaS application. Beyond functional requirements, every module must satisfy the following six Non-Functional Requirement (NFR) pillars to ensure system integrity, security, and resilience:

| # | NFR Pillar | Policy & Requirements | System-Wide Scope |
|---|---|---|---|
| **1** | **Security Hardening** | All client input must be validated and sanitized server-side. Rate limiting enforced on sensitive endpoints (auth, mutations, AI calls). Mandatory email verification for registration. CAPTCHA/bot detection (Cloudflare Turnstile) on public endpoints. Strict OWASP Top 10 compliance (XSS, CSRF, SQLi, IDOR prevention). | Auth, Board API, Webhooks, AI Chat |
| **2** | **Observability & Monitoring** | Structured logging (JSON format) with distributed MDC correlation IDs across requests. Health check endpoints (`/actuator/health`) for liveness/readiness. Standardized metrics export via Micrometer to Prometheus. Alerting rules for error rate spikes, P99 latency degradation, and resource exhaustion. | All Modules, Docker & Cloud Infra |
| **3** | **Scalability & Performance** | HikariCP connection pool tuned for production concurrency. Caching layer (in-memory Caffeine initially, Redis-ready) for hot paths. Asynchronous execution (`@Async` / event workers) for heavy tasks (AI inference, email delivery, webhook fan-out). P95 API response times under 200ms for core CRUD operations. | AI Service, Notification, Auth, Board |
| **4** | **Reliability & Resilience** | Graceful error responses conforming to RFC 7807 (`ProblemDetail`) — zero stack traces leaked to clients. Retry policies with exponential backoff and jitter for external calls (LLM providers, email delivery, Git APIs). Circuit breaker patterns (Resilience4j) to prevent cascading failures. Documented DB backup and point-in-time recovery strategy. | Git & CI, AI Service, Notification |
| **5** | **Data Privacy & Compliance** | GDPR-aligned principles: right to erasure (account deletion cascade or anonymization). Complete audit logging for security-sensitive and entity-mutation events (actor, action, resource, timestamp, IP). Password hashing via BCrypt with configurable cost factor. Ephemeral verification token storage with strict expiration and one-time use. | Auth, User profile, Board Audit |
| **6** | **Input Integrity & Anti-Abuse** | Zero-trust client validation: server-side validation is mandatory for every request. Robust input sanitization (trimming, email normalization, HTML stripping). Disposable email domain filtering on registration. DNS MX record validation for recipient domains. Strict payload size and pagination limits on all collections. | Auth, Board, Chat, Webhook Ingestion |

## 9. Glossary

| Term | Meaning |
|---|---|
| Task / Card | A unit of work on the board (interchangeable terms) |
| Board | A project's Trello-style workspace containing status columns |
| AI Service | The shared backend capability powering task breakdown, Q&A, and CI/CD summarization — used by both the in-app chat and the MCP server, not duplicated logic |
| MCP Server | The adapter exposing AI Service capabilities to external AI coding agents |
| MVP | Minimum viable product — the required scope for the first shippable version |

## 10. Notes for AI Coding Agents

- Do not invent architecture, service boundaries, or a tech stack based on this document alone — those decisions live in a separate architecture document once finalized.
- The AI Service is a single shared capability with two client types (chat UI, MCP server) — avoid implementing duplicate logic for each.
- Treat items marked **Later** as explicitly out of scope unless the human maintainer says otherwise — do not proactively implement them.
- When in doubt about a feature's intended scope, prefer the description in Section 5 over inferring intent from the feature name alone.
- Adhere strictly to the Quality Standards & NFRs defined in Section 8. "Happy-path only" implementations are unacceptable.
