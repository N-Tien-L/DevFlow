# T-025 — Kế hoạch tiếp nhận lỗi CI và cung cấp dữ liệu cho AI

**Milestone 3 · Phụ trách:** Xuân Lê (@xuanle2). **Ngày khảo sát:** 19/11/2026, Asia/Saigon.

**Trạng thái:** Triển khai hoàn tất trên nhánh `feature/T-025-ci-failure-webhook` (xếp trên T-024 `1e67642`), bao gồm toàn bộ test tự động và smoke GitHub thật (`E2E-01/02/03` PASS ngày 24/11/2026). Card đang ở **In Progress**; hai acceptance criteria gốc giữ nguyên chờ commit/PR và chốt phối hợp T-031/V14 trước khi chuyển In Review / Done.

**Cập nhật triển khai ngày 24/11/2026:** Toàn bộ implementation, migration V18, 8 bộ test chuyên biệt T-025 (111 test case), full backend check (936 test, 0 failure/error), `frontend/ npm run build` (365 modules), diễn tập rollback độc lập `devflow-backend:t024` trên schema V18 và smoke GitHub thật có fine-grained PAT Actions read (`READY` + `[redacted]` + `PUBLISHED` + restart bền vững) đều đã PASS. Đã đối chiếu trực tiếp PR #37 T-031 đã merge vào `origin/develop` (`d1b10e0`). Theo dõi chi tiết ở [bằng chứng thực thi](15_tiep_nhan_loi_ci_results.md) và [runbook](15_tiep_nhan_loi_ci_operations.md).

[Yêu cầu gốc](15_tiep_nhan_loi_ci.md) · [Trello T-025](https://trello.com/c/J7oKhrJB/30-t-025-implement-ci-failure-webhook-receiver-and-publish-cifailuredetected) · [Tổng quan nhiệm vụ](00_nhiemvu.md) · [Plan dữ liệu GitCI](10_thiet_ke_du_lieu_git_ci_plan.md) · [Plan webhook an toàn](11_tiep_nhan_webhook_github_an_toan_plan.md).

## 1. Mục tiêu, hiện trạng và phạm vi

### 1.1. Kết quả cần đạt

Khi một workflow GitHub Actions kết thúc với `conclusion=failure`, DevFlow ghi nhận đúng lần chạy, lấy được phần log lỗi đã loại thông tin nhạy cảm, lưu bền vững và phát `ci.failure_detected` để AI có thể xử lý. GitHub gửi lại thông báo, nhiều backend cùng xử lý hoặc hệ thống khởi động lại không được tạo thêm một lỗi nghiệp vụ cho cùng lần chạy.

Hai tiêu chí gốc được đọc trực tiếp từ Trello:

- Lưu thông tin thất bại vào bảng `ci_failures`.
- Phát event `ci.failure_detected` sẵn sàng cho module AI xử lý.

Các policy, giới hạn và tên component bên dưới là **thiết kế đề xuất cho T-025**, chưa phải chức năng đang hoạt động. Các ngưỡng phải được đưa vào cấu hình có validation và kiểm thử ở biên; thay đổi khi triển khai cần cập nhật plan cùng bằng chứng.

### 1.2. Kết quả đọc dự án

Baseline khảo sát: checkout `feature/T-024-git-activity-event-listener`, commit `1e67642`. Đây là baseline có các thay đổi T-021 đến T-024; cần kiểm tra lại trạng thái tích hợp vào `develop` trước khi tách branch triển khai T-025.

| Nguồn hiện tại | Điều đã quan sát từ mã nguồn | Ý nghĩa với T-025 |
|---|---|---|
| [GitHubWebhookController](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitHubWebhookController.java), [GitHubWebhookService](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitHubWebhookService.java) | Có endpoint GitHub, giới hạn body/concurrency, HMAC theo binding, đối chiếu repository ID, quota, JSON parser chặt, receipt chống trùng. `workflow_run` hợp cấu trúc vẫn bị trả `503 WEBHOOK_HANDLER_NOT_READY`. | Mở rộng đường nhận đã được bảo vệ; không tạo endpoint bỏ qua HMAC hoặc dùng controller scaffold làm đường nhận thứ hai. |
| [GitWebhookIngestion](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitWebhookIngestion.java) | Đã prepare/persist push và PR; receipt và dữ liệu nghiệp vụ ghi cùng transaction, khóa repository. | Thêm plan workflow; việc tải log không được nằm trong transaction này. |
| [V6 GitCI schema](../../backend/app/src/main/resources/db/migration/V6__gitci_schema.sql) | Có `ci_pipelines`, unique `(repository_id, pipeline_id, run_attempt)`; `ci_failures.pipeline_id` là FK UUID đến **attempt nội bộ**. Snippet tối đa 16.384 byte; error summary tối đa 8.192 byte. Commit SHA không có FK đến commit. | Tận dụng bảng hiện có, phân biệt provider run ID và UUID; workflow có thể tới trước push. |
| [V13 receipts](../../backend/app/src/main/resources/db/migration/V13__github_webhook_receipts.sql) | Receipt chống trùng delivery và digest, được dùng bởi webhook service. | Chống trùng vận chuyển chưa thay được unique nghiệp vụ của run/attempt, đặc biệt sau khi receipt hết hạn. |
| [GitDomainEventOutbox](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitDomainEventOutbox.java), [V16](../../backend/app/src/main/resources/db/migration/V16__git_domain_event_outbox.sql), [V17](../../backend/app/src/main/resources/db/migration/V17__git_task_transitions.sql) | Outbox hiện phục vụ ba `git.*` event; schema bắt buộc task ID và giới hạn event type. Có lease, claim token, retry, worker pause. Migration lớn nhất tại baseline là V17. | Dùng lại nguyên tắc vận hành, không chèn CI event vào bảng Git bằng task UUID giả. |
| [CiFailureDetectedEvent](../../backend/common/src/main/java/io/devflow/common/event/CiFailureDetectedEvent.java) | Record implements `DevFlowEvent`, có `pipelineId`, `rawLogReference`, repo, SHA, thời điểm; chưa có event ID hoặc tenant provenance. | Cần chốt identity, log reference và dedup contract với Tiến trước khi đổi record. Không gửi toàn bộ log trong event. |
| [GitCiApi](../../backend/gitci-api/src/main/java/io/devflow/gitci/api/GitCiApi.java), [CiFailureInfo](../../backend/gitci-api/src/main/java/io/devflow/gitci/api/CiFailureInfo.java), [GitCiService](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitCiService.java) | Có interface tìm failure bằng chuỗi pipeline ID nhưng implementation còn ném `UnsupportedOperationException`; DTO chỉ có reference, chưa có nội dung log. | Chỉ phát reference mà không có cơ chế đọc thực tế chưa đạt tiêu chí “AI xử lý được”. |
| [AI listener](../../backend/ai-impl/src/main/java/io/devflow/ai/internal/AiEventListeners.java) | CI listener tại checkout đang log và TODO. Đây không phải bằng chứng về code T-031 trên branch/PR khác. | Có contract test với consumer giả; tích hợp T-031 là gate riêng, không coi log listener là AI đã phân tích. |
| [GitTaskReferenceCleanup](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitTaskReferenceCleanup.java) | Workspace deletion xóa repositories theo typed event; task cleanup chưa xử lý `ci_failures.related_task_id`. | Dữ liệu CI mới phải theo cascade của repo; task link nếu bổ sung sau cần cleanup riêng. |
| [GitCiPostgresSupport](../../backend/app/src/test/java/io/devflow/gitci/schema/GitCiPostgresSupport.java), [schema tests](../../backend/app/src/test/java/io/devflow/gitci/schema/GitCiSchemaIntegrationTest.java) | Có PostgreSQL 17 Testcontainers, test unique attempt, giới hạn byte, CI trước push, cascade. Helper mặc định phục vụ V6. | Test mới phải migrate đến version mới nhất; test schema cũ không chứng minh webhook/worker CI đã chạy. |

Tài liệu đối chiếu: [ARCHITECTURE](../ARCHITECTURE.md) §2, §4, §6, §9.2, §10; [PRODUCT_SPEC](../PRODUCT_SPEC.md) §5.2a, §8; [IMPLEMENTATION_PLAN](../IMPLEMENTATION_PLAN.md) Phase 3–4; [quy tắc module](../../.agents/rules/module-boundaries.md).

**Điểm cần thống nhất:** master plan và Javadoc scaffold còn nói AI gọi trực tiếp `GitCiApi`; kiến trúc §4 và workspace instructions hiện tại yêu cầu giao tiếp qua typed event. T-025 chọn request/response event đồng bộ để đọc log. Cần cập nhật phần tài liệu/Javadoc liên quan khi triển khai, không âm thầm để hai hợp đồng mâu thuẫn.

### 1.3. Phạm vi và phụ thuộc

- **Trong phạm vi:** nhận `workflow_run`; phân loại kết quả; lưu attempt/failure; hàng đợi lấy log; GitHub read client; lọc log; durable CI event; hợp đồng đọc log; migration, metrics, test, runbook.
- **Ngoài phạm vi:** gọi LLM/tạo summary T-031; tự chuyển cột theo CI; badge/UI T-026; tìm nguyên nhân gốc hoặc gom lỗi theo nội dung; tự chạy lại workflow; GitLab; backfill toàn bộ lịch sử; thêm broker/object storage bắt buộc.
- **Phụ thuộc nền:** T-020 schema và T-021 webhook. Cơ chế durable worker T-023 là mẫu tham khảo, không phải yêu cầu task phải có Git association. Chưa có task/PR liên quan vẫn phải ghi nhận CI failure.
- **Phối hợp Tiến/T-031:** chốt định danh attempt, event provenance, quyền đọc, log readiness và giới hạn đầu vào. Không chỉnh code trên branch của người khác hoặc đánh dấu T-031 hoàn thành từ kết quả T-025.

## 2. Kế hoạch nghiệp vụ — luồng bằng ngôn ngữ

### NV-01. Một lần build/test thất bại

Developer push hoặc mở PR; GitHub chạy workflow và báo kết thúc thất bại. DevFlow xác minh thông báo thuộc repository đã kết nối, ghi nhận workflow, commit, lần chạy và lỗi. Hệ thống tiếp nhận nhanh, sau đó tải log ở nền. Khi có log đã lọc và dùng được, hệ thống thông báo cho AI bằng một tham chiếu ổn định. AI có thể đọc nội dung tương ứng; việc tạo bản tóm tắt do T-031 phụ trách.

### NV-02. Workflow thành công hoặc có kết quả khác

Workflow thành công được ghi nhận là thành công, không tạo lỗi hay yêu cầu AI phân tích. Hủy, bỏ qua, trung lập, timeout và các kết quả khác được lưu riêng theo trạng thái thực tế; trong T-025 **chỉ `failure` kích hoạt luồng phân tích**. Timeout không tự đổi thành failure. Nếu muốn mở rộng các kết quả cần AI xử lý, phải cập nhật policy và test trước.

### NV-03. Chạy lại workflow

Lần chạy lại là attempt mới của cùng workflow run. Lần đầu thất bại và lần sau thành công phải được giữ riêng; thành công không xóa lỗi lịch sử. Hai attempt thất bại có thể tạo hai yêu cầu phân tích khác nhau. Không lấy log “mới nhất” của run để gắn vào attempt cũ.

### NV-04. GitHub gửi lặp, gửi chậm hoặc gửi sai thứ tự

Gửi lại cùng thông báo không tạo thêm failure hay event nghiệp vụ. Thông báo đến trước push vẫn được lưu bằng commit SHA. Thông báo cũ không ghi đè kết quả mới đã xác nhận của cùng attempt; các attempt độc lập không ghi đè nhau. Nội dung mâu thuẫn với kết quả cuối cùng đã lưu được báo lỗi an toàn và cần kiểm tra, không tự chọn kết quả có lợi hơn.

### NV-05. Không tải được log ngay

Lỗi CI vẫn được ghi nhận dù GitHub chậm hoặc log chưa xuất hiện. Hệ thống phân biệt đang chờ, chờ thử lại, thiếu quyền và log không còn lấy được. Không gửi log rỗng hoặc tự tạo error trace để AI đoán. Có giới hạn thử lại và hướng dẫn phục hồi; sau khi khắc phục credential có thể thử lại công việc cũ mà không tạo failure mới.

### NV-06. Log nhiều job hoặc chứa dữ liệu nhạy cảm

Một attempt có nhiều job lỗi được biểu diễn bằng một failure tổng hợp và một event, kèm đoạn log phân bổ theo job. Hệ thống ưu tiên ngữ cảnh bước lỗi trong giới hạn lưu trữ; ghi rõ nếu đã cắt bớt. Secret, token và dữ liệu nhạy cảm nhận diện được phải bị loại trước khi lưu hoặc cung cấp cho AI. Log chỉ là dữ liệu không đáng tin cậy, không phải chỉ dẫn để hệ thống thực thi.

### NV-07. Dữ liệu bị xóa hoặc worker tạm dừng

Khi repository/workspace bị xóa, log và công việc chờ liên quan phải được dọn theo, worker không tạo lại dữ liệu từ kết quả tải còn dang dở. Tạm dừng worker không làm mất webhook đã tiếp nhận. Khi bật lại hoặc restart, công việc chưa xong có thể tiếp tục, không cần GitHub tự gửi lại.

## 3. Kế hoạch kỹ thuật — hợp đồng và các quyết định triển khai

### KT-01. Định danh, tenant và hợp đồng AI

| Giá trị | Quy ước đề xuất |
|---|---|
| Provider run ID | `workflow_run.id`, chuỗi số nguyên dương canonical; lưu ở `ci_pipelines.pipeline_id`, không dùng làm định danh toàn cục. |
| Attempt | `workflow_run.run_attempt`, số nguyên dương; unique nghiệp vụ `(repository UUID, provider run ID, attempt)`. Không mặc định về 1 nếu payload thiếu/sai. |
| Pipeline ID trong event mới | Chuỗi UUID `ci_pipelines.id`, định danh đúng attempt. Ghi rõ thay đổi semantics; không đoán external ID hay latest attempt khi nhận chuỗi legacy. |
| Failure | Một row/attempt, `failure_key=workflow-run`, `stage_name=workflow`, `job_name=aggregate`. Không gom theo SHA hoặc nội dung log. |
| Event ID | UUID của CI outbox row, giữ nguyên qua retry/restart; unique một `ci.failure_detected` cho một pipeline attempt. |
| Log reference | `ci-log:<failure UUID>`, opaque internal reference tới snippet đã lọc trong DB; không phải URL tải GitHub, file path hoặc credential. |
| Provenance | `repositoryId`, `workspaceId`, provider run ID, run attempt và provider occurrence time snapshot từ binding/attempt đã xác minh. Không lấy workspace từ JSON. |

Mở rộng record `CiFailureDetectedEvent` trong `common` với event ID và provenance; giữ source compatibility cho constructor cũ khi khả thi, nhưng constructor thiếu provenance không được cấp quyền đọc log. Dùng `implements DevFlowEvent` theo mã hiện tại. Kiểm tra tất cả producer/consumer và PR T-031 trước khi thay đổi; source compatibility không đồng nghĩa behavior compatibility.

Đề xuất typed `CiFailureLogReadRequestedEvent` và response immutable trong `common`, xử lý đồng bộ trên publishing thread: đầu vào gồm correlation ID, pipeline UUID, failure reference, workspace, giới hạn byte và authorization context; đầu ra có `READY / NOT_READY / NOT_FOUND / DENIED / EXPIRED`, nội dung đã lọc, truncation và log version/digest. Phải có đúng một response; thiếu hoặc nhiều response thì fail closed, không dùng dữ liệu mặc định.

- AI xử lý hệ thống phải đối chiếu request với event/outbox provenance đã lưu của chính attempt; UUID/reference không phải bearer credential. `actorId=null` không tự cấp quyền.
- AI lookup từ chat/MCP phải mang user identity và được kiểm membership bằng cơ chế request/decision event của Auth; GitCI kiểm tenant/resource scope trước trả log. Không cho client tự chọn “system mode”.
- Handler không gọi LLM, GitHub, hoặc repository/entity của AI/Board; chỉ đọc dữ liệu GitCI đã lọc. Không thêm public REST endpoint tải log cho T-025.
- `GitCiApi.findFailure` scaffold không trở thành đường bypass: đồng bộ tài liệu, giữ nội bộ nếu còn cần tương thích, không wire consumer mới gọi trực tiếp qua module.
- Consumer T-031 dedup theo event ID/attempt trong storage riêng; bus at-least-once không bảo đảm exactly-once cho side effect LLM. Contract test T-025 dùng consumer giả, không khẳng định LLM đã chạy.

### KT-02. Nhận webhook và chuẩn hóa workflow

Mở rộng `GitHubWebhookService`/`GitWebhookIngestion` sau toàn bộ bước xác thực hiện có. Giữ HMAC trên raw bytes, khóa binding theo repository, kiểm body/media/headers, rate limit và ProblemDetail. Webhook máy với HMAC không áp Turnstile hoặc yêu cầu JWT người dùng.

1. Đối chiếu `repository.id` với remote ID của binding; không tin `repositoryId`, owner, workspace hoặc `logs_url` do payload chỉ định để cấp quyền/tải mạng.
2. Nhận action `completed`; action khác đã xác thực được ACK 204, không tạo failure/job. Với action mới chưa hỗ trợ phải ghi metric ignored, không coi là CI failure.
3. Validate ID/attempt đúng kiểu integer và giới hạn cột; SHA lowercase 40/64 hex, không toàn zero; status/conclusion, chuỗi dài và thời gian hợp lệ. `completed` nhưng status chưa completed hoặc thiếu conclusion là payload không hợp lệ. Unknown conclusion có định dạng/độ dài hợp lệ lưu `UNKNOWN`, không kích hoạt failure.
4. Nếu workflow có repository metadata bổ sung, kiểm consistency với target repository; `head_repository` có thể là fork và không phải quyền để tải từ repository fork. Run trước push vẫn hợp lệ, không tạo commit giả.
5. `head_branch=null` là trường hợp cần hỗ trợ: migration cho phép `ci_pipelines.branch` nullable, không dùng default branch để gán sai nguồn. Chuỗi có giá trị phải nonblank và <=255 ký tự; cập nhật schema tests tương ứng.
6. Dùng `run_started_at` khi có; `updated_at` là provider version và mốc quan sát kết thúc của payload completed, không giả định webhook luôn có `completed_at` hoặc coi đây là thời điểm kết thúc chính xác từng job. `finished_at` dùng mốc quan sát này, documented; không cho finished trước started. Không dùng đồng hồ nhận request để thay provider version.

| Conclusion khi completed | Status nội bộ | Tạo failure + log job |
|---|---|---|
| `failure` | `FAILED` | Có, tối đa một bộ/attempt |
| `success` | `SUCCESS` | Không |
| `cancelled`, `skipped`, `timed_out` | `CANCELLED`, `SKIPPED`, `TIMED_OUT` | Không |
| `action_required`, `neutral`, `stale` | `ACTION_REQUIRED`, `NEUTRAL`, `STALE` | Không |
| Giá trị mới hợp định dạng | `UNKNOWN` + lưu provider conclusion giới hạn độ dài | Không |

Payload invalid trả 400; HMAC/binding không hợp lệ trả 401 theo endpoint hiện tại; delivery conflict hoặc terminal snapshot mâu thuẫn trả 409; quá body 413, media 415, quota 429, DB/lock tạm thời 503. Không trả stack trace, JSON payload hoặc upstream response body cho client.

### KT-03. Transaction, ordering và chống trùng

**Transaction A — tiếp nhận:** giữ khóa repository và timeout hiện có (transaction 6s, lock 2s, statement 4s), claim receipt → upsert attempt → tạo failure `PENDING` và log-fetch job nếu failure mới → receipt COMPLETED → commit → HTTP 204. COMPLETED chỉ nghĩa đã lưu công việc, không nghĩa log/AI đã hoàn tất. ACK chỉ sau commit; lỗi trước commit rollback toàn bộ. Không gọi GitHub hay publish AI event trong A.

- Unique của run/attempt, failure key và job bảo vệ cả delivery mới với JSON/digest khác và replay sau retention receipt. Lock/unique xử lý đồng thời nhiều backend.
- Snapshot cùng attempt, cùng kết quả: chỉ nhận metadata mới hơn được phép; bằng version và cùng nội dung nghiệp vụ là no-op. Metadata mới không tạo lại job/event.
- Snapshot cũ hơn bỏ qua; cùng version nhưng khác nội dung nghiệp vụ trả 409. Kết quả terminal khác nhau trong cùng attempt cũng conflict, không đổi FAILED sang SUCCESS hoặc ngược lại bằng webhook. Rerun hợp lệ phải có attempt mới.
- Sau khi log/event bị purge, giữ marker “đã tạo event” cho attempt còn tồn tại; webhook replay không tái tạo event đã phát. Không suy ra dedup từ receipt/outbox retention ngắn hạn.

**Transaction B — claim job:** claim tối đa một job cho mỗi execution slot bằng `FOR UPDATE SKIP LOCKED`, ghi token/lease rồi commit. Bounded pool khởi điểm 2 slot/instance; không claim sẵn hàng chục job rồi để hết lease khi chờ tải.

**Ngoài transaction:** tải/chuẩn hóa/lọc log với tổng budget tối đa 30s/lần claim. Lease khởi điểm 60s lớn hơn budget xử lý; nếu thay budget phải thay lease/test tương ứng.

**Transaction C — hoàn tất log:** kiểm token, lease, attempt/tenant/repository vẫn tồn tại → ghi snippet, reference, digest, trạng thái READY → tạo CI outbox cùng transaction → DONE job. Rollback không được để log READY thiếu event row. Kết quả từ worker mất lease hoặc resource đã xóa bị loại, không insert lại parent.

### KT-04. Migration và thành phần dự kiến

Dự kiến thêm migration **V18** vì baseline đang ở V17; kiểm tra version trống lại lúc code, không sửa migration đã áp dụng.

| Hạng mục | Nội dung dự kiến |
|---|---|
| `ci_failures` | Thêm log status, safe failure code, ready/expiry time, truncation, byte count/digest; CHECK trạng thái và liên hệ READY với reference/snippet có nội dung. Unique hiện có giữ nguyên. `error_summary` chỉ là mô tả kỹ thuật đã lọc, không ghi như thể AI summary. |
| `ci_pipelines` | Cho branch nullable với CHECK nonblank khi có giá trị; marker event đã enqueue không bị mất sau purge; constraints phân biệt attempt. |
| `ci_log_fetch_jobs` (mới) | FK pipeline ON DELETE CASCADE, unique pipeline, status, attempts, next-attempt, claim token/lease, bounded error code; partial indexes cho pending/expired leases. Không lưu token hoặc URL signed. |
| `ci_failure_event_outbox` (mới) | FK pipeline/repository phù hợp cascade, stable event UUID, unique pipeline + event type, workspace snapshot, immutable payload nhỏ, state/retry/lease/token. Không bắt buộc task. Không nhúng log vào JSON payload. |
| Worker/client GitCI | `CiWorkflowIngestion`, `CiLogFetchWorker`, `GitHubActionsReadClient`, `CiLogSanitizer`, `CiFailureEventOutbox`, read-request handler và validated properties; tên có thể điều chỉnh theo cấu trúc thực tế. |
| Tests/docs | Parser/unit tests trong gitci/common; HTTP, PostgreSQL, migration, worker và contract tests trong app; runbook/results cùng thư mục xuan. |

Không thêm FK/JPA relationship tới bảng module khác; workspace/task vẫn là soft UUID. Không backfill tự động CI event từ dữ liệu lịch sử V6. Migration upgrade phải giữ nguyên rows CI cũ, không tự gán READY cho snippet/reference cũ chưa được chứng minh đã lọc.

### KT-05. GitHub client, credential và giới hạn mạng

Workflow webhook không phải nguồn log đầy đủ. Dùng API jobs và logs **của attempt cụ thể**, không endpoint run “latest”. GitHub mô tả API download trả redirect ngắn hạn; reference lưu trong DB phải thuộc DevFlow. Nguồn chính thức: [workflow run attempt logs](https://docs.github.com/en/rest/actions/workflow-runs#download-workflow-run-attempt-logs), [jobs theo attempt](https://docs.github.com/en/rest/actions/workflow-jobs#list-jobs-for-a-workflow-run-attempt).

- Credential API là credential riêng, không dùng webhook HMAC secret. Dùng fine-grained PAT/GitHub App có Actions read trên đúng repository; deployment-managed reference, không DB plaintext hoặc hard-code. Chưa cần gửi credential để hoàn thành tài liệu này.
- Tạo API URL từ repository binding đã kiểm tra và run/attempt ID hợp lệ. Đối chiếu metadata response với target run/attempt/SHA; không tải URL tùy ý trong webhook hay job list.
- API host HTTPS cố định `api.github.com`; redirect archive dùng downloader tách biệt **không có Authorization/Cookie**, không log Location/query. Chặn HTTP, userinfo, port lạ, localhost/private/link-local IP và redirect vượt allowlist host GitHub Actions log storage đã xác minh khi triển khai. Kiểm từng hop/DNS, không cấu hình `*.com` hay tin hostname qua suffix thiếu dấu chấm. Host chưa cho phép thì fail closed và có mã vận hành để cập nhật.
- Timeout đề xuất connect 3s, read 10s, tổng một claim 30s; HTTP concurrency hữu hạn, response size/page/redirect hữu hạn. Tắt redirect tự động trên client có credential. API version/header được pin theo tài liệu hiện hành và có contract test.
- Phân trang jobs: tối đa 5 trang x 100 jobs/lần; vượt giới hạn ghi truncated và áp policy lựa chọn deterministic, không vòng lặp vô hạn. Không theo Link URL sang host/path ngoài phạm vi.
- Tải attempt ZIP theo streaming: tối đa 8 MiB compressed, 32 MiB expanded tổng, 200 entries, 2 MiB/entry; giới hạn thực đo dù header/ZIP metadata khai sai. Xử lý trong memory có giới hạn, không giải nén theo entry path ra đĩa; chặn path traversal, nested archive, entry bất thường và ZIP bomb.
- Lọc job/step lỗi nếu có mapping chắc chắn từ metadata; nếu archive format không map được, dùng ngữ cảnh tổng hợp có nhãn rõ, không gán nhầm job. Lấy đoạn có thông báo lỗi và ngữ cảnh gần đó, không chỉ lấy đầu file. Chia budget công bằng giữa job, thứ tự ổn định, tổng snippet sau lọc <=16 KiB UTF-8.

### KT-06. Retry, lỗi quyền và trạng thái log

Log status đề xuất: `PENDING → FETCHING → READY`; lỗi tạm thời sang `RETRY_WAIT`, hết budget sang `FAILED`; thiếu quyền sang `PERMISSION_DENIED`; log không có/không dùng được sang `UNAVAILABLE`; hết retention sang `EXPIRED`. Trạng thái worker và log là hai thông tin khác nhau, không đồng nhất workflow FAILED với worker FAILED.

| Tình huống | Cách xử lý |
|---|---|
| Network timeout, GitHub 5xx, 429, secondary rate limit 403 có dấu hiệu throttle | Durable retry exponential 5s–300s + jitter; tôn trọng Retry-After/reset với trần trì hoãn 1h. Tối đa 8 lần; không retry lồng nhiều tầng làm tăng số request ngoài budget. |
| API 401 hoặc 403 thiếu quyền | `PERMISSION_DENIED`, dừng retry nóng; metric/cảnh báo. Requeue nội bộ sau khi sửa credential. Không đưa upstream message/token vào failure code. |
| 404 khi log có thể chưa sẵn sàng | Tối đa 3 lần chờ trong budget tổng; sau đó UNAVAILABLE. Phân biệt không tìm thấy run và chưa có archive bằng safe codes. |
| Log đã bị xóa/hết hạn, archive hỏng, rỗng hoặc toàn nội dung bị loại | UNAVAILABLE; không event READY giả. Có manual requeue nếu nguồn được phục hồi, giữ nguyên failure identity. |
| Quá giới hạn download/archive | Ngắt stream, ghi LIMIT_EXCEEDED; chỉ READY nếu có tập log đã xử lý hoàn chỉnh/an toàn và có ngữ cảnh, nếu không UNAVAILABLE. Không publish nội dung dang dở chưa được sanitize. |
| Circuit breaker mở | Reschedule có delay, không giữ thread/DB lock; không đốt hết retry trong một vòng poll. Áp Resilience4j cho GitHub calls. |
| Process chết/mất lease | Claim lại sau lease; stale token không được ghi READY, đổi lịch hoặc hoàn tất job của owner mới. |

Giới hạn backlog theo repo/toàn hệ thống và số jobs hoạt động phải có cấu hình. Khi không thể lưu thêm công việc, trả 503 trước commit, không ACK rồi bỏ job. Không giả định GitHub luôn tự redeliver; runbook phải hướng dẫn người vận hành redelivery khi việc nhận chưa commit. Với job đã commit thì worker tự phục hồi.

### KT-07. Privacy, tính toàn vẹn và retention

- Decode log có giới hạn, bỏ ANSI/control sequences; xử lý UTF-8 lỗi theo policy xác định. Chuẩn hóa trước khi detect secret, lọc trước **mọi persistence/event/application log**. Không lưu raw archive, webhook body hay signed download URL.
- Redact token patterns, Authorization headers, URL userinfo/query nhạy cảm, private keys nhiều dòng, giá trị credential cấu hình và PII theo policy dự án. Test canary synthetic cả multiline/chunk boundary; so khớp không chỉ một regex. Truncation theo byte UTF-8 sau redaction, không cắt giữa code point; `error_summary` <=8 KiB.
- Không cam kết tự nhận diện mọi secret tùy ý: chỉ cung cấp excerpt cần thiết, credential có scope tối thiểu, hạn chế quyền đọc và retention; residual risk phải được review với chủ dữ liệu trước rollout. AI tiếp tục có lớp lọc riêng ở T-031.
- Lưu digest của log **đã lọc**, không dùng digest raw log như bằng chứng không chứa secret. Event không có email, log text, token hoặc signed URL. Metrics không dùng repo/SHA/user/event ID làm tag cardinality cao.
- Retention đề xuất snippet 7 ngày, tính từ READY; không purge snippet đang có outbox chưa terminal. Consumer chậm phải xử lý EXPIRED và có metric, không coi là log rỗng. Outbox PUBLISHED purge sau 7 ngày; giữ marker dedup trên attempt, giữ FAILED để điều tra/requeue theo runbook. Metadata failure giữ theo policy repository; xóa repository/workspace cascade ngay cả khi còn pending.
- Nếu sau này ghi `related_task_id`, bổ sung `TaskReferencesRemovedEvent` cleanup và tenant validation trước khi bật. T-025 để null khi chưa có liên kết được xác minh; không suy task từ log hay chọn task đầu tiên của SHA.

### KT-08. Durable event và ranh giới transaction

Worker CI outbox dùng `FOR UPDATE SKIP LOCKED`, lease/token và tối đa 8 attempts, backoff + jitter; publish ngoài transaction claim và ngoài transaction ingest. Payload immutable có stable event ID; requeue không sinh UUID mới. Pause switch riêng cho fetch và publish; pause publish vẫn cho enqueue.

1. Chỉ enqueue khi READY, nonempty excerpt đã được sanitize và reference đọc được.
2. Trước publish kiểm resource/tenant và log chưa expired; nếu bị xóa thì kết thúc an toàn, không phát để consumer truy cập dữ liệu cũ.
3. Listener throw → retry bền vững; crash sau publish trước mark PUBLISHED → có thể gửi lại cùng event ID. Listener swallow exception/`@Async` return không chứng minh side effect AI thành công; consumer phải có inbox/retry riêng trong T-031.
4. Spring bus có thể không có listener mà vẫn return. PUBLISHED chỉ là bus đã trả về; nghiệm thu phải có consumer contract test đọc được log. Không dùng con số published làm bằng chứng “AI summary hoàn tất”.
5. Xóa có thể xảy ra giữa kiểm tra và publish; read handler luôn recheck tenant/resource, consumer phải chịu được NOT_FOUND/EXPIRED. Không thêm transaction kéo dài bao quanh LLM để cố giải quyết race này.

### KT-09. Observability và runbook

Metrics đề xuất: webhook CI accepted/ignored/conflict; log-fetch outcome, duration, retries; queue depth/oldest age; log readiness/unavailable; CI outbox enqueue/publish/fail; read denied/expired. Tags chỉ event/status/safe error code hữu hạn. Structured log có correlation/job/event UUID cần tra cứu, không body, snippet, credential hoặc upstream exception message có URL.

Health phân biệt DB/app readiness và trạng thái integration GitHub: một repo thiếu credential không kéo liveness toàn app xuống. Cảnh báo oldest pending vượt 5 phút, FAILED/PERMISSION_DENIED tăng và nhiều attempt FAILED chưa READY. Các ngưỡng là khởi điểm phải hiệu chỉnh qua load test.

Runbook cần: cấu hình secret reference; cấp Actions read; bật subscription `workflow_run`; trạng thái log; tìm job theo internal attempt UUID; pause/resume; requeue đúng job/event với token fencing; redelivery; phục hồi sau rotate/revoke credential; cleanup/retention; backup trước migration và rollback bằng image tương thích. Không cung cấp endpoint requeue công khai hoặc sửa SQL không có điều kiện state/tenant.

## 4. Thứ tự triển khai và các gate

| Bước | Công việc cụ thể | Điều kiện ra bước |
|---|---|---|
| P0 — baseline/contract | Kiểm tra dependencies đã vào develop hay phải dùng stacked PR; tạo branch `feature/T-025-ci-failure-webhook`; đối chiếu PR T-031 với Tiến; chốt ID/log reader/tenant contract và policy failure-only. | Ghi rõ baseline SHA, hợp đồng và phạm vi; chưa thay public event semantics khi consumer chưa được đối chiếu. |
| P1 — schema | Thêm migration/jobs/outbox/status/nullable CI branch; test V17 → mới và DB trống, legacy rows/constraints/cascade. | Migration không mất dữ liệu, không backfill event, unique/byte/index đúng trên PostgreSQL 17. |
| P2 — webhook | Workflow plan, validation, status map, attempt ordering; transaction A và receipt/job atomicity. | Failure creates pending job; success/nonfailure không tạo failure/event; duplicate/concurrency tests đạt. |
| P3 — log worker | Scoped credential resolver, GitHub client/redirect, attempt logs, archive limits, sanitizer, retries/circuit breaker. | Log thực sự dùng được được lưu an toàn; lỗi nguồn có state/recovery rõ, không khóa DB trong network I/O. |
| P4 — event/read | Durable publish, provenance, typed log-read handler, consumer giả và hợp đồng T-031. | Event phát sau commit, reader trả đúng log cùng attempt, tenant isolation và crash/replay tests đạt. |
| P5 — hardening | Metrics, privacy/abuse, concurrency/deletion/load, runbook và full regression. | Các AC và bộ test bắt buộc có Actual/evidence, không còn lỗi nghiêm trọng chưa xử lý. |
| P6 — rollout | Migrate/backup, cấu hình một repo thử nghiệm, fail → rerun success → redelivery → restart → credential lỗi/khôi phục. | Có HTTP, DB, sanitized log, event/consumer evidence; rollout có rollback; không tự tuyên bố cloud/AI UI đã triển khai. |

Khi bắt đầu code, chỉ dừng phần phụ thuộc đầu vào nếu thực sự thiếu: repository thử nghiệm/cho phép gây một CI failure có kiểm soát; GitHub App/PAT Actions read được người dùng đặt vào secret config local; public callback cho GitHub nếu stack chỉ có localhost; phối hợp Tiến về contract/branch T-031. Không yêu cầu người dùng gửi token vào chat. Unit/integration dùng fixture và GitHub stub vẫn tiếp tục được khi chưa có credential thật.

## 5. Tiêu chí hoàn thành

| ID | Tiêu chí nghiệm thu | Bằng chứng cần có |
|---|---|---|
| AC-01 | Webhook `workflow_run` hợp lệ, completed failure tạo đúng attempt và failure trong `ci_failures`. | HTTP thật + SQL oracle, bao gồm CI tới trước push, không có task link. |
| AC-02 | `ci.failure_detected` đúng tenant/run/attempt/SHA và stable event ID; reference cho AI đọc được excerpt đã lọc. | Event recorder + typed read response + đối chiếu DB; đây là hai tiêu chí Trello gốc được nghiệm thu cùng nhau. |
| AC-03 | Success và mọi conclusion ngoài failure không tạo failure/log job/CI failure event. | Parameterized status/action cases; rerun success không xóa attempt cũ. |
| AC-04 | Delivery replay, semantic replay, multi-instance, stale snapshot, crash/restart không nhân đôi failure hoặc logical event. | PostgreSQL concurrency/recovery tests; duplicate publish vẫn cùng event ID. |
| AC-05 | Log theo đúng attempt, có nội dung hữu ích, multi-job/truncation rõ; thiếu log/quyền không giả dữ liệu. | GitHub stub asserts request path, safe states và manual recovery; smoke repo thật. |
| AC-06 | Secret/privacy/SSRF/ZIP bomb/bounds được xử lý trước persist hoặc external side effect; đọc sai tenant bị từ chối. | Canary assertions trên DB/event/app logs; malicious inputs; authorization tests. |
| AC-07 | Transaction receipt/attempt/job và transaction READY/outbox atomic; lease fencing, retry hữu hạn và deletion race an toàn. | Fault injection, SQL row counts/state/token, worker restart tests. |
| AC-08 | Metrics/log/runbook đủ tìm, pause, requeue và xử lý credential/log expiry; bounded backlog/worker/network. | Operational test và metric samples không chứa secret/high-cardinality tags. |
| AC-09 | Migration mới không sửa migration cũ, dữ liệu legacy được giữ, không event lịch sử; module boundaries và regression đạt. | Migration reports, `:verifyModuleBoundaries`, `check`, frontend build theo workflow. |
| AC-10 | Contract với T-031 được đối chiếu; PR có validation thực tế và hạn chế còn lại. Chỉ chuyển In Review khi code/PR sẵn sàng; Done sau review/merge và DoD. | Contract compatibility result, PR URL, results/runbook, Trello cập nhật theo evidence. |

Không đánh dấu hoàn thành vì webhook trả 204, listener in log, có một row trống hoặc toàn bộ test cũ pass. Bộ test không chạy phải ghi NR/BLOCKED với lý do, không tính vào số PASS.

## 6. Kế hoạch testing — Expected và Actual

### 6.1. Quy ước, môi trường và oracle

- **U:** unit parser/policy/sanitizer với Clock và input cố định. **I:** integration PostgreSQL 17 Testcontainers, Flyway đến migration mới, HTTP thật của app và GitHub HTTP stub. **C:** contract liên module qua Spring event bus. **E:** end-to-end với repo GitHub thử nghiệm và stack Docker. **O:** vận hành/khả năng phục hồi/tải.
- Mỗi biến thể liệt kê trong một dòng phải thành testcase/parameter riêng; không pass cả dòng vì chỉ chạy một biến thể. Không dùng H2 cho unique/locks/lease/cascade/SQL query plan.
- Fixture: hai workspace A/B, hai repo cùng provider run ID để thử scope; run R, attempt 1/2, SHA khác nhau, failed jobs và success jobs; HMAC key synthetic, token/PII canary synthetic. Mốc T0 cố định, advance clock/latches thay sleep tùy ý. Không dùng credential hoặc log khách hàng làm fixture.
- Happy-path oracle sau transaction A: 1 attempt FAILED + 1 failure PENDING + 1 fetch job, **0 CI outbox/event**, receipt COMPLETED. Sau C: cùng failure READY, excerpt có ngữ cảnh lỗi, 1 outbox logical event, reader trả đúng excerpt. Sau publish: event ID không đổi; count DB và listener record khớp attempt.
- HTTP tests phải đi qua controller/HMAC thật. GitHub stub assert host/path/run/attempt/auth headers, request count, redirect handling; recorder bắt typed event và kiểm trạng thái committed từ connection độc lập. Concurrency dùng barrier/latch cho hai connection/worker thực sự.
- **Actual:** `NR` = chưa thực thi cho T-025. Khi chạy thay bằng `PASS/FAIL/BLOCKED/PARTIAL — thời điểm, commit, môi trường, actual output và evidence path/run ID`. Quan sát source ở §1.2 không phải runtime test PASS.

### 6.2. Nhận webhook, nghiệp vụ và ordering

| ID | Mức | Đầu vào / thao tác | Expected | Actual |
|---|---|---|---|---|
| WH-01 | I | Signed completed/failure hợp lệ | 204 sau commit; đúng oracle A, chưa fetch/publish trong request. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| WH-02 | I | Completed/success | Lưu SUCCESS; 0 failure/job/CI event. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest`, `CiWorkflowIngestionTest` |
| WH-03 | U/I | Lần lượt cancelled, skipped, timed_out, action_required, neutral, stale, unknown hợp lệ | Map đúng từng status; không tạo failure/job/event. | PASS — 24/11/2026, parameterized trong `CiWorkflowWebhookHttpIntegrationTest` và `CiWorkflowIngestionTest` |
| WH-04 | I | Action requested/in_progress/action mới, signature hợp lệ | ACK ignored; không failure/job/event, metric ignored. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` (assert metric `ignored`), `CiWorkflowIngestionTest` |
| WH-05 | U/I | Thiếu workflow object/action/ID/attempt/conclusion; sai kiểu, số <=0 hoặc overflow | 400 safe code; không ghi receipt/attempt/failure/job. | PASS — 24/11/2026, `CiWorkflowIngestionTest` + `CiWorkflowWebhookHttpIntegrationTest` + `GitDomainEventHttpIntegrationTest` |
| WH-06 | U/I | SHA sai độ dài/ký tự/toàn zero; timestamp sai/time order; completed nhưng status running | 400, rollback, không silent fallback. | PASS — 24/11/2026, `CiWorkflowIngestionTest` + `CiWorkflowWebhookHttpIntegrationTest` |
| WH-07 | I | CI tới trước push, không có commit/task/PR | Ghi failure bình thường; không commit/task giả, không đổi Board. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| WH-08 | I | `head_branch=null`; empty string; branch 255/256 ký tự | Null được lưu; 255 hợp lệ; blank/256 bị reject. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` + `CiWorkflowIngestionTest` |
| WH-09 | I | Failure attempt 1 → success attempt 2 cùng run | Hai pipeline rows; failure/event chỉ attempt 1, không xóa lịch sử. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| WH-10 | I | Cả attempt 1/2 failure, nhận ngược thứ tự | Hai failure và hai logical event, log đúng attempt, identity riêng. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest.outOfOrderFailedAttemptsAndMultiWorkspaceSameRunIdStayIsolated` |
| WH-11 | I | Gửi lại delivery/body; delivery mới cùng body | ACK theo receipt; một attempt/failure/job, không fetch/event mới. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| WH-12 | I | Delivery mới, JSON khác formatting/metadata nhưng cùng attempt/kết quả | Semantic dedup vẫn một failure/job/event. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| WH-13 | I | Cùng delivery khác digest | 409, dữ liệu ban đầu không đổi. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` (`WEBHOOK_DELIVERY_CONFLICT`) |
| WH-14 | I | Snapshot cũ hơn; cùng version cùng nội dung; cùng version khác SHA/kết quả | Cũ bỏ qua; lặp no-op; conflict 409, không mutate. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` (kể cả metadata mới hơn cùng terminal) |
| WH-15 | I | Kết quả terminal khác trong cùng attempt dù timestamp mới hơn | 409, giữ trạng thái/event trước, metric conflict. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` (`FAILED→SUCCESS` & `SUCCESS→FAILED` + metric `conflict`) |
| WH-16 | I | Hai request đồng thời cùng attempt/delivery khác nhau | Unique/lock bảo vệ; một failure/job, response không leak SQL error. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| WH-17 | I | Cùng run ID/attempt ở repo A và B | Hai identity độc lập, không đọc hoặc ghi chéo repo/workspace. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` (2 repo/2 workspace) |
| WH-18 | I | Replay sau purge receipt/outbox published | Marker/unique bảo vệ; không sinh event lịch sử mới. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |

### 6.3. Validation, bảo mật và lỗi HTTP

| ID | Mức | Đầu vào / thao tác | Expected | Actual |
|---|---|---|---|---|
| SEC-01 | I | HMAC thiếu/sai, body bị sửa; binding không tồn tại/sai repo | 401 theo contract hiện tại; 0 writes, 0 GitHub calls. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| SEC-02 | I | Key current, previous còn hạn, previous hết hạn | Chỉ key hợp lệ được nhận, không lộ key trong response/log. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| SEC-03 | I | Top-level repo sai; metadata target repo mâu thuẫn; head repo là fork hợp lệ | Sai target bị từ chối; fork không thành quyền fetch repo khác. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest`, `CiWorkflowIngestionTest` |
| SEC-04 | I | JSON duplicate key/trailing JSON/UTF-8 hỏng/depth quá mức | 400; không bypass parser qua workflow payload. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` (+ depth trong `GitHubWebhookHttpIntegrationTest`) |
| SEC-05 | I | Body đúng max/vượt max/chunked vượt max; media/compression không hỗ trợ | Boundary hợp lệ xử lý; 413/415 đúng trường hợp, memory bounded. | PARTIAL — 24/11/2026, PASS cho 413 (>max), 415 (`text/plain`, `gzip`) trong `CiWorkflowWebhookHttpIntegrationTest`; chunked streaming riêng cho `workflow_run` chưa chạy lại (đã phủ chung endpoint ở T-021) |
| SEC-06 | I | Header trùng/sai delivery/event; selector/query sai | 400 hoặc auth-first response hiện có; không side effect. | PARTIAL — 24/11/2026, PASS selector sai 400 trên `workflow_run`; duplicate header phủ chung endpoint ở `GitHubWebhookHttpIntegrationTest` |
| SEC-07 | I/O | Vượt webhook quota, concurrency hoặc backlog giới hạn | 429/503 + Retry-After phù hợp; không ACK làm mất công việc, không fetch. | PARTIAL — 24/11/2026, PASS per-repo & global backlog 503 + `Retry-After` trong `CiWorkflowWebhookHttpIntegrationTest`; quota 429/in-flight 503 phủ ở T-021 |
| SEC-08 | I | DB down/lock timeout/unexpected exception | ProblemDetail 503/500 theo loại, correlation ID; không stack trace/SQL/token. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` (ProblemDetail, `correlationId`, 0 leak SQL/key/exception) |
| SEC-09 | U/I | Payload/job chứa localhost/private-IP/file URL/userinfo và giả Link pagination | Không thực hiện network ngoài target được phép, safe error. | PASS — 24/11/2026, `GitHubActionsReadClientTest` (`repositorySlug`, `Link` giả, `publicAddress`) |
| SEC-10 | I | Redirect HTTPS→HTTP/private IP/host ngoài allowlist, loop/quá hop/DNS rebind | Downloader chặn; Authorization/Cookie không đi sang archive host. | PARTIAL — 24/11/2026, PASS toàn bộ kiểm tra redirect/host/port/userinfo/loop/hop/IP/no-auth ở `GitHubActionsReadClientTest` & `GitHubActionsPropertiesTest`; chưa giả lập DNS rebind socket sống |
| SEC-11 | U/I | Secret canary token/header/query/private key multiline/PII; ANSI/chunk boundary | Không tìm thấy canary trong DB/event/app logs/reader; vẫn có error context đã lọc. | PASS — 24/11/2026, `CiLogSanitizerTest` + `CiWorkflowWebhookHttpIntegrationTest` + `CiFailureWorkerAndContractIntegrationTest` (Logback `ListAppender`) |
| SEC-12 | U/I | Log chứa HTML/script, prompt injection và Unicode/control chars | Chỉ là bounded text đã chuẩn hóa; không execute/render HTML/gọi tool theo log. | PASS — 24/11/2026, `CiLogSanitizerTest.handlesPromptInjectionHtmlControlCharsAndInvalidUtf8AsPlainText` |

### 6.4. GitHub client, log worker và dữ liệu đầu vào AI

| ID | Mức | Đầu vào / thao tác | Expected | Actual |
|---|---|---|---|---|
| LOG-01 | I | Jobs + archive hợp lệ cho attempt thất bại | Đúng path attempt; READY sau C; log có cause/trace, 1 outbox. | PASS — 24/11/2026, `GitHubActionsReadClientTest` + `CiWorkflowWebhookHttpIntegrationTest` |
| LOG-02 | I | Attempt 2 có log khác attempt 1; default/latest endpoint trả log sai | Client chỉ gọi attempt endpoint, không tráo excerpt. | PASS — 24/11/2026, `GitHubActionsReadClientTest` + `CiWorkflowWebhookHttpIntegrationTest` |
| LOG-03 | I | Nhiều job lỗi + job thành công, matrix job tên giống nhau | Deterministic aggregate theo job identity; giữ ngữ cảnh nhiều lỗi, không gán tên sai. | PASS — 24/11/2026, `CiLogSanitizerTest` (matrix trùng tên & khác tên) |
| LOG-04 | I | Jobs phân trang đến giới hạn và vượt giới hạn | Theo page đúng scope; request hữu hạn, truncated rõ, không mất kiểm soát vòng lặp. | PASS — 24/11/2026, `GitHubActionsReadClientTest.paginatesJobsUpToConfiguredPageCapAndIgnoresMaliciousLinkHeaders` |
| LOG-05 | U/I | Archive format không map job; metadata run/SHA/attempt không khớp | Không map được thì aggregate có nhãn; identity mismatch không READY/publish. | PASS — 24/11/2026, `CiLogSanitizerTest` + `GitHubActionsReadClientTest.rejectsRunAndJobMetadataIdentityMismatches` |
| LOG-06 | U/I | UTF-8 đa byte đúng/vượt 16.384 byte; summary đúng/vượt 8.192 byte | Snippet/summary trong byte limit, valid UTF-8, truncation flag đúng. | PASS — 24/11/2026, `CiLogSanitizerTest.enforcesUtf8SnippetAndSummaryByteBoundariesAndFairMultiJobBudget` |
| LOG-07 | U/I | Error ở cuối log rất dài; nhiều job tranh budget | Giữ phần lỗi + context trong budget, không chỉ lấy đầu log/job đầu. | PASS — 24/11/2026, `CiLogSanitizerTest` |
| LOG-08 | I | ZIP traversal/nested/enormous ratio/quá entries/header giả/kích thước sát biên | Không ghi file ngoài memory; giới hạn thực đo, ngắt an toàn; không lưu dữ liệu chưa lọc. | PASS — 24/11/2026, `CiLogSanitizerTest` (traversal, nested ext & magic bytes, ZIP bomb, entry cap biên) |
| LOG-09 | I | ZIP hỏng/rỗng/log toàn secret hoặc không còn excerpt hữu ích | UNAVAILABLE, không event READY giả, safe code. | PASS — 24/11/2026, `CiLogSanitizerTest` + `CiFailureWorkerAndContractIntegrationTest` |
| LOG-10 | I | API timeout/reset connection/5xx rồi hồi phục | Retry durable đúng budget; cuối cùng một READY/outbox, không duplicate failure. | PASS — 24/11/2026, `GitHubActionsReadClientTest` + `CiFailureWorkerAndContractIntegrationTest` (hồi phục & kiệt 8 lần) |
| LOG-11 | I | 429/403 throttle có Retry-After/reset; header bất thường | Delay bounded, không tight loop hoặc retry lồng; request count đúng budget. | PASS — 24/11/2026, `GitHubActionsReadClientTest` + `CiFailureWorkerAndContractIntegrationTest` |
| LOG-12 | I | 401/403 thiếu Actions read, rotate credential rồi requeue | PERMISSION_DENIED trước; cùng failure chuyển READY sau sửa, không lưu token. | PASS — 24/11/2026, `GitHubActionsReadClientTest` + `CiFailureWorkerAndContractIntegrationTest` (SQL requeue runbook) |
| LOG-13 | I | 404 tạm 1–2 lần rồi có log; 404 kéo dài/log đã bị xóa | Ca đầu READY; ca sau UNAVAILABLE theo budget 3 lần, không event rỗng. | PASS — 24/11/2026, `GitHubActionsReadClientTest` + `CiFailureWorkerAndContractIntegrationTest` |
| LOG-14 | I | Redirect signed URL hết hạn; retry lấy URL mới | Xin URL mới qua API, không lưu/tái dùng URL hết hạn hoặc gửi auth sang storage. | PASS — 24/11/2026, `GitHubActionsReadClientTest` (`ARCHIVE_URL_EXPIRED`, không gửi `Authorization`) |
| LOG-15 | I/O | Circuit breaker mở/half-open/hồi phục | Không call khi open; reschedule không đốt hết attempts; hồi phục có giới hạn. | PARTIAL — 24/11/2026, PASS mở mạch + short-circuit + worker refund attempt ở `GitHubActionsReadClientTest` & `CiFailureWorkerAndContractIntegrationTest`; pha `HALF_OPEN` sau 30s chưa chờ wall-clock trong unit test |
| LOG-16 | I | Chưa cấu hình API credential cho repo private | Failure đã lưu, permission/config status rõ; không dùng HMAC secret làm API token. | PASS — 24/11/2026, `GitHubActionsReadClientTest`, `GitHubActionsPropertiesTest`, `CiWorkflowWebhookHttpIntegrationTest` |
| LOG-17 | I | Download vượt budget 30s hoặc size cap giữa stream | Hủy request, đóng stream, giải phóng slot; không treo lease/DB connection. | PASS — 24/11/2026, `GitHubActionsReadClientTest` (chunked stream > cap + deadline), `CiLogSanitizerTest` |

### 6.5. Transaction, event, quyền đọc và khả năng phục hồi

| ID | Mức | Đầu vào / thao tác | Expected | Actual |
|---|---|---|---|---|
| TX-01 | I | Inject lỗi sau receipt, attempt, failure, job trước commit A | Toàn bộ rollback; redelivery tạo đủ một bộ, không trạng thái nửa vời. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| TX-02 | I | Process chết sau commit A, trước fetch | Restart đọc job bền vững và hoàn tất, không cần webhook gửi lại. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` + `CiFailureWorkerAndContractIntegrationTest` |
| TX-03 | I | Network chậm; connection độc lập đọc/khóa attempt | HTTP network ngoài DB transaction; không giữ repository lock trong 30s tải log. | PASS — 24/11/2026, `CiFailureWorkerAndContractIntegrationTest` (connection độc lập `FOR UPDATE NOWAIT` trên `repositories` & `ci_pipelines`) |
| TX-04 | I | Hai worker claim cùng hàng đợi, worker chết khi FETCHING | Mỗi job một token tại một thời điểm; lease hết được claim lại. | PASS — 24/11/2026, `CiFailureWorkerAndContractIntegrationTest` (`SKIP LOCKED` 2 worker + `LEASE_RETRY_EXHAUSTED`) |
| TX-05 | I | Owner cũ trả kết quả sau khi owner mới đã claim | Update theo stale token bị từ chối; không overwrite log/state của owner mới. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` (complete) + `CiFailureWorkerAndContractIntegrationTest` (fail) |
| TX-06 | I | Inject lỗi giữa ghi READY và enqueue outbox trong C | C rollback cả hai; retry tạo đúng một READY + một outbox. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| EVT-01 | I/C | Event recorder dùng connection độc lập khi receive | Chỉ thấy event sau commit C; ID/repo/workspace/run/attempt/SHA/reference đúng. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| EVT-02 | I/C | Listener throw trước/giữa side effect; crash sau publish trước mark | Retry cùng event ID/payload; consumer giả có inbox chỉ xử lý một lần. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` + `CiFailureWorkerAndContractIntegrationTest` |
| EVT-03 | I | Publish liên tục lỗi qua 8 attempts | FAILED có metric, không mất row; requeue nội bộ giữ event UUID. | PASS — 24/11/2026, `CiFailureWorkerAndContractIntegrationTest` (8 failures + SQL requeue outbox runbook) |
| EVT-04 | I/O | Pause fetch/publish riêng rồi resume; restart backlog | Webhook vẫn lưu job; không publish khi pause; resume xử lý đủ, không backfill ngoài scope. | PASS — 24/11/2026, `CiFailureWorkerAndContractIntegrationTest` (không backfill `LEGACY`) |
| EVT-05 | C | Bus không có consumer; listener swallow lỗi/async return | Phân biệt bus PUBLISHED với consumer completion; nghiệm thu end-to-end chưa được coi PASS. | PASS — 24/11/2026, `CiFailureWorkerAndContractIntegrationTest` |
| READ-01 | C | System consumer đọc reference từ valid persisted event | Đúng một response READY, excerpt/digest đúng attempt, byte limit được áp. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` + `CiFailureWorkerAndContractIntegrationTest` |
| READ-02 | C | Legacy event thiếu provenance; giả event/reference; null actor xin system mode | DENIED/fail closed, không trả log theo UUID đơn thuần. | PASS — 24/11/2026, `CiFailureEventsTest` + `CiWorkflowWebhookHttpIntegrationTest` + `CiFailureWorkerAndContractIntegrationTest` |
| READ-03 | C | User A là member; user B ngoài workspace; membership bị revoke | Chỉ member scope hợp lệ đọc được; ngoài workspace/revoked bị từ chối. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` |
| READ-04 | C | Reference thuộc repo/attempt khác, resource không tồn tại, PENDING hoặc EXPIRED | DENIED/NOT_FOUND/NOT_READY/EXPIRED đúng contract; không trả snippet khác hoặc rỗng giả READY. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` + `CiFailureWorkerAndContractIntegrationTest` |
| READ-05 | C | Không có responder/hai responders, request limit âm/quá max | Fail closed/validation error có kiểm soát, không đọc vượt giới hạn. | PASS — 24/11/2026, `CiFailureEventsTest` + `CiWorkflowWebhookHttpIntegrationTest` |
| DEL-01 | I | Xóa repo/workspace khi job pending/claimed, lúc fetch hoặc trước commit C | Cascade sạch, kết quả đang tải bị bỏ; không resurrect parent hoặc publish mới sau khi đã biết xóa. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` + `CiFailureWorkerAndContractIntegrationTest` (`WorkspaceDeletedEvent`) |
| DEL-02 | I/C | Xóa resource sau prepublish check, trước consumer read | Reader NOT_FOUND; consumer tolerate, không truy cập tenant khác. | PASS — 24/11/2026, `CiFailureWorkerAndContractIntegrationTest` |
| DEL-03 | I | Purge retention khi pending event, khi PUBLISHED và replay sau purge | Pending giữ excerpt; published eligible purge bounded; expired trả rõ; replay không enqueue lại. | PASS — 24/11/2026, `CiFailureWorkerAndContractIntegrationTest` (pending guard) + `CiWorkflowWebhookHttpIntegrationTest` |

### 6.6. Migration, regression, vận hành và smoke thật

| ID | Mức | Đầu vào / thao tác | Expected | Actual |
|---|---|---|---|---|
| DB-01 | I | DB trống → mới; V17 có pipeline/failure legacy → mới | Flyway thành công, giữ dữ liệu/constraints; legacy không bị gắn READY hoặc phát event. | PASS — 24/11/2026, `CiFailureMigrationIntegrationTest` |
| DB-02 | I | Vi phạm unique attempt/failure/job/event, FK, lease-state, byte/state checks | PostgreSQL reject đúng constraint; không cần app để giữ invariants. | PASS — 24/11/2026, `CiFailureMigrationIntegrationTest` |
| DB-03 | I | Branch null/nonblank, timestamp/attempt biên; xóa pipeline/repo | Schema mới hỗ trợ null đúng policy; vẫn reject invalid; cascade toàn bộ children. | PASS — 24/11/2026, `CiFailureMigrationIntegrationTest` |
| DB-04 | I/O | EXPLAIN claim/cleanup trên backlog synthetic, concurrent workers | Dùng partial index phù hợp, batch bounded; không scan/lock toàn bảng kéo dài. | PASS — 24/11/2026, `CiFailureMigrationIntegrationTest` (`EXPLAIN ANALYZE`) + 23/11 fixture 15.000 job (0,096–0,216 ms) |
| REG-01 | I | Push/PR signed, Git link/outbox, Board auto-transition/realtime hiện có | Không đổi hành vi T-021–024, không ghi CI failure khi nhận push/PR. | PASS — 24/11/2026, full `./gradlew check` (936 test, 0 fail) |
| REG-02 | C/I | Consumer T-031 đang tích hợp, constructor/contract cũ/mới | Compile/behavior compatibility được ghi rõ; không fallback external run ID mơ hồ. | BLOCKED — 24/11/2026, đã đối chiếu PR #37 (`origin/develop` `d1b10e0`): source-compatible PASS, nhưng T-031 còn đọc inline/`GitCiApi` và dùng `V14__ai_ci_summaries.sql`; xem `results.md` |
| REG-03 | U/I | Gradle boundaries/check và frontend build | Checks exit 0; counts/report gắn đúng commit, không lấy số test baseline làm kết quả mới. | PASS — 24/11/2026, `:verifyModuleBoundaries check :app:bootJar` (936 test, 0 fail, 1 opt-in skip) + `frontend/ npm run build` (365 modules) |
| OPS-01 | O | 100 completed webhook hợp lệ/phút trong 10 phút, GitHub stub chậm có giới hạn | ACK P95 mục tiêu <=1s trên môi trường ghi cấu hình; không external I/O trong request, không mất/nhân đôi job. | PASS — 23/11/2026, P95 ACK 34 ms, 1.000/1.000 PUBLISHED (`.env.t025-rollout/ops-01.xml`) |
| OPS-02 | O | Backlog tối đa, GitHub chậm/throttle, pool DB hữu hạn | Memory/threads/connections bounded; metric saturation; reject admission an toàn, app core còn phục vụ. | PASS — 24/11/2026, `CiWorkflowWebhookHttpIntegrationTest` + `GitHubActionsPropertiesTest` + `CiFailureWorkerAndContractIntegrationTest` |
| OPS-03 | O | Theo dõi queue age, fail/denied, circuit-open, resource deletion | Metrics/log đủ tra cứu, tags hữu hạn; không secret/PII/log text/high-cardinality labels. | PASS — 24/11/2026, `CiFailureWorkerAndContractIntegrationTest` (Logback + Micrometer + `/actuator/health` 200 UP) |
| OPS-04 | O | Runbook pause/resume/requeue/rotation/expired lease/backup/rollback | Thao tác tái lập được, không đổi event identity hoặc cần ghi secret vào chat/file tracking. | PASS — 24/11/2026, SQL requeue runbook đã chạy test thật, backup/restore V17 (23/11), diễn tập rollback `devflow-backend:t024` trên PostgreSQL 17 V18 cô lập (`200 UP`) |
| E2E-01 | E | Repo thử nghiệm chạy workflow fail với canary synthetic không nhạy cảm | GitHub delivery 204 → DB FAILED/READY → event + reader có error context; không thấy canary bị cấm lưu. | PASS — 24/11/2026, run `45210759214`, delivery `3855698172697395200` 204 → `READY` (761B, digest `08184dbb...`, canary `[redacted]`) → event `15cac546-1d35-419f-bbf8-d5d714d2fa07` `PUBLISHED`, 0 leak log |
| E2E-02 | E | Rerun success; redeliver failure cũ; restart stack | Attempt cũ giữ nguyên, success không tạo failure, không thêm logical event sau replay/restart. | PASS — 24/11/2026, attempt 2 `SUCCESS` (0 failure), redelivery `3855698476201418752` 204 + restart backend sau `PUBLISHED`: giữ đúng 2 attempts / 1 failure / 1 job / 1 outbox `PUBLISHED` |
| E2E-03 | E | Revoke/sửa Actions read rồi phục hồi đúng repo | Failure giữ bền, state permission rõ, requeue xong READY, không lộ token. | PASS — 24/11/2026, `PERMISSION_DENIED` bền vững + health `200 UP` → nạp PAT cục bộ + SQL requeue runbook → cùng failure `bbe922fc-...` chuyển `READY` → `PUBLISHED`, 0 leak token |
| E2E-04 | E/C | Kết nối T-031 đã thống nhất hợp đồng | Consumer đọc excerpt đúng attempt; summary chỉ nghiệm thu thêm nếu code T-031 thực sự có mặt và chạy, không nằm trong AC T-025 độc lập. | BLOCKED — chờ thống nhất adapter `CiFailureLogReadRequestedEvent` và thứ tự migration với Tiến sau PR #37 (`d1b10e0`) |

### 6.7. Cách chạy và ghi nhận kết quả

Các bộ test T-025 thực tế: `CiFailureEventsTest` (`:common`); `GitHubActionsPropertiesTest`, `CiWorkflowIngestionTest`, `CiLogSanitizerTest`, `GitHubActionsReadClientTest` (`:gitci-impl`); `CiFailureMigrationIntegrationTest`, `CiWorkflowWebhookHttpIntegrationTest`, `CiFailureWorkerAndContractIntegrationTest` (`:app`).

```powershell
# Chạy trong backend/
.\gradlew.bat :gitci-impl:test :common:test
.\gradlew.bat :app:test --tests '*CiWorkflowWebhookHttpIntegrationTest' --tests '*CiFailureMigrationIntegrationTest' --tests '*CiFailureWorkerAndContractIntegrationTest'
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat check

# Chạy trong frontend/ theo workspace workflow; lint khi có thay đổi frontend.
npm run build
```

Điều kiện chạy integration: JDK 17, Docker/Testcontainers và PostgreSQL 17. GitHub stub phải chạy độc lập với credential thật; không cho test trỏ nhầm database Compose đang dùng. Real E2E chỉ chạy sau khi cấu hình scope/quyền và repository thử nghiệm. Không cố chạy các tên test chưa tồn tại rồi ghi “test thất bại của feature”.

Bằng chứng thực thi được ghi nhận trong [15_tiep_nhan_loi_ci_results.md](15_tiep_nhan_loi_ci_results.md) với đầy đủ commit/build, migration version, môi trường, từng ID/biến thể đã chạy, Expected/Actual, artifact/report/redelivery/event IDs và các gate còn lại. Evidence che toàn bộ token, signed URL, raw payload/log và PII. Test bị external setup/dependency chặn được giữ nguyên `PARTIAL`/`BLOCKED` thay vì ghi `PASS`.

## 7. Tham khảo và câu hỏi phải chốt trước rollout

- [GitHub workflow_run webhook](https://docs.github.com/en/webhooks/webhook-events-and-payloads#workflow_run): xác minh loại thông báo và policy completed/conclusion; không suy log đầy đủ từ payload.
- [GitHub workflow runs](https://docs.github.com/en/rest/actions/workflow-runs#download-workflow-run-attempt-logs): xác minh endpoint attempt, quyền đọc và redirect download; không lưu URL download làm reference lâu dài.
- [GitHub workflow jobs](https://docs.github.com/en/rest/actions/workflow-jobs#list-jobs-for-a-workflow-run-attempt): xác minh jobs theo attempt và phân trang để chuẩn bị log nhiều job.
- Chốt với Tiến: pipeline UUID semantics, provenance/dedup, read request authorization và cách T-031 xử lý NOT_READY/EXPIRED. Nếu PR của Tiến có contract khác, ghi rõ phương án migration/adapter trước khi merge.
- Chốt môi trường rollout khi implementation sẵn sàng: local Docker hay backend deploy, repo thử nghiệm và credential reference Actions read. Dùng secret manager/config local của chủ máy; không yêu cầu copy secret vào tài liệu hoặc bình luận Trello.

Các tài liệu GitHub được đối chiếu ngày 19/11/2026; khi code cần kiểm tra lại API version, archive host allowlist và quyền trước rollout.
