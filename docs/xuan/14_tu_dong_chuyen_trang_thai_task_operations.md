# T-024 — Vận hành tự động chuyển cột task

Áp dụng V18 và code producer/consumer T-024 cùng nhau. [Kế hoạch](14_tu_dong_chuyen_trang_thai_task_plan.md) · [Kết quả kiểm chứng](14_tu_dong_chuyen_trang_thai_task_results.md) · [Git outbox T-023](13_phat_su_kien_git_operations.md).

## Hành vi đã triển khai

Commit linked đưa TODO → IN_PROGRESS; PR opened đưa TODO/IN_PROGRESS → IN_REVIEW; PR merged đưa mọi nhóm trước DONE → DONE. Không lùi tiến độ, không chờ CI hoặc tất cả PR cùng merge. Đích là **đúng một cột cùng board có status_category phù hợp**, không dựa vào tên hoặc vị trí. Task được thêm cuối cột; nguồn và đích được đánh số lại từ 0, giữ giới hạn `devflow.board.tasks.max-per-column`.

Worker gọi listener đồng bộ, Board xử lý trong transaction REQUIRES_NEW timeout 6 giây; PostgreSQL lock timeout 2 giây, statement timeout 4 giây. Khóa Board dùng chung với thao tác tay. Mutation, audit GIT và receipt cùng commit/rollback. Receipt không được xóa khi GitCI purge outbox sau 7 ngày: nó tồn tại suốt vòng đời task và cascade khi task/board bị xóa.

Task event và CARD_MOVED được phát **best effort sau commit** theo baseline. DB thành công nhưng process chết trước dispatch có thể mất tín hiệu. Replay không phát lại vì receipt đã chặn; reload/reconnect đọc lại DB. BoardPage cũng refetch khoảng 30–35 giây khi trang visible và khi focus; cơ chế này hỗ trợ hội tụ bảng nhưng không thay thế durable domain event delivery hoặc bảo đảm mọi task detail cập nhật ngay. Không coi đây là durable downstream delivery. Nếu yêu cầu phát hành bắt buộc không mất task event hoặc client connected phải tự phục hồi mọi frame, phải bổ sung Board outbox/resync trong phạm vi riêng trước release.

## Rollout

1. Sao lưu DB theo quy trình môi trường; pause worker bằng `GIT_OUTBOX_WORKER_ENABLED=false`, restart backend để áp dụng. Webhook vẫn enqueue; thông báo Git cũng bị trì hoãn.
2. Deploy code producer + consumer và migration V18. Không sửa checksum V1–V17. V18 snapshot workspace từ repository cho outbox cũ, tạo index `ix_git_outbox_claim_token` để gia hạn lease theo batch trước mỗi row phát đi, và backfill manual watermark bằng `tasks.updated_at` cho task cũ. Đây là chính sách bảo thủ: Git cũ có thể bị bỏ qua dù updated_at chỉ đến từ chỉnh nội dung.
3. Kiểm tra Flyway/Hibernate validate và cấu hình cột. Mỗi board cần đúng một đích IN_PROGRESS, IN_REVIEW, DONE cho các bước muốn tự động hóa. Không ép unique toàn bộ category vì nhiều cột cùng nhóm vẫn là tính năng hợp lệ.
4. Khi `BOARD_GIT_AUTO_TRANSITION_ENABLED=false` (mặc định), consumer trả về `DISABLED` (không ném lỗi, không ghi receipt) để không làm hỏng outbox hay chặn Notification. Đặt `BOARD_GIT_AUTO_TRANSITION_ENABLED=true` khi sẵn sàng kích hoạt tự động chuyển cột cho các event mới. Các switch đọc lúc khởi động, không phải hot reload.
5. Kiểm tra và requeue các row FAILED do lỗi tạm thời (`TARGET_FULL`, lock/DB) sau khi đã xử lý nguyên nhân; giữ nguyên event ID và payload.

## Chẩn đoán và khôi phục

Chạy truy vấn bằng quyền vận hành DB; không có endpoint công khai để ép trạng thái hoặc requeue.

```sql
SELECT id, event_type, task_id, workspace_id, status, attempts,
       next_attempt_at, lease_until
FROM git_domain_event_outbox
WHERE status IN ('PENDING','PROCESSING','FAILED')
ORDER BY created_at, id;

SELECT board_id, status_category, count(*) AS targets
FROM columns
GROUP BY board_id, status_category
ORDER BY board_id, status_category;

-- Thay UUID bằng event cần kiểm tra; không truy payload tác giả/message/secret.
SELECT event_id, task_id, repository_id, workspace_id, event_type,
       outcome, received_at, processed_at
FROM board_git_event_receipts
WHERE event_id = '<event-uuid>'::uuid;

SELECT source_event_id, resource_id, cause, actor_id,
       source_column_id, destination_column_id, correlation_id, created_at
FROM board_audit_events
WHERE source_event_id = '<event-uuid>'::uuid;
```

| Reason | Xử lý |
|---|---|
| SKIPPED_TARGET_MISSING / SKIPPED_TARGET_AMBIGUOUS | Ghi terminal receipt và trả về không ném lỗi (không đốt retry outbox, không chặn Notification). Sửa category/cột qua Board UI cho các sự kiện sau; không đoán đích, không tự tạo cột trong consumer. |
| TARGET_FULL | Lỗi tạm thời: ném lỗi về retry queue T-023. Giải phóng chỗ hoặc điều chỉnh cấu hình capacity theo quy trình vận hành, rồi retry/requeue. |
| DISABLED | Consumer đang tắt cờ: trả về bình thường (outcome=SKIPPED, không ghi receipt) để outbox/Notification chạy bình thường. Bật `BOARD_GIT_AUTO_TRANSITION_ENABLED=true` và restart để áp dụng cho sự kiện mới. |
| INVALID_EVENT / TENANT_MISMATCH | Bỏ qua (outcome=SKIPPED, không ghi receipt, không chặn listener khác). Điều tra producer/trust boundary; không sửa tenant, timestamp hoặc ID để vượt guard. |
| EVENT_CONFLICT | Trùng `event_id` nhưng khác fingerprint payload: fail-closed (ném lỗi). Điều tra ngay producer/corruption. |
| DATABASE_OR_CONSUMER | Xem DB/lock/optimistic/audit và log an toàn; lỗi rollback đi tới retry T-023. |
| STALE_MANUAL | Quyết định cuối: Git occurredAt <= mốc đổi cột tay. Dùng thao tác tay nếu cần, không backdate watermark. |
| NOOP / ARCHIVED / DUPLICATE / MISSING | Không có move; không tạo audit/task event/frame thừa. MISSING không tạo receipt FK giả. |

Sau khi sửa lỗi, dùng `GitDomainEventOutbox.requeue(UUID)` qua công cụ nội bộ T-023. Tương đương SQL có điều kiện, dùng trong transaction vận hành đã kiểm tra đúng UUID:

```sql
UPDATE git_domain_event_outbox
SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP
WHERE id = '<event-uuid>'::uuid AND status = 'FAILED';
```

Kỳ vọng đúng một row được cập nhật. Không xóa receipt, không tạo outbox row mới, không phát commit giả. Requeue một quyết định đã có receipt không tái áp dụng sau khi người dùng kéo tay.

## Quan sát

- `devflow.board.git.transitions`: tags eventType/outcome/reason hữu hạn; SUCCESS/APPLIED chỉ ghi khi proxy đã commit và chạy afterCommit.
- `devflow.board.git.transition.duration`: tổng thời gian xử lý, gồm commit và dispatch sau commit.
- `devflow.board.git.lock.wait`: thời gian chờ khóa Board.
- Đối chiếu `devflow.gitci.outbox.*`, `devflow.task.events.dispatch` và `devflow.board.realtime.publications` để phân biệt ingest/consumer/downstream.
- Log dùng eventId/taskId/correlationId; không log webhook, HMAC, email, message/title, repo URL hoặc dùng ID làm metric tag. Worker context không kế thừa userId/IP từ request; MDC được restore sau cả success và exception.

Không rollback SQL bằng cách xóa schema/receipt. Pause worker trước rollback, giữ V17 và dữ liệu dedup; dùng phiên bản tương thích schema/metadata hoặc forward-fix rồi resume.

## Kiểm chứng tại máy phát triển

Backend cần JDK 17 và Docker Desktop Linux engine. Trên máy hiện tại, JDK có sẵn tại `C:/Users/ADMIN/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2`.

```powershell
$env:JAVA_HOME = 'C:/Users/ADMIN/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2'
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_DOCKER_CLIENT_STRATEGY = 'org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy'
./backend/gradlew.bat -p backend :app:test --tests '*GitTaskTransitionIntegrationTest' --tests '*GitDomainEventMigrationTest'
./backend/gradlew.bat -p backend check
```

Testcontainers tạo DB biệt lập; không reset database Compose đang có dữ liệu làm việc.


## Stack local đã rollout — 19/11/2026

Stack hiện dùng `devflow-backend:t024` và `devflow-frontend:t024`, schema V17, worker/automation cùng bật. File `.env.t024-local-compose.yml` chỉ chứa image và cờ rollout; cấu hình webhook vẫn tham chiếu file secret gốc qua `.env.github-webhook-compose.yml`. Không có bản sao secret mới trong overlay.

Khởi động đúng phiên bản local đã đóng gói, chờ health và làm mới URL quick tunnel:

```powershell
.\.env.t024-rollout\start-local.ps1
```

Helper đã chạy kiểm chứng thành công. Nó dùng ba file Compose gốc/webhook/local, khởi động gateway/tunnel T-022 hiện có và cập nhật riêng URL callback của repo thử bằng credential Git sẵn có. Nó không đổi secret, không lưu token và không rebuild image từ source. Khi code thay đổi, phải build image mới trước khi dùng bản mới.

Đường truy cập:

- UI: http://localhost:3000
- Backend health: http://localhost:8080/actuator/health
- Mailpit local: http://localhost:8025
- Board kiểm chứng: http://localhost:3000/boards/0ea50110-2f7e-4151-bdfc-f83046fb6554
- Repo kiểm chứng: `lvXuankg/repo-test-DevFlow`, PR #5 đã merge; file fixture trên default branch đã xóa.
- Tài khoản demo MEMBER theo yêu cầu người dùng: `t024-local@example.test`. Mật khẩu ngẫu nhiên được cung cấp trong chat; không lưu trong runbook/state.

Quick tunnel có URL tạm và có thể đổi sau khi khởi động lại. Nếu credential Git không còn hợp lệ, helper dừng; lấy URL hiện tại từ log `devflow-t022-tunnel`, rồi cập nhật Payload URL tại Settings → Webhooks của repo thử, giữ path `/api/v1/gitci/webhook/github` và query `repositoryId=5165bd7f-15d5-4923-b0b4-c0e356cd3759`. Chỉ sửa URL bằng [webhook configuration API](https://docs.github.com/en/rest/repos/webhooks#update-a-webhook-configuration-for-a-repository) khi tự động hóa để tránh thay secret ngoài ý muốn.

Backup trước migration và rollback image IDs/tags nằm ở `.env.t024-rollout/state.json`; archive PostgreSQL ở `.env.t024-rollout/backups/`. Đã kiểm tra archive bằng `pg_restore --list`. Không tự restore DB hoặc xóa schema khi rollback. Nếu phải dùng lại image V15 cũ, dừng tunnel/ingest trước để phiên bản không có outbox không nhận rồi âm thầm bỏ mất sự kiện T-024; ưu tiên forward-fix/phiên bản tương thích V17. Không resume ingest/worker cho tới khi producer/consumer đầy đủ trở lại.
