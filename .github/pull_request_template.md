<!-- Title: [T-XXX] Concise imperative summary. Base branch: develop. -->

## Summary
-

## Related
- Trello: [T-XXX](https://trello.com/c/...)
- OpenSpec change: `openspec/changes/t-xxx-.../` (or "n/a: chore/docs/one-line fix")

## Production quality checklist (AGENTS.md section 4)
- [ ] Input validation on every new request DTO, server-side
- [ ] Errors are RFC 7807 ProblemDetail, no internal details leaked
- [ ] AuthZ/IDOR checks and rate limiting on new endpoints (Turnstile on public forms)
- [ ] Structured logs with correlation ID, no tokens/PII
- [ ] Metrics/health for new critical paths
- [ ] Timeouts, retries, circuit breaker on new external calls
- [ ] DB constraints mirror business rules; migrations append-only
- [ ] No `impl -> impl` dependency; cross-module data only via `DevFlowEvent`

## Verification
- [ ] `./gradlew check` (backend)
- [ ] `npm run lint && npm test && npm run build` (frontend)
- [ ] `openspec validate`
- [ ] Manual / browser check:
