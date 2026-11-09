# T-023 — Báo cáo triển khai và kiểm thử

**Ngày:** 09/11/2026, Asia/Saigon. **Nhánh:** `feature/T-023-git-domain-events`. **Baseline:** `191d6a5`. Báo cáo nghiệm thu triển khai T-023 trên nhánh feature, chờ peer review/merge. Phụ thuộc [T-021 #32](https://github.com/N-Tien-L/DevFlow/pull/32) và [T-022 #33](https://github.com/N-Tien-L/DevFlow/pull/33). [Kế hoạch](13_phat_su_kien_git_plan.md) · [Vận hành](13_phat_su_kien_git_operations.md).

## Kết quả triển khai

- Flyway V16 thêm `git_domain_event_outbox`: unique logical event, repository FK cascade, task soft reference, immutable payload snapshot theo luồng ghi ứng dụng, trạng thái/attempt/lease/token, index claim/cleanup. Không backfill dữ liệu cũ.
- Ingestion chỉ tạo commit event khi commit insert và association insert thành công. Commit/task association và outbox dùng JDBC batch; tránh dùng UUID chưa persist khi conflict. PR giữ action, thời gian provider và trạng thái trước snapshot. Chỉ mở lần tạo row/action opened; chỉ merge khi chưa MERGED. Chặn quay lui từ MERGED, không phát cho edited/synchronize/reopened/closed chưa merge hoặc stale/replay.
- Repo và PR URL dựng từ repository cấu hình đã validate, fork dùng base binding. Commit author chỉ nhận username hợp lệ, không dùng sender/email. PR `actorId=null` khi chưa có mapping GitHub đã xác minh. Timestamp chuyên biệt thiếu/null dùng updated_at, sai kiểu/định dạng trả 400, giữ constructor event cũ.
- Worker claim 50 row bằng SKIP LOCKED, transaction riêng ngắn, lease 60 giây/token. Publish typed event ngoài transaction ingest/claim; retry có jitter/backoff, tối đa 8 lần, FAILED và requeue theo UUID giữ snapshot. Cleanup PUBLISHED quá 7 ngày theo batch 1.000; task/board deletion qua typed cleanup, repository/workspace cascade.
- Metric enqueue chỉ tăng sau commit; counter/timer/gauge hữu hạn tag, đo backlog/tuổi/retry/failed. Log Board không ghi SHA/repo URL/PR URL. Pause switch không tắt enqueue. Kiến trúc EN/VI và cấu hình Compose/env đã cập nhật.

Delivery là **at-least-once**, không bảo đảm FIFO. PUBLISHED không khẳng định consumer tự bắt lỗi đã hoàn tất side effect; theo dõi metric Notification riêng. T-024 vẫn chịu trách nhiệm chuyển cột trong transaction Board, idempotent và không lùi trạng thái. Thiếu mapping danh tính vẫn chưa loại được self-notification của Git actor không xác định.

## Quality gates

JDK toolchain **17.0.19**, Gradle 8.14, Docker Desktop 29.1.3, PostgreSQL 17-alpine Testcontainers. Test HTTP dùng Spring Boot RANDOM_PORT + Flyway thật, worker nền tắt để điều khiển từng lượt; PostgreSQL thật, không dùng DB Compose đang làm việc. Staging riêng có hai backend process và một PostgreSQL riêng.

`backend/gradlew.bat :verifyModuleBoundaries check --console=plain` **PASS**, lần cuối kết thúc khoảng 17:16 ngày 09/11/2026. Một số module không đổi dùng Gradle UP-TO-DATE với JUnit XML hiện có; app chạy lại đầy đủ 293 tests, gitci-impl đã chạy lại sau thay đổi production. Tổng XML hiện tại:

| Module | Tests | Failures / errors / skipped |
|---|---:|---|
| common | 9 | 0 / 0 / 0 |
| gitci-impl | 37 | 0 / 0 / 0 |
| app | 293 | 0 / 0 / 0 |
| board-impl | 38 | 0 / 0 / 0 |
| auth-impl | 178 | 0 / 0 / 0 |
| ai-impl | 130 | 0 / 0 / 0 |
| notification-impl | 56 | 0 / 0 / 0 |
| **Tổng** | **741** | **0 / 0 / 0** |

Frontend `npm run build` và `npm run lint`: **PASS**. Backend bootJar: PASS. `git diff --check`: PASS.

Evidence máy sinh: `backend/<module>/build/test-results/test/TEST-*.xml`, `backend/<module>/build/reports/tests/test/index.html` (git-ignored). Suite mới: `GitDomainEventSelectionTest` **4**, `GitDomainEventHttpIntegrationTest` **27**, `GitDomainEventMigrationTest` **2**. Hồi quy gồm `GitCommitLinkHttpIntegrationTest` 15 và `GitHubWebhookHttpIntegrationTest` cùng toàn bộ Board/Auth/Notification. Không tính báo cáo baseline là kiểm thử T-023 mới.

Ca tối đa **100 commit × 20 task = 2.000 outbox**: lần cuối **509 ms** qua HTTP, vẫn giữ transaction/lock/statement timeout 6s/2s/4s. Đây là một mẫu local, không suy ra P95 hay SLA GitHub staging.

## Bằng chứng staging tách biệt

Image `devflow-backend:t023-staging` chứa JAR build từ working tree. Container riêng `devflow-t023-db`, `devflow-t023-backend`, `devflow-t023-backend-2`, network `devflow-t023`. Backend chỉ bind host loopback 18080/18081; workspace, user, hai task và Git rows đều là fixture tổng hợp trong DB riêng. Cấu hình/secret/state/runner nằm ở `.env.t023-*` bị git-ignore, không đưa vào report/Git. Không nâng cấp hay dùng DB làm việc của Compose.

| Thao tác | Actual |
|---|---|
| Worker tắt, push hai link và opened một link bằng HTTP ký HMAC | HTTP 204; 2 commit PENDING + 1 opened PENDING; 0 Notification |
| Restart backend, vẫn tắt worker | Cùng 3 PENDING còn nguyên; 0 Notification; không gửi webhook mới |
| Bật worker, restart và chạy hai replica | 3 PUBLISHED; 1 Notification opened; không còn backlog |
| Sửa PR từ A sang B rồi merge; PR khác mở rồi đóng không merge | Tổng 5 PUBLISHED (2 commit, 2 opened, 1 merged); 3 Notification; PR23 MERGED, PR24 CLOSED |
| Replay push/opened cũ và merge snapshot mới hơn | Counts/payload nghiệp vụ giữ nguyên: 5 PUBLISHED, 3 Notification |
| Hai process đồng thời nhận cùng SHA và cùng PR merge; body/delivery khác nhau | Cả 4 HTTP 204. Chỉ thêm 1 commit event và 1 merged event: tổng **7 PUBLISHED**, **4 Notification**, 0 pending/processing/failed và 0 receipt chưa COMPLETED |
| Scan log hai backend | Không có sentinel email, nội dung commit hay repository URL của fixture; task column/version vẫn nguyên |

Restart ở đây là restart container/process thật, dùng cùng DB bền trong container PostgreSQL riêng. Claim/lease expiration và crash sau publish được fault-inject trong integration suite; không gọi các mô phỏng đó là crash JVM thật. Hai process staging xác minh repository lock và logical dedup dùng chung DB; race fencing có test JDBC riêng.

Evidence staging đã ẩn dữ liệu: [JSON tổng hợp kèm SHA-256 JAR](13_phat_su_kien_git_staging_actual.json).

## Phạm vi Expected → Actual

PASS chỉ áp dụng assertions đã chạy. PARTIAL ghi rõ biến thể chưa có bằng chứng riêng, không coi là hoàn thành toàn nhóm case. Bảng Actual trong plan trỏ đến các suite và các giới hạn dưới đây.

| Case | Evidence / giới hạn |
|---|---|
| EVT-01–03 | PASS HTTP commit payload/multi-link/canonical key/branch fallback/unresolved không fallback; precedence fixture hồi quy T-022 |
| EVT-04 | PASS username thiếu/sai control/hợp lệ; sender khác author không thay tác giả; email vắng trong payload/log |
| EVT-05–09 | PASS fork/base tenant/canonical URL/provider timestamps, mở/sửa/merge, close/reopen/sync, zero-link rồi merge, timestamp null/missing/invalid |
| EVT-10 | PASS canonical round-trip, constructor commit/PR cũ, actor null và stable replay |
| REP-01–05 | PASS replay, concurrent SHA, immutable metadata conflict, delivery conflict và PR version conflict; kết hợp HTTP mới và hồi quy T-021/T-022 |
| REP-06 | PASS merge-first rồi opened cũ/mới: chỉ một merge event, không quay lui MERGED |
| REP-07 | PASS merge snapshot mới hơn và replay sau dọn receipt/outbox không phát thêm; PR vẫn MERGED |
| REP-08 | PASS trigger fail tại association đầu tiên, outbox insert và receipt completion; rollback toàn bộ rồi redelivery phục hồi |
| REP-09 | PASS zero/double resolver response không enqueue, HTTP repo-lock timeout 503 không receipt/outbox, retry phục hồi; outage regression |
| REP-10 | PASS V15 upgrade giữ legacy/outbox rỗng; replay/purge không phát bù; OPEN merge; PR version NULL trả 409 không event mới |
| REP-11–12 | PASS late opened không phát bù; concurrent merge qua hai process staging chỉ tạo một logical event/task |
| SEC-01–02 | PASS HMAC sai/rỗng/tamper, binding/base mismatch, selector invalid/duplicate, malformed/type/number, media 415/body 413/quota 429; no outbox và response không leak |
| SEC-03 | PASS fixture hai tenant cùng key, archived/deleted/missing bị loại; business event đúng task active |
| SEC-04 | PASS tag/delete/empty push, unsupported PR, ping, workflow_run không tạo Git business event |
| SEC-05 | PASS 2.000 pairs và giới hạn 101 commit/21 key; 100 distinct keys/declared incomplete chạy trong suite hồi quy, không ghi một phần |
| SEC-06 | PASS task/board/workspace/repository cleanup khi claimed, rollback giữ row, claim đã xóa không phát; consumer vẫn cần xử lý cửa sổ race sau pre-publish check |
| RUN-01–06 | PASS transaction isolation qua HTTP, restart process staging, lease/token/concurrent claim, retry/exhaustion/requeue, crash-after-publish Notification dedup, ba typed event/recipient membership; lease/crash được mô phỏng bằng DB trigger/state có kiểm soát |
| RUN-07 | PASS listener latch không kéo dài ACK kế tiếp; backlog metric và sentinel log/tag scan |
| RUN-08 | PARTIAL: retention chỉ PUBLISHED, 1.000/batch và snapshot times ổn định PASS; chưa có test downstream status policy đảo thứ tự, thuộc T-024 |
| REL-01–02 | PASS clean V16/upgrade V15/validate/legacy integrity; full check/boundary/frontend build/lint |
| REL-03 | PASS: ba typed event GitHub thật, opened(A)/merged(B) qua PR #4; closed chưa merge PR #3 và push/opened/merged replay 204, Notification không trùng |
| REL-04 | PASS: delivery GitHub thật tạo 3 PENDING khi worker tắt; bật/restart hai replica chuyển PUBLISHED, không mất backlog |

## Lịch sử lỗi và retest

1. Truy vấn fixture Notification ban đầu dùng `task_id`, schema thật dùng `reference_id`; sửa fixture. Retest crash/replay với Notification thật PASS, một notification cho cùng payload.
2. Lượt chạy cùng frontend build có batch 2.000 pairs chạm transaction timeout 6s và trả 503. Chuyển ghi association từ 2.000 lệnh round-trip sang JDBC batch, vẫn chỉ enqueue association đã insert. Lượt full check cuối đo 509ms.
3. Full check đầu có 1 lỗi ở ca receipt rollback: kỳ vọng 503 nhưng nhận 429 vì 19 test dùng chung quota repository 60 token trong cùng context. Fixture nay reset bucket riêng cho từng scenario, không thay đổi quota production. Full check sau đó PASS 741 tổng XML.
4. Các log scheduler cleanup của suite nền có thể báo datasource đã đóng khi Testcontainers kết thúc; JUnit cuối không failure/error/skip. Không coi các dòng shutdown đó là bằng chứng delivery production thất bại.

Trello đã xác nhận hai AC gốc 2/2; bàn giao In Review khi PR triển khai được mở. AC-01–04 của plan có bằng chứng triển khai, AC-05 đã đủ gate kỹ thuật; peer review/merge code vẫn theo workflow dự án. Các assertion bổ sung đã chạy trong quality gate cuối; phần downstream status ordering ở RUN-08 được bàn giao T-024 đúng phạm vi.

## GitHub thật — đã hoàn tất nghiệm thu

Người dùng đã cho phép demo GitHub T-023 và đối chiếu UI bằng ảnh, xác nhận closed lượt đầu thuộc PR #3 không merge. Agent đã chạy lại ca merge bằng [PR #4](https://github.com/lvXuankg/repo-test-DevFlow/pull/4) trên cùng base demo riêng: opened(A), edited sang B, merged(B), provider time giữ nguyên. Delivery closed/merged=true HTTP 204 (0,37s), merged redelivery HTTP 204 (0,36s) không thêm event/Notification trùng. REL-03 và REL-04 PASS. [Báo cáo GitHub](13_github_demo_actual.md) · [JSON evidence](13_phat_su_kien_git_github_actual.json).

Tổng cuối 17 PUBLISHED/8 Notification, trong đó 10 event/4 Notification mới từ các delivery GitHub thật; phần còn lại là fixture tổng hợp đã nghiệm thu trước đó. Không backlog/FAILED/incomplete receipt, task column/version giữ nguyên. Callback lỗi URL cũ sau restart đã được sửa và opened #4 redelivery 204; chi tiết lỗi/retest trong báo cáo GitHub. Delivery merged #2 vẫn không thấy tại lần đối chiếu, là quan sát lịch sử được giữ lại; không tự vá DB hoặc gọi fixture là webhook thật.

Webhook đã được khôi phục URL/active/events ban đầu và đọc lại xác minh. Các container staging/tunnel đã dừng giữ dữ liệu; stack Compose làm việc không đổi. Không merge nhánh default hoặc PR triển khai DevFlow. Không cần người dùng tìm thêm delivery hay cấp lại quyền/credential cho demo này. Code được đóng gói trên feature branch để mở PR vào develop; chờ peer review/merge theo workflow dự án. Bằng chứng runtime được thu trước commit đóng gói, production source không thay đổi sau quality gate.
