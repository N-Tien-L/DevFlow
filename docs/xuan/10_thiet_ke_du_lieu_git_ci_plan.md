# T-020 — Kế hoạch triển khai thiết kế dữ liệu Git và CI

**Milestone 3 · Phạm vi:** `:app`, dữ liệu do `gitci` sở hữu · **Phụ trách:** Xuân Lê (@xuanle2).
**Ngày đối chiếu:** 15/10/2026, Asia/Saigon · **Baseline mã nguồn:** `4bf5d17`, branch `feature/T-018-stomp-board-sync`.
**Trạng thái:** ĐÃ TRIỂN KHAI VÀ KIỂM CHỨNG LOCAL — 84 test Git/CI PASS, backend check và frontend build PASS; chờ peer review/merge. Branch: `feature/T-020-gitci-schema`.

[Task gốc](10_thiet_ke_du_lieu_git_ci.md) · [Thẻ Trello T-020](https://trello.com/c/BK5P5I6g/25-t-020-setup-flyway-migration-for-git-ci-schema-v3gitcischemasql) · [Tổng quan](00_nhiemvu.md).

## 1. Mục tiêu, căn cứ và phạm vi

### 1.1. Kết quả cần đạt

Tạo nền tảng dữ liệu PostgreSQL cho repository, commit, Pull Request, lần chạy CI và lỗi CI. Sau triển khai, hệ thống nâng cấp được database đang có Auth/Kanban, lưu và đọc lại đúng quan hệ Git/CI, chống bản ghi trùng theo định danh nghiệp vụ và giữ ranh giới module. Đây là nền tảng cho T-021–T-026; riêng việc có các bảng chưa làm webhook, tự chuyển cột hoặc badge hoạt động.

Thẻ Trello được đọc trực tiếp ngày 15/10/2026: task thuộc Xuân, ở `Backlog`, branch gợi ý `feature/T-020-gitci-schema`; hai acceptance criteria gốc chưa được đánh dấu. Sau lệnh triển khai, đã thử chuyển In Progress nhưng API trả `401 unauthorized card permission requested`; không ghi nhận thay đổi thành công trên Trello.

### 1.2. Tài liệu và mã nguồn đã đối chiếu

Bảng dưới ghi nhận baseline trước triển khai tại `4bf5d17`; trạng thái mới nhất và bằng chứng thực tế nằm ở mục 6 và 8.

| Nguồn | Kết quả đối chiếu / tác động tới plan |
|---|---|
| [DATABASE_DESIGN.md](../DATABASE_DESIGN.md), mục 3.3, 4–6 | Năm bảng chuẩn: `repositories`, `git_commits`, `pull_requests`, `ci_pipelines`, `ci_failures`; workspace/task là UUID mềm |
| [ARCHITECTURE.md](../ARCHITECTURE.md), mục 2, 4, 6, 9.2, 10 | Modular monolith, quyền sở hữu bảng, event bus, bảo mật và khả năng phục hồi |
| [PRODUCT_SPEC.md](../PRODUCT_SPEC.md), mục 5.2a, 6.2, 8 | Liên kết code/task, truy vết lỗi CI, privacy, giới hạn dữ liệu và NFR |
| [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md), Phase 3, T-020, DoD | Migration là nền tảng; webhook/parser/events/board/frontend có ticket riêng |
| [DoD của Xuân](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung), [task T-020](10_thiet_ke_du_lieu_git_ci.md) | Chứng minh PostgreSQL, round-trip dữ liệu, nâng cấp không mất board, review và bằng chứng |
| [Thư mục migration](../../backend/app/src/main/resources/db/migration/) | Có V1 Auth, V2 Board, V3 board audit, V4 task audit, V5 task optimistic version; chưa có schema Git/CI |
| [application.yml](../../backend/app/src/main/resources/application.yml) | Flyway bật, `ddl-auto: validate`; đang có `baseline-on-migrate: true`, cần kiểm soát khi rollout |
| [Cấu hình test app](../../backend/app/src/test/resources/application.yml) | H2, Flyway tắt, Hibernate create-drop; smoke test này không chứng minh migration PostgreSQL |
| [app/build.gradle.kts](../../backend/app/build.gradle.kts) | Đã có Flyway core/PostgreSQL adapter, PostgreSQL driver, JUnit và Testcontainers BOM 1.21.4 |
| [KanbanMigrationIntegrationTest](../../backend/app/src/test/java/io/devflow/board/schema/KanbanMigrationIntegrationTest.java), [KanbanSchemaIntegrationTest](../../backend/app/src/test/java/io/devflow/board/schema/KanbanSchemaIntegrationTest.java) | Có mẫu PostgreSQL 17 thật, Flyway Java API, JDBC và test nâng cấp/rollback/checksum để kế thừa cách tổ chức |
| [gitci-impl](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitCiService.java) | Service còn stub, chưa có entity/repository; controller webhook hiện trả 501 |
| [BaseEntity](../../backend/common/src/main/java/io/devflow/common/entity/BaseEntity.java) | UUID, `createdAt`, `updatedAt`; mapping sau này cần khớp schema |
| [CiFailureInfo](../../backend/gitci-api/src/main/java/io/devflow/gitci/api/CiFailureInfo.java), [CiFailureDetectedEvent](../../backend/common/src/main/java/io/devflow/common/event/CiFailureDetectedEvent.java) | Hợp đồng hiện dùng `pipelineId` kiểu String và log reference; chưa đủ ngữ cảnh tenant/run attempt cho mọi trường hợp |

`AGENTS.md` đang yêu cầu trao đổi liên module qua typed events thuộc `common`. Một số tài liệu cũ còn mô tả gọi trực tiếp public API hoặc tên bảng `git_repositories`/`ci_runs`; plan áp dụng chỉ dẫn workspace hiện hành và dùng tên bảng thống nhất với task/T-000. Không gọi service/repository của Auth/Board, không thêm JOIN hay FK sản xuất sang bảng do module khác sở hữu.

### 1.3. Phạm vi triển khai sau khi plan được thực hiện

**Bao gồm:** một migration mới cho năm bảng, PK/FK nội bộ/check/unique/index/default; kiểm thử JDBC/Flyway trên PostgreSQL 17; kiểm thử nâng cấp, dữ liệu âm, concurrent inserts, rollback, cascade, truy vấn và hồi quy Kanban; cập nhật tài liệu thiết kế/mapping và bằng chứng nghiệm thu.

**Bàn giao cho ticket sau:** cấu hình repository và quyền quản trị, resolver webhook secret, HMAC/rate limiting (T-021), parser và phân giải task (T-022), phát event/idempotent side effects (T-023), tự chuyển cột (T-024), lấy log/redaction/CI ingestion (T-025), API đọc và badge frontend (T-026), AI summary thuộc module AI. T-020 không triển khai entity/service/API mới chỉ để chứng minh migration; dùng SQL/JDBC độc lập.

Không thêm GitLab webhook, outbox, bảng receipt webhook, nhiều task cho một commit/PR, failure clustering hay trigger gọi module khác trong migration này. Các nhu cầu mở rộng được ghi thành hợp đồng/rủi ro, không âm thầm coi là đã giải quyết.

## 2. Kế hoạch nghiệp vụ — luồng theo ngôn ngữ người dùng

### NV-01. Gắn một kho code vào workspace

Người quản trị chọn kho code để nhóm theo dõi. Hệ thống lưu nhà cung cấp, định danh ổn định của kho và tên/đường dẫn hiển thị trong đúng workspace. Đổi tên kho không được tạo một kho mới; đăng ký trùng cùng kho trong cùng workspace không tạo thêm bản ghi.

Một kho có thể được đăng ký ở hai workspace, nhưng dữ liệu theo dõi của từng workspace độc lập. Việc xác minh người đăng ký có quyền, chọn đúng integration khi nhận webhook và bảo vệ secret thuộc luồng tích hợp sau này; một ID workspace trong database không chứng minh quyền truy cập.

### NV-02. Ghi nhận commit và liên kết task khi xác định được

Khi nhận hoạt động Git hợp lệ, hệ thống cần lưu mã commit, nội dung, thông tin tác giả có sẵn và thời điểm commit. Cùng commit có thể xuất hiện ở nhiều repository/fork; mỗi repository được lưu riêng, gửi lại cho cùng repository không tạo bản ghi commit trùng.

Chưa xác định được task thì vẫn lưu commit với liên kết trống. Khi T-022 xác định đúng task trong workspace, liên kết có thể được bổ sung. Không đoán UUID từ chuỗi `T-020`, không coi tác giả Git là user đã xác thực và không mất commit chỉ vì nguồn không cung cấp email.

### NV-03. Ghi nhận vòng đời Pull Request

Một PR được nhận diện bằng repository và số PR. Mở, sửa, đóng, mở lại hoặc merge cập nhật cùng một bản ghi phù hợp; PR đóng nhưng chưa merge phải khác PR đã merge. Hai repository đều có PR số 12 là hợp lệ và không được gộp nhầm.

Lưu nhánh nguồn/đích, tiêu đề, URL và task liên quan nếu có. Thứ tự sự kiện và tự chuyển trạng thái task được xử lý ở các task ingestion/events, không do database tự suy diễn.

### NV-04. Theo dõi lần chạy CI và lần chạy lại

Người dùng cần phân biệt lần chạy đang chờ, đang chạy, kết thúc thành công, thất bại hoặc kết thúc vì nguyên nhân khác. Khi chạy lại cùng workflow run, lịch sử attempt trước vẫn còn; hai workflow khác nhau chạy trên cùng commit không bị nhập làm một.

Thông báo CI có thể đến trước thông báo push. Vẫn phải lưu được pipeline theo repository và SHA mà không buộc đã có bản ghi commit. Thời gian chạy nếu có phải đọc đúng ở các múi giờ khác nhau.

### NV-05. Lưu lỗi để truy vết và chuyển cho AI

Một attempt thất bại có thể có nhiều job lỗi. Mỗi lỗi cần liên hệ được về attempt, repository và commit; việc gửi lại không tạo thêm cùng lỗi theo định danh đã thống nhất. Lỗi chưa xác định task hoặc chưa tải được log vẫn được ghi nhận.

Log snippet, mô tả lỗi và AI summary có thể chưa có. Nội dung log lưu vào hệ thống phải được loại secret trước, có giới hạn và có thể bị xóa theo chính sách. Trạng thái pipeline thành công không được tự động sinh lỗi; quy tắc này thuộc T-025, không được tuyên bố đã có chỉ nhờ FK.

### NV-06. Xóa và bảo vệ lịch sử

Khi xóa vật lý repository đã được cho phép, dữ liệu commit/PR/pipeline/lỗi của repository đó được dọn theo quan hệ nội bộ; repository khác còn nguyên. Xóa pipeline chỉ xóa lỗi của pipeline đó. Không xóa workspace, user hoặc task chỉ vì xóa dữ liệu Git/CI.

Xóa task/workspace không tự cascade sang Git/CI vì đây là tham chiếu mềm. Cần luồng event/cleanup riêng trước khi bật tính năng xóa tài khoản/workspace trên môi trường thật; T-020 chỉ kiểm chứng đúng đặc tính schema và ghi nhận phần bàn giao này.

### NV-07. Khởi động và nâng cấp hệ thống

Database mới chạy toàn bộ migration; database đang có Auth/Kanban chỉ nhận phần bổ sung còn thiếu. Người dùng vẫn thấy nguyên board/task/audit/version sau nâng cấp. Khởi động lại không tạo thêm bảng hay xóa dữ liệu. Nếu migration lỗi, dừng rollout và giữ trạng thái trước migration, không cho ứng dụng chạy với cấu trúc dang dở.

## 3. Kế hoạch kỹ thuật — quyết định thiết kế và luồng xử lý

### 3.1. Chọn version migration và quản lý thay đổi

Tên `V3__gitci_schema.sql` trên Trello là tên lịch sử, **không còn dùng được ở checkout hiện tại**. Đã tạo `backend/app/src/main/resources/db/migration/V6__gitci_schema.sql` sau khi fetch/đối chiếu refs: checkout có V1–V5, origin/develop có V1–V2 và chưa có Git/CI. Trước merge/deploy vẫn kiểm tra lịch sử DB đích và version mới từ các nhánh khác; không đổi tên, sửa nội dung hoặc checksum V1–V5.

AC gốc “migrate V3 thành công” được truy vết thành “migration Git/CI ở version hợp lệ được áp dụng thành công”; ghi rõ mapping V3 lịch sử → V6 dự kiến trong PR và đề nghị cập nhật cách diễn đạt checklist, không tự đánh dấu AC gốc đã qua. Flyway theo dõi version/checksum trong history; tham khảo [versioned migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html).

```text
Chọn đúng database/profile -> kiểm tra history + version + backup
  -> Flyway validate -> chạy V1..V5 còn thiếu (nếu có)
  -> migration Git/CI: repositories -> commits/PR/pipelines -> failures -> indexes
  -> kiểm tra history/metadata/round-trip -> Hibernate validate -> smoke hồi quy
Lỗi -> dừng startup/rollout, thu log đã che dữ liệu nhạy cảm, kiểm tra rollback
```

Không dùng `IF NOT EXISTS` để che bảng đã tồn tại với cấu trúc sai, không dùng `ddl-auto: update`, không thêm `CREATE INDEX CONCURRENTLY` vào migration giao dịch này. Transaction boundary do Flyway quản lý; không thêm `BEGIN/COMMIT` thủ công.

### 3.2. Những điều chỉnh so với từ điển dữ liệu cũ

Các quyết định dưới đây đã được áp dụng trong V6 theo lệnh triển khai của người dùng. Đã cập nhật data dictionary EN/VI, ERD, master plan EN/VI và task gốc cho đúng schema. Review của đầu mối T-021/T-025 vẫn cần trước merge/bật tích hợp; không coi việc tác giả tự kiểm tra là peer review. Nếu thay contract phải sửa expected và bổ sung forward migration khi version đã áp dụng ở môi trường dùng chung.

| Điểm | Thiết kế cũ | Phương án đề xuất và lý do |
|---|---|---|
| Version | V3 Git/CI | V6 dự kiến; không trùng V3 board audit |
| Secret | `webhook_secret VARCHAR(255)` | Đổi thành `webhook_secret_ref VARCHAR(255) NOT NULL`: tham chiếu opaque tới secret cấu hình/secret manager; không lưu plaintext hoặc hash thay cho khóa HMAC |
| Repository identity | Chưa có unique nghiệp vụ | `UNIQUE(workspace_id, provider, remote_repo_id)`; định danh ổn định, không dùng tên repo làm khóa |
| Commit uniqueness | SHA unique toàn hệ thống | `UNIQUE(repository_id, sha)`; cùng SHA ở repo khác hợp lệ |
| Tác giả commit | Bắt buộc name/email | Cho phép NULL khi thiếu/được ẩn danh; không tạo email giả hoặc dùng làm khóa auth |
| Repo URL | Chưa có | Thêm `repository_url VARCHAR(2048) NOT NULL` để bàn giao đường dẫn code; kiểm tra host/quyền thuộc ingestion |
| CI identity | Chưa unique; chưa có attempt | Thêm `run_attempt INT NOT NULL DEFAULT 1`, unique `(repository_id, pipeline_id, run_attempt)` |
| CI status | Bốn trạng thái | Giữ bốn trạng thái, bổ sung các kết thúc khác và UNKNOWN; không gán mọi kết thúc thành FAILED |
| Failure identity | Chưa có | Thêm `failure_key VARCHAR(200) NOT NULL`, unique `(pipeline_id, failure_key)`; không dùng tên job hoặc nội dung lỗi làm khóa |
| Audit/mapping | Một số bảng chỉ có created_at | Thêm updated_at cho cả năm bảng để tương thích BaseEntity và cập nhật link/state/log sau này |
| Log contract | Chỉ snippet/summary | Thêm raw_log_reference nullable; giới hạn byte cho các TEXT lớn, snippet phải được redaction trước khi ghi |

Không thêm bảng thứ sáu. Một commit/PR có tối đa một `task_id` theo baseline; hỗ trợ nhiều task cần bảng nối và migration riêng. Nhiều event hoặc cùng delivery được gửi lại vẫn cần idempotency ở tầng nghiệp vụ; các unique key trên không bảo đảm event chỉ phát một lần và không thay transactional outbox.

### 3.3. Quy ước chung

- Năm bảng thuộc `gitci`; `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`; `created_at`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.
- `workspace_id`, `task_id`, `related_task_id` là UUID mềm, không `REFERENCES` Auth/Board, không quan hệ JPA xuyên module. Dữ liệu cùng module dùng FK thật.
- `updated_at` không tự đổi chỉ vì SQL UPDATE; writer tương lai/JPA auditing phải cập nhật. T-020 không thêm trigger cập nhật mọi bảng hoặc dùng trigger phát event.
- Chuỗi định danh/tên bắt buộc dùng `NOT NULL` và `CHECK(btrim(field) <> '')`. SHA canonical lowercase hex dài 40 hoặc 64, khác toàn số 0; ingestion chịu trách nhiệm chuẩn hóa trước INSERT. `message` có thể rỗng vì commit hợp lệ có thể không có thông điệp.
- UUID không hợp lệ bị từ chối ở kiểu DB; UUID đúng kiểu nhưng không tồn tại bên Auth/Board vẫn được DB chấp nhận. Đây là hành vi mềm cần kiểm thử, không phải cơ chế authorization.
- Dùng query parameter; không nối nội dung Git, tên nhánh hoặc tên schema do request cung cấp thành SQL. Test schema ngẫu nhiên chỉ do harness tạo.

### 3.4. Từ điển dữ liệu đã triển khai trong V6

Các cột chung ở mục 3.3 áp dụng cho cả năm bảng. Không thêm FK tự động theo tên cột có hậu tố `_id`.

#### A. `repositories`

| Cột | Kiểu / nullability | Quy tắc |
|---|---|---|
| workspace_id | UUID NOT NULL | Soft reference Auth |
| provider | VARCHAR(50) NOT NULL | CHECK trong `GITHUB`, `GITLAB`; lưu được enum không đồng nghĩa có GitLab integration |
| remote_repo_id | VARCHAR(100) NOT NULL | Opaque stable provider ID; trim/canonical trước lưu, không dùng slug mutable |
| name | VARCHAR(255) NOT NULL | Tên hiển thị, có thể cập nhật khi rename |
| repository_url | VARCHAR(2048) NOT NULL | Không rỗng; writer kiểm HTTPS/canonical provider host, không nhúng credential/query token |
| webhook_secret_ref | VARCHAR(255) NOT NULL | Không rỗng; opaque reference, chỉ resolver cấu hình allowlist của T-021 được diễn giải |

Unique `uq_repositories_workspace_provider_remote(workspace_id, provider, remote_repo_id)`; index `idx_repositories_workspace(workspace_id)` được prefix unique bao phủ nên không cần tạo trùng. Chỉ thêm index `(provider, remote_repo_id)` nếu truy vấn webhook routing đã được chốt và đo được nhu cầu; không dùng index đó để suy ra một workspace duy nhất.

#### B. `git_commits`

| Cột | Kiểu / nullability | Quy tắc |
|---|---|---|
| repository_id | UUID NOT NULL | FK repositories(id), ON DELETE CASCADE |
| sha | VARCHAR(64) NOT NULL | CHECK `^([0-9a-f]{40}\|[0-9a-f]{64})$` và không phải toàn số 0 |
| message | TEXT NOT NULL | Cho phép rỗng, tối đa 65.536 byte theo octet_length; không strip nội dung code/Markdown tại DB |
| author_name | VARCHAR(255) NULL | Thiếu/ẩn danh thì NULL; nếu có không rỗng |
| author_email | VARCHAR(255) NULL | Thiếu/ẩn danh thì NULL; là metadata Git, không chứng minh identity/membership |
| task_id | UUID NULL | Soft reference Board, có index |
| committed_at | TIMESTAMPTZ NOT NULL | Thời điểm từ Git; không ép <= now vì đồng hồ tác giả có thể lệch |

Unique `uq_git_commits_repo_sha(repository_id, sha)`; index `idx_git_commits_task_id(task_id)`. Unique đã bao phủ truy vấn prefix repository; không cần index repository đơn trùng lặp. Không cascade khi xóa task và không mất commit chỉ vì chưa link.

#### C. `pull_requests`

| Cột | Kiểu / nullability | Quy tắc |
|---|---|---|
| repository_id | UUID NOT NULL | FK repositories(id), ON DELETE CASCADE |
| pr_number | INT NOT NULL | CHECK > 0; unique theo repository |
| title | VARCHAR(255) NOT NULL | Không rỗng |
| source_branch, target_branch | Mỗi cột VARCHAR(255) NOT NULL | Không rỗng; nhánh là nội dung, không bị lowercase tùy tiện |
| status | VARCHAR(50) NOT NULL | CHECK `OPEN`, `MERGED`, `CLOSED`; không default ngầm khi thiếu |
| task_id | UUID NULL | Soft reference Board |
| pr_url | VARCHAR(512) NOT NULL | Không rỗng; writer T-021 kiểm URL/canonical host, UI T-026 render an toàn |
| provider_updated_at | TIMESTAMPTZ NULL | Thời điểm phiên bản nguồn, hỗ trợ chống event cũ; chưa triển khai ordering logic |

Unique `uq_pr_repo_number(repository_id, pr_number)`; indexes `idx_pull_requests_task_id(task_id)`, `idx_pull_requests_repo_status(repository_id, status)`. CHECK enum không ngăn event cũ ghi đè MERGED thành OPEN; T-023 phải xử lý concurrency/freshness trong transaction.

#### D. `ci_pipelines`

| Cột | Kiểu / nullability | Quy tắc |
|---|---|---|
| repository_id | UUID NOT NULL | FK repositories(id), ON DELETE CASCADE |
| pipeline_id | VARCHAR(100) NOT NULL | External run ID dạng opaque string, không nhầm với PK UUID nội bộ |
| run_attempt | INT NOT NULL DEFAULT 1 | CHECK > 0; một hàng là một attempt |
| commit_sha | VARCHAR(64) NOT NULL | Cùng CHECK như sha; không FK tới git_commits |
| branch | VARCHAR(255) NOT NULL | Không rỗng |
| status | VARCHAR(50) NOT NULL | CHECK allowlist bên dưới, không default khi thiếu |
| provider_status, provider_conclusion | Mỗi cột VARCHAR(50) NULL | Giá trị nguồn phục vụ mapping, không dùng trực tiếp làm trạng thái UI |
| started_at, finished_at | Mỗi cột TIMESTAMPTZ NULL | CHECK finished_at >= started_at khi cả hai có giá trị |
| provider_updated_at | TIMESTAMPTZ NULL | Phục vụ chống cập nhật cũ ở ingestion |

Allowlist status đề xuất: `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED`, `SKIPPED`, `TIMED_OUT`, `ACTION_REQUIRED`, `NEUTRAL`, `STALE`, `UNKNOWN`.

Unique `uq_ci_pipelines_repo_run_attempt(repository_id, pipeline_id, run_attempt)`; indexes `idx_ci_pipelines_repo_status(repository_id, status)`, `idx_ci_pipelines_repo_commit(repository_id, commit_sha)`. Không unique trên SHA vì một commit có thể chạy nhiều workflow/attempt. Không yêu cầu commit row tồn tại; đọc commit nếu có bằng repository + SHA.

GitHub biểu diễn riêng status, conclusion và run_attempt; T-025 cần mapping rõ queued/waiting → PENDING, in_progress → RUNNING, completed + success → SUCCESS, failure → FAILED; các conclusion khác giữ nghĩa tương ứng, chưa nhận diện → UNKNOWN. Đây là mapping đề xuất của DevFlow, không phải schema trạng thái nguyên bản của provider. Xem [workflow runs](https://docs.github.com/en/rest/actions/workflow-runs).

#### E. `ci_failures`

| Cột | Kiểu / nullability | Quy tắc |
|---|---|---|
| pipeline_id | UUID NOT NULL | FK ci_pipelines(id), ON DELETE CASCADE; khác external ci_pipelines.pipeline_id |
| failure_key | VARCHAR(200) NOT NULL | Không rỗng; unique theo pipeline attempt |
| stage_name, job_name | Mỗi cột VARCHAR(100) NOT NULL | Không rỗng; tên hiển thị không dùng làm khóa |
| error_summary | TEXT NULL | Tối đa 8.192 byte |
| raw_log_snippet | TEXT NULL | Tối đa 16.384 byte sau redaction; không mặc định có full log |
| raw_log_reference | VARCHAR(2048) NULL | Opaque storage reference; không lưu signed URL dài hạn hoặc credential |
| ai_summary | TEXT NULL | Tối đa 16.384 byte; chỉ là projection do Git/CI ghi qua hợp đồng event sau này |
| related_task_id | UUID NULL | Soft reference Board |

Unique `uq_ci_failures_pipeline_key(pipeline_id, failure_key)`; index `idx_ci_failures_related_task(related_task_id)`. Unique bao phủ lookup theo pipeline; không tạo thêm index pipeline đơn. Failure key đề xuất `job:<provider job ID>` cho một bản tổng hợp lỗi mỗi job, hoặc `run` khi chỉ có lỗi cấp run; key từ nguồn tin cậy, không phát UUID ngẫu nhiên mỗi delivery. Nếu cần nhiều lỗi trong một job, chốt định danh riêng trước khi thay contract.

Không đặt CHECK đọc status bảng pipeline: một CHECK không phải công cụ kiểm tra trạng thái bảng khác. T-025 kiểm tra pipeline kết thúc lỗi và ghi lỗi trong transaction. Việc tải log thất bại cho phép log NULL và retry ở T-025, không dùng chuỗi rỗng như thể đã có log.

### 3.5. Index, quyền dữ liệu và xử lý đồng thời

| Truy vấn cần hỗ trợ | Đường đọc dự kiến / index |
|---|---|
| Danh sách repo trong workspace | repositories WHERE workspace_id = ?; prefix unique repo |
| Tìm một commit / chống trùng | repository_id + sha; unique commit |
| Commit/PR theo task | Index task_id, đồng thời giới hạn qua repository.workspace_id |
| PR theo số / trạng thái | Unique repo-number; index repo-status |
| CI theo repo/trạng thái/commit | Index repo-status hoặc repo-commit; phân trang có khóa phụ id |
| Failure của attempt / task | Unique pipeline-key; index related_task_id; tenant đi qua pipeline → repository |

Unique/FK là hàng rào dữ liệu, không phải authorization hoặc delivery dedupe. Với INSERT đồng thời cùng key, một insert thành công; insert còn lại nhận unique violation hoặc ON CONFLICT theo policy writer. Sau lỗi PostgreSQL, rollback/savepoint trước khi tiếp tục transaction; không bắt exception rồi tiếp tục trên transaction đã hỏng.

Không dùng `ON CONFLICT DO UPDATE` mù để ghi đè task link hoặc state mới bằng payload cũ. T-020 test SQL conflict/atomicity; logic upsert, lock/version và phát event được nghiệm thu trong T-022/T-023/T-025. Query production chỉ JOIN các bảng Git/CI, nhận workspace context từ luồng xác thực hợp lệ, không tin workspace/task UUID của payload.

### 3.6. Bảo mật, privacy và giới hạn phạm vi

1. `webhook_secret_ref` chỉ lưu reference giả lập trong fixture; key thực ở cấu hình/secret manager. HMAC cần key có thể lấy lại, không dùng một hash không thể khôi phục thay key. T-021 phải fail closed nếu không resolve được; không đọc file/URL tùy ý từ giá trị DB. T-020 không viết resolver.
2. Email/tên tác giả, commit message và log có thể chứa PII/secret. Không ghi các giá trị này vào log kiểm thử, log SQL bind hoặc metric label. Dùng dữ liệu tổng hợp; snippet phải được redaction ở writer trước khi lưu. Length check không chứng minh đã che secret.
3. Giữ Markdown/code nguyên văn ở DB; không tự strip HTML làm hỏng evidence. T-026/AI phải xử lý nội dung untrusted và escape/render an toàn. Validation URL/provider/SSRF thuộc boundary ingestion, không được coi là đã có từ CHECK không rỗng.
4. Retention đề xuất cho snippet/reference là 30 ngày, cấu hình theo môi trường và review với Tiến trước release; scheduler/object deletion không thuộc T-020. Xóa blob ngoài DB không do FK đảm nhiệm; cần worker/event cleanup và audit không chứa raw log.
5. Cần quyết định và test cleanup/anonymization khi xóa user/workspace/task trước khi bật các luồng đó. Không áp CASCADE xuyên module để giải quyết privacy. `ai_summary` không cho phép AI import repository Git/CI để UPDATE trực tiếp.
6. Migration không tạo endpoint nên không thêm CAPTCHA/rate-limit API giả định. Các yêu cầu HMAC, quota, timeout/retry/circuit breaker và ProblemDetail được bàn giao T-021/T-025; không có network call trong SQL migration.

### 3.7. Rollout, quan sát và phục hồi

- Dùng database/schema test riêng. Trước rollout thật, kiểm tra đúng DB/user/schema, lịch sử Flyway, version còn trống và backup/PITR; thử restore trên clone cách ly. Không chạy `clean`, `repair`, baseline tự động hoặc xóa bảng trên DB dùng chung.
- Vì app hiện bật auto-baseline, test migration cấu hình `baselineOnMigrate(false)`; rollout đặt `SPRING_FLYWAY_BASELINE_ON_MIGRATE=false` để schema không có history không bị hợp thức hóa ngoài ý muốn. Thay default toàn dự án nếu cần là thay đổi riêng cần review; không khẳng định cấu hình production hiện đã fail closed.
- Chạy validate trước/sau, lưu version/checksum/execution time và trạng thái history; log lỗi đã che connection secret, tránh in payload hoặc metadata tác giả. Theo dõi migration failure, startup/readiness và áp lực DB bằng hạ tầng hiện có; chỉ thêm metric khi có writer nghiệp vụ.
- Chạy migration giao dịch với DDL thông thường và index trên năm bảng mới. Kiểm tra lock/migration hai instance trên DB cách ly, timeout hữu hạn trong harness, không retry startup vô hạn.
- Nếu SQL lỗi: xác minh không còn DDL Git/CI dang dở, history không có success của version mới, dữ liệu cũ không đổi. Nếu đã áp dụng thành công thì rollback code chỉ khi tương thích schema thêm mới; sửa schema bằng forward migration. Không drop bảng đã có dữ liệu để “undo”; cần restore có quy trình và người vận hành chịu trách nhiệm.

## 4. Trình tự triển khai và bàn giao

| Bước | Công việc | Kết quả kiểm chứng |
|---|---|---|
| K1 — Chốt thiết kế | Review các sai khác mục 3.2, tenant routing, identity pipeline/failure, secret ref, phạm vi một task/link và giới hạn text | Data dictionary/ERD và expected thống nhất; không còn hai tên bảng hoặc hai nghĩa pipelineId |
| K2 — Chọn baseline | Khi triển khai code, dùng branch T-020 theo workflow; kiểm tra migration nhánh tích hợp và DB; chọn V6 hoặc version hợp lệ tiếp theo | Danh sách version/checksum trước đổi; không sửa V1–V5 |
| K3 — Viết DDL | Tạo năm bảng, constraint/index theo mục 3; đặt tên constraint rõ, đúng thứ tự FK | Một migration mới, không thêm quan hệ xuyên module, không dependency mới không cần thiết |
| K4 — Test migration/schema | Thêm test tại `app/src/test/java/io/devflow/gitci/schema/`; dùng Flyway Java API + JDBC + PostgreSQL 17 | Bộ MG, SC, RT, IV có report thực |
| K5 — Test ranh giới/lỗi/tải | Cascade, soft refs, concurrency, error rollback, query plan, backup/restore clone và hồi quy | Bộ DL, CC, PF, QA; Actual và evidence từng ca |
| K6 — Review/bàn giao | Cập nhật data dictionary/roadmap version, thống nhất với T-021–T-026 và Tiến; review PR | Checklist dựa trên evidence; không đánh Done chỉ vì SQL compile/build qua |

Artifact đã tạo: `V6__gitci_schema.sql`; bốn lớp `GitCiMigrationIntegrationTest`, `GitCiSchemaIntegrationTest`, `GitCiConcurrencyIntegrationTest`, `GitCiQueryPlanIntegrationTest` và helper `GitCiPostgresSupport`, tại `app/src/test/java/io/devflow/gitci/schema/`. Các test gộp hoặc parameterize theo đặc tính dữ liệu, giữ ID trong tên test để truy vết 75 case plan.

## 5. Tiêu chí hoàn thành

### 5.1. Acceptance criteria gốc

- [x] **AC01 — Trello:** Flyway migrate Git/CI thành công trên PostgreSQL; tên V3 lịch sử đã được đối chiếu sang version thực tế không trùng, có history/checksum và validate PASS.
- [x] **AC02 — Trello:** Các bảng lưu vết Git hoạt động: lưu/đọc được repository → commit/PR/pipeline → failure, metadata và liên kết nullable đúng, update/delete/conflict/rollback có test.

Đánh dấu trên là bằng chứng kỹ thuật local trong tài liệu; không đồng nghĩa checklist Trello đã được cập nhật hoặc ticket đã Done.

### 5.2. Điều kiện nghiệm thu cụ thể

- [x] **AC03:** Fresh DB, DB ở V2 và DB ở V5 nâng cấp thành công, dữ liệu Auth/Kanban/audit/version không đổi; migrate lại là no-op.
- [x] **AC04:** Metadata năm bảng đúng kiểu/null/default/PK/FK/CHECK/unique/index; chỉ có FK nội bộ Git/CI; không phụ thuộc H2 để chứng minh PostgreSQL.
- [x] **AC05:** Cùng SHA ở repo khác, PR số trùng ở repo khác, nhiều workflow và run attempt được phân biệt; cùng business key không bị nhân đôi khi concurrency.
- [x] **AC06:** Xóa/cascade/rollback không tác động repo khác hoặc dữ liệu Auth/Board; soft refs, privacy cleanup và log lifecycle được ghi rõ giới hạn và người nhận bàn giao.
- [x] **AC07:** Không lưu key HMAC plaintext trong schema/fixture/log; TEXT có bound; thiếu tác giả/log/task vẫn lưu được theo nullability đã chốt. Không tuyên bố DB đã thực thi auth/redaction.
- [x] **AC08:** Có query plan/số đo trên dataset đủ lớn, pagination ổn định và tenant filter; không lấy thời gian build thay bằng chứng performance DB/API.
- [x] **AC09:** Schema collision, duplicate version, checksum drift và migration failure bị phát hiện; có bằng chứng rollback và hướng dẫn phục hồi không phá dữ liệu.
- [ ] **AC10:** `:verifyModuleBoundaries`, `check`, hồi quy schema/Kanban đều PASS; không test nào bị skip do thiếu Docker rồi được tính là thành công; review và tài liệu thống nhất.

## 6. Kế hoạch testing — Expected và Actual

### 6.1. Môi trường, fixture và cách ghi kết quả

- JDK 17, Gradle wrapper, JUnit 5, Testcontainers hiện có; PostgreSQL `17-alpine` hoặc image digest được ghi lại. Docker phải sẵn sàng. Không dùng H2/create-drop thay production migration.
- Migration tests dùng Flyway Java API vì chưa có Gradle Flyway plugin/task migrate. Mỗi test có schema/database cách ly; bật Flyway và `ddl-auto: validate` khi test startup, tắt auto-baseline trong harness.
- Fixture: W1/W2; R1/R2 cùng workspace và R3 workspace khác; một provider remote ID xuất hiện ở W1 và W2; SHA40/SHA64, PR số 12 ở hai repo; run 100 attempt 1/2 và run 101 cùng SHA; hai job lỗi, task UUID hợp lệ/NULL/không tồn tại. Dữ liệu người dùng giả, không gọi GitHub hoặc AI thật.
- Invalid input tests chạy parameterized theo **từng cột** và từng biên, không chỉ một cột đại diện. Mỗi thao tác dự kiến lỗi được bọc transaction/savepoint độc lập và kiểm SQLSTATE/constraint; không assert nguyên chuỗi lỗi phụ thuộc locale.
- `Expected` là điều kiện phải chứng minh; `Actual` ghi PASS/FAIL/BLOCKED/SKIPPED cùng timestamp, commit, môi trường, số đo và evidence. Actual được cập nhật từ report T-020 tại mục 8; không kế thừa kết quả PASS từ T-010 hoặc T-018.
- Thiếu Docker/JDK/dependency là BLOCKED sau khi xác định nguyên nhân; không skip rồi đánh AC hoàn thành. Xóa fixture chỉ trên schema/database có marker do test tạo, xác minh target trước cleanup.

### 6.2. Bộ MG — Migration, nâng cấp và phục hồi

| ID | Dữ liệu / thao tác | Expected | Actual |
|---|---|---|---|
| MG01 | DB rỗng, migrate production resources | V1–V5 và migration Git/CI áp dụng đúng thứ tự; đủ năm bảng; history mỗi version success đúng một lần | PASS — V1–V6 đúng thứ tự; đủ năm bảng và checksum. |
| MG02 | Migrate tới V2, seed Auth/board/task/comment/tag rồi nâng lên latest | Dữ liệu từng cột/ID/liên kết giữ nguyên; audit/version bổ sung như V3–V5; Git/CI được tạo | PASS — nâng từ V2; snapshot Auth/Kanban giữ nguyên. |
| MG03 | Migrate tới V5, seed task version > 0, audit MOVE và dữ liệu Auth/Board rồi nâng cấp | Count và giá trị/checksum fixture trước-sau không đổi; không reset task version/audit | PASS — nâng từ V5; task version 7 và audit MOVE giữ nguyên. |
| MG04 | Sau migrate, gọi migrate lần 2 và restart | Không migration mới; không thêm bảng/index/history trùng; dữ liệu Git/CI đã seed còn nguyên | PASS — migrate lại 0 migration; dữ liệu và history không đổi. |
| MG05 | Validate history trước/sau nâng cấp | Version/checksum file khớp history; không sửa checksum V1–V5; validate PASS | PASS — validate/checksum các version khớp. |
| MG06 | Hai SQL cùng version trong resource copy cách ly | Flyway từ chối, không ghi success cho version trùng; production resources không bị sửa | PASS — resource V6 trùng bị từ chối. |
| MG07 | Giả checksum khác của migration đã áp dụng trên test DB | Validate FAIL, không tự repair/bỏ qua | PASS — checksum V6 bị đổi làm validate thất bại. |
| MG08 | Tạo trước một bảng Git/CI sai cấu trúc ở DB V5 | Migration FAIL rõ; không dùng IF NOT EXISTS để báo thành công; bảng cũ và dữ liệu V5 nguyên vẹn | PASS — repositories sai cấu trúc gây lỗi; dữ liệu V5 nguyên vẹn. |
| MG09 | Bản copy migration mới chèn lỗi sau CREATE TABLE/INDEX đầu tiên | DDL mới rollback, V1–V5 và fixture giữ nguyên; không có success version mới | PASS — lỗi sau DDL rollback đủ năm bảng; không V6 success. |
| MG10 | Sửa bản copy lỗi trong schema test sau MG09 và migrate lại | Chạy được một lần, không cần sửa production history/checksum; validate PASS | PASS — sửa bản copy cách ly, chạy lại và validate thành công. |
| MG11 | Schema nonempty thiếu history; baseline false và env rollout tương ứng | Từ chối nâng cấp tự động; giữ nguyên dữ liệu; chỉ ra app default hiện bật baseline cần override | PASS — schema không history bị từ chối khi baseline=false; dữ liệu cũ còn. |
| MG12 | Hai migrator chạy đồng thời cùng DB cách ly | Lock được giải quyết trong timeout harness, không schema dở dang; mỗi version success một lần | PASS — hai migrator đồng thời; mỗi version success một lần. |
| MG13 | Role migration thiếu quyền CREATE/ALTER | Dừng có lỗi, không success giả; dữ liệu trước đổi giữ nguyên; log không lộ password | PASS — role thiếu quyền bị từ chối; không V6 success. |
| MG14 | Backup DB V5, restore clone, migrate clone và so sánh | Restore dùng được, dữ liệu đối chiếu nguyên vẹn; version mới áp dụng được; DB gốc không bị thao tác | PASS — pg_dump/pg_restore vào clone; snapshot trước/sau upgrade khớp, DB nguồn không đổi. |

### 6.3. Bộ SC — Cấu trúc, constraint và module boundary

| ID | Dữ liệu / thao tác | Expected | Actual |
|---|---|---|---|
| SC01 | Inspect metadata năm bảng sau migrate | Tên/cột/kiểu/độ dài/nullability/default đúng mục 3.3–3.4; không có bảng Git/CI song song tên cũ | PASS — đối chiếu đủ cột, kiểu, VARCHAR length, nullable và default audit. |
| SC02 | Bỏ id/created_at/updated_at khi INSERT từng bảng | UUID hợp lệ, timestamps có giá trị; INSERT id trùng bị SQLSTATE 23505 | PASS — UUID/timestamps mặc định; PK trùng bị 23505 trên năm bảng. |
| SC03 | Inspect FK và delete rules từ pg_constraint/metadata | Commit/PR/pipeline → repo; failure → pipeline; CASCADE nội bộ; không FK tới tasks/users/workspaces | PASS — bốn FK đều nội bộ Git/CI và CASCADE; không FK Auth/Board. |
| SC04 | Inspect unique và thứ tự cột | Đủ năm business unique key mục 3; không unique SHA toàn cục hoặc unique commit_sha pipeline | PASS — đủ năm scoped unique; không global SHA unique. |
| SC05 | Inspect CHECK enum/positive/SHA/time/text size | Đúng biểu thức và đúng cột; CHECK không thay NOT NULL | PASS — enum, positive integer, SHA, time và text-byte bound được test runtime. |
| SC06 | Inspect indexes/validity | Đủ task_id, related_task_id, repo-status, repo-commit; unique backing indexes valid; không tạo index prefix trùng vô ích | PASS — index mong đợi tồn tại/valid; không index đơn trùng prefix đã bỏ. |
| SC07 | INSERT NULL vào từng NOT NULL, lần lượt từng cột | SQLSTATE 23502, không hàng lỗi nào được giữ sau rollback | PASS — UPDATE NULL lần lượt từng NOT NULL bị 23502, hàng gốc còn nguyên. |
| SC08 | INSERT UUID sai kiểu và UUID mềm hợp lệ nhưng không có bản ghi đích | Sai kiểu bị từ chối; UUID mềm hợp lệ được lưu, không truy vấn module khác | PASS — UUID sai kiểu bị 22P02; soft UUID không tồn tại vẫn lưu được. |
| SC09 | Kiểm tra dependency và production SQL/JPA imports | Không impl→impl, không JOIN/FK/repository chéo; :verifyModuleBoundaries PASS kèm review source | PASS — verifyModuleBoundaries và review SQL: chỉ có FK nội bộ, không Java production mới. |

### 6.4. Bộ RT — Round-trip nghiệp vụ ở tầng dữ liệu

| ID | Dữ liệu / thao tác | Expected | Actual |
|---|---|---|---|
| RT01 | INSERT/SELECT repository có Unicode, URL canonical và secret ref giả | Đọc đúng fields và workspace; DB không chứa key thực | PASS — metadata repo Unicode/URL/ref round-trip. |
| RT02 | Đổi name/URL cùng remote_repo_id | Vẫn một repo, child IDs/link còn nguyên; updated_at do writer cập nhật rõ ràng | PASS — rename/URL giữ ID và child; updated_at đổi khi writer cập nhật rõ. |
| RT03 | Commit có tiếng Việt, emoji, newline, Markdown, empty message | Nội dung round-trip nguyên vẹn trong giới hạn; không strip code hoặc execute nội dung | PASS — Unicode/emoji/newline/code và message rỗng lưu nguyên văn. |
| RT04 | Commit chưa link, bổ sung task_id rồi bỏ link | NULL → UUID → NULL lưu đúng; SHA/repo không đổi | PASS — task_id NULL → UUID → NULL không đổi commit identity. |
| RT05 | Tác giả thiếu name/email hoặc dữ liệu đã anonymize | Các trường nullable đọc đúng NULL, không tạo giá trị user/email giả | PASS — thiếu/ẩn danh tác giả lưu NULL, không tạo identity giả. |
| RT06 | PR OPEN → CLOSED → OPEN; PR khác OPEN → MERGED | Update cùng ID/key; phân biệt đóng và merge; DB round-trip không tự phát event/đổi task | PASS — CLOSED → OPEN → MERGED cập nhật cùng PR ID; không auto đổi task. |
| RT07 | Pipeline chưa có commit row; thêm commit row sau | Cả hai thao tác lưu được; lookup repo + SHA liên hệ đúng, không có FK cản event đến trước | PASS — pipeline ghi trước commit; lookup sau theo repo + SHA khớp. |
| RT08 | Một commit có hai workflow và attempt 1/2 của cùng run | Lưu đủ ba attempt độc lập; không overwrite lịch sử run/attempt khác | PASS — hai workflow và hai attempt không ghi đè nhau. |
| RT09 | Mỗi status allowlist của PR/pipeline, provider status/conclusion nullable | Lưu/đọc đúng enum và nguồn; UNKNOWN hợp lệ; không đổi canceled/skipped thành failure ở DB | PASS — mọi enum allowlist và provider state độc lập lưu được. |
| RT10 | Failure chỉ metadata; bổ sung snippet/reference/summary/task sau | NULL fields hợp lệ, update giữ nguyên pipeline/failure key; dữ liệu đọc đúng | PASS — failure metadata-only, bổ sung log/ref/summary/task sau. |
| RT11 | Hai job failure cùng attempt, cùng job key ở attempt khác | Hai lỗi độc lập; cùng key ở attempt khác hợp lệ | PASS — nhiều job cùng attempt và cùng job key khác attempt được phân biệt. |
| RT12 | Lưu cùng Instant qua timezone UTC và Asia/Saigon | So sánh Instant bằng nhau trong precision PostgreSQL; không assert chuỗi offset; committed_at tương lai không bị chặn tùy tiện | PASS — Instant microsecond không đổi giữa UTC/Asia-Saigon; commit tương lai được chấp nhận. |

### 6.5. Bộ IV — Dữ liệu sai, biên và chống trùng

| ID | Dữ liệu / thao tác | Expected | Actual |
|---|---|---|---|
| IV01 | Repo trùng workspace/provider/remote; khác workspace hoặc provider | Cùng bộ key bị 23505; hai trường hợp khác được lưu độc lập | PASS — trùng repo key bị 23505; khác workspace/provider hợp lệ. |
| IV02 | Cùng SHA trong cùng repo và SHA đó ở repo khác | Cùng repo bị 23505; khác repo hợp lệ, metadata/task link không lẫn | PASS — SHA trùng cùng repo bị 23505; khác repo hợp lệ. |
| IV03 | Cùng PR number trong cùng repo và khác repo | Cùng repo bị 23505; khác repo hợp lệ | PASS — PR number trùng cùng repo bị 23505; khác repo hợp lệ. |
| IV04 | Cùng external run/attempt trong cùng repo; khác attempt/repo | Cùng bộ key bị 23505; khác attempt/repo hợp lệ | PASS — run+attempt trùng bị 23505; attempt/repo khác hợp lệ. |
| IV05 | Cùng failure_key trong cùng pipeline; khác pipeline | Cùng pipeline bị 23505; khác pipeline hợp lệ | PASS — failure key trùng cùng attempt bị 23505; attempt khác hợp lệ. |
| IV06 | SHA40/SHA64 hợp lệ, 39/41/63/65 ký tự, nonhex, uppercase và toàn 0 | Chỉ lowercase hex40/64 khác 0 được lưu; sai CHECK 23514 hoặc overlength 22001; canonicalization là việc writer | PASS — lowercase hex40/64 khác 0 hợp lệ; các biên/sai dạng bị 23514/22001. |
| IV07 | pr_number/run_attempt = -1, 0, 1, INT_MAX, vượt INT_MAX | >0 trong phạm vi INT được lưu; còn lại CHECK/range error, không overflow im lặng | PASS — 1/INT_MAX hợp lệ; 0/-1 bị 23514, overflow bị 22003. |
| IV08 | Enum provider/PR/pipeline lạ, sai case, rỗng | CHECK từ chối; NULL bắt bởi NOT NULL; không fallback default giả | PASS — sai enum/case/rỗng bị 23514; NULL bắt bởi NOT NULL. |
| IV09 | Từng field bắt buộc không rỗng nhận '', spaces; từng optional field nhận NULL | Field bắt buộc bị CHECK; NULL optional được lưu; empty message riêng vẫn hợp lệ | PASS — các field có blank check từ chối rỗng/spaces; nullable và empty message hợp lệ. |
| IV10 | Từng VARCHAR ở giới hạn n và n+1 ký tự, gồm Unicode | Field tự do chấp nhận n, n+1 bị 22001; enum/SHA còn phải thỏa ràng buộc nội dung riêng; không silent truncate | PASS — toàn bộ VARCHAR n+1 bị 22001; free text/Unicode ở n hợp lệ; enum/SHA kiểm riêng. |
| IV11 | Từng TEXT bound B-1/B/B+1 byte, Unicode nhiều byte | B hợp lệ, B+1 bị CHECK; phân biệt byte với ký tự; không dùng char count thay octet_length | PASS — mọi TEXT bound ở B-1/B/B+1 byte UTF-8; vượt bound bị 23514. |
| IV12 | started/finished NULL cả hai, chỉ một có giá trị, bằng nhau, trước/sau | NULL/equal/increasing hợp lệ; finished < started khi cả hai có bị 23514 | PASS — NULL/equal/increasing hợp lệ; finished trước started bị 23514. |
| IV13 | Commit/PR/pipeline với repository UUID không tồn tại; failure với pipeline UUID không tồn tại | SQLSTATE 23503 cho từng FK; không để orphan nội bộ | PASS — từng internal FK từ chối orphan với 23503. |
| IV14 | Nội dung có quote, SQL fragment, HTML/script trong message/summary qua bound parameters | Lưu như dữ liệu trong bound; bảng/index không đổi; test này không chứng minh UI đã chống XSS | PASS — bound parameters giữ quote/SQL/script như dữ liệu; bảng không đổi, chưa nghiệm thu UI XSS. |

### 6.6. Bộ DL — Cascade, tham chiếu mềm và privacy

| ID | Dữ liệu / thao tác | Expected | Actual |
|---|---|---|---|
| DL01 | Xóa R1 có đủ commit/PR/pipeline/failure | Mọi child R1 bị xóa; R2/R3, Auth/Board và history còn nguyên | PASS — xóa R1 dọn đủ graph; graph R2 và history còn nguyên. |
| DL02 | Xóa một pipeline có nhiều failure | Chỉ failure của pipeline đó bị xóa; repo, commit, PR và attempt khác còn | PASS — xóa attempt dọn failure, giữ repo/attempt khác. |
| DL03 | Xóa một commit hoặc PR đã link task | Không xóa task/repo/pipeline; pipeline SHA vẫn giữ được dù commit row không còn | PASS — xóa commit/PR không xóa pipeline hoặc repo. |
| DL04 | Xóa một failure | Không xóa pipeline hoặc failure khác; task không đổi | PASS — xóa failure giữ pipeline và dữ liệu khác. |
| DL05 | Trong transaction xóa repo, sau đó rollback | Khôi phục đủ repo và child; không chỉ kiểm count bảng cha | PASS — rollback DELETE repo khôi phục repo và đủ child IDs. |
| DL06 | Test fixture xóa task/workspace ở module chủ sở hữu | Git/CI không tự cascade; ghi nhận UUID mềm còn lại cần cleanup event, không coi đây là privacy đã hoàn thành | PASS — xóa task/workspace không cascade Git/CI; cleanup event vẫn là phần việc sau. |
| DL07 | Update author_name/email thành NULL, snippet/reference thành NULL | Không vướng NOT NULL; IDs, links và trace CI không bị mất | PASS — anonymize author/log/ref về NULL không mất identity. |
| DL08 | Inspect DDL/fixture/report/log có giá trị secret/PII tổng hợp đánh dấu | Không có plaintext key thực; test không print payload/SQL bind; redaction/retention worker vẫn được bàn giao T-025 | PASS review DDL/fixture — chỉ dùng config ref và dữ liệu tổng hợp; không đồng nghĩa writer đã có redaction. |

### 6.7. Bộ CC — Đồng thời và atomicity

| ID | Dữ liệu / thao tác | Expected | Actual |
|---|---|---|---|
| CC01 | Hai connection INSERT cùng business key; parameterized năm bảng | Sau commit chỉ một hàng; loser nhận 23505 hoặc no-op ON CONFLICT DO NOTHING, không treo quá timeout | PASS — race INSERT trên cả năm bảng: một OK, một 23505, chỉ thêm một hàng. |
| CC02 | Hai connection INSERT các key khác nhau cùng repo | Cả hai lưu được; không serialize toàn repo không cần thiết | PASS — hai key khác nhau cùng repo đều commit. |
| CC03 | Ghi pipeline và nhiều failures trong một transaction, một failure sai FK/CHECK | Toàn transaction rollback, không giữ pipeline/failure nửa chừng | PASS — một failure sai làm rollback cả pipeline/failure graph. |
| CC04 | Lỗi insert với savepoint rồi rollback-to-savepoint | Có thể ghi bản hợp lệ sau rollback; không tiếp tục trên transaction aborted và báo PASS giả | PASS — rollback-to-savepoint rồi ghi hợp lệ và commit được. |
| CC05 | Một transaction xóa repo, transaction khác insert child, thứ tự commit đảo nhau | FK/lock giải quyết nhất quán; không orphan; kết thúc trong timeout, kiểm cả hai thứ tự | PASS — kiểm hai thứ tự delete/insert; không orphan hoặc treo quá timeout. |
| CC06 | Đọc list Git/CI W1/W2 có cùng remote ID/SHA/PR number qua query tenant-scoped | Kết quả đúng workspace qua repo chain; đây là test query mẫu, không thay API auth/IDOR test T-021/T-026 | PASS — query mẫu commit/PR/failure giới hạn đúng workspace; API auth thuộc ticket sau. |

### 6.8. Bộ PF — Truy vấn, tải và giới hạn

Fixture đề xuất: 50 repo ở ít nhất hai workspace, 50.000 commits, 5.000 PR, 10.000 attempts và 10.000 failures; phân bố task/status lệch để tránh kết luận từ dữ liệu quá đều. Ghi CPU/RAM, storage, image/digest, PostgreSQL settings, dataset, warm/cold cache và concurrency. Đây là budget kiểm thử đề xuất, chưa phải số đo hay SLA đã được xác nhận.

| ID | Dữ liệu / thao tác | Expected | Actual |
|---|---|---|---|
| PF01 | EXPLAIN (ANALYZE, BUFFERS) lookup commit/PR/run business key | Có index phù hợp; rows/results đúng; không bắt buộc planner chọn index trên fixture nhỏ, không tắt seqscan để làm đẹp | PASS — business-key lookups dùng index; có EXPLAIN ANALYZE/BUFFERS trong evidence. |
| PF02 | Query task commits/PR/failures và CI repo-status/repo-commit, có tenant predicate | Kết quả đúng, index hỗ trợ; ghi execution/rows/buffers và p50/p95 qua nhiều lượt | PASS — task/repo queries trả dữ liệu đúng; có plan/buffers và benchmark DB-only. |
| PF03 | Phân trang stable order theo created_at + id với timestamp bằng nhau | Không trùng/mất hàng trên snapshot cố định; có tie-breaker; chưa quy định API pagination T-026 | PASS — 1.000 commit cùng timestamp phân trang đủ, không trùng nhờ tie-breaker id. |
| PF04 | Benchmark lookup/list đơn, 20 connection trong giới hạn pool harness | Mục tiêu DB-only P95 < 100ms trên fixture đã chốt; ghi số thực, không thay SLA CRUD API < 200ms của sản phẩm | PASS đo local — 320 mẫu/20 connection; P50 2,6793ms, P95 34,6611ms; không phải SLA API. |
| PF05 | Migrate clone có dữ liệu V5 và CASCADE xóa repo nhiều child | Ghi duration, lock wait, child count; mục tiêu migration < 60s trên fixture chuẩn cần thống nhất máy; không mất dữ liệu ngoài scope | PASS đo local — V5→V6 với 5.000 task: 405,8776ms; cascade repo 1.000 commit: 47,4092ms. |
| PF06 | Nhiều TEXT gần bound, insert/delete lặp, query lại | Không vượt bound, không leak connection; ghi kích thước bảng/index và chi phí; không tuyên bố soak/retention đã giải quyết | PASS — TEXT sát bound và 20 lượt insert/delete; pool active=0; lưu kích thước bảng/index. |

### 6.9. Bộ QA — Startup, hồi quy và bàn giao

| ID | Dữ liệu / thao tác | Expected | Actual |
|---|---|---|---|
| QA01 | App startup với PostgreSQL, Flyway on, ddl-auto validate | Context lên được, schema latest, không dựa create-drop; H2 smoke riêng không dùng thay bằng chứng | PASS — Spring Boot/PostgreSQL/Flyway/ddl-auto validate trong test tích hợp app. |
| QA02 | KanbanMigrationIntegrationTest và KanbanSchemaIntegrationTest | Các test cũ qua khi thêm migration; vẫn kiểm V1/V2 theo ý nghĩa riêng, không bỏ assertion để che lỗi | PASS — Kanban migration 6 test và schema 55 test, không lỗi/skip. |
| QA03 | Hồi quy board persistence/API, task version/audit, module tests qua check | Không phá fixture/query/mapping T-010–T-018; full check PASS, report không có lỗi/skip môi trường bị che | PASS — check toàn backend; report 267 test, 0 fail/error/skip; module không đổi dùng UP-TO-DATE. |
| QA04 | :verifyModuleBoundaries + review schema/code | Không dependency/import/query production xuyên module; Git/CI dữ liệu nằm đúng ownership | PASS — verifyModuleBoundaries và review ownership SQL. |
| QA05 | Review data dictionary, version mapping, schema/secret/CI identity và Actual | Tài liệu thống nhất SQL; AC gốc có evidence; ticket sau biết đúng giới hạn, không ghi đã có webhook/UI | PASS tự đối chiếu EN/VI/ERD/version; peer review riêng tại QA06. |
| QA06 | Review PR và tập evidence trên commit bàn giao | Có commit FE/BE tương ứng, report XML/HTML, history/metadata/query plan và lỗi/retest; chỉ Done khi mọi AC đạt | CHỜ REVIEW — chưa commit/PR/peer review/merge; test summary và query evidence local đã có. |

### 6.10. Truy vết tiêu chí và lệnh chạy dự kiến

| Tiêu chí | Bộ test chứng minh chính |
|---|---|
| AC01, AC03, AC09 | MG01–MG14, QA01–QA02 |
| AC02, AC04 | SC01–SC08, RT01–RT12, IV01–IV14 |
| AC05 | IV01–IV05, RT07–RT11, CC01–CC05 |
| AC06, AC07 | DL01–DL08, SC03/SC08, IV10–IV14, CC06 và review các giới hạn mục 3.6 |
| AC08 | PF01–PF06 |
| AC10 | SC09, QA01–QA06, report các bộ test trên |

Chạy từ `backend/`; các lớp test bên dưới đã có trong repository:

```powershell
.\gradlew.bat :app:test --tests 'io.devflow.gitci.schema.GitCiMigrationIntegrationTest'
.\gradlew.bat :app:test --tests 'io.devflow.gitci.schema.GitCiSchemaIntegrationTest'
.\gradlew.bat :app:test --tests 'io.devflow.gitci.schema.GitCiConcurrencyIntegrationTest'
.\gradlew.bat :app:test --tests 'io.devflow.gitci.schema.GitCiQueryPlanIntegrationTest'
.\gradlew.bat :app:test --tests 'io.devflow.board.schema.*'
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat check
```

Không có Gradle task `flywayMigrate` được cấu hình sẵn; fresh/upgrade/validate chạy qua test Flyway Java API. Report tại `backend/app/build/reports/tests/test/index.html` và `backend/app/build/test-results/test/`; sao lưu report cần bàn giao vì lọc test lượt sau có thể thay report lượt trước. Benchmark tự xuất `backend/app/build/reports/gitci/t020-query-evidence.txt`.

Chỉ cần kiểm tra frontend build/lint nếu diff triển khai thực tế có ảnh hưởng frontend; SQL-only không chứng minh tính năng UI và không cần dựng frontend để test constraint. Thứ tự chạy: MG/SC → RT/IV → DL/CC → QA hồi quy → PF/rollout clone → review. Dừng nghiệm thu nếu phát hiện mất dữ liệu, trùng version, lẫn tenant hoặc lộ secret; sửa và retest có evidence.

## 7. Rủi ro, quyết định và phụ thuộc bàn giao

| Rủi ro / điểm cần phối hợp | Kiểm soát / người nhận |
|---|---|
| Version migration ở branch tích hợp khác checkout | Xuân kiểm lại version/history ở K2; V6 chỉ là dự kiến, không tái sử dụng V3 |
| Một remote repo ở nhiều workspace, webhook lookup chọn nhầm | T-021 cần integration routing và xác thực secret đúng scope; không SELECT-first theo remote ID |
| T-XXX không có cột task key trong schema Board hiện tại | T-022 cần hợp đồng phân giải task/workspace bằng typed event theo AGENTS; T-020 không tự thêm task_code hoặc query tables Board |
| pipelineId String hiện mơ hồ nội bộ/external, thiếu tenant/attempt | T-023/T-025 và Tiến thống nhất event/lookup identity trước publish; khuyến nghị dùng UUID attempt nội bộ được biểu diễn String, không dùng run ID global |
| Secret ref khác data dictionary cũ | Xuân + đầu mối T-021 chốt resolver và tài liệu; không quay về lưu key plaintext để khớp tên cũ |
| Cùng commit/PR nhắc nhiều task | Baseline một link; không chọn task đầu tiên tùy tiện; T-022 quyết định reject/unresolved hoặc migration bảng nối riêng |
| Event trùng/đảo thứ tự hoặc process chết sau commit | Unique không chống lặp side effect; T-023/T-025 có freshness/idempotency; outbox cần scope riêng trước bảo đảm delivery bền vững |
| Tên job/message/log vượt bound hoặc thiếu metadata | Writer xử lý có chủ đích: reject/truncate display có báo cáo/reference; không cắt business key, không tạo email/task giả |
| Xóa workspace/user và retention blob chưa có workflow | Đầu mối Auth/GitCI/AI thống nhất cleanup event, anonymization, object cleanup trước release các luồng xóa; ghi rõ chưa có trong T-020 |
| Smoke H2 qua nhưng PostgreSQL lỗi hoặc Docker bị skip | Bắt buộc report PostgreSQL thật; skip/BLOCKED không thay PASS |

## 8. Kết quả thực tế và mẫu cập nhật nghiệm thu

### 8.1. Kết quả triển khai và kiểm chứng local

| Hạng mục | Kết quả thực tế ngày 15/10/2026 |
|---|---|
| Đọc task/tài liệu/code | Đã đối chiếu T-020, DATABASE_DESIGN, architecture/product/master plan, migrations V1–V5, GitCI stubs và mẫu test PostgreSQL |
| Đọc Trello | Đọc thành công card T-020 và danh sách task được giao sau khi cấp quyền mạng; card đang Backlog, hai AC chưa check |
| Contract đã áp dụng | V6 thay tên V3 lịch sử; unique đúng scope repo/attempt/failure; secret ref, nullable author, bounded TEXT và log reference |
| Artifact triển khai | Migration V6, bốn lớp integration test và helper; cập nhật data dictionary EN/VI, ERD, master plan EN/VI, task/plan; không thêm service/API/entity hoặc dependency |
| PostgreSQL thật | PostgreSQL 17.11, image postgres:17-alpine; image ID trong query evidence; Testcontainers cách ly, không migrate database dùng chung |
| Test Git/CI | 84 test: migration 11, schema 62, concurrency 10, query/benchmark 1; 0 failure/error/skip |
| Backend cuối | `gradlew.bat check :verifyModuleBoundaries` PASS, 2m22s; app task chạy 163 test gồm 84 Git/CI; report toàn backend 267 test, 0 failure/error/skip; module không đổi dùng UP-TO-DATE |
| Startup/hồi quy | Spring Boot + Flyway on + ddl-auto validate trên PostgreSQL PASS; Kanban migration 6 và schema 55 test PASS; hồi quy Board/Auth/Common thuộc check |
| Frontend | `npm run build` PASS, tsc và Vite; không sửa frontend trong T-020 |
| Benchmark cuối | 50 repo, 50.000 commit, 5.000 PR, 10.000 attempts, 10.000 failures; 320 mẫu/20 connection, warm cache; P50 2,6793ms, P95 34,6611ms DB-only |
| Nâng cấp / cascade có số đo | V5→V6 với 5.000 task version 7: 405,8776ms; cascade repo có 1.000 commit: 47,4092ms; chỉ là số đo máy local |
| Ma trận 75 case | 74 case có evidence tự động/tự đối chiếu; QA06 CHỜ REVIEW. Số case plan khác số JUnit vì parameterization/gộp case và các gate review thủ công |
| Trello / Git | Branch feature/T-020-gitci-schema; chuyển In Progress bị API 401; không cập nhật AC Trello; chưa commit/push/PR/peer review/merge trong lượt triển khai này |

Bằng chứng được lưu tại [test-summary.json](evidence/T-020/test-summary.json) và [query-plan.txt](evidence/T-020/query-plan.txt). Summary ghi rõ baseline `4bf5d17` + working tree, lệnh chạy, số test và cache freshness. Log đo lúc 15:57 ngày 15/10/2026 (Asia/Saigon), JDK 17.0.19, Windows 11, 12 CPU logical, RAM host khoảng 25,4GB; cấu hình PostgreSQL và JVM heap có trong evidence. Không dùng số DB-only để tuyên bố API/GitHub end-to-end đạt SLA.

JUnit HTML local: [app test report](../../backend/app/build/reports/tests/test/index.html); XML trong `backend/app/build/test-results/test/`. Report build là artifact local có thể bị ghi lại ở lần chạy sau; hai file evidence trong docs lưu snapshot của đợt nghiệm thu này.

**Các lỗi trong quá trình làm và retest:** Lượt đầu có 72/73 test fail do harness nối thêm `?currentSchema` vào JDBC URL vốn đã có query string, khiến query chạy sai search_path. Đã sửa chọn `?`/`&` đúng. Lượt sau còn 2/83 test fail vì đếm cả dòng tạo schema không có version của Flyway như migration; đã lọc version NOT NULL. Sau sửa, bộ Git/CI 84/84 PASS và full check cuối PASS. Các lỗi này thuộc harness/assertion, không sửa/repair history DB dùng chung để làm test qua.

### 8.2. Mẫu điền Actual sau mỗi lần triển khai/test

| Lần chạy / timestamp | Commit + migration version + môi trường | Test IDs và subcases | Actual / số đo | Evidence / lỗi / retest |
|---|---|---|---|---|
| 15/10/2026, lượt kiểm tra cuối | 4bf5d17 + working tree, V6, JDK17/PostgreSQL17 Testcontainers | 84 JUnit Git/CI, hồi quy app; 74 case plan xác nhận local | PASS; P95 DB 34,6611ms, V5→V6 405,8776ms | evidence/T-020; QA06 còn chờ PR/peer review |

Đính kèm history version/checksum đã che thông tin kết nối, metadata constraints/indexes, before/after fixture, test XML/HTML, query plans/số đo và biên bản review. Nếu ca thất bại, ghi expected, actual cụ thể, nguyên nhân, commit sửa và lần retest; không thay “CHƯA CHẠY” bằng PASS dựa trên việc đã có plan.

### 8.3. Tài liệu kỹ thuật đối chiếu thêm

- [Flyway versioned migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html): quy tắc version/checksum và migration đã áp dụng.
- [PostgreSQL 17 — Constraints](https://www.postgresql.org/docs/17/ddl-constraints.html): đối chiếu CHECK, NOT NULL, unique và FK khi viết DDL/test.
- [GitHub REST — Workflow runs](https://docs.github.com/en/rest/actions/workflow-runs): đối chiếu run ID, run_attempt, status/conclusion và log theo attempt; các mapping vào status DevFlow ở trên là đề xuất thiết kế.

Các nguồn được đối chiếu ngày 15/10/2026; khi triển khai phải kiểm API/version dependency thực tế đang dùng. Chỉ hoàn thành T-020 khi migration, dữ liệu và các tiêu chí nghiệm thu có kết quả thực chứng.
