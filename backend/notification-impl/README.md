# Notification delivery (T-040A)

The six domain event consumers notify the task's current assignee, resolved through
`BoardApi.findTask`. The recipient must still be a member of the task's workspace
according to `AuthApi`. Unassigned/deleted tasks, unresolved CI tasks, and sprint-only
risk events are skipped because the current public APIs cannot resolve their recipients.
No module's internal classes or tables are read directly.

Task events carry an actor and event ID. The actor is excluded. Legacy events without
an actor use the authenticated principal when one is available; otherwise they are
treated as system events. Git PR, CI summary, and risk event contracts do not currently
carry an actor or event ID. These events receive a deterministic UUID derived from
their type and serialized payload, including `occurredAt`. An exact replay deduplicates;
reconstructing an event with a new timestamp is a new notification. A Git author's
DevFlow identity cannot be excluded unless the producer supplies that identity.

Consumers run after commit, with fallback execution for producers that already emit
after commit or operate without a transaction. Each insert commits in a new transaction.
PostgreSQL `ON CONFLICT (event_id, recipient_id) DO NOTHING` prevents concurrent replay
duplicates without aborting the transaction. Only a newly committed row is pushed and
counted in `devflow.notification.delivered{type}`. A realtime failure leaves the row
available over REST. Consumer failures are contained and logs omit payloads and identities.
The existing in-process event bus has no durable outbox or retry across process crashes.

## Client contract

All HTTP routes require authentication and derive identity from the principal.
The shared per-user limit defaults to 120 requests/minute. A rejected request returns
429 ProblemDetail with `Retry-After`. No request accepts a recipient ID.

| Method and route | Response |
| --- | --- |
| `GET /api/v1/notifications?page=0&size=20` | `{content: NotificationResponse[], page, size, hasNext}` |
| `GET /api/v1/notifications/unread-count` | `{count: number}` |
| `PATCH /api/v1/notifications/{id}/read` | Updated `NotificationResponse`; repeat calls preserve `readAt` |
| `POST /api/v1/notifications/read-all` | `{updated: number}` |

`page` is 0–10000 and `size` is 1–100. Ordering is `createdAt DESC, id DESC`.
Missing and foreign notifications both return 404. Mark-all only updates the caller's rows.

`NotificationResponse` contains `id`, `eventId`, `workspaceId`, `type`, `title`,
`content`, `referenceId` (the task ID), `isRead`, `createdAt`, and `readAt`.
Titles and content are plain text; clients must render them as text, never HTML.
Event notifications use generic text and deep-link to the task instead of copying
potentially sensitive task descriptions, CI logs, or risk reasons into notifications.

Subscribe to `/user/queue/notifications` after STOMP authentication. Delivery uses
`convertAndSendToUser(recipientId, "/queue/notifications", response)`. Explicit other-user
destinations, public notification topics, raw `/queue` subscriptions (including wildcards),
and client SENDs to notification destinations are rejected. The shared broker change
only enables `/queue` alongside `/topic`. Raw broker queue access is denied to prevent
guessed physical session queues from bypassing Spring's user destination resolver.

The pre-existing Java `NotificationApi.sendToUser` remains available for trusted internal
system delivery. Such rows have no workspace/task reference and use a fresh event ID.

## Retention and migration

`V11__notification_schema.sql` is a temporary version reserved by the master; reconcile
it with the other lanes before merging. Recipient FK deletion cascades from `users`.
No cross-module JPA relationships are introduced. Idempotency lasts for the retained
notification rows; replaying a deleted old event can create a new notification.

| Configuration property | Default |
| --- | --- |
| `devflow.notification.retention-days` | `90` |
| `devflow.notification.cleanup-cron` | `0 0 3 * * *` (UTC) |
| `devflow.notification.cleanup-batch-size` | `1000` |
| `devflow.notification.cleanup-max-batches` | `10` per run |
| `devflow.notification.rate-limit-per-minute` | `120` |

Cleanup commits each batch and bounds the number of batches per run. Rate limiting
uses a bounded in-memory bucket map, matching the single-instance deployment.

## Verification

`./gradlew :notification-impl:test` covers all six event types, actor exclusion,
commit/rollback and the Board after-commit publisher, stable replay IDs, API pagination,
unread counts, recipient isolation, idempotent read mutations, rate limiting, retention,
and private STOMP routing. PostgreSQL Testcontainers tests cover the actual migration,
atomic concurrent replay, commit-before-push, realtime failure, and recipient FK cascade.
The PostgreSQL tests skip only when Docker is unavailable; CI provides Docker.
