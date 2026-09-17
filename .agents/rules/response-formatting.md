# Chat Response Formatting Rules (No Mermaid, No KaTeX) — DevFlow

These rules apply to all AI Agent responses in the **IDE chat window** to ensure clean rendering, readability, and zero broken markup in the user interface.

> [!NOTE]
> **Chat Window vs. Artifacts:**
> - **Chat Responses (strictly enforced):** NEVER use Mermaid or LaTeX/KaTeX in normal chat responses.
> - **Artifacts & Markdown Docs (allowed):** Dedicated artifacts (e.g., `implementation_plan.md`, `walkthrough.md`) and project docs (e.g., `docs/ARCHITECTURE.md`) may utilize standard GitHub-flavored Mermaid blocks when rendered by full markdown artifact viewers.

---

## 1. No Mermaid Diagrams in Chat Responses

- **PROHIBITED:** Never output ````mermaid ... ```` code blocks in chat responses. The IDE chat window cannot render Mermaid graphics and displays broken/raw code blocks.
- **ALLOWED ALTERNATIVES:**
  - **ASCII / Unicode Box Art & Flowcharts:** Use plain text arrows (`-->`, `->`, `──►`) and box characters (`┌─┐`, `└─┘`, `├──`, `└──`, `│`).
  - **Markdown Tables:** For structured flows, state transitions, or component mappings.
  - **Structured Bullet / Numbered Lists:** For step-by-step pipeline stages.

### Example A: Cross-Module Event Pipeline (ASCII Box Art)
```text
[Git Webhook / PR] ──► [Git & CI Module]
                              │
                              ▼ (publish: ci.failure_detected)
                     [Spring Event Bus]
                      ├──► [AI Risk Module] ──► (Assess risk & root cause)
                      └──► [Notification Module] ──► [WebSocket UI / Alert]
```

### Example B: Kanban Task State Machine (ASCII Flow)
```text
[BACKLOG] ──► [TODO] ──► [IN_PROGRESS] ──► [IN_REVIEW] ──► [DONE]
                ▲              │
                └──(Rejected)──┘
```

---

## 2. No KaTeX / LaTeX Math Syntax in Chat Responses

- **STRICTLY PROHIBITED:** Never use KaTeX/LaTeX delimiters (`$...$`, `$$...$$`) or backslash LaTeX commands (`\frac`, `\Delta`, `\approx`, `\ge`, `\le`, `\times`, `\rightarrow`, `\to`, `\Rightarrow`, `\in`, `\dots`, etc.) in chat responses. The IDE chat UI markdown renderer does NOT support LaTeX and will render them as broken literal text (e.g., `\le`, `$\rightarrow$`, `\Delta`).

### Quick Reference: Banned LaTeX vs Required Alternatives (DevFlow Domain)

| Meaning / Concept | BANNED LaTeX Syntax | REQUIRED Alternative | DevFlow Example |
|---|---|---|---|
| State Transitions / Flow | `$\rightarrow$`, `\rightarrow`, `$\to$`, `\to` | `->` or `→` | `TODO -> IN_PROGRESS -> DONE` |
| Implication / Triggers | `$\Rightarrow$`, `\Rightarrow` | `=>` or `⇒` | `ci.failure_detected => trigger_ai_triage` |
| Less Than or Equal | `$\le$`, `\le`, `$\leq$`, `\leq` | `<=` or `≤` | `failure_rate <= 0.05`, `p99 <= 200ms` |
| Greater Than or Equal | `$\ge$`, `\ge`, `$\geq$`, `\geq` | `>=` or `≥` | `risk_score >= 0.75`, `failure_count >= 3` |
| Not Equal | `$\neq$`, `\neq`, `\ne` | `!=` or `≠` | `task.status != 'DONE'` |
| Approximation | `$\approx$`, `\approx`, `$\sim$` | `~` or `≈` | `latency ~ 150ms`, `~300 tasks` |
| Multiplication / Dimension | `$\times$`, `\times`, `$\cdot$` | `*` or `x` | `0.4 * churn`, `1920x1080` |
| Delta / Difference | `$\Delta$`, `\Delta` | `Δ` (Unicode) | `Δrisk = +0.25`, `Δcoverage = -4.2%` |
| Microsecond / Micro | `$\mu s$`, `\mu` | `μs` or `us` | `query_time = 450μs` |
| Ellipsis | `$\dots$`, `\ldots`, `\cdots` | `...` | `[task1, task2, ...]` |
| Set Membership | `$\in$`, `\in`, `$\notin$` | `in`, `thuộc`, or `∈` | `role in ['ADMIN', 'DEVELOPER']` |
| Subscripts / Superscripts | `$O(N^2)$`, `$p_{99}$` | `O(N^2)`, `p99` | `p99 latency`, `O(N)` |
| Math Formulas / Fractions | `\frac{A}{B}`, `$\sum$` | `A / B`, `sum(...)` | `risk = (0.4 * churn + 0.4 * ci + 0.2 * complex)` |

### Examples: Broken vs Clean

- **Event & State Mapping:**
  - **BROKEN (Avoid):** `Event $\text{git.pr_merged} \rightarrow \text{BoardService}$`
  - **CLEAN (Use):** `Event git.pr_merged -> BoardService` or `git.pr_merged → BoardService`
- **Comparisons & Thresholds:**
  - **BROKEN (Avoid):** `Nếu risk_score \ge 0.75 và latency \le 200ms`
  - **CLEAN (Use):** `Nếu risk_score >= 0.75 và latency <= 200ms` (hoặc `≥ 0.75`, `≤ 200ms`)
- **Metric / Risk Calculations:**
  - **BROKEN (Avoid):** `$risk\_score = 0.4 \times \text{churn} + 0.6 \times \frac{\text{failures}}{\text{total}}$ với $\Delta risk \ge +0.2$`
  - **CLEAN (Use):** `risk_score = 0.4 * churn + 0.6 * (failures / total)` với `Δrisk >= +0.2`

---

## 3. Clickable File Links & Code Anchors

- Always format file paths and classes as clickable links using Markdown format: `[basename](file:///path/to/file)` or `[ClassName](file:///path/to/file#L10-L20)`.
- Use forward slashes (`/`) even on Windows paths.
- Examples:
  - Controller: [`TaskController.java`](file:///backend/task-impl/src/main/java/io/devflow/task/internal/controller/TaskController.java)
  - Event: [`DevFlowEvent.java`](file:///backend/common/src/main/java/io/devflow/common/event/DevFlowEvent.java)
  - Frontend Component: [`BoardColumn.tsx`](file:///frontend/src/components/board/BoardColumn.tsx)

---

## 4. Response Conciseness & Language Consistency

- **Language:** Respond in the language used by the user (Vietnamese if asked in Vietnamese, English if asked in English). Keep technical terms (e.g., *Modular Monolith, Event Bus, DTO, Entity, Hook, Service*) standard.
- **Conciseness:** Keep chat responses direct, actionable, and focused on the code/solution. Avoid unnecessary boilerplate pleasantries.
