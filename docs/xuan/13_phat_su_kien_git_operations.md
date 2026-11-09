# T-023 — Vận hành Git domain event outbox

## Hợp đồng triển khai

`V16__git_domain_event_outbox.sql` phải áp dụng trước khi producer nhận delivery. Không backfill Git cũ. Mỗi `(repository_id, event_type, git_object_key, task_id)` có tối đa một row khi còn retention. Commit cũ không đổi link; PR chỉ phát opened khi action `opened` tạo row mới, merge khi action `closed` đưa PR từ chưa merge sang `MERGED`. MERGED là trạng thái cuối. Timestamp của provider giữ nguyên qua retry; không dùng thời điểm worker chạy. `author` chỉ là username GitHub hợp lệ của commit, `actorId=null` do chưa có mapping danh tính đã xác minh.

Event được giao ít nhất một lần khi phục hồi. Crash sau gọi bus nhưng trước ghi PUBLISHED có thể tạo hai lượt cùng payload. Notification dedup bằng ID ổn định từ payload. T-024 phải xử lý idempotent, transaction Board riêng, không lùi trạng thái khi event đảo thứ tự; không giả định FIFO. PUBLISHED chỉ xác nhận bus trả về; Notification tự bắt lỗi nên phải theo dõi metric consumer riêng.

## Rollout và tạm dừng

1. Backup staging theo quy trình hiện hành; xác nhận migration kế tiếp của nhánh tích hợp vẫn là V16 trước khi merge. Không sửa checksum migration đã áp dụng.
2. Deploy tất cả instance nhận webhook với `GIT_OUTBOX_WORKER_ENABLED=false`. Xác nhận Flyway V16 thành công, producer vẫn ACK và ghi PENDING.
3. Sau khi mọi instance đều có producer mới, đặt `GIT_OUTBOX_WORKER_ENABLED=true`, khởi động lại instance để áp cấu hình. Poll mặc định mỗi 1.000 ms (`GIT_OUTBOX_POLL_INTERVAL_MS`). Dừng worker bằng false và restart, ingest vẫn ghi hàng đợi. Không xóa queue để khắc phục sự cố.
4. Kiểm tra backlog giảm, không có FAILED chưa xử lý, Notification ghi đúng recipient. Chỉ công bố tính năng sau khi toàn bộ instance nhận webhook đã dùng bản mới.

Worker claim 50 row/lượt bằng `FOR UPDATE SKIP LOCKED`, lease 60 giây, token fencing. Tám lần thử; delay ngẫu nhiên từ `2^(attempt-1)` đến gấp đôi giá trị đó (giới hạn hàm backoff 300 giây). Claim hết lease nhận lại; claim hết retry budget chuyển FAILED. Worker cũ không được ghi đè token mới. Kiểm tra row/token trước publish giảm phát dữ liệu đã xóa, nhưng consumer vẫn cần bỏ qua task biến mất trong cửa sổ race.

## Theo dõi

Các tag chỉ chứa type/outcome/status có tập giá trị cố định; không gắn repo, SHA, task UUID hay delivery ID vào tag.

| Tên Micrometer | Mục đích |
|---|---|
| `devflow.gitci.outbox.events{type,outcome}` | enqueued chỉ tăng sau commit; published, retry, failed |
| `devflow.gitci.outbox.depth{status}` | PENDING, PROCESSING, FAILED |
| `devflow.gitci.outbox.oldest.pending.seconds` | Tuổi row chưa phát lâu nhất, gồm lease đang xử lý |
| `devflow.gitci.outbox.publish.duration{type}` | Thời gian gọi bus và ghi kết quả |
| `devflow.gitci.outbox.worker.errors` | Lỗi polling/DB cần điều tra |
| `devflow.task.events.consume{consumer="notification",outcome="failure"}` | Lỗi Notification tự bắt, không được suy ra từ PUBLISHED |

Prometheus đổi dấu chấm thành gạch dưới, thêm `_total` cho counter và `_seconds` cho timer. Cảnh báo khi FAILED > 0, backlog/tuổi pending tăng liên tục, worker errors tăng, hoặc consumer failure tăng. Gauge NaN biểu thị không đọc được DB, cần kiểm tra health/DB cùng worker errors.

Chỉ kiểm tra số liệu và UUID nội bộ, tránh xuất toàn payload/secret:

```sql
SELECT status,event_type,count(*) FROM git_domain_event_outbox GROUP BY status,event_type;
SELECT id,event_type,attempts,next_attempt_at,lease_until FROM git_domain_event_outbox
WHERE status='FAILED' ORDER BY created_at LIMIT 50;
```

## Xử lý FAILED và redelivery

1. Dùng log nội bộ và metric xác định lỗi DB hoặc listener; log không ghi exception message hay payload Git. Sửa nguyên nhân trước khi requeue.
2. Dùng `GitDomainEventOutbox.requeue(UUID)` từ tooling nội bộ, hoặc lệnh SQL có điều kiện dưới đây bằng tài khoản vận hành. Không có endpoint HTTP công khai. Truyền ID chính xác, không update toàn queue.
3. Requeue giữ nguyên repo/task/type/key/author/occurred_at. Sau phục hồi, xác nhận row PUBLISHED và consumer thành công. Lỗi consumer bị tự bắt cần kiểm tra/replay có mục tiêu theo quy trình của consumer; không coi PUBLISHED là chứng cứ mọi side effect đã hoàn tất.

```sql
UPDATE git_domain_event_outbox
SET status='PENDING', attempts=0, next_attempt_at=CURRENT_TIMESTAMP
WHERE id='<outbox UUID cần khôi phục>'::uuid AND status='FAILED'
RETURNING id,status;
```

Webhook trả 503 trước commit chưa có outbox bền vững: GitHub repo → Settings → Webhooks → chọn webhook → Recent Deliveries → chọn delivery → Redeliver. Kiểm tra mã HTTP và số sự kiện trước/sau; không dán secret hoặc raw delivery chứa dữ liệu riêng tư vào báo cáo. [GitHub: redelivery](https://docs.github.com/en/webhooks/testing-and-troubleshooting-webhooks/redelivering-webhooks).

## Retention và cleanup

Dọn tối đa 1.000 PUBLISHED quá 7 ngày mỗi ngày 04:15 UTC. Không purge PENDING/PROCESSING/FAILED theo tuổi. Task/board deletion phát `task.references_removed`, GitCI xóa outbox trong cùng transaction; rollback giữ dữ liệu. Repo/workspace deletion cascade từ repositories. Không dùng cleanup retention để sửa lỗi delivery. Sau retention, commit uniqueness và terminal PR vẫn chặn phát bù lịch sử.

## Thông tin cần để nghiệm thu GitHub staging (REL-03/04)

**Hiện trạng 09/11/2026:** GitHub demo T-023 đã PASS REL-03/04 qua PR #4 (merged delivery + redelivery 204, không trùng Notification). Webhook ban đầu đã khôi phục, staging dừng giữ dữ liệu. Không cần người dùng tìm thêm closed #2 hoặc gửi credential. [Evidence cuối](13_github_demo_actual.md). Phần dưới áp dụng khi chuyển sang một staging khác.

- URL repo GitHub dùng thử có quyền quản trị Webhooks; không dùng repo production cho demo mutation.
- URL HTTPS backend staging công khai tới GitHub, cách truy cập/logs deploy và DB staging; workspace/repository UUID binding trong DevFlow.
- Hai task active cùng workspace với repo (key hiển thị như T-001, T-002); có thể tạo khi có quyền truy cập staging.
- Secret webhook cấu hình trực tiếp trong môi trường deploy và GitHub, không gửi giá trị secret trong chat. UUID binding phải có current secret/reference khớp cấu hình T-021.

Cách lấy: GitHub mở repo, copy URL thanh địa chỉ; Settings → Webhooks xác nhận quyền. Hosting dashboard chọn backend staging → copy public URL và chỉ rõ dịch vụ/deployment tương ứng. DevFlow chọn workspace/board và lấy task key từ task card; UUID có thể tra qua tooling nội bộ sau khi được cấp quyền. Nếu chưa có staging, cung cấp repo test và chọn nơi chạy backend/tunnel để cấu hình ở bước tiếp theo.

Kịch bản: push commit gắn hai task → mở PR → sửa title từ A sang B → merge; PR thứ hai đóng không merge; redeliver. Ghi HTTP, event/outbox counts, occurrence time và Notification. Tạm dừng worker, gửi tiếp delivery để thấy PENDING tăng rồi bật và xác nhận backlog hết/FAILED=0. Kiểm tra GitHub ACK dưới 10 giây trên hạ tầng staging thực tế.

Tham chiếu kỹ thuật: [PostgreSQL 17 locking và SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html), [Spring transaction-bound events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html), [GitHub webhook payloads](https://docs.github.com/en/webhooks/webhook-events-and-payloads#pull_request).
