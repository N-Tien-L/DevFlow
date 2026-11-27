---
name: frontend-dev
description: DevFlow frontend rules and commands (React 19, TypeScript 5.8, Vite 6, Tailwind 4, React Router 7, zustand, STOMP realtime, vitest). Use before adding or changing anything under frontend/ - pages, components, API clients, stores, realtime sync, tests - or when running npm scripts. Covers the project's folder layout, ProblemDetail error handling, auth and Turnstile, realtime patterns and the fast test commands. For visual design and UI flows, the frontend-delivery skill leads; this skill holds the DevFlow-specific code rules.
---

# DevFlow frontend

## Commands (run in `frontend/`)

| Goal | Command |
|---|---|
| Dev server (proxies `/api` and WebSocket to :8080) | `npm run dev` (port 5173) |
| One test file | `npx vitest run src/api/problemDetail.test.ts` |
| All tests | `npm test` |
| Lint (also runs per edited file through a hook) | `npm run lint` |
| Type-check and build (the CI gate) | `npm run build` |

CI runs `npm ci && npm run lint && npm test && npm run build`. Run all four before a PR.

## Layout (actual)

```
src/
├── api/         # one client per backend module: authApi, boardApi, notificationApi, plus problemDetail.ts
├── auth/        # AuthProvider, session/return-to logic, validation, token-in-URL handling
├── board/       # board mutations and the sync coordinator
├── realtime/    # STOMP message types (boardMutation.ts)
├── hooks/       # useBoardSync, useNotificationSync, useTurnstile, useCountdown
├── stores/      # zustand stores (notificationStore)
├── components/  # auth/, board/, common/, layout/, notification/, ChatPanel
└── pages/       # route-level screens
```

Tests sit next to the file: `foo.ts` gets `foo.test.ts` (vitest + jsdom).

## Rules

- **API calls**: relative `/api/v1/...` only. Parse responses with `readJsonResponse` and errors with `parseProblemDetail` from `api/problemDetail.ts`. Branch on `problemCode(...)` and `fieldErrors(...)`, never on message text. Honour `Retry-After` through `retryAfterSeconds`.
- **Cancellation**: API functions take an `AbortSignal`. Effects abort on unmount, and dropdown or polling fetches must not loop (see commit 13e068d).
- **Auth**: session state comes from `AuthProvider`. Clear per-user stores on logout or user switch, as `notificationStore` does. Public forms (register, login, resend, forgot password) need Turnstile through `useTurnstile`.
- **Realtime**: board and notification updates arrive over STOMP (`@stomp/stompjs`) through `useBoardSync` / `useNotificationSync`. Apply server events idempotently, because optimistic updates and server echoes both arrive.
- **State**: use zustand for cross-component state and local `useState` otherwise. No new state library.
- **Styling**: Tailwind 4 utilities and `clsx`. Keep the existing component look; new screens follow the frontend-delivery workflow.
- **Types**: mirror backend DTO records exactly, including nullability. No `any`.

## Done means

AGENTS.md Section 4 checklist passes; lint, tests and build are green. UI changes are checked in a real browser (Playwright plugin) at desktop and mobile widths.
