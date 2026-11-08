# T-022 — Implementation và bằng chứng

**Ngày:** 08/11/2026, Asia/Saigon. **Nhánh:** `feature/T-022-git-commit-regex-parser`. **Baseline:** `4c5af65` cùng source T-021 `a50e3df`; full check kết thúc 23:06 ngày 08/11/2026. Demo GitHub thật hoàn tất khoảng 23:30 cùng ngày.

Code được triển khai trên checkout có T-021 chưa commit. Đã đối chiếu các source T-021 với commit `a50e3df`, giữ lịch sử baseline này trong nhánh tích hợp và đóng gói thay đổi T-022 riêng. Các artifact báo cáo cá nhân và cấu hình/credential/backup demo nằm ngoài commit. T-021 PR #32 chưa merge; T-022 chưa được peer review/merge và Trello chưa Done. [Plan](12_lien_ket_commit_va_task_plan.md) · [Vận hành và hướng dẫn cung cấp thông tin](12_huong_dan_lien_ket_commit_task.md).

## Quyết định và phần đã triển khai

- **Board:** V14 backfill ổn định theo `(created_at,id)` trong workspace, preflight dải 9999; PostgreSQL trigger cấp số atomically cho mọi đường insert; immutable key/workspace và chặn thay parent xuyên namespace. Counter tombstone không tái sử dụng số đã xóa. `taskKey` có trong create/get/list DTO và card/detail UI. Hết mã trả ProblemDetail 409 với code riêng.
- **Common:** typed synchronous request/decision lookup, exactly one complete response, immutable mapping và fail closed; bounded typed cleanup event trong transaction xóa. Kiến trúc đã cập nhật catalog.
- **GitCI:** parser stateless Unicode boundaries, canonical zero padding, source precedence, giới hạn text/keys/batch. Payload được chuẩn hóa sau HMAC; metadata tác giả không thành actor/quyền và không lưu email. Resolve batch theo binding workspace, Board share locks theo thứ tự board→task. Commit business keys và repository lock chống trùng giữa nhiều instance; immutable metadata conflict 409. PR fingerprint + provider timestamp chống stale/equal-version conflict. Association là nguồn chính, scalar mirror chỉ khi đúng một task. Nguồn LEGACY chỉ dành backfill scalar cũ; không bịa provenance MESSAGE/TITLE.
- **Webhook:** Git/link/receipt cùng transaction, ACK sau commit. Giữ behavior ping; push/PR handler đã bật, workflow handler vẫn not-ready. Transaction/lock/statement timeouts lần lượt 6/2/4 giây. Exact callback được tách khỏi bộ lọc API 64 KiB vì controller đã có raw/chunked limit, quota và in-flight guard riêng; các API khác giữ giới hạn cũ. Metrics chỉ dùng outcome/reason allowlist.
- **Cleanup:** API xóa task/board phát cleanup đồng bộ trong transaction, xóa liên kết và sửa scalar mirror; workspace deletion xóa repository/Git/link/receipt theo cascade nội bộ GitCI. Không có FK/JPA relationship tới Board từ GitCI. Không phát business Git event, tự chuyển task hay tạo notification trong T-022.

## Lệnh kiểm tra và report

Các lệnh dùng JDK toolchain 17.0.19, PostgreSQL `17-alpine` Testcontainers, Docker Desktop 29.1.3. Không dùng database Compose của người dùng cho test. Tool launcher mặc định JDK 23, Gradle tự chọn toolchain 17 cho compile/test.

```powershell
cd backend
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_DOCKER_CLIENT_STRATEGY='org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy'
.\gradlew.bat check --console=plain
cd ../frontend
npm run build
npm run lint
```

**Kết quả cuối:** `gradlew check` PASS (4m49s), gồm :verifyModuleBoundaries. 708 tests, 0 failures, 0 errors, 0 skipped theo JUnit XML hiện tại; các module không đổi dùng cache UP-TO-DATE hợp lệ. Frontend build/lint và `git diff --check` PASS.

| Module | Tests | Failures / errors / skipped |
|---|---:|---|
| app | 264 | 0 / 0 / 0 |
| board-impl | 38 | 0 / 0 / 0 |
| gitci-impl | 33 | 0 / 0 / 0 |
| common | 9 | 0 / 0 / 0 |
| auth-impl | 178 | 0 / 0 / 0 |
| ai-impl | 130 | 0 / 0 / 0 |
| notification-impl | 56 | 0 / 0 / 0 |

Report máy sinh nằm ở `backend/<module>/build/reports/tests/test/index.html` và JUnit XML ở `backend/<module>/build/test-results/test/`; chúng bị git-ignore. Các suite mới: `TaskReferenceParserTest`, `TaskReferenceContractTest`, `GitCommitLinkHttpIntegrationTest`, `TaskReferenceMigrationTest`. Hồi quy bổ sung ở `BoardPersistenceIntegrationTest`, `BoardColumnApiIntegrationTest`, `KanbanSchemaIntegrationTest` và `GitHubWebhookHttpIntegrationTest`.

Fixture đều giả: workspace A/B cùng task number; repository ID nội bộ cố định riêng cho test, remote ID 123, fork head ID 987; secret test-only. Không lưu payload thật, signature hoặc credential triển khai vào evidence.

## Expected → Actual theo nhóm của plan

Các dòng PASS sau đây mô tả đúng assertions đã chạy; PARTIAL không đồng nghĩa đủ toàn bộ tình huống gộp trong một case. Các case bổ sung về vận hành không được suy ra từ unit test.

| Case | Actual / evidence |
|---|---|
| RX01–RX14 | PASS parser convention/boundaries/Unicode/zero/normalization/order, byte/text/unique limits, near-match budget, concurrency; `TaskReferenceParserTest` |
| SP01–SP07 | PASS direct precedence, unresolved không fallback, từng commit riêng, tag/deleted/empty push ignored; parser policy + HTTP suite |
| RS01–RS06 | PASS cấp mã đồng thời qua nhiều board trong cùng workspace, A/B isolation, ổn định backfill/Flyway validate, hết dải/no reuse, missing/archived/DONE; HTTP + migration + Board API |
| RS07–RS09 | PASS zero/double/incomplete/foreign-scope response contract, outage rollback/retry qua HTTP, mixed resolved/missing/dedup; common contract + HTTP |
| RS10 | PARTIAL: archive race có lock wait thật và PG lock timeout/rollback đã đo; chưa có riêng test race task-delete ở cả hai thứ tự commit |
| RS11–RS12 | PARTIAL: JPA reload giữ key, cùng-workspace reorder/move chạy qua trigger, sửa key và cross-workspace move/parent bị reject; chưa có riêng assertion taskKey trước/sau mọi kiểu move |
| RS13 | PASS callback JWT rác không thành actor, author email bị loại, business events=0 |
| DB01–DB07 | PASS zero/one/multiple links/scalar mirror, receipt/business duplicate, concurrent deliveries cùng SHA, branch replay giữ links, field/SHA/timestamp validation, rollback sau row đầu; HTTP suite và schema suites |
| DB08–DB10 | PARTIAL: rollback/retry, redelivery sau commit và replay sau xóa receipt đã PASS; chưa inject process crash hoặc mất response thực trên network |
| DB11–DB14 | PASS title replacement atomic, stale PR no-op, equal timestamp conflict, fork lấy tenant từ base binding và mismatch 401 |
| DB15 | PARTIAL: CLOSED và MERGED đã PASS; action allowlist có implementation, chưa có riêng HTTP assertion cho mọi action lạ |
| DB16–DB17 | PASS cleanup qua API task/board, workspace/repository cascade, batch 100/101 và declared incomplete push; HTTP + Board API |
| DB18 | PARTIAL: PG lock timeout trả 503 và receipt rollback; chưa inject deadlock/DB mất mạng riêng |
| IT01–IT03 | PASS signed HTTP, persistence/resolver thật, unchanged task column/version, PR ordering/fork, signature/repo mismatch reject |
| IT04–IT06 | PARTIAL: body oversized, invalid UTF-8, duplicate/trailing/deep JSON, charset, invalid JWT/cookie/Turnstile, resolver contract đã PASS; chưa riêng chunked-overflow/legacy-route/từng tổ hợp giả response HTTP |
| IT07–IT08 | PARTIAL: message script/SQL/URL chỉ là stored data, error response không echo marker/secret; metric labels hữu hạn trong code; chưa chạy bộ scan log/metric độc lập trên mọi error path |
| IT09 | PARTIAL: 100 commits, 100 unique keys, overflow và concurrency đã PASS; chưa đo quota exhaustion/in-flight saturation bằng tải HTTP |
| IT10 | PARTIAL: fixture 100 commit có assertion <8 giây, lần targeted đo 231 ms, lần full check cuối 335 ms; đây là các mẫu local, chưa có phân bố P50/P95/max từ benchmark nhiều lượt, query profiling/heap profile; không suy ra SLA |
| IT11 | ĐÃ ĐO direct backend / Nginx / HTTPS: Nginx đóng idle ~3s, drip vẫn chờ trên 10s. Absolute upload deadline là GAP được xác nhận; xem evidence demo |
| IT12 | PASS full `gradlew check` + boundary, frontend build/lint trên mã cuối; app có cả Auth/Board/account cleanup và GitCI regression |
| IT13 | PASS ping/push/PR opened+edited/redelivery thật; Git/link đúng, task state không đổi, zero Git notifications; xem [evidence demo](12_github_demo_actual.md) |

## Lịch sử lỗi và retest

1. Lượt HTTP ban đầu có một lỗi fixture: workspace deletion chạy cleanup Board after-commit nên task dùng cho assertion cuối không còn tồn tại. Sửa assertion để kiểm đúng cleanup toàn workspace; các assertions nghiệp vụ vẫn giữ.
2. Full check đầu chạy 259 app tests, có ba lỗi schema Board: trigger parent lookup trả sai SQLSTATE cho null/missing column, và fixture reorder cũ di chuyển task xuyên workspace. Trigger giữ lại 23502/23503; fixture reorder dùng task cùng workspace. Cross-workspace rejection có test riêng. Nhóm schema/Board retest đạt.
3. HTTP boundary test phát hiện message 65537 byte trả 413 từ global 64 KiB filter trước semantic handler. Sửa exact-route exemption để dùng guard webhook riêng; thêm positive message đúng 65536 byte + mã ở cuối. Nhóm HTTP/Board retest đạt, bao gồm error code hết namespace và cleanup qua REST.

Các sửa lỗi compile/fixture trong lúc viết test không được tính là nghiệm thu. Full check cuối là gate tổng hợp, không lấy số liệu từ lượt fail làm kết quả cuối.

## Việc cần người dùng và bàn giao

- Trello đã khôi phục sau khi người dùng cập nhật credential cục bộ. Đã xác minh board **Nhóm 25** có ID `6aa2a6d90ff05c484f131687` và short link `CKoJR2cC` (cùng board từng mang tên development); đọc lại T-022, chuyển **In Progress**, đánh dấu **2/2 AC gốc**, thêm comment bằng chứng và đọc lại để xác nhận. Chưa Done. Dùng credential từ `.env.trello` cho riêng tiến trình CLI, không in hoặc đưa credential vào Git.
- Đã hoàn tất demo GitHub thật bằng quyền Git Credential Manager hiện có; callback mới, secret mới, backend/frontend local và V14/V15 đã cập nhật. [PR demo](https://github.com/lvXuankg/repo-test-DevFlow/pull/1) và [evidence chi tiết](12_github_demo_actual.md). Callback cũ do người dùng cung cấp đã hết DNS. Không cần cung cấp thêm credential cho demo.
- Review/merge `develop` còn chờ baseline [T-021 PR #32](https://github.com/N-Tien-L/DevFlow/pull/32), hiện OPEN. Đã đối chiếu blob source T-021 trong checkout với commit `a50e3df`: khớp hoàn toàn ngoài ba file T-022 đã sửa (service, HTTP test, Gradle test dependency). Các artifact báo cáo cá nhân không thuộc T-022 phải giữ ngoài commit. Plan chưa Done khi chưa peer review/merge; absolute upload deadline là gap hardening đã đo.
