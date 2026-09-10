# Git Workflow & Commit Guidelines — DevFlow

This rule defines the Git branching strategy, commit message standards, and pull request workflow that AI agents and team members must follow.

---

## 1. Branch Strategy

The repository follows a simplified GitFlow model:

- **`main`**: Production-ready code. Releases and stable deployment tags only. Direct commits to `main` are restricted.
- **`develop`**: Integration branch for active sprint development. All feature and bugfix branches branch off from and merge back into `develop`.
- **Feature Branches**: Short-lived branches created for specific tickets or user stories.

### Branch Naming Conventions
Always include the Trello ticket ID if working on a tracked task:

| Type | Pattern | Example |
|---|---|---|
| **Feature** | `feature/T-<id>-<short-description>` | `feature/T-010-board-crud-endpoints` |
| **Bugfix** | `fix/T-<id>-<short-description>` | `fix/T-012-cors-websocket-origin` |
| **Chore / Setup** | `chore/<short-description>` | `chore/update-gradle-dependencies` |
| **Documentation** | `docs/<short-description>` | `docs/update-architecture-event-catalog` |

---

## 2. Commit Message Standards (Conventional Commits)

Commit messages must adhere strictly to the [Conventional Commits](https://www.conventionalcommits.org/) specification.

### 2.1 Format
```
<type>(<scope>): <short imperative description>

[optional body explaining motivation, context, or breaking changes]

[optional footer referencing tickets: Refs #T-XXX or Closes #T-XXX]
```

### 2.2 Allowed Types
- `feat`: A new feature or capability
- `fix`: A bug fix
- `docs`: Documentation changes only
- `style`: Code style, formatting, missing semi-colons (no code logic changes)
- `refactor`: Code refactoring that neither fixes a bug nor adds a feature
- `perf`: Performance improvement
- `test`: Adding or correcting tests
- `build`: Changes that affect build system or external dependencies (Gradle, npm)
- `ci`: Changes to CI configuration files and scripts
- `chore`: Maintenance tasks, repo tooling, agent config

### 2.3 Allowed Scopes
- Backend modules: `board`, `auth`, `gitci`, `ai`, `notification`, `common`, `app`
- Frontend: `frontend`, `ui`, `routing`, `components`
- Infrastructure: `docker`, `infra`, `postgres`, `prometheus`, `grafana`
- Meta: `agent`, `trello`, `docs`

### 2.4 Examples
- `feat(board): add REST endpoint for creating columns`
- `fix(gitci): handle null commit author email in GitHub webhook`
- `feat(ai): integrate OpenAI client for risk summary generation`
- `docs: update AGENTS.md with new skill reference`
- `chore(deps): bump vite to 6.3.5 in frontend`

---

## 3. Pull Request Guidelines

### 3.1 PR Title Format
```
[T-XXX] <Concise imperative summary>
```
*Example:* `[T-010] Implement Board and Column REST API endpoints`

### 3.2 PR Description Template
When creating or proposing a PR description, include:

```markdown
## Summary of Changes
- Implemented `BoardController` and `BoardService` in `board-impl`.
- Added `TaskCreatedEvent` publisher upon task assignment.
- Verified module boundaries and passed all unit tests.

## Related Ticket
- Trello Ticket: [T-010](https://trello.com/c/...)

## Verification Steps
- [x] `./gradlew check` passes (including `:verifyModuleBoundaries`).
- [x] `npm run build` passes with zero TypeScript errors.
- [x] Tested endpoint with curl / Postman.
```

### 3.3 Merge Strategy
- **Target Branch:** `develop` for features/fixes; `main` for release milestones.
- **Merge Type:** Use **Squash and Merge** into `develop` to preserve clean, linear history.
