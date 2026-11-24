# Vận hành T-025 · Tiếp nhận lỗi GitHub Actions

## Cấu hình môi trường thử nghiệm

1. Chọn repository thử nghiệm có quyền chạy Actions và quản trị webhook. Khi dùng repo [lvXuankg/repo-test-DevFlow](https://github.com/lvXuankg/repo-test-DevFlow), kiểm tra repository binding cũ trong stack T-024 và giữ các event push/PR đang dùng. GitHub cần URL HTTPS trỏ vào đúng backend. Với Docker local, dùng gateway/tunnel theo [hướng dẫn T-024](14_tu_dong_chuyen_trang_thai_task_operations.md) và kiểm tra đường nhận webhook ngoài mạng trước khi thử.
2. Tạo fine-grained PAT với thời hạn ngắn. Trong **GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens**, chọn resource owner của repo, **Only select repositories**, rồi đặt **Repository permissions → Actions → Read-only**. Lưu token trong secret config backend trên máy. Cấu hình dưới đây cũng nhận GitHub App installation token được cấp sẵn. Token cài đặt hết hạn cần được deployment cấp mới. [Hướng dẫn của GitHub](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-fine-grained-personal-access-token).
3. Lấy UUID nội bộ và GitHub repository ID từ truy vấn bên dưới. Khi dùng Docker local cũ, thêm khối `ci` vào cùng `devflow.gitci` của `.env.github-webhook-config.yml` đã được backend mount. Dùng trình soạn thảo cục bộ để nhập token. Giữ nguyên khối `github-webhook` và secret HMAC hiện có.
4. Sau khi sao lưu PostgreSQL, khởi động image mới để Flyway migrate đến V19 (`V19__ci_failure_ingestion.sql`, đánh số lại từ V18 sau khi T-024 giữ V18). Kiểm tra health và cấu hình trước khi bật hai worker. Ở lần rollout đầu, bật fetch trước, xác nhận một đoạn log READY rồi bật publish. Bật retention độc lập với fetch và publish.
5. Trong repo GitHub, mở **Settings → Webhooks → Edit → Let me select individual events**, chọn thêm **Workflow runs**, giữ Content type là `application/json`. Payload URL có dạng `https://<callback-host>/api/v1/gitci/webhook/github?repositoryId=<UUID-nội-bộ>`. Kiểm tra ping và deliveries. [Cấu hình webhook GitHub](https://docs.github.com/en/webhooks/using-webhooks/creating-webhooks).

Stack T-025 trên máy hiện dùng overlay `.env.t025-local-compose.yml` và mount credential từ file ignored `.env.t025-actions.yml`. Đối với repo thử hiện tại, chạy lệnh sau trong thư mục gốc để nhập token qua lời nhắc ẩn. Sau đó restart riêng backend và requeue attempt đã ở `PERMISSION_DENIED`.

```powershell
powershell -ExecutionPolicy Bypass -File .\.env.t025-rollout\set-actions-token.ps1
docker compose -f docker-compose.yml -f .env.github-webhook-compose.yml -f .env.t024-local-compose.yml -f .env.t025-local-compose.yml up -d --no-build --force-recreate --wait backend
```

```sql
SELECT id, workspace_id, remote_repo_id
FROM repositories
WHERE provider = 'GITHUB' AND repository_url IN (
    'https://github.com/lvXuankg/repo-test-DevFlow',
    'https://github.com/lvXuankg/repo-test-DevFlow.git'
);
```

```yaml
devflow:
  gitci:
    ci:
      fetch-enabled: false
      publish-enabled: false
      retention-enabled: true
      credentials:
        "<UUID-nội-bộ>":
          repository-id: "<GitHub-repository-ID>"
          token: "<nhập token trên máy>"
      archive-hosts:
        - results-receiver.actions.githubusercontent.com
```

Nếu gặp `ARCHIVE_HOST_BLOCKED`, tra redirect của API tải log đúng attempt trong môi trường quản trị. Tắt auto-follow và chỉ lấy hostname từ `Location` trong bộ nhớ. Đối chiếu hostname với [tài liệu mạng GitHub Actions](https://docs.github.com/en/actions/reference/runners/github-hosted-runners#communication-requirements-for-github-hosted-runners), rồi thêm đúng hostname đã xác minh vào `archive-hosts`. Ví dụ Azure Blob cần hostname của storage account cụ thể. Tránh in hoặc lưu `Location` vì query có chữ ký tạm.

## Kiểm chứng GitHub thật

- Chạy workflow thử với một lệnh kết thúc mã 1 và dòng lỗi giả. Kiểm tra delivery `workflow_run.completed` trả 204. Kiểm tra SQL có một attempt FAILED, một failure, một job. Khi worker xử lý xong, đối chiếu READY, byte count, digest và một event outbox.
- Dùng workflow thử có điều kiện dựa trên `github.run_attempt` để attempt 1 fail và rerun attempt 2 thành công. Kiểm tra attempt 2 có status SUCCESS, lịch sử attempt 1 còn nguyên. Từ **Recent deliveries**, redeliver sự kiện của attempt 1 và đối chiếu số row cùng ID event. Restart backend để xác nhận trạng thái bền vững.
- Với bài thử quyền, dùng credential thử thiếu Actions read hoặc bỏ binding token. Sau khi thấy `PERMISSION_DENIED`, khôi phục quyền bằng credential phù hợp và requeue cùng attempt. Health của app vẫn phản ánh tình trạng app và DB.
- `PUBLISHED` nghĩa listener đồng bộ đã trả về. Consumer T-031 đọc bằng `CiFailureLogReadRequestedEvent.forDetection` và dùng event ID làm khóa idempotency. Kết quả đọc có `READY`, `NOT_READY`, `DENIED`, `NOT_FOUND`, `EXPIRED`. Digest nhận diện đoạn đã lưu, kể cả khi consumer chọn budget nhỏ hơn. T-031 theo dõi kết quả xử lý AI riêng.

## Tra cứu, pause và khôi phục

Truy vấn vận hành theo UUID attempt và workspace, ví dụ trong `psql` sau khi đặt `pipeline_id` và `workspace_id` bằng `\set`. Kết quả chỉ hiển thị metadata an toàn.

```sql
SELECT p.id, p.pipeline_id AS github_run_id, p.run_attempt, p.status,
       f.log_status, f.log_error_code, f.log_bytes, f.log_truncated,
       j.status AS job_status, j.attempts AS fetch_attempts, j.next_attempt_at,
       f.log_event_id AS event_id, o.status AS outbox_status
FROM ci_pipelines p
JOIN repositories r ON r.id = p.repository_id
LEFT JOIN ci_failures f ON f.pipeline_id = p.id
LEFT JOIN ci_log_fetch_jobs j ON j.failure_id = f.id
LEFT JOIN ci_failure_event_outbox o ON o.id = f.log_event_id
WHERE p.id = :'pipeline_id'::uuid AND r.workspace_id = :'workspace_id'::uuid;
```

- Pause hoặc resume bằng `fetch-enabled` và `publish-enabled`, sau đó restart từng backend. Công việc đã commit sẽ tiếp tục khi bật lại. Job bị gián đoạn được claim lại khi lease hết. Chờ worker dừng trước khi chỉnh hàng đợi.
- `RETRY_WAIT` tiếp tục tự động theo `next_attempt_at`. Với `PERMISSION_DENIED`, `UNAVAILABLE` hoặc `FAILED`, sửa nguyên nhân rồi requeue có điều kiện bên dưới. Chỉ làm khi hàng đợi còn dung lượng và archive nguồn còn khả dụng. Với `503` từ lần tiếp nhận webhook, dùng **Recent deliveries → Redeliver** sau khi xử lý lỗi.
- Chỉ requeue outbox `FAILED` khi excerpt còn `READY` và chưa hết hạn. Dùng lại ID cũ. Log hết hạn và dữ liệu cũ V6 chưa được xác nhận lọc không có nội dung để AI đọc. Xóa repo/workspace xóa cả log, job và outbox theo cascade.
- Theo dõi `devflow.gitci.ci.fetch.oldest.seconds` và `devflow.gitci.ci.outbox.oldest.seconds` với ngưỡng ban đầu 300 giây. Kiểm tra `devflow.gitci.ci.fetch.outcomes`, `devflow.gitci.ci.outbox.outcomes` và `devflow.gitci.ci.log.reads` khi lỗi tăng. Worker giới hạn 2 slot mặc định, 8 lần thử và lease 60 giây. Excerpt giữ 7 ngày mặc định. Retention dọn tối đa 100 row mỗi lượt.

```sql
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '4s';

SELECT p.id
FROM ci_pipelines p JOIN repositories r ON r.id = p.repository_id
WHERE p.id = :'pipeline_id'::uuid AND r.workspace_id = :'workspace_id'::uuid
FOR UPDATE OF p;

SELECT pg_advisory_xact_lock(13724375,25);
WITH requeued AS (
    UPDATE ci_log_fetch_jobs j
    SET status='PENDING', attempts=0, not_found_attempts=0, error_code=NULL, next_attempt_at=now()
    FROM ci_pipelines p, repositories r, ci_failures f
    WHERE j.pipeline_id=p.id AND r.id=p.repository_id AND f.id=j.failure_id
        AND p.id=:'pipeline_id'::uuid AND r.workspace_id=:'workspace_id'::uuid
        AND j.status='FAILED' AND j.claim_token IS NULL AND j.lease_until IS NULL
        AND f.log_status IN ('FAILED','UNAVAILABLE','PERMISSION_DENIED') AND NOT p.failure_event_enqueued
    RETURNING j.failure_id
)
UPDATE ci_failures SET log_status='PENDING',log_error_code=NULL,updated_at=now()
WHERE id IN (SELECT failure_id FROM requeued);
COMMIT;
```

```sql
UPDATE ci_failure_event_outbox o
SET status='PENDING',attempts=0,next_attempt_at=now()
FROM ci_failures f, ci_pipelines p, repositories r
WHERE o.pipeline_id=:'pipeline_id'::uuid AND o.workspace_id=:'workspace_id'::uuid
    AND o.status='FAILED' AND o.claim_token IS NULL AND o.lease_until IS NULL
    AND o.pipeline_id=p.id AND r.id=p.repository_id AND r.id=o.repository_id AND r.workspace_id=o.workspace_id
    AND f.id=o.failure_id AND f.log_event_id=o.id AND f.log_status='READY' AND f.log_expires_at>now()
RETURNING o.id;
```

## Rollback

Dùng `pg_dump` để tạo backup trước V18, kiểm tra có thể đọc backup và ghi lại image đang chạy. Khi cần rollback, pause cả hai worker và bỏ subscription Workflow runs để hạn chế deliveries mới. Đã diễn tập ngày 24/11/2026 trên bản sao PostgreSQL 17 cô lập: image cũ `devflow-backend:t024` khởi động sạch trên schema V18 (Flyway 10 / Spring Boot 3.4 mặc định bỏ qua future migration và Hibernate `ddl-auto=validate` đạt `200 UP` tại `/actuator/health`). Nếu cần quay hẳn schema về V17, phục hồi backup vào DB riêng rồi xác minh và chuyển kết nối. Những lần nhận mới sau backup cần đối chiếu từ GitHub Recent deliveries trước khi redeliver. Giữ backup và dữ liệu CI theo chính sách của môi trường.
