# Chat Response Formatting Rules (No Mermaid, No KaTeX/LaTeX) — DevFlow

These rules apply to all AI Agent responses in the **IDE chat window** to ensure clean rendering, readability, and zero broken markup in the user interface.

> [!NOTE]
> **Chat Window vs. Artifacts:**
> - **Chat Responses (strictly enforced):** NEVER use Mermaid or LaTeX/KaTeX in normal chat responses. The chatbox markdown renderer CANNOT render LaTeX math blocks or formulas; it displays raw tags and broken escape sequences.
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

## 2. Zero KaTeX / LaTeX Syntax in Chat Responses (Strictly Enforced)

The IDE chatbox markdown engine **does not parse KaTeX/LaTeX**. Any LaTeX syntax will render as broken, ugly literal text (e.g., `$$\text{User hiện tại (từ JWT)}$$`, `\longrightarrow`, `\le`, `$\Delta$`).

### Critical Banned Patterns

1. **NEVER use Math Delimiters:**
   - No block math: `$$ ... $$`
   - No inline math: `$ ... $`
2. **NEVER use `\text{...}` or LaTeX font commands:**
   - No `\text{...}`, `\textbf{...}`, `\textit{...}`, `\mathrm{...}`, `\mathbf{...}`.
   - For text or labels, use plain markdown: `User hiện tại (từ JWT)` or `**User hiện tại (từ JWT)**`.
3. **NEVER use LaTeX Arrows as pseudo-diagrams:**
   - No `\longrightarrow`, `\rightarrow`, `\to`, `\longleftarrow`, `\leftarrow`.
   - No `\Longrightarrow`, `\Rightarrow`, `\iff`, `\implies`, `\mapsto`.
   - Use plain Unicode/ASCII arrows: `->`, `→`, `-->`, `──►`, `=>`, `⇒`.
4. **NEVER use LaTeX Math Commands:**
   - No `\frac{A}{B}`, `\ge`, `\le`, `\approx`, `\times`, `\Delta`, `\sum`, `\dots`, `\in`, etc.

---

### Quick Reference: Banned LaTeX vs Required Alternatives

| Category | BANNED LaTeX Syntax (Breaks Chat UI) | REQUIRED Clean Alternative | DevFlow Examples |
|---|---|---|---|
| **Delimiters & Text Blocks** | `$$ ... $$`, `$ ... $`, `\text{...}` | Plain text, bold, or code span | `User hiện tại (từ JWT)`<br>`**User hiện tại (từ JWT)**`<br>`User (từ JWT)` |
| **Long / Short Arrows** | `\longrightarrow`, `\rightarrow`, `$\to$`, `\to` | `->`, `→`, or `──►` | `User (JWT) -> Workspace`<br>`TODO → IN_PROGRESS → DONE` |
| **Double / Implication Arrows** | `\Longrightarrow`, `\Rightarrow`, `\implies`, `\iff` | `=>` or `⇒` | `ci.failure_detected => trigger_ai_triage`<br>`token_valid => allow_access` |
| **Left Arrows** | `\longleftarrow`, `\leftarrow` | `<-` or `←` | `State <- StateHistory` |
| **Less Than / Greater Than or Equal** | `\le`, `$\le$`, `\leq`, `\ge`, `$\ge$`, `\geq` | `<=` or `≤`, `>=` or `≥` | `failure_rate <= 0.05`<br>`risk_score >= 0.75` |
| **Not Equal** | `\neq`, `\ne`, `$\neq$` | `!=` or `≠` | `task.status != 'DONE'` |
| **Approximation / Tilde** | `\approx`, `$\approx$`, `$\sim$` | `~` or `≈` | `latency ~ 150ms`, `~300 tasks` |
| **Multiplication / Dimension** | `\times`, `$\times$`, `\cdot$` | `*` or `x` | `0.4 * churn`, `1920x1080` |
| **Delta / Difference** | `\Delta`, `$\Delta$` | `Δ` (Unicode) | `Δrisk = +0.25`, `Δcoverage = -4.2%` |
| **Microsecond / Micro** | `\mu s`, `\mu` | `μs` or `us` | `query_time = 450μs` |
| **Ellipsis** | `\dots`, `\ldots`, `\cdots` | `...` | `[task1, task2, ...]` |
| **Set Membership** | `\in`, `$\in$`, `\notin$` | `in`, `thuộc`, or `∈` | `role in ['ADMIN', 'DEVELOPER']` |
| **Subscripts / Superscripts** | `$O(N^2)$`, `$p_{99}$` | `O(N^2)`, `p99` | `p99 latency`, `O(N)` |
| **Fractions / Math Formulas** | `\frac{A}{B}`, `$\sum$` | `A / B`, `sum(...)` | `risk = (0.4 * churn + 0.4 * ci + 0.2 * complex)` |

---

### Examples: Broken vs Clean

#### 1. Architecture Flow / Sequence Steps in Chat
- **BROKEN (Raw LaTeX and unrendered blocks in chat):**
  > `$$\text{User hiện tại (từ JWT)} \longrightarrow \text{WorkspaceService} \longrightarrow \text{Database}$$`
- **CLEAN (Plain text with Unicode arrows):**
  > `User hiện tại (từ JWT) → WorkspaceService → Database`
- **CLEAN (ASCII block art):**
  ```text
  [User hiện tại (JWT)] ──► [WorkspaceService] ──► [Database]
  ```

#### 2. Event & State Mapping
- **BROKEN (Avoid):** `Event $\text{git.pr_merged} \longrightarrow \text{BoardService}$`
- **CLEAN (Use):** `Event git.pr_merged -> BoardService` or `git.pr_merged → BoardService`

#### 3. Comparisons & Thresholds
- **BROKEN (Avoid):** `Nếu risk_score \ge 0.75 và latency \le 200ms`
- **CLEAN (Use):** `Nếu risk_score >= 0.75 và latency <= 200ms` (hoặc `≥ 0.75`, `≤ 200ms`)

#### 4. Metric / Formula Calculations
- **BROKEN (Avoid):** `$risk\_score = 0.4 \times \text{churn} + 0.6 \times \frac{\text{failures}}{\text{total}}$ với $\Delta risk \ge +0.2$`
- **CLEAN (Use):** `risk_score = 0.4 * churn + 0.6 * (failures / total)` với `Δrisk >= +0.2`

---

## 3. Agent Self-Check Checklist (Before Sending Any Chat Response)

Before outputting a message in the chatbox, verify:
- [ ] **Zero Math Delimiters:** No `$$...$$` or `$ ... $` anywhere in the response.
- [ ] **Zero LaTeX Text Tags:** No `\text{...}`, `\textbf{...}`, etc.
- [ ] **Zero LaTeX Arrows:** No `\longrightarrow`, `\rightarrow`, `\Longrightarrow`, `\iff`, `\to`.
- [ ] **Zero LaTeX Math Symbols:** No `\le`, `\ge`, `\times`, `\frac{...}{...}`, `\approx`.
- [ ] **Zero Mermaid Blocks:** No ````mermaid```` code blocks in the chat response.

---

## 4. Clickable File Links & Code Anchors

- Always format file paths and classes as clickable links using Markdown format: `[basename](file:///path/to/file)` or `[ClassName](file:///path/to/file#L10-L20)`.
- Use forward slashes (`/`) even on Windows paths.
- Examples:
  - Controller: [`TaskController.java`](file:///backend/task-impl/src/main/java/io/devflow/task/internal/controller/TaskController.java)
  - Event: [`DevFlowEvent.java`](file:///backend/common/src/main/java/io/devflow/common/event/DevFlowEvent.java)
  - Frontend Component: [`BoardColumn.tsx`](file:///frontend/src/components/board/BoardColumn.tsx)

---

## 5. Response Conciseness & Language Consistency

- **Language:** Respond in the language used by the user (Vietnamese if asked in Vietnamese, English if asked in English). Keep technical terms (e.g., *Modular Monolith, Event Bus, DTO, Entity, Hook, Service*) standard.
- **Conciseness:** Keep chat responses direct, actionable, and focused on the code/solution. Avoid unnecessary boilerplate pleasantries.
