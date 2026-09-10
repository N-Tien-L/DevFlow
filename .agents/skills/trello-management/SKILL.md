---
name: trello-management
description: >-
  Use this skill to read, search, create, update, and manage Trello boards, lists, cards, checklists, and comments for the DevFlow project.
---

# Trello Management Skill

This skill allows the agent to interact directly with Trello boards via the official Trello REST API using the CLI tool at `scripts/trello.js`.

## 🔐 Credentials & Security

API credentials are saved securely in:
- Project root: `.env.trello`
- Skill directory: `.agents/skills/trello-management/.credentials.json`

Both files are explicitly excluded in `.gitignore` (`.env.*`, `*.credentials.json`) to prevent accidental leaks or commits to GitHub.

---

## 📌 Project Board Reference (DevFlow)

- **Default Workspace:** `DevFlow` (ID: `6aa2a6a6a6f7eedd30886b3b`)
- **Main Development Board:** `development` (ID: `6aa2a6d90ff05c484f131687`, URL: https://trello.com/b/CKoJR2cC/development)
- **Backup/Course Board:** `QLDAPM` (ID: `6aa267a425df7c6929264c9f`, URL: https://trello.com/b/SEChN9sP/qldapm)

### Columns in `development` Board:
| Column Name | List ID | Purpose |
|---|---|---|
| `Info` | `6aa2a6ecefc8fea394cfde8a` | Project overview, specs, quick links, DoD (Read-only) |
| `Backlog` | `6aa2a6f3ec35f4c26d96df17` | Future tasks & unplanned user stories |
| `To Do` | `6aa2a6f88929c8c5e64b0cc6` | Sprint backlog, prioritized tasks ready for work |
| `In Progress` | `6aa2a708b202fdf02e8858de` | Tasks currently being coded |
| `In Review` | `6aa2ad215698374992fc6654` | Tasks with open PRs pending peer code review & approval |
| `Done` | `6aa2a701c3f781dbc0abc59c` | Merged and completed tasks |

---

## 🛠️ CLI Tool Usage (`scripts/trello.js`)

All commands are run using Node.js:

```bash
node .agents/skills/trello-management/scripts/trello.js <command> [options]
```

### 1. Inspecting Boards & Lists
```bash
# List all accessible boards
node .agents/skills/trello-management/scripts/trello.js list-boards

# List all columns/lists on the default board
node .agents/skills/trello-management/scripts/trello.js list-lists

# List all cards in a specific column
node .agents/skills/trello-management/scripts/trello.js list-cards <listId>
```

### 2. Reading Card Details
```bash
# View card title, full description, checklists, and status
node .agents/skills/trello-management/scripts/trello.js get-card <cardId>
```

### 3. Creating Tasks / Cards
```bash
# Create a new card in a list (e.g. Backlog or To Do)
node .agents/skills/trello-management/scripts/trello.js create-card <listId> --name "T-011: Implement JWT Refresh Token" --desc "Details here" --color "sky"

# Supported colors: sky, blue, purple, green, yellow, lime, orange, pink, red, black
```

### 4. Moving & Updating Cards
```bash
# Move card to another column (e.g. from To Do to In Progress)
node .agents/skills/trello-management/scripts/trello.js move-card <cardId> <targetListId>

# Update card title or description
node .agents/skills/trello-management/scripts/trello.js update-card <cardId> --name "New Title" --desc "New description"

# Archive / close card
node .agents/skills/trello-management/scripts/trello.js update-card <cardId> --closed
```

### 5. Managing Checklists
```bash
# Add a checklist to a card
node .agents/skills/trello-management/scripts/trello.js add-checklist <cardId> --name "Acceptance Criteria"

# Add an item to a checklist
node .agents/skills/trello-management/scripts/trello.js add-checkitem <checklistId> --name "Unit tests passed"

# Check off an item (mark complete)
node .agents/skills/trello-management/scripts/trello.js check-item <cardId> <checkItemId> --state complete
```

### 6. Adding Comments & Notes
```bash
# Add a progress comment or link a PR
node .agents/skills/trello-management/scripts/trello.js add-comment <cardId> --text "PR #12 merged to develop: https://github.com/..."
```

### 7. Searching Cards
```bash
# Search for cards by keyword across the board
node .agents/skills/trello-management/scripts/trello.js search "DnD"
```

---

## 🔄 Common Agent Workflows

1. **When breaking down a feature into tickets:**
   - Query `list-lists` to find the `Backlog` (`6aa2a6f3ec35f4c26d96df17`) or `To Do` (`6aa2a6f88929c8c5e64b0cc6`) column.
   - Run `create-card` for each subtask with clear acceptance criteria in the description.
   - Add a checklist using `add-checklist` and `add-checkitem`.

2. **When user starts working on a ticket:**
   - Find the ticket ID using `search` or `list-cards`.
   - Call `move-card <cardId> 6aa2a708b202fdf02e8858de` to transition it to `In Progress`.

3. **When code is ready and PR is opened:**
   - Call `move-card <cardId> 6aa2ad215698374992fc6654` to transition it to `In Review`.
   - Add a comment with PR URL using `add-comment`.

4. **When PR is approved & merged:**
   - Verify all DoD criteria.
   - Call `check-item` for the checklist items.
   - Call `move-card <cardId> 6aa2a701c3f781dbc0abc59c` to transition it to `Done`.
