# T-024 — Kết quả triển khai và kiểm chứng

**Cập nhật:** 19/11/2026, Asia/Saigon. **Nhánh:** `feature/T-024-git-activity-event-listener`; baseline phụ thuộc T-023 `506154d`; bằng chứng kiểm thử được thu trên working tree trước khi publish commit/PR. **Trạng thái:** code đã triển khai và rollout vào stack Docker local người dùng chọn, V16/V17 thành công; 825 test backend, 117 test frontend, build/lint pass. Commit/PR/merge và redelivery GitHub thật đã kiểm chứng. Trello đã hoàn thành 2 AC gốc; trạng thái review/merge được theo dõi trực tiếp trên card. Các biến thể nghiệm thu mở rộng còn mở được liệt kê rõ bên dưới. Đây không phải chứng nhận phát hành production.

Các RUN cũ bên dưới được giữ làm lịch sử. Kết quả mới nhất nằm ở RUN-FINAL-LOCAL cuối tài liệu; các ghi chú BLOCKED UI cũ đã được giải quyết cho đường chạy bình thường/task detail, UI port 3000 sau rollout đã được người dùng xác nhận bằng ảnh T-005 DONE và banner realtime xanh.

[Kế hoạch](14_tu_dong_chuyen_trang_thai_task_plan.md) · [Runbook](14_tu_dong_chuyen_trang_thai_task_operations.md).

## Thay đổi thực tế

- Ba Git record có eventId/repositoryId/workspaceId; giữ constructors cũ với metadata null. Legacy event bị Board từ chối, không tự cấp tenant/ID giả.
- GitCI enqueue snapshot workspace trong cùng transaction; outbox row ID được dùng xuyên retry/requeue/restart. Claim/publish kiểm tra repository/workspace hợp lệ.
- V17 backfill tenant, watermark task cũ, bổ sung receipt FK task/cascade và audit cause/sourceEventId. MANUAL vẫn bắt buộc actor; GIT MOVE/TASK dùng actor null với source event bắt buộc.
- Listener đồng bộ gọi service REQUIRES_NEW, khóa Board, tenant/dedup/manual/progression/category/capacity guards. TaskMoveExecutor dùng chung với manual, giữ membership/JWT/ordering/limit hiện hữu.
- Task event GIT và CARD_MOVED actor null được phát sau commit; correlation giữ qua audit/event/STOMP và MDC restore. Metrics hữu hạn theo reason/outcome, timer xử lý và chờ lock; lỗi metric không làm đổi kết quả.
- Switch rollout mặc định false, có biến Compose/.env.example và hướng dẫn pause/resume/requeue. Không bật automation trên database Compose hoặc môi trường thật của người dùng.
- Bổ sung UTF-8 cho JavaCompile để source/test tiếng Việt biên dịch ổn định trên Windows.

## Môi trường và lệnh đã chạy

Windows, JDK Temurin 17.0.19, Node 22.23.1, Docker Desktop Linux engine 29.1.3, PostgreSQL `postgres:17-alpine` (DB smoke báo 17.11). Testcontainers dùng DB tạm biệt lập; không reset dữ liệu Compose. Polling tắt trong integration tests; test gọi worker có kiểm soát, consumer bật riêng.

| Lần chạy | Kết quả quan sát | Evidence |
|---|---|---|
| Compile + boundaries | PASS, exit 0 | `:verifyModuleBoundaries :board-impl:compileJava :gitci-impl:compileJava` |
| Lần đầu test T-024/T-023 | 57 test app, 1 FAIL do assertion đọc nhầm field STOMP `toColumnId` thay vì `destinationColumnId`; đã sửa test và retest | Không phải lỗi mutation; không ghi PASS cho lần này |
| Retest T-024 + V17 | PASS, exit 0, app 33 test (30 transition + 3 migration); Board/common pass | `backend/app/build/reports/tests/test/` |
| Full `check :app:bootJar` | PASS, exit 0, 7m50s; tổng 791 test, 0 failure/error/skip | Các report module bên dưới; chạy kết thúc khoảng 19:59 Asia/Saigon |
| Frontend build | PASS, exit 0 | `npm run build`, TypeScript/Vite bundle thành công |
| Frontend lint | PASS, exit 0 | `npm run lint` |
| Frontend test | PASS, exit 0, 11 file / 117 test | `npm test` |
| UI thực trên Windows | BLOCKED trước thao tác trang local: công cụ không xác định chắc URL Brave để thực thi policy, đã dừng input | Cần người dùng mở board smoke thủ công để tiếp tục, không dùng kết quả source-read làm UI PASS |

Full backend gồm: app 324, auth-impl 178, ai-impl 130, board-impl 57, notification-impl 56, gitci-impl 37, common 9. Một số task Gradle được dùng lại từ lần chạy ngay trước khi code không đổi. Có cảnh báo Gradle API deprecated, unchecked operations của test Auth sẵn có; không có compilation failure. Log teardown có scheduler thử DB Testcontainer đã đóng; không biến các log này thành assertion failure, nhưng cũng không coi chúng là bằng chứng resilience production.

Report được Gradle ghi đè khi chạy lại:

- [App integration](../../backend/app/build/reports/tests/test/index.html)
- [Board](../../backend/board-impl/build/reports/tests/test/index.html)
- [Common](../../backend/common/build/reports/tests/test/index.html)
- [GitCI](../../backend/gitci-impl/build/reports/tests/test/index.html)
- [Notification](../../backend/notification-impl/build/reports/tests/test/index.html)
- [Auth](../../backend/auth-impl/build/reports/tests/test/index.html)
- [AI](../../backend/ai-impl/build/reports/tests/test/index.html)

## Phạm vi đã được thực thi

`GitTaskTransitionIntegrationTest` dùng HTTP HMAC thật vào app, event bus, transaction proxy, PostgreSQL/Flyway và hai STOMP session có JWT của hai thành viên khác nhau. Các test không giả lập listener hoặc database H2 cho đường chuyển trạng thái.

| Phạm vi | Actual |
|---|---|
| 12 tổ hợp chuyển trạng thái, 6 thứ tự commit/open/merge | PASS; chỉ tiến lên, cuối DONE, no-op giữ nguyên row/version |
| Move/audit/receipt/afterCommit, replay | PASS; một receipt/audit/status/frame cho lần APPLIED, replay không tạo thêm |
| Manual reopen, event replay và timestamp = watermark, event mới hơn, cùng category, reorder | PASS cho các case được test; không sửa watermark khi reorder cùng cột |
| Target missing/ambiguous/full, sửa cấu hình và retry | PASS; lỗi không ghi receipt thành công, sau sửa append đúng vị trí và capacity |
| Task missing/board archived/wrong tenant/legacy constructor/metadata thiếu/type sai | PASS; không mutation trái phép |
| Cùng eventId khác payload | PASS; EVENT_CONFLICT, không dùng lại receipt như duplicate |
| Audit failure, lock timeout, rollback và retry | PASS; task/receipt không commit nửa chừng, không dispatch khi rollback |
| Concurrent cùng và khác Git event | PASS; một receipt mỗi ID, cuối DONE |
| Exception broadcaster sau commit | PASS; DB/receipt giữ nguyên, replay không move/phát lại |
| Manual JWT/membership, MDC restore, correlation audit/domain/frame | PASS |
| HMAC push/open/merge → worker → Board → hai STOMP client → REST đọc lại | PASS; ba PUBLISHED outbox và ba receipt, hai client nhận CARD_MOVED actor null |
| Worker 8 failures do thiếu cột → FAILED → sửa/requeue cùng ID | PASS |
| Fresh Flyway/Hibernate và upgrade V16→17, constraints audit, receipt cascade | PASS cho fixture/test hiện có |
| Regression Auth/Board/GitCI/Notification/AI, frontend build/lint/test | PASS theo quality gates ở trên |

## Phần chưa được nghiệm thu

Không đánh đồng các test trên với toàn bộ từng biến thể trong §5 kế hoạch. Chưa thực thi UI thật cho task detail mở, pagination, drag pending, reconnect/mất quyền với Git frame; race manual/Git theo cả hai thứ tự khóa và lifecycle race đầy đủ; process kill tại cửa sổ afterCommit; workload/p95/p99/DB outage và rollout/rollback lệch phiên bản ở môi trường phát hành. Các case này còn NOT RUN hoặc BLOCKED UI, không ghi PASS từ suy luận code. Policy cho durable downstream giữ đúng giới hạn baseline, chưa có chấp thuận phát hành thay cho chủ dự án.

Đã tạo môi trường smoke riêng để tiếp tục:

- Frontend: `http://127.0.0.1:15124/boards/20000000-0000-4000-8000-000000000001`.
- Backend: port 18024; DB container `devflow-t024-smoke-pg`, port 15424, chỉ loopback, database `devflow_t024`.
- Hai tài khoản tổng hợp `t024-a@example.test`, `t024-b@example.test`; mật khẩu fixture `T024-demo-only!`. Chỉ dùng ở database smoke, không phải thông tin người dùng thật.
- Runtime config ở `backend/app/build/t024-smoke/application.yml`; Vite config ở `frontend/node_modules/.cache/t024/vite.config.ts`. Cả hai nằm trong output/cache git-ignored.
- Chưa gửi webhook smoke UI nên task T-001 vẫn TODO, sẵn để quan sát luồng tiếp theo. Test integration tự động dùng fixture riêng đã kiểm tra đủ ba bước.

Khi người dùng mở board, đăng nhập tài khoản A rồi báo trang hiển thị/URL hoặc lỗi gặp phải, có thể tiếp tục kiểm chứng UI ở lượt kế tiếp. Không cần gửi token, password cá nhân hoặc GitHub secret để tiếp tục smoke. Dừng server/container tạm sau khi kiểm chứng xong; không xóa volume Compose.

### Cập nhật sau ảnh người dùng

Ảnh xác nhận người dùng đã đăng nhập và mở UI smoke. Banner “Realtime chưa kết nối” đến từ fixture board UUID version/variant 0, bị `useBoardSync.BOARD_ID_PATTERN` chặn trước khi mở socket. Đã tạo lại board/column với UUID v4/variant RFC, cập nhật task ID/column trong cùng workspace và giữ T-001, tài khoản/session hiện có. Không thay validation sản phẩm. URL mới ở trên. CUA tiếp tục bị URL guard của Brave chặn khi điều hướng; đã dừng UI input. Cần người dùng mở URL mới; chưa ghi PASS cho realtime UI sau sửa fixture.


## RUN-UI-02 và vận hành smoke — 18/11/2026, khoảng 20:12–20:30

Người dùng mở lại URL đã sửa và cung cấp ảnh banner “Đồng bộ realtime đang hoạt động / Dữ liệu đã đối soát”. Sau webhook push ký HMAC (HTTP 204), screenshot Brave hiện task ở Dang code. Người dùng mở modal task theo hướng dẫn; CUA đọc được `IN_REVIEW` sau webhook opened và `DONE` sau merged ngay khi modal vẫn mở, title/description giữ nguyên. Không bấm save, không đóng/mở modal hoặc reload để nhận trạng thái. Đây là kiểm chứng UI thật cho đường bình thường và task detail, bổ sung cho hai STOMP client có JWT trong test integration.

Đọc DB từ connection riêng sau ba bước: task T-001 DONE/version 3; 3 receipt APPLIED; 3 audit MOVE/GIT; 3 outbox PUBLISHED. Gửi lại cả ba payload với delivery ID mới: vẫn DONE/version 3, đúng 3 receipt/3 audit/3 outbox. Sender và payload tổng hợp được lưu ở `backend/app/build/t024-smoke/send-webhook.cjs` và các JSON cùng thư mục, chỉ dùng secret fixture.

Đã kiểm tra các switch qua restart JAR thật trong môi trường smoke:

1. Worker=false, consumer=false: một push mới vẫn HTTP 204 và outbox PENDING/attempts 0; task không đổi.
2. Worker=true, consumer=false: event bị DISABLED, retry tới FAILED/attempts 8. Chỉ đẩy `next_attempt_at` của đúng row thử về hiện tại để rút ngắn thời gian chờ; không thay ID/payload, không giảm số attempts để giả kết quả.
3. Consumer=true sau restart, requeue đúng row FAILED: cùng eventId `8208ad12-9082-4351-b094-f2238f575ced` PUBLISHED/attempts 1, receipt NOOP vì task đã DONE. Audit GIT vẫn 3, version vẫn 3.
4. DB smoke bị pause có `finally` unpause: health trước UP, trong lỗi HTTP 503/status DOWN/db DOWN sau 3.087ms, sau phục hồi UP. Runtime smoke dùng JDBC connectTimeout=2/socketTimeout=3 và Hikari connection-timeout=2500/validation-timeout=1000; số đo này không đại diện cho timeout mặc định production.
5. Recovery qua process restart: dừng backend, dựng lại một row PROCESSING/lease expired từ snapshot đã có Board receipt rồi khởi động lại cùng JAR/schema. Đây là **mô phỏng trạng thái bền vững tại ranh giới Board đã commit/worker chưa ack**, không phải đo được crash đúng nanosecond giữa commit và afterCommit dispatch. Integration test cũng kiểm tra lease recovery và receipt còn sau outbox retention.

Các phần pagination/drag đang pending, kill chính xác trước frame, rollout lệch phiên bản ở môi trường phát hành chưa được coi là PASS từ những phép thử trên. Cập nhật này thay thế ghi chú “chưa gửi webhook UI / chưa kiểm chứng sau sửa fixture” ở lịch sử phía trên.

## RUN-EXTENDED-01 — test bổ sung

`GitTaskTransitionIntegrationTest`: 51 test, 0 fail/error, exit 0; Board unit/integration cũng pass. Đã bổ sung: fresh activity sau manual reopen cho cả ba loại event; nội dung không tạo watermark; đích chỉ tồn tại ở board khác; đủ ba vị trí nguồn, giữ assignee đã rời workspace/comment/due/priority; lỗi insert receipt; downstream subscriber exception; recovery leased event và retention; hai thứ tự khóa manual/Git; tranh slot cuối; race archive/delete task/delete board/delete target/đổi category; mất membership; batch 50 trên nhiều board.

Mẫu hiệu năng cục bộ lần này: 50 leased deliveries + 50 authenticated task reads đồng thời; batch 3.451ms, transition p95=101ms/p99=111ms, CRUD p95=39ms/p99=80ms. Lease 60 giây không hết hạn, tất cả 50 PUBLISHED/receipt/audit. Đây là workload tổng hợp nhỏ trong Testcontainers trên máy hiện tại, không phải capacity guarantee hoặc benchmark production. Một lần full `check` mới đang được chạy sau khi thêm tiếp test stale editor, flush failure, NOOP replay, fingerprint và multi-repo/multi-PR; chỉ cập nhật PASS khi có report kết thúc.


## RUN-FINAL-LOCAL — 19/11/2026, Asia/Saigon

### Quality gates cuối cùng

Full `./backend/gradlew.bat -p backend check` exit 0, 6m41s, **825 test / 0 failure / 0 error / 0 skipped**. App 356 (riêng GitTaskTransitionIntegrationTest 62), Board 59, Auth 178, AI 130, Notification 56, GitCI 37, common 9. Frontend: 117 test pass, build/lint exit 0. Frontend Docker build cũng pass sau khi thêm `.dockerignore` loại node_modules/dist/.env khỏi build context.

Các test bổ sung sau RUN-EXTENDED-01 đã pass: optimistic precondition cho editor giữ ETag cũ; lỗi flush task và retry; NOOP receipt bảo vệ sau manual reopen; fingerprint đổi task/workspace/repository/time/object/author/type; multi-task/multi-repo/multi-PR; đọc từ connection độc lập khi Git transaction bị giữ trước commit không thấy move/audit/receipt/frame. Lần full cuối đo batch 50 + 50 task reads: batch 2.046ms, transition p95=59ms/p99=63ms, CRUD p95=17ms/p99=19ms. Không dùng số đo này như capacity guarantee production.

### Rollout stack Docker hiện có

- Người dùng chọn “Stack Docker local hiện có”. Schema trước rollout V15, một board, bốn task; đủ đúng một cột mỗi category.
- Đã lưu rollback tag cho backend/frontend, dừng backend để pause ingest/worker và tạo pg_dump custom format. Đã chạy pg_restore --list kiểm tra archive; backup 81.217 bytes, nằm trong `.env.t024-rollout/backups/`, không đưa vào Git.
- Backend đóng gói đúng JAR đã qua kiểm thử, SHA256 `722d69bc1e485be2ad13087f1607a12f18ec0556e43579d606d0201bff019b4e`; image `devflow-backend:t024`. Frontend image `devflow-frontend:t024` build từ source hiện tại.
- Giữ file Compose/mount secret gốc; overlay mới chỉ chứa image và cờ public, không sao chép giá trị secret. Flyway V16/V17 và Hibernate validate pass. Bốn task cũ được backfill manual watermark; checksum các trường nội dung/cột/vị trí/version/key/timestamp không đổi.
- Sau preflight mới resume worker; backend/frontend healthy, HTTP 8080/actuator/health và 3000 đều 200. `BOARD_GIT_AUTO_TRANSITION_ENABLED=true`, `GIT_OUTBOX_WORKER_ENABLED=true` trong local overlay.
- Cấu hình local và checksum/backup/image state: `.env.t024-local-compose.yml`, `.env.t024-rollout/state.json`. Lệnh khởi động đã thực thi kiểm chứng: `.\.env.t024-rollout\start-local.ps1`.

### GitHub thật qua gateway/tunnel

Tái dùng gateway/tunnel T-022 có sẵn trên network local. Public GET / trả 404; GET webhook trả 403; POST không HMAC trả 401. Chỉ POST webhook đi tới ứng dụng, không public UI/API còn lại.

Đã cập nhật URL riêng qua webhook `/config` để giữ nguyên events/active và secret; sau đó phát hiện secret GitHub đã lệch với binding backend local. Ping ký theo binding hiện có được local/public chấp nhận, nhưng ping GitHub và hai push ban đầu bị 401. Đây là FAIL vận hành trước khắc phục, không ghi PASS cho các delivery đó.

Auto-review từ chối gửi secret ra GitHub khi chưa được cho phép rõ. Người dùng sau đó trả lời **“Cho phép đồng bộ secret”** cho đúng repo/hook; đã đồng bộ trong bộ nhớ tới GitHub hook #706317070, không in hoặc lưu secret vào artifact mới. Ping tiếp theo HTTP 200; các push/opened/closed và redelivery sau khắc phục HTTP 204. Secret/config nguồn không bị nhân bản sang overlay. Việc chỉ sửa URL qua endpoint cấu hình riêng dựa trên [GitHub webhook configuration API](https://docs.github.com/en/rest/repos/webhooks#update-a-webhook-configuration-for-a-repository).

Đã tạo board/task kiểm chứng mới, giữ nguyên bốn task gốc. Task **T-005**: commit thật → IN_PROGRESS/version 1/audit 1; PR opened → IN_REVIEW/version 2/audit 2; PR merged → DONE/version 3/audit 3. PR kiểm chứng: [repo-test-DevFlow #5](https://github.com/lvXuankg/repo-test-DevFlow/pull/5). PR chỉ chứa file fixture, đã merge để tạo sự kiện; file fixture đã xóa khỏi default branch bằng commit cleanup.

Sau merge có 5 receipt và 5 outbox PUBLISHED, gồm ba APPLIED và hai commit NOOP do push lúc merge chứa các commit liên quan. Có đúng 3 MOVE/GIT audit. Redelivery closed từ GitHub HTTP 204, vẫn DONE/version 3/audit 3/receipt 5. Checksum bốn task gốc sau toàn bộ quá trình vẫn bằng trước migration. T-005 và board kiểm chứng được giữ lại làm evidence; task key đã cấp không bị tái sử dụng.

Board đã rollout: [http://localhost:3000/boards/0ea50110-2f7e-4151-bdfc-f83046fb6554](http://localhost:3000/boards/0ea50110-2f7e-4151-bdfc-f83046fb6554).

Tài khoản local demo được tạo **theo yêu cầu người dùng xin thông tin đăng nhập**: `t024-local@example.test`, quyền MEMBER trong workspace kiểm chứng; BCrypt được tạo trước khi seed, password không ghi vào SQL plaintext/state/report. Đã login bằng Auth endpoint bình thường và GET task T-005 trả DONE; không tạo fake JWT hoặc thay mật khẩu tài khoản fixture cũ. Mật khẩu ngẫu nhiên đã gửi riêng trong chat theo yêu cầu, không ghi lại tại đây.

### Phần còn mở và giới hạn

UI thường và task detail đã được quan sát trên môi trường smoke; hai client STOMP JWT kiểm chứng bằng integration test. Người dùng đã đăng nhập bằng tài khoản MEMBER trên port 3000 và gửi ảnh xác nhận T-005 ở DONE, banner “Đồng bộ realtime đang hoạt động / Dữ liệu đã đối soát”. Chưa đánh dấu PASS cho mọi biến thể UI pagination/drag pending/duplicate frame, crash đúng giữa Board commit và dispatch frame, deadlock/FK race cleanup đầy đủ hoặc rollout/rollback lệch phiên bản. Những phần đó không được suy ra từ test xanh.

Cơ chế afterCommit downstream vẫn best effort theo scope KT-07: có thể mất tín hiệu nếu process chết sau DB commit; receipt không tự phát lại frame. Quick tunnel đổi URL sau restart; helper start-local khởi động stack/gateway/tunnel và refresh riêng URL GitHub bằng credential Git sẵn có, không đổi secret. Nếu credential không còn dùng được, helper dừng và hướng dẫn cập nhật URL theo runbook.


### Xác nhận UI cuối cùng — 19/11/2026

Ảnh người dùng cung cấp sau khi đăng nhập tài khoản MEMBER trên stack thật xác nhận: board “T-024 GitHub rollout verification”, 4 cột; TODO/IN_PROGRESS/IN_REVIEW đều 0 task; DONE có đúng T-005; banner “Đồng bộ realtime đang hoạt động” và “Dữ liệu đã đối soát”. Như vậy bước bàn giao UI sau rollout đã hoàn tất, không còn chờ thông tin đăng nhập hoặc ảnh cho đường chạy chính.

Evidence: [Ảnh UI sau rollout](evidence/T-024/rollout-local-ui.png). Ảnh được sao chép nguyên bản từ attachment người dùng, không chỉnh sửa. Các biến thể mở rộng còn PARTIAL/NR ở ma trận kế hoạch vẫn giữ nguyên trạng thái; ảnh này không được dùng để suy ra các case chưa thực thi.
