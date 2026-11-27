# AI agent setup (DevFlow)

What the repo ships, and the one-time steps each developer runs on their machine.

## What is in the repo

| Path | Purpose |
|---|---|
| `AGENTS.md` | Main guide, auto-loaded by Claude Code, Codex, Cursor and Antigravity |
| `CLAUDE.md` | Imports the rules every Claude Code session must follow |
| `.agents/skills/`, `.agents/rules/` | Shared skills and rules (source of truth) |
| `.claude/skills/` | Claude Code entry points for the shared skills, plus the OpenSpec skills |
| `.claude/settings.json` | Shared permissions, secret-file deny rules, hooks, plugin marketplaces and enabled plugins |
| `.claude/hooks/` | `guard-migrations.mjs` blocks edits to merged Flyway migrations; `post-edit-checks.mjs` runs `eslint --fix` on edited frontend files and `:verifyModuleBoundaries` after Gradle build-file edits |
| `.claude/agents/devflow-reviewer.md` | Read-only pre-PR reviewer subagent |
| `openspec/` | Spec-driven workflow: `config.yaml` (project rules), `specs/` (living specs), `changes/` (work in progress) |
| `.github/pull_request_template.md`, `.github/CODEOWNERS` | PR checklist and review routing |

## One-time setup per machine

Requirements: Node 22+, Java 17, Docker Desktop, Claude Code, `gh` logged in.

Open a terminal at the repository root, run `claude`, and paste the prompt below.

```text
Set up this machine for the DevFlow agent workflow described in docs/AGENT_SETUP.md. Do each step and report what you changed and what failed.

1. Check the tools: node --version (22 or later), java -version (17), docker info, gh auth status. Report any that are missing, but do not install system software.
2. Install the OpenSpec CLI if `openspec --version` does not print 2.2.0: npm install -g @fission-ai/openspec@2.2.0.
3. Install the project plugins that .claude/settings.json enables. Run each command; "already installed" counts as success:
   claude plugin install superpowers --marketplace anthropics/claude-plugins-official --scope project
   claude plugin install playwright --marketplace anthropics/claude-plugins-official --scope project
   claude plugin install skill-creator --marketplace anthropics/claude-plugins-official --scope project
   claude plugin install insecure-defaults --marketplace trailofbits/skills --scope project
   claude plugin install differential-review --marketplace trailofbits/skills --scope project
   claude plugin install sharp-edges --marketplace trailofbits/skills --scope project
   claude plugin install postgres-best-practices --marketplace supabase/agent-skills --scope project
   Afterwards, run `git diff .claude/settings.json`. If the installs changed anything besides key order, restore the committed version with `git checkout -- .claude/settings.json` and tell me what differed.
4. If openspec/ is missing, run: openspec init --scope project --tools claude,codex,antigravity,cursor --no-animation. If it exists, run: openspec update. Then run `git status --short` and report any generated file that is not already committed.
5. If .agents/rules/personal-user-identity.md does not exist, ask me who I am (Tien or Xuan, per AGENTS.md section 1.1). Then create the file from that section with my name, GitHub login, Trello ID and owned modules. The file is git-ignored; never commit it.
6. Install dependencies: npm ci in frontend/.
7. Verify the hooks without changing any tracked file:
   - Pipe {"tool_input":{"file_path":"backend/app/src/main/resources/db/migration/V1__init_schema.sql"}} into node .claude/hooks/guard-migrations.mjs. It must exit 2 with a "Blocked" message. Run git fetch origin first.
   - Pipe {"tool_input":{"file_path":"frontend/src/api/problemDetail.ts"}} into node .claude/hooks/post-edit-checks.mjs. It must exit 0.
8. Run openspec validate --all, and run ./gradlew :verifyModuleBoundaries in backend/.
9. Finish with a table of the steps with OK or FAILED and the reason. Tell me to restart Claude Code so the plugins, skills and hooks load.
```

After the restart, paste this second prompt. It tests the hooks end to end inside Claude Code; the first prompt only tested the scripts:

```text
Check the DevFlow agent setup after the restart:
1. List the skills you have. backend-dev, frontend-dev, trello-management, docker-local, the openspec-* skills and the superpowers, Trail of Bits and postgres-best-practices skills must all be there.
2. Write frontend/src/__hooktest.ts containing `const unused = 1` and `export {}`. The PostToolUse hook must report the ESLint no-unused-vars error back to you. Then delete the file.
3. Try to Edit backend/app/src/main/resources/db/migration/V1__init_schema.sql by appending a comment. The PreToolUse hook must block it. Do not work around the block.
4. Try to Read .env. The permission rules must deny it.
Report each check as OK or FAILED, with the exact message you got.
```

## Repository settings (owner only, once)

These change GitHub for the whole team, so they are not part of the prompt. Run them yourself after agreeing with the team:

```bash
# PRs default to the integration branch
gh repo edit N-Tien-L/DevFlow --default-branch develop

# Require green CI on develop before merging
gh api -X PUT repos/N-Tien-L/DevFlow/branches/develop/protection \
  -F required_status_checks[strict]=true \
  -f required_status_checks[contexts][]='Backend (check + module boundaries)' \
  -f required_status_checks[contexts][]='Frontend (lint + test + build)' \
  -F enforce_admins=false -F required_pull_request_reviews=null -F restrictions=null
```

## Daily workflow

See AGENTS.md section 8: card → `/openspec-propose` → review → `/openspec-apply-change` → verify → `devflow-reviewer` subagent + `differential-review` → `gh pr create --base develop` → merge → `/openspec-archive-change`.

## Known gaps (follow-up tickets)

- No formatter yet. Spotless and Prettier are deferred, because reformatting the codebase would conflict with every open branch. Introduce them in one dedicated PR when no lanes are open.
- No shared database MCP server. Use `docker compose exec db psql -U devflow devflow` (see the `docker-local` skill).
