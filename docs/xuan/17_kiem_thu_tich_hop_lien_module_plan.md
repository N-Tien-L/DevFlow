# T-041 — Kế hoạch triển khai bộ kiểm thử tích hợp liên module (In-Process Event Bus)

**Milestone 4 · Phụ trách:** Xuân Lê (`@xuanle2`) · **Phối hợp:** Tiến Lâm (`@tienlam15` cho consumer `notification-impl` và `ai-impl`).  
**Ngày khảo sát và lập kế hoạch:** 28/11/2026 (Asia/Saigon).  
**Baseline mã nguồn:** nhánh `origin/develop` tại commit `09fe624` (Flyway V1–V19; đã tích hợp T-001..T-009, T-010..T-018, T-020..T-025, T-030..T-033, T-038, T-040A, T-040B, T-048) và đối chiếu nhánh đang review `feature/T-026-git-badges-on-cards` tại commit `1f3e25e` (bổ sung Flyway V20 và `TaskGitActivityChangedEvent`).  
**Phạm vi:** `:app` (bộ integration test liên module & architecture test) + gia cố kiểm tra ranh giới `:verifyModuleBoundaries` tại `backend/build.gradle.kts`.  
**Branch triển khai:** `feature/T-041-integration-test-suite` (tách trực tiếp từ `develop` @ `09fe624`).  
**Trạng thái:** Đã hoàn tất triển khai toàn bộ P0–P5 trên nhánh `feature/T-041-integration-test-suite` (6 lớp kiểm thử trong `:app` + gia cố `:verifyModuleBoundaries` ở `backend/build.gradle.kts`), vượt qua **3/3 lượt chạy lặp liên tiếp** `--rerun-tasks` (37/37 test methods PASS, 0 failed, 0 skipped, ~14,8s thực thi trên PostgreSQL 17 Testcontainers: Run 1 `02:06`, Run 2 `01:26`, Run 3 `01:26`) và vượt qua toàn bộ full backend quality gate `./gradlew :verifyModuleBoundaries check` (**115 suites / 1.008 tests**, 0 failed, 0 error, 0 vi phạm ranh giới module, `BUILD SUCCESSFUL in 7m 37s`; 1 test skipped pre-existing nằm trong `CiWorkflowWebhookHttpIntegrationTest`). Đạt **54/55** case trong ma trận kiểm thử (riêng `E2E-10` ở trạng thái `BLOCKED` do phụ thuộc PR T-026 `TaskGitActivityChangedEvent` chưa merge vào `develop`). Sẵn sàng tạo PR review và trao đổi Gate `G3` với Tiến (`@tienlam15`).

[Yêu cầu gốc](17_kiem_thu_tich_hop_lien_module.md) · [Thẻ Trello T-041](https://trello.com/c/Nec2nAGv/40-t-041-author-multi-module-integration-test-suite-validating-in-process-event-bus) · [Tổng quan nhiệm vụ](00_nhiemvu.md) · [Plan tự chuyển cột T-024](14_tu_dong_chuyen_trang_thai_task_plan.md) · [Plan lỗi CI T-025](15_tiep_nhan_loi_ci_plan.md) · [Plan badge Git/CI T-026](16_hien_thi_git_va_ci_tren_task_plan.md).

---

## 1. Mục tiêu, hiện trạng mã nguồn và phạm vi

### 1.1. Kết quả cần đạt

Chứng minh toàn bộ kiến trúc Modular Monolith của DevFlow (5 module nghiệp vụ `auth`, `board`, `gitci`, `ai`, `notification` cùng chạy trong một JVM `:app`, giao tiếp qua in-process Spring Event Bus trên cơ sở dữ liệu PostgreSQL 17 thật) hoạt động đúng xuyên suốt từ đầu đến cuối — không chỉ từng module chạy đúng khi đứng riêng với mock.

Trọng tâm là chứng minh bằng dữ liệu thật trong database, REST API thật và khung hình STOMP thật cho chuỗi nghiệp vụ liên module cốt lõi:

```text
GitHub Webhook (push / pull_request / workflow_run)
   ──► gitci-impl (xác thực HMAC, giải mã T-XXX qua board-impl, ghi outbox)
   ──► In-Process Event Bus (git.commit_linked / git.pr_opened / git.pr_merged / ci.failure_detected)
   ──► board-impl (tự đổi cột task, ghi audit/receipt, phát task.status_changed & STOMP board)
   ──► notification-impl (xác minh assignee & membership qua BoardApi/AuthApi, lưu DB notifications, đẩy STOMP /user/queue/notifications)
   ──► ai-impl (tiêu thụ task events & ci.failure_detected -> tóm tắt CI vào ai_ci_summaries -> ci.failure_summarized)
```

Hai tiêu chí nghiệm thu gốc được đọc trực tiếp từ thẻ Trello T-041 (`6aaabc5ce8cc19190c7a5b4d`):

- `./gradlew check` chạy pass 100% tất cả test.
- Kiểm thử không có vi phạm ranh giới module (`:verifyModuleBoundaries`).

Ba kiểm tra nghiệm thu bổ sung theo [yêu cầu gốc](17_kiem_thu_tich_hop_lien_module.md):

- Có bằng chứng test kiểm tra đúng task đổi cột và thông báo lưu cho đúng người; ca bị từ chối không làm đổi dữ liệu.
- Bài test có thể chạy lại với dữ liệu độc lập và kết quả ổn định.
- Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Các mở rộng kiểm thử (bao phủ đầy đủ cả PR/CI/AI/Manual/Lifecycle GDPR, STOMP 2 kênh, gia cố boundary tĩnh và bài kiểm tra lặp ổn định) dưới đây là **kế hoạch kỹ thuật đề xuất để đạt chuẩn SaaS production**, không thay đổi checklist gốc trên Trello.

### 1.2. Kết quả đối chiếu trực tiếp từ mã nguồn

Bảng dưới ghi nhận hiện trạng thực tế quan sát từ mã nguồn tại thời điểm khảo sát (không phải bằng chứng test runtime cho T-041):

| Thành phần hiện tại | Hiện trạng quan sát trong mã nguồn | Ý nghĩa và tác động tới T-041 |
|---|---|---|
| [DevFlowApplication.java](../../backend/app/src/main/java/io/devflow/DevFlowApplication.java), [app/src/test/resources/application.yml](../../backend/app/src/test/resources/application.yml) | `:app` gom toàn bộ `auth-impl`, `board-impl`, `gitci-impl`, `ai-impl`, `notification-impl`, `common`. File test `application.yml` mặc định dùng **H2 in-memory** với `flyway.enabled=false`, `ddl-auto=create-drop`, `devflow.gitci.outbox.worker-enabled=false`, `devflow.ai.chat.model=none`. | Mọi bài test T-041 trong `:app` **bắt buộc** dùng `@DynamicPropertySource` chuyển sang **PostgreSQL 17 Testcontainers** với `spring.flyway.enabled=true` và `spring.jpa.hibernate.ddl-auto=validate`; tuyệt đối không chạy test tích hợp trên H2. |
| [EventTypes.java](../../backend/common/src/main/java/io/devflow/common/event/EventTypes.java) | Định nghĩa 14 loại sự kiện chuẩn `DevFlowEvent`: `task.references_resolve_requested`, `task.references_removed`, `workspace.membership_check_requested`, `task.created`, `task.status_changed`, `git.commit_linked`, `git.pr_opened`, `git.pr_merged`, `git.task_activity_changed` *(T-026)*, `ci.failure_detected`, `ci.failure_log_read_requested`, `ci.failure_summarized`, `risk.deadline_flagged`, `user.account_deleted`, `workspace.deleted`. | T-041 cần ma trận phủ rõ ràng cả 14 hợp đồng sự kiện, phân biệt nhóm trọng tâm (chuỗi Webhook → Board → Notification) với nhóm mở rộng (CI/AI, Manual, Lifecycle GDPR). |
| [GitHubWebhookController.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitHubWebhookController.java), [GitHubWebhookService.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitHubWebhookService.java), [GitWebhookIngestion.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitWebhookIngestion.java) | Xác thực HMAC SHA-256 trên raw bytes (`X-Hub-Signature-256`), đối chiếu `repository.id` với binding, chống trùng `github_webhook_receipts`, gọi đồng bộ `TaskReferencesResolveRequestedEvent` (`board-impl` khóa `FOR SHARE` trong cùng transaction), ghi `git_commits`/`pull_requests`/bảng link V16 và `git_domain_event_outbox` nguyên tử. | Test phải gửi HTTP request thật ký HMAC qua `/api/v1/gitci/webhook/github?repositoryId=...` (không gọi tắt service), kiểm chứng đủ các nhánh `204`, `400`, `401`, `409`, `413`, `415`, `422`. |
| [GitDomainEventOutbox.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitDomainEventOutbox.java) | Claim tối đa 50 row bằng `FOR UPDATE SKIP LOCKED`, gia hạn lease 60s, phát `GitCommitLinkedEvent`, `GitPrOpenedEvent`, `GitPrMergedEvent` (`actorId=null`, `eventId=outbox.id`, `workspaceId` snapshot) **ngoài transaction** (`PROPAGATION_NOT_SUPPORTED`). Hỗ trợ `dispatch()`, `claim()`, `publish(entry)`, `requeue(id)` public cho vận hành/test khi tắt scheduler. | Cho phép điều khiển vòng lặp phát sự kiện hoàn toàn xác định (deterministic) trong test mà không cần `Thread.sleep`, đồng thời mô phỏng được crash sau publish trước mark `PUBLISHED` và hết hạn lease. |
| [GitActivityEventListener.java](../../backend/board-impl/src/main/java/io/devflow/board/internal/GitActivityEventListener.java), [GitTaskTransitionService.java](../../backend/board-impl/src/main/java/io/devflow/board/internal/GitTaskTransitionService.java), [TaskMoveExecutor.java](../../backend/board-impl/src/main/java/io/devflow/board/internal/TaskMoveExecutor.java), [TaskEventPublisher.java](../../backend/board-impl/src/main/java/io/devflow/board/internal/TaskEventPublisher.java) | `GitTaskTransitionService.apply` chạy `REQUIRES_NEW` (timeout 6s, `lock_timeout=2s`, `statement_timeout=4s`), khóa board `FOR UPDATE`, kiểm tenant, chống replay qua `board_git_event_receipts`, bảo vệ `last_manual_status_changed_at`, chuyển cột chỉ tiến (`TODO→IN_PROGRESS→IN_REVIEW→DONE`). Sau commit mới phát `TaskStatusChangedEvent(cause=GIT, actorId=null)` và STOMP `CARD_MOVED`. Cờ `devflow.board.git.auto-transition-enabled` mặc định `false`. | Cần kiểm chứng cả 2 chế độ: `auto-transition-enabled=true` (luồng tự động hóa đầy đủ) và `false` (mặc định an toàn — không tự chuyển cột nhưng không chặn thông báo PR). |
| [NotificationEventListeners.java](../../backend/notification-impl/src/main/java/io/devflow/notification/internal/NotificationEventListeners.java), [NotificationService.java](../../backend/notification-impl/src/main/java/io/devflow/notification/internal/NotificationService.java), [NotificationRepository.java](../../backend/notification-impl/src/main/java/io/devflow/notification/internal/NotificationRepository.java), [NotificationController.java](../../backend/notification-impl/src/main/java/io/devflow/notification/internal/NotificationController.java) | Lắng nghe 6 sự kiện bằng `@TransactionalEventListener(phase=AFTER_COMMIT, fallbackExecution=true)`: `TaskCreatedEvent`, `TaskStatusChangedEvent`, `GitPrOpenedEvent`, `GitPrMergedEvent`, `CiFailureSummarizedEvent`, `RiskDeadlineFlaggedEvent`. Gọi `BoardApi.findTask` lấy `assigneeId`/`workspaceId`, kiểm `AuthApi.isWorkspaceMember`, chèn DB trong `REQUIRES_NEW` với `ON CONFLICT (event_id, recipient_id) DO NOTHING`, rồi đẩy STOMP `/user/{recipientId}/queue/notifications`. **Hiện chưa có bất kỳ integration test nào cho `notification-impl` trong `:app`** (các test hiện có trong `notification-impl` đều mock `BoardApi`/`AuthApi`/`NotificationService` hoặc mock `SimpMessagingTemplate`). | **Khoảng trống lớn nhất mà T-041 phải lấp đầy:** lần đầu tiên ghép thật `gitci + board + auth + notification` trong `:app` trên PostgreSQL 17, đối chiếu đồng thời bảng `notifications`, REST `/api/v1/notifications`, và STOMP `/user/queue/notifications`. |
| [AiEventListeners.java](../../backend/ai-impl/src/main/java/io/devflow/ai/internal/AiEventListeners.java), [CiFailureSummarizerService.java](../../backend/ai-impl/src/main/java/io/devflow/ai/internal/ci/CiFailureSummarizerService.java), [CiLogFetcher.java](../../backend/ai-impl/src/main/java/io/devflow/ai/internal/ci/CiLogFetcher.java), [AiCiExecutor.java](../../backend/ai-impl/src/main/java/io/devflow/ai/internal/ci/AiCiExecutor.java) | Lắng nghe `TaskCreatedEvent`, `TaskStatusChangedEvent` (log + metric `devflow.task.events.consume{consumer="ai"}`) và `CiFailureDetectedEvent` (submit bất đồng bộ lên thread pool `ai-ci-*`, chạy `DeterministicChatModel` khi `model=none`, lưu `ai_ci_summaries` unique theo `pipeline_id`, phát `CiFailureSummarizedEvent` với `relatedTaskId = null`). | Phải kiểm chứng đồng bộ `AiCiExecutor` bằng chờ có giới hạn (bounded await), kiểm tra idempotency `ai_ci_summaries`, đồng thời xử lý minh bạch độ lệch hợp đồng CI (chi tiết ở §1.3). |
| [CiFailureEventOutbox.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/CiFailureEventOutbox.java), [CiFailureLogReader.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/CiFailureLogReader.java), [CiLogFetchWorker.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/CiLogFetchWorker.java) | `CiLogFetchWorker` tải/lọc log ZIP từ GitHub (hoặc stub), lưu `raw_log_snippet` đã redact, tạo `ci_failure_event_outbox`; `CiFailureEventOutbox` phát `CiFailureDetectedEvent` với `rawLogReference = "ci-log:<failureId>"`; `CiFailureLogReader` phục vụ đọc đồng bộ `CiFailureLogReadRequestedEvent`. | Cho phép chạy chuỗi `workflow_run` → `CiLogFetchWorker` → `CiFailureEventOutbox` → `AiEventListeners` thật trong `:app` bằng `@MockitoBean GitHubActionsReadClient`. |
| [GitTaskTransitionIntegrationTest.java](../../backend/app/src/test/java/io/devflow/board/api/GitTaskTransitionIntegrationTest.java), [GitTaskTransitionDefaultFlagIntegrationTest.java](../../backend/app/src/test/java/io/devflow/board/api/GitTaskTransitionDefaultFlagIntegrationTest.java), [AccountDeletionApiIntegrationTest.java](../../backend/app/src/test/java/io/devflow/auth/api/AccountDeletionApiIntegrationTest.java) | Đã phủ rất sâu từng lát cắt riêng lẻ (chuyển cột Git T-024, xóa tài khoản T-009, đọc GitCI T-026), nhưng `GitTaskTransitionIntegrationTest` **không hề kiểm tra** bảng `notifications`, REST Notification hay STOMP `/user/queue/notifications`; `CiFailureWorkerAndContractIntegrationTest` chỉ dùng `ProbeConsumer` giả mà không kiểm tra `ai_ci_summaries` của `AiEventListeners` thật. | T-041 không sao chép thừa test đơn lẻ đã có của T-021..T-026 mà tập trung vào **khớp nối toàn chuỗi giữa các module** (`gitci + board + auth + notification + ai`) và các điểm giao cắt mà test cục bộ bỏ sót. |
| [backend/build.gradle.kts](../../backend/build.gradle.kts) (`:verifyModuleBoundaries`) | Hiện kiểm tra không có subproject nào khác `:app` phụ thuộc vào `*-impl` trong Gradle configurations. Chưa kiểm tra luật `:common -> nothing`, `:X-api -> :common only`, và chưa kiểm tra source-level imports (`io.devflow.<other>.internal.*`) hoặc annotation JPA/Spring lạc vào `-api`. | Gia cố `:verifyModuleBoundaries` theo đúng bảng luật `ARCHITECTURE.md §10` và bổ sung bài test kiến trúc tĩnh `ModuleBoundaryArchitectureTest` trong `:app`. |
| [WebhookQuota.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/WebhookQuota.java), [GitHubIntegrationQuota.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitHubIntegrationQuota.java), [NotificationRateLimiter.java](../../backend/notification-impl/src/main/java/io/devflow/notification/internal/NotificationRateLimiter.java) | `WebhookQuota` dùng in-memory bucket (60 global, 100/IP), `GitHubIntegrationQuota` (60/repositoryId, refill 2/s), `NotificationRateLimiter` (Bucket4j per-user). | Phải tính toán ngân sách request và tách `repositoryId` / fresh `userId` giữa các test để chạy lặp nhiều lần liên tiếp không bao giờ bị lỗi giả `429`. |

### 1.3. Các phát hiện kỹ thuật quan trọng cần lưu ý và phối hợp liên module

Quá trình đọc sâu mã nguồn phát hiện **4 điểm giao cắt quan trọng** quyết định trực tiếp độ chính xác của oracle kiểm thử và phạm vi phối hợp với Tiến (`@tienlam15`):

1. **Sự khác biệt chính xác về số lượng Notification giữa `push` và `pull_request` (`opened`/`merged`):**
   - **Webhook `push`:** `NotificationEventListeners` *không* lắng nghe `GitCommitLinkedEvent`. Do đó notification chỉ sinh ra gián tiếp khi Board thực sự đổi cột (`TODO → IN_PROGRESS`) và phát `TaskStatusChangedEvent(cause=GIT, actorId=null)` → tạo **đúng 1 dòng** `notifications` với `type = "task.status_changed"`. Nếu task đã ở `IN_PROGRESS` (`NOOP`) hoặc `auto-transition-enabled=false` (`DISABLED`), số notification mới là **0**.
   - **Webhook `pull_request` (`opened` hoặc `closed` với `merged=true`):** `NotificationEventListeners` lắng nghe **cả hai** sự kiện:
     - `GitPrOpenedEvent` / `GitPrMergedEvent` (trực tiếp từ `GitDomainEventOutbox`) → tạo 1 dòng `type = "git.pr_opened"` hoặc `"git.pr_merged"` với `event_id = legacyEventId(event)` (UUID v3 xác định theo payload đã bỏ `actorId`).
     - `TaskStatusChangedEvent` (gián tiếp từ `Board` nếu task thực sự tiến cột `→ IN_REVIEW` hoặc `→ DONE`) → tạo thêm 1 dòng `type = "task.status_changed"` với `event_id = statusEvent.eventId()`.
     - **Hệ quả cho Oracle:** khi `auto-transition-enabled=true` và task tiến cột, 1 PR opened/merged trên task có assignee hợp lệ tạo ra **2 thông báo phân biệt** (`task.status_changed` + `git.pr_opened`/`git.pr_merged`); khi `auto-transition-enabled=false` hoặc `NOOP`, chỉ tạo **1 thông báo** (`git.pr_opened`/`git.pr_merged`).
2. **Độ lệch hợp đồng CI giữa `gitci-impl` (T-025), `ai-impl` (T-031) và `notification-impl` (T-040A):**
   - `CiFailureEventOutbox` (T-025) phát `CiFailureDetectedEvent` với `pipelineId = <internal attempt UUID>` và `rawLogReference = "ci-log:<failure UUID>"`, đồng thời `CiFailureLogReader` chờ `CiFailureLogReadRequestedEvent` để trả log đã lọc theo provenance.
   - Tuy nhiên tại baseline `develop` (`09fe624`), `CiLogFetcher.fetch(rawLogReference)` trong `ai-impl` (T-031 merge trước T-025) chỉ nhận diện `https://` hoặc inline text — **chưa gọi `CiFailureLogReadRequestedEvent`** khi gặp tiền tố `ci-log:`, nên hiện coi chuỗi `"ci-log:<uuid>"` như inline text. Dù vậy, `CiFailureSummarizerService` vẫn lưu thành công 1 dòng vào `ai_ci_summaries` (qua `DeterministicCiSummaryResponder` khi `model=none`) và phát `CiFailureSummarizedEvent(pipelineId, summary, relatedTaskId = null, occurredAt)`.
   - Vì `relatedTaskId = null`, `NotificationEventListeners.on(CiFailureSummarizedEvent)` trả sớm (`outcome = "skipped"`, 0 dòng `notifications`).
   - **Quyết định kỹ thuật T-041:** Không âm thầm chỉnh sửa code thuộc quyền sở hữu của Tiến (`ai-impl`, `notification-impl`). T-041 kiểm thử trung thực toàn bộ chuỗi thật đang chạy (`workflow_run` → `CiLogFetchWorker` → `CiFailureEventOutbox` → `AiEventListeners` → `ai_ci_summaries` + `CiFailureSummarizedEvent`), kiểm thử riêng hợp đồng `CiFailureLogReadRequestedEvent` ↔ `CiFailureLogReader` và `CiFailureSummarizedEvent(relatedTaskId != null)` ↔ `NotificationEventListeners`, đồng thời mở **Gate phối hợp G-AI với Tiến** để nâng cấp `CiLogFetcher` đọc `ci-log:` và giải quyết `relatedTaskId` ở ticket/PR phối hợp.
3. **Tương tác `SecurityContextHolder` với `TaskStatusChangedEvent(cause=GIT)` trong `NotificationEventListeners`:**
   - Tại [NotificationEventListeners.java:86-87](../../backend/notification-impl/src/main/java/io/devflow/notification/internal/NotificationEventListeners.java#L86-L87):
     `boolean gitEvent = event instanceof GitPrOpenedEvent || event instanceof GitPrMergedEvent;`
     `UUID actor = actorId == null && !gitEvent ? currentActor() : actorId;`
   - Khi `TaskStatusChangedEvent` có `cause = GIT` và `actorId = null`, nếu luồng gọi vô tình còn giữ `SecurityContextHolder` của chính `assignee`, `currentActor()` trả về `assignee` khiến `recipient.equals(actor)` thành `true` và thông báo bị bỏ qua âm thầm!
   - Trên production, `GitDomainEventOutbox.poll()` chạy trên thread `@Scheduled` nên `SecurityContextHolder` trống. Trong T-041, vừa phải bảo đảm dọn sạch `SecurityContextHolder` trước khi chạy `outbox.dispatch()`, vừa ghi nhận phát hiện này để phối hợp Tiến cân nhắc bổ sung điều kiện `cause != StatusChangeCause.GIT` trong `NotificationEventListeners` nhằm loại bỏ bẫy tiềm ẩn này triệt để.
4. **Tính tương thích baseline `develop` (V19) vs nhánh T-026 (V20):**
   - `feature/T-026-git-badges-on-cards` hiện ở cột `In Review` trên Trello (bổ sung `V20__pull_request_head_sha.sql` và sự kiện `TaskGitActivityChangedEvent`).
   - Nếu T-026 đã merge vào `develop` trước khi triển khai T-041, nhánh `feature/T-041-integration-test-suite` tách từ `develop` mới nhất sẽ tích hợp luôn test cho `TaskGitActivityChangedEvent` (trường hợp commit thứ 2 / CI cập nhật phát STOMP `CARD_UPDATED(gitActivity)`). Nếu T-041 tách trước khi T-026 merge, phần test `TaskGitActivityChangedEvent` được đánh dấu rõ điều kiện phụ thuộc và chạy kích hoạt ngay sau khi rebase lên `develop` có T-026.

### 1.4. Phạm vi, ngoài phạm vi và các Gate kiểm soát

**Trong phạm vi T-041:**
- Xây dựng bộ kiểm thử tích hợp liên module tập trung trong `:app` (`backend/app/src/test/java/io/devflow/integration/...`) chạy trên PostgreSQL 17 Testcontainers + Flyway đầy đủ + Spring Boot web/STOMP thật.
- Kiểm chứng chuỗi đầy đủ: `Webhook push/PR → Git event → Board tự đổi trạng thái → Notification lưu DB + REST đọc được + STOMP đẩy đúng kênh`, cùng các biến thể tiêu cực (HMAC sai, không tìm thấy task, cross-tenant, board lưu trữ, webhook lặp, không có assignee, revoked member, lỗi/rollback, concurrency).
- Kiểm chứng các chuỗi liên module còn lại trên cùng Event Bus: `CI failure → AI summarizer → Notification`, `Board manual CRUD → Notification/AI`, `Task/Account/Workspace deletion → cleanup đa module`.
- Gia cố kiểm tra ranh giới module: nâng cấp `:verifyModuleBoundaries` trong `backend/build.gradle.kts` (đủ ma trận `ARCHITECTURE.md §10`) và thêm `ModuleBoundaryArchitectureTest` kiểm tra import tĩnh ở mức mã nguồn.
- Xác minh chạy lặp ổn định (repeatability), quan sát metrics/health/privacy logs và vượt qua 100% `./gradlew :verifyModuleBoundaries check`.

**Ngoài phạm vi T-041:**
- Tự ý thay đổi hành vi nghiệp vụ cốt lõi trong `auth-impl`, `ai-impl`, `notification-impl` của Tiến khi chưa thống nhất (mọi lỗi phát hiện trong module của Tiến sẽ được tái hiện bằng test và phối hợp xử lý chung).
- Kiểm thử E2E trình duyệt React/Vite (đã phủ ở T-016..T-018, T-026 và T-040B) hoặc tải trọng benchmark hệ thống phân tán ngoài single-instance monolith.
- Thiết lập Grafana dashboard (T-042) hay triển khai cloud PaaS (T-043).

| Gate | Điều kiện cần chốt trước khi nghiệm thu | Phụ trách |
|---|---|---|
| **G1 — Baseline & Môi trường Test** | Chốt commit gốc `develop` (trước hay sau khi merge PR T-026); tách nhánh `feature/T-041-integration-test-suite`; Docker Desktop / Testcontainers PostgreSQL 17 hoạt động ổn định. | Xuân (`@xuanle2`) |
| **G2 — Chuỗi vàng Push/PR → Board → Notification** | Xác minh đầy đủ 4 lớp oracle (HTTP/REST, DB thật, STOMP 2 kênh, metrics/log) cho happy path, default-flag `false`, và toàn bộ ca bị từ chối/trùng lặp. | Xuân (`@xuanle2`) |
| **G3 — Phối hợp consumer AI & Notification** | Trao đổi với Tiến về 2 phát hiện: (a) `CiLogFetcher` đọc `ci-log:<uuid>` qua `CiFailureLogReadRequestedEvent` + `relatedTaskId` ở `CiFailureSummarizedEvent`, (b) `NotificationEventListeners` không đọc `currentActor()` khi `TaskStatusChangedEvent.cause == GIT`. Chốt hướng giữ nguyên test theo contract hiện hành hay đồng thời vá phối hợp. | Xuân + Tiến (`@tienlam15`) |
| **G4 — Ranh giới Module & Độ ổn định** | `:verifyModuleBoundaries` + `ModuleBoundaryArchitectureTest` PASS 0 vi phạm; bộ test T-041 chạy lặp 3 lần liên tiếp 100% PASS, không dính flake `429` hay timing. | Xuân (`@xuanle2`) |
| **G5 — Full Regression & Nghiệm thu** | `./gradlew :verifyModuleBoundaries check` PASS 100% toàn bộ subprojects; cập nhật đầy đủ cột `Thực tế` + bằng chứng trong plan; mở PR review trước khi merge và cập nhật Trello. | Xuân + reviewer |

---

## 2. Kế hoạch nghiệp vụ — mô tả luồng bằng ngôn ngữ

### NV-01. Chuỗi tự động hóa cốt lõi khi lập trình viên đẩy commit (`push`)

1. Một thành viên trong workspace (`Người phụ trách`) được giao một task có mã `T-001` đang ở cột `To Do`, trên một bảng Kanban đã liên kết với repository GitHub của workspace. Đồng thời trong workspace còn có một đồng nghiệp khác (`Đồng nghiệp`) và ở workspace khác có một người ngoài (`Người ngoài`).
2. Lập trình viên đẩy một commit lên nhánh làm việc với thông điệp nhắc tới `T-001`. GitHub gửi thông báo `push` có chữ ký hợp lệ về DevFlow.
3. Hệ thống xác minh thông báo đúng của repository đã đăng ký, tra cứu và xác nhận `T-001` là task hợp lệ thuộc đúng workspace, lưu bản ghi commit và liên kết commit–task, rồi ghi nhận sự kiện để phát đi an toàn.
4. Khi sự kiện được phát qua bus nội bộ, module Board tự động di chuyển thẻ `T-001` từ cột `To Do` sang cột `In Progress`, cập nhật lại vị trí các thẻ trong cả hai cột và ghi nhật ký kiểm toán với nguồn gốc là tự động hóa Git.
5. Ngay sau khi việc đổi cột được lưu chắc chắn vào cơ sở dữ liệu, Board phát thông báo đổi trạng thái task. Module Notification tiếp nhận, tìm đúng `Người phụ trách` của `T-001`, xác minh người này vẫn đang là thành viên hợp lệ của workspace, rồi lưu một thông báo chưa đọc mới vào cơ sở dữ liệu.
6. Kết quả người dùng thấy được:
   - Trên giao diện bảng Kanban, cả `Người phụ trách` lẫn `Đồng nghiệp` đang mở bảng đều nhận cập nhật tức thì thấy `T-001` đã sang `In Progress`.
   - `Người phụ trách` nhận được thông báo tức thì trên kênh thông báo cá nhân; khi gọi API xem hộp thư thông báo và số lượng chưa đọc thấy đúng thông báo gắn với `T-001`, và có thể đánh dấu đã đọc.
   - `Đồng nghiệp` (không phải người phụ trách) và `Người ngoài` (khác workspace) **không** nhận được thông báo nào cho `T-001`.

### NV-02. Vòng đời Pull Request (`opened` → `merged`) và hai chế độ bật/tắt tự chuyển cột

1. Khi lập trình viên mở Pull Request có tiêu đề hoặc tên nhánh chứa `T-001` (`action = opened`), hệ thống liên kết PR với `T-001` và phát sự kiện PR đã mở.
   - Nếu chế độ tự động chuyển cột đang **bật**: thẻ `T-001` tự động tiến sang cột `In Review`; `Người phụ trách` nhận đủ thông báo đổi trạng thái task và thông báo có Pull Request mới mở gắn với task của mình.
   - Nếu chế độ tự động chuyển cột đang **tắt** (mặc định an toàn khi mới bật hệ thống): thẻ `T-001` **giữ nguyên** ở cột cũ, không có nhật ký đổi cột, nhưng `Người phụ trách` **vẫn nhận được** thông báo có Pull Request mới mở mà không bị lỗi hay nghẽn hàng đợi.
2. Khi Pull Request được duyệt và gộp vào nhánh chính (`action = closed`, `merged = true`), hệ thống cập nhật trạng thái PR thành đã merge và phát sự kiện PR đã merge:
   - Nếu tự động chuyển cột đang **bật**: thẻ `T-001` tự động tiến sang cột `Done`; `Người phụ trách` nhận thông báo hoàn thành trạng thái và thông báo PR đã được merge.
   - Nếu PR chỉ bị đóng mà **không merge** (`merged = false`), hoặc chỉ sửa mô tả/tiêu đề (`edited`): thẻ không đổi cột và không sinh thêm thông báo mở/merge PR giả.
3. Khi một commit duy nhất gắn đồng thời nhiều task (ví dụ `T-001` giao cho `Người A` và `T-002` giao cho `Người B`), cả hai thẻ đều tự chuyển sang `In Progress` và mỗi người chỉ nhận đúng thông báo thuộc về thẻ của riêng mình.

### NV-03. Những trường hợp hợp lệ nhưng không gửi thông báo

1. **Task chưa giao cho ai:** Commit đẩy lên với mã task chưa có người phụ trách vẫn liên kết commit và tự động chuyển thẻ sang `In Progress`, nhưng hệ thống không tạo bản ghi thông báo mồ côi nào.
2. **Người phụ trách đã bị gỡ khỏi workspace:** Nếu thành viên từng được giao `T-001` vừa bị quản trị viên gỡ khỏi workspace trước khi webhook xử lý xong, thẻ vẫn có thể được hệ thống cập nhật theo Git nhưng người đã bị thu hồi quyền tuyệt đối không nhận được thông báo mới và không thể đọc dữ liệu bảng hay kênh realtime.
3. **Đẩy commit tiếp theo khi task đã ở hoặc vượt qua trạng thái mục tiêu:** Khi `T-001` đã ở `In Progress` (hoặc `In Review`/`Done`) và lập trình viên đẩy thêm commit thứ hai, commit vẫn được lưu liên kết với thẻ nhưng thẻ không bị nhảy lại hay thụt lùi cột, và không phát thêm thông báo đổi trạng thái thừa.
4. **Người dùng tự thao tác trên thẻ của chính mình:** Khi `Người phụ trách` tự tạo task gán cho mình hoặc tự kéo thả thẻ của mình sang cột khác, hệ thống không gửi thông báo tự nhắc cho chính họ; chỉ khi đồng nghiệp khác giao việc hoặc di chuyển thẻ của họ thì thông báo mới được lưu và gửi đi.

### NV-04. Từ chối tuyệt đối các webhook giả mạo, sai cấu trúc hoặc trái phạm vi

1. **Chữ ký sai, thiếu hoặc nội dung bị sửa giữa đường:** Mọi yêu cầu webhook có chữ ký HMAC không khớp, thiếu header bắt buộc, ký bằng khóa cũ đã hết hạn hoặc bị chỉnh sửa dù chỉ 1 ký tự đều bị từ chối ngay tại cổng tiếp nhận (`401`), không ghi biên nhận, không lưu commit, không đổi cột và không tạo thông báo.
2. **Giả mạo repository hoặc payload hỏng:** Webhook mang mã repository GitHub không khớp với cấu hình liên kết, định dạng JSON hỏng, mã SHA toàn số 0 hoặc vượt giới hạn kích thước đều bị từ chối (`400`/`401`/`413`/`415`/`422`) mà không để lại dữ liệu rác.
3. **Không tìm thấy task hoặc sai workspace:** Commit nhắc đến mã `T-999` không tồn tại, hoặc nhắc đến mã `T-001` nhưng `T-001` đó chỉ tồn tại ở workspace B (không phải workspace A của repository), thì commit vẫn có thể được lưu ở phạm vi repo A nhưng **không tạo liên kết task**, **không đổi cột bất kỳ task nào ở workspace B** và **không tạo thông báo** cho ai.
4. **Bảng đã lưu trữ (`archived`):** Task nằm trên bảng đã lưu trữ ở chế độ chỉ đọc sẽ không bị tự động đổi cột hay sinh thông báo đổi trạng thái khi nhận webhook Git.

### NV-05. Chống trùng lặp, chịu sai thứ tự và an toàn khi gặp sự cố

1. **GitHub gửi lặp cùng webhook:** Nếu GitHub gửi lại đúng lần giao hàng cũ (do mạng chập chờn) hoặc gửi một lần giao hàng mới nhưng chứa cùng commit/PR đã xử lý, hệ thống ghi nhận an toàn (`204`), không tạo thêm commit trùng, không chuyển cột lại và **không tạo bản sao thông báo** cho người phụ trách.
2. **Xung đột cùng mã giao hàng khác nội dung:** Nếu cùng mã `X-GitHub-Delivery` nhưng nội dung bị tráo đổi khác trước, hệ thống báo xung đột (`409`) và giữ nguyên toàn bộ dữ liệu cũ.
3. **Phát lại sự kiện sau sự cố máy chủ:** Nếu quá trình phát sự kiện bị ngắt giữa chừng sau khi Board đã lưu xong nhưng trước khi hàng đợi đánh dấu hoàn tất, lần chạy phục hồi tiếp theo có thể phát lại đúng sự kiện đó. Cả Board lẫn Notification đều tự nhận diện sự kiện trùng và bỏ qua an toàn — không tăng số lượng thông báo hay nhật ký kiểm toán.
4. **Sự kiện đến ngược thứ tự và tôn trọng thao tác thủ công:** Nếu sự kiện PR merge đến trước commit push, thẻ đã lên `Done` sẽ không bao giờ bị kéo lùi về `In Progress`. Nếu thành viên vừa kéo tay mở lại thẻ từ `Done` về `To Do`, mọi sự kiện Git cũ hơn hoặc bằng thời điểm thao tác tay đó đều bị bỏ qua.
5. **Cách ly lỗi giữa các module:** Nếu cột đích trên Board đang đầy (lỗi tạm thời), việc chuyển cột tự động bị hoàn tác sạch và **không** sinh thông báo giả; sau khi giải phóng chỗ trống, hàng đợi tự thử lại và hoàn tất trọn vẹn cả đổi cột lẫn thông báo. Ngược lại, nếu dịch vụ thông báo gặp trục trặc tạm thời khi gửi realtime, trạng thái cột đã đổi trên Board vẫn được bảo toàn nguyên vẹn.

### NV-06. Chuỗi phân tích lỗi CI tự động qua AI và hợp đồng dữ liệu nhạy cảm

1. Khi một quy trình GitHub Actions kết thúc thất bại (`workflow_run`, `action = completed`, `conclusion = failure`), hệ thống lưu bản ghi lần chạy lỗi, lập lịch tải và lọc bỏ thông tin nhạy cảm (token, mật khẩu, URL ký tên) từ log ở nền.
2. Khi đoạn log đã sạch sẵn sàng, hệ thống phát sự kiện lỗi CI qua Event Bus. Module AI tiếp nhận ở luồng nền riêng (không làm nghẽn luồng webhook), tạo bản tóm tắt ngắn gọn 3 gạch đầu dòng và lưu bền vững (chống trùng lặp nếu sự kiện phát lại), rồi phát sự kiện đã tóm tắt lỗi CI.
3. Các lần chạy thành công, bị hủy hoặc bỏ qua tuyệt đối không tạo bản ghi lỗi CI, không gọi AI và không sinh thông báo lỗi.
4. Quyền truy cập đoạn log lỗi CI luôn được kiểm soát chặt theo bằng chứng sự kiện hợp lệ hoặc quyền thành viên workspace; mọi yêu cầu giả mạo hoặc chéo workspace đều bị từ chối. Khi lỗi CI được gắn với task cụ thể, người phụ trách hợp lệ nhận được thông báo tương ứng.

### NV-07. Đồng bộ realtime đa kênh và kiểm soát quyền truy cập

1. Hai thành viên hợp lệ cùng mở bảng Kanban đều nhận khung cập nhật thay đổi cột trên kênh bảng (`/topic/boards/{boardId}`).
2. Chỉ đúng người phụ trách task mới nhận khung thông báo cá nhân trên kênh riêng (`/user/queue/notifications`).
3. Mọi nỗ lực kết nối hoặc đăng ký nghe lén kênh thông báo thô (`/queue/**`), kênh của người dùng khác hoặc kênh bảng của workspace khác đều bị chặn ngay tại lớp kiểm soát WebSocket; thành viên vừa bị gỡ khỏi workspace không còn nhận được khung cập nhật mới.

### NV-08. Thao tác thủ công trên Board và các tín hiệu bổ sung từ AI

1. Khi một thành viên tạo task mới và giao cho đồng nghiệp, hoặc kéo thả task của đồng nghiệp sang cột khác qua REST API, Board phát sự kiện tương ứng để Notification lưu thông báo mới cho đúng người phụ trách và AI cập nhật bộ đếm tiêu thụ sự kiện.
2. Khi AI phát tín hiệu cảnh báo rủi ro trễ hạn (`risk.deadline_flagged`) gắn với một task cụ thể, Notification lưu cảnh báo cho người phụ trách (nếu vẫn thuộc workspace); cảnh báo cấp sprint chưa gắn task cụ thể được bỏ qua an toàn theo thiết kế hiện hành.

### NV-09. Dọn dẹp vòng đời dữ liệu xuyên module (Task, Tài khoản GDPR, Workspace)

1. **Xóa task:** Khi một task bị xóa trên Board, các liên kết commit/PR và các sự kiện Git chưa phát của task đó ở module Git & CI được dọn sạch trong cùng giao dịch, trong khi liên kết của task khác dùng chung cùng commit vẫn được giữ nguyên.
2. **Xóa tài khoản người dùng (GDPR):** Khi người dùng xóa tài khoản ở module Auth, các task từng giao cho họ tự động chuyển thành chưa giao, tên tác giả bình luận và lịch sử kiểm toán đổi sang người dùng đã xóa (giữ nguyên nội dung nhóm), và toàn bộ thông báo thuộc người dùng đó bị xóa sạch.
3. **Xóa workspace:** Khi workspace bị xóa ở module Auth, toàn bộ repository/commit/PR/CI thuộc workspace đó ở module Git & CI và toàn bộ bảng/cột/task ở module Board đều được dọn sạch đồng bộ.

### NV-10. Kỷ luật ranh giới kiến trúc và độ ổn định khi chạy lặp

1. Không module nghiệp vụ nào được phép truy cập trực tiếp vào mã nguồn nội bộ hay bảng dữ liệu của module khác; mọi phối hợp đều đi qua lớp hợp đồng công khai (`-api`) hoặc sự kiện `DevFlowEvent` trong `common`.
2. Toàn bộ bộ kiểm thử liên module có thể chạy lại nhiều lần liên tiếp trên môi trường sạch hoặc nối tiếp nhau với dữ liệu hoàn toàn độc lập, luôn cho kết quả ổn định 100%, không phụ thuộc thứ tự chạy test.

---

## 3. Kế hoạch kỹ thuật — phân tích kiến trúc và các yếu tố triển khai

### KT-01. Kiến trúc tổng thể Event Bus và Ma trận phủ 14 hợp đồng `DevFlowEvent`

Sơ đồ dưới mô tả chính xác đường đi dữ liệu, ranh giới transaction và các điểm kiểm chứng (Oracle `O1`–`O4`) trong cùng một Spring `ApplicationContext` (`:app`):

```text
                        ┌──────────────────────────────────────────────────────┐
                        │  HTTP / WebSocket Entrypoints (:app + SecurityChain) │
                        └───────────┬──────────────────────────┬───────────────┘
                                    │                          │
        POST /api/v1/gitci/webhook/github (HMAC)      JWT REST / STOMP /ws
                                    │                          │
                                    ▼                          ▼
                       ┌────────────────────────┐   ┌─────────────────────┐
                       │      gitci-impl        │   │      auth-impl      │
                       │ GitHubWebhookService   │   │ JwtTokenProvider    │
                       │ (Tx A: lock repo,      │   │ MembershipListener  │
                       │  receipt, ingest)      │   │ AccountDeletionSvc  │
                       └─────┬────────────┬─────┘   └──────────┬──────────┘
                             │            │                    │
       sync MANDATORY        │            │ commit             │ workspace.membership_check_requested
   task.references_resolve   │            ▼                    │ user.account_deleted / workspace.deleted
             ┌───────────────┘   ┌──────────────────┐          │
             ▼                   │ Outbox Workers   │          │
   ┌───────────────────┐         │ GitDomainEvent   │          │
   │    board-impl     │         │ CiLogFetchWorker │          │
   │ TaskRefResolver   │         │ CiFailureOutbox  │          │
   │ (FOR SHARE lock)  │         └────────┬─────────┘          │
   └───────────────────┘                  │ outside-tx publish │
                                          ▼                    ▼
         ╔══════════════════════════════════════════════════════════════════╗
         ║           In-Process Spring Event Bus (ApplicationEvent)         ║
         ╚═══════╤════════════════════════╤════════════════════════╤════════╝
                 │                        │                        │
   git.commit_linked /              git.pr_opened /         ci.failure_detected /
   git.pr_opened /                  git.pr_merged /         task.created /
   git.pr_merged /                  task.status_changed /   task.status_changed
   git.task_activity_changed        task.created /                 │
                 │                  ci.failure_summarized /        ▼
                 ▼                  risk.deadline_flagged   ┌───────────────────┐
       ┌───────────────────┐              │                 │     ai-impl       │
       │    board-impl     │              │                 │ AiEventListeners  │
       │ GitTaskTransition │              ▼                 │ AiCiExecutor      │
       │ (Tx B REQUIRES_NEW├────► ┌──────────────────────┐  │ DeterministicLLM  │
       │  FOR UPDATE board)│      │  notification-impl   │  │ -> ai_ci_summaries│
       │  afterCommit:     │      │ NotificationListeners│  │ -> ci.failure_    │
       │  - TaskStatusChg ─┼─────►│ (BoardApi + AuthApi) │  │    summarized ────┼──► Bus
       │  - STOMP /topic   │      │ NotificationService  │  └───────────────────┘
       └─────────┬─────────┘      │ (Tx C REQUIRES_NEW)  │
                 │                │ -> DB notifications  │
                 ▼                │ -> STOMP /user/queue │
         [O3: STOMP Board]        └──────────┬───────────┘
                                             ▼
                          [O1: REST API] [O2: PG17 DB] [O3: STOMP User] [O4: Metrics/Logs]
```

**Ma trận 14 hợp đồng sự kiện `DevFlowEvent` trong T-041:**

| # | Sự kiện (`EventTypes`) | Producer | Consumer thật trong `:app` | Ranh giới Transaction / Thread | Nhóm test phủ trong T-041 |
|---|---|---|---|---|---|
| 1 | `task.references_resolve_requested` | `gitci-impl` (`GitWebhookIngestion`) | `board-impl` (`TaskReferenceResolver`) | Đồng bộ, `MANDATORY` tham gia Tx A (`FOR SHARE`) | `E2E-01..06`, `NEG-05..07` |
| 2 | `git.commit_linked` | `gitci-impl` (`GitDomainEventOutbox`) | `board-impl` (`GitActivityEventListener`) | Ngoài Tx, at-least-once outbox | `E2E-01`, `E2E-04..08`, `IDEM-01..10` |
| 3 | `git.pr_opened` | `gitci-impl` (`GitDomainEventOutbox`) | `board-impl` + `notification-impl` | Ngoài Tx, fan-out 2 consumer đồng bộ trên thread dispatch | `E2E-02`, `E2E-04`, `E2E-07`, `IDEM-04..05` |
| 4 | `git.pr_merged` | `gitci-impl` (`GitDomainEventOutbox`) | `board-impl` + `notification-impl` | Ngoài Tx, fan-out 2 consumer | `E2E-03`, `E2E-04`, `E2E-07`, `IDEM-05..06` |
| 5 | `task.status_changed` | `board-impl` (`TaskEventPublisher`) | `notification-impl` + `ai-impl` | `afterCommit()` của Tx B (`GIT` hoặc `MANUAL`) | `E2E-01..04`, `E2E-11`, `LIFE-02..03` |
| 6 | `task.created` | `board-impl` (`TaskEventPublisher`) | `notification-impl` + `ai-impl` | `afterCommit()` của tạo task | `LIFE-01`, `LIFE-02` |
| 7 | `workspace.membership_check_requested` | `board-impl`, `gitci-impl`, `ai-impl` | `auth-impl` (`WorkspaceMembershipCheckListener`) | Đồng bộ request/decision, fail-closed | `NEG-09`, `NEG-11`, `CI-04` |
| 8 | `ci.failure_detected` | `gitci-impl` (`CiFailureEventOutbox`) | `ai-impl` (`AiEventListeners` → `AiCiExecutor`) | Ngoài Tx outbox → bất đồng bộ pool `ai-ci-*` | `CI-01..03`, `CI-07` |
| 9 | `ci.failure_log_read_requested` | Consumer CI (`ai`/contract) | `gitci-impl` (`CiFailureLogReader`) | Đồng bộ request/response + provenance/membership | `CI-04` |
| 10 | `ci.failure_summarized` | `ai-impl` (`AiEventListeners`) | `notification-impl` (`NotificationEventListeners`) | Phát từ `ai-ci-*` sau khi lưu `ai_ci_summaries` | `CI-01`, `CI-05` |
| 11 | `risk.deadline_flagged` | `ai-impl` *(contract T-037)* | `notification-impl` (`NotificationEventListeners`) | Đồng bộ / `AFTER_COMMIT` fallback | `CI-06` |
| 12 | `task.references_removed` | `board-impl` (`TaskReferenceLifecycle`) | `gitci-impl` (`GitTaskReferenceCleanup`) | Đồng bộ `MANDATORY` trong Tx xóa task/board | `LIFE-04` |
| 13 | `user.account_deleted` | `auth-impl` (`AccountDeletionService`) | `board-impl` (`AccountDeletionEventListener`) + FK cascade `notifications` | Enqueue trong Tx Auth, thực thi `AFTER_COMMIT` | `LIFE-05` |
| 14 | `workspace.deleted` | `auth-impl` (`AccountDeletionService`) | `gitci-impl` (`MANDATORY`) + `board-impl` (durable job `AFTER_COMMIT`) | Xóa `repositories` trong Tx Auth + dọn `boards` sau commit | `LIFE-06` |
| *(15)* | `git.task_activity_changed` *(T-026)* | `gitci-impl` (`GitWebhookIngestion`, `CiWorkflowIngestion`) | `board-impl` (`BoardGitActivityRealtimeListener`) | Best-effort `afterCommit()` → STOMP `CARD_UPDATED(gitActivity)` | `E2E-10` *(khi baseline có T-026)* |

### KT-02. Cấu trúc tổ chức Test Suite trong `:app` và Chiến lược Spring Context Caching

Một bài toán kỹ thuật quan trọng khi thêm integration test trong `:app` là **cân bằng giữa phạm vi phủ đầy đủ và thời gian/tài nguyên chạy `./gradlew check`** (hiện tại toàn bộ backend đã có >1.000 test chạy trong ~8 phút trên Windows + Docker Desktop). Nếu mỗi class tạo một `PostgreSQLContainer` riêng hoặc cấu hình `@MockitoBean` khác nhau làm vỡ Spring `TestContext` cache, thời gian build sẽ phình lớn và dễ gây cạn kết nối HikariCP.

**Thiết kế tổ chức gói `backend/app/src/test/java/io/devflow/integration/`:**

| Tệp dự kiến | Loại & Context Spring | Nhiệm vụ cốt lõi |
|---|---|---|
| `MultiModulePostgresSupport.java` | Lớp nền abstract (dùng chung **1 singleton** `PostgreSQLContainer("postgres:17-alpine")` static không bị restart giữa các lớp con cùng JVM) | Cung cấp `@DynamicPropertySource` chuẩn PostgreSQL 17 + Flyway đầy đủ + `ddl-auto=validate`, cấu hình webhook bindings đa repository (`REPO_WS_A`, `REPO_WS_A_ALT`, `REPO_WS_B`, `REPO_CI`), helper ký HMAC SHA-256, helper phát JWT thật, helper kết nối STOMP client thật cho cả `/topic/boards/{boardId}` và `/user/queue/notifications`, và `FixtureBuilder` tạo dữ liệu sạch/độc lập. |
| `GitBoardNotificationFlowIntegrationTest.java` | `@SpringBootTest(RANDOM_PORT)` với `auto-transition-enabled=true`, `outbox.worker-enabled=false`, `ci.fetch-enabled=false`, `ci.publish-enabled=false`, `max-per-column=3` | Bao phủ **Bộ 1 (`E2E-01..06`, `E2E-08..12`)**, **Bộ 2 (`NEG-01..12`)**, và **Bộ 3 (`IDEM-01..10`)**: toàn bộ chuỗi `Webhook push/PR → Git event → Board đổi cột → Notification lưu DB + REST + STOMP`, ca từ chối bảo mật, idempotency, out-of-order, manual watermark, fault isolation và concurrency. Dùng `ApplicationEvents` hoặc listener probe phụ trợ nhẹ (không thay thế bean thật bằng mock!). |
| `GitBoardNotificationDefaultFlagIntegrationTest.java` | `@SpringBootTest(RANDOM_PORT)` với cấu hình mặc định production (`auto-transition-enabled=false`) | Bao phủ **`E2E-07`** và đối chứng `E2E-10`: chứng minh cấu hình mặc định không tự di chuyển cột, không tạo notification `task.status_changed`, nhưng vẫn giao đúng notification `git.pr_opened`/`git.pr_merged` và không làm poison outbox. |
| `CiAiNotificationEventFlowIntegrationTest.java` | `@SpringBootTest(RANDOM_PORT)` với `@MockitoBean GitHubActionsReadClient` (chỉ mock ranh giới mạng ngoài GitHub Actions API; toàn bộ `CiLogFetchWorker`, `CiFailureEventOutbox`, `CiFailureLogReader`, `AiEventListeners`, `CiFailureSummarizerService`, `DeterministicChatModel`, `NotificationEventListeners` đều là bean thật!) | Bao phủ **Bộ 4 (`CI-01..08`)**: chuỗi `workflow_run(failure) → fetch/sanitize → CiFailureDetectedEvent → AiEventListeners → ai_ci_summaries → CiFailureSummarizedEvent → NotificationEventListeners`, kèm contract `CiFailureLogReadRequestedEvent` và `RiskDeadlineFlaggedEvent`. |
| `LifecycleAndManualEventFlowIntegrationTest.java` | `@SpringBootTest(RANDOM_PORT)` dùng chung context chuẩn (có thể gộp cùng context với `GitBoardNotificationFlowIntegrationTest` nếu property tương đồng và không thêm `@MockitoBean` để **tái sử dụng 100% Spring Context cache**!) | Bao phủ **Bộ 5 (`LIFE-01..06`)**: `POST /tasks` (`task.created`), `PATCH /tasks/{id}/move` (`task.status_changed MANUAL`), `DELETE /tasks/{id}` (`task.references_removed`), `DELETE /api/v1/auth/me` (`user.account_deleted` + `workspace.deleted`). |
| `ModuleBoundaryArchitectureTest.java` | Pure JUnit 5 (**không** `@SpringBootTest`, **không** Docker, chạy < 1 giây) | Bao phủ **Bộ 6 (`ARCH-01..03`)**: quét toàn bộ cây thư mục nguồn Java và `build.gradle.kts` của các module kiểm chứng 0 vi phạm ranh giới ở cả tầng Gradle lẫn tầng import/annotation. |

**Phân tích trade-off về Context Caching:**
- Gộp `LifecycleAndManualEventFlowIntegrationTest` và `GitBoardNotificationFlowIntegrationTest` sử dụng cùng cấu hình `@SpringBootTest` (chỉ khác ở lớp CI cần `@MockitoBean GitHubActionsReadClient` và lớp default-flag cần `auto-transition-enabled=false`) giúp cả bộ T-041 chỉ khởi tạo tối đa **3 Spring context trên 1 PostgreSQL container duy nhất**, ước tính tổng thời gian chạy toàn bộ ~55 case chỉ khoảng **30–50 giây**!
- Chú ý về `AccountDeletionService`: trong `LifecycleAndManualEventFlowIntegrationTest`, việc xóa tài khoản gọi `POST /api/v1/auth/delete` hoặc gọi trực tiếp luồng `AccountDeletionService`? Nếu đi qua HTTP `DELETE /api/v1/auth/me` có thể gửi email thông báo hoặc không cần `JavaMailSender` mock tùy code hiện hành — cần kiểm tra `AccountDeletionService` trước khi viết `LIFE-05` để không làm phát sinh thêm context mới không cần thiết.

### KT-03. Phân tích sâu Ranh giới Transaction, Thứ tự Event Bus và Cách ly Lỗi

Để tránh các bài test "qua mặt" bằng giả lập sai bản chất đồng bộ/giao dịch, T-041 phải mô hình hóa chính xác 4 đường ranh giới transaction của hệ thống:

1. **Ranh giới 1 — Webhook Ingestion (`Tx A`, `GitHubWebhookService`):**
   - Khóa `repositories` bằng `FOR UPDATE` (`lock_timeout = 2s`, `statement_timeout = 4s`, tx timeout `6s`).
   - Chèn `github_webhook_receipts` (`PROCESSING`), gọi `TaskReferenceResolver` (`Propagation.MANDATORY` → giữ `FOR SHARE` trên `boards` & `tasks` đến khi Tx A commit), chèn `git_commits` / `pull_requests` / bảng link V16, chèn `git_domain_event_outbox`, cập nhật receipt `COMPLETED`, rồi mới **commit** và trả HTTP `204`.
   - **Điểm kiểm chứng (`IDEM-09`):** Nếu ép lỗi DB trong Tx A (ví dụ trigger lỗi trên `git_domain_event_outbox` hoặc `github_webhook_receipts`), toàn bộ commit/link/outbox phải rollback sạch (`503 WEBHOOK_UNAVAILABLE`), Board và Notification không nhận bất kỳ sự kiện nào.
2. **Ranh giới 2 — Outbox Dispatch (`Out-of-Tx`, `GitDomainEventOutbox`):**
   - Claim row bằng `REQUIRES_NEW` (`FOR UPDATE SKIP LOCKED`), sau đó chạy `publisher.publishEvent(entry.event())` trong `PROPAGATION_NOT_SUPPORTED` (hoàn toàn ngoài transaction!).
   - **Tác động thứ tự fan-out trên `GitPrOpenedEvent` / `GitPrMergedEvent`:**
     - `GitActivityEventListener` (dùng `@EventListener`) và `NotificationEventListeners` (dùng `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`) đều chạy đồng bộ trên luồng `publishEvent(...)` vì không có transaction đang mở (`fallbackExecution=true`).
     - Trong `GitActivityEventListener`, `GitTaskTransitionService.apply()` mở `Tx B (REQUIRES_NEW)`. Nếu `Tx B` throw lỗi transient (ví dụ `TARGET_FULL` hay lock timeout):
       - Thứ tự thực thi giữa 2 listener của Spring là không đảm bảo cố định nếu không có `@Order`!
       - **Phân tích kỹ thuật cực kỳ quan trọng:** Khi `GitPrOpenedEvent` được phát và cột `In Review` đang đầy (`TARGET_FULL`), nếu `NotificationEventListeners.on(GitPrOpenedEvent)` chạy *trước* `GitActivityEventListener.on(GitPrOpenedEvent)`, thì `Notification` vẫn ghi nhận đúng 1 dòng `git.pr_opened` (bởi PR thực sự đã mở trên GitHub!) và `onConflictDoNothing` sẽ chống trùng khi outbox retry; còn `TaskStatusChangedEvent` tuyệt đối **không** được phát cho đến khi Board thực sự di chuyển thành công sau retry! Ngược lại với `GitCommitLinkedEvent` (chỉ có Board lắng nghe), khi `TARGET_FULL` thì **0** notification được sinh ra cho tới khi retry thành công! Oracle của `IDEM-07` phải phân biệt chính xác 2 trường hợp này.
3. **Ranh giới 3 — Board Transition → TaskStatusChangedEvent → Notification (`Tx B` → `afterCommit` → `Tx C`):**
   - `TaskEventPublisher` đăng ký `TransactionSynchronization.afterCommit()` bên trong `Tx B`. Chỉ sau khi `tasks.column_id`, `tasks.position`, `tasks.version`, `board_audit_events`, và `board_git_event_receipts` đã **commit** xong xuôi vào PostgreSQL, `TaskStatusChangedEvent` mới được phát.
   - `NotificationEventListeners.on(TaskStatusChangedEvent)` nhận sự kiện khi `Tx B` đã đóng, gọi `BoardApi.findTask` (thấy trạng thái cột *mới* đã commit!), gọi `AuthApi.isWorkspaceMember`, rồi `NotificationService.deliver` mở `Tx C (REQUIRES_NEW)` ghi bảng `notifications` và commit **trước** khi gọi `SimpMessagingTemplate.convertAndSendToUser(...)`.
   - **Điểm kiểm chứng (`IDEM-08`):** Nếu gây lỗi trong `Tx C` (ví dụ trigger DB lỗi tạm thời trên bảng `notifications`) hoặc lỗi trên STOMP broker:
     - `NotificationEventListeners` bắt `RuntimeException`, ghi metric `devflow.task.events.consume{consumer="notification", outcome="failure"}` và **không** ném ngược làm hỏng `Tx B` (đã commit) hay đẩy outbox sang vòng lặp retry làm lặp chuyển cột.
     - Trạng thái `tasks.column_id`, `board_audit_events` và `board_git_event_receipts` vẫn bảo toàn 100%.
4. **Ranh giới 4 — CI Failure → AI Summarizer (Bất đồng bộ `AiCiExecutor`):**
   - `CiFailureEventOutbox.dispatch()` phát `CiFailureDetectedEvent` đồng bộ tới `AiEventListeners.on(...)`, nhưng `AiEventListeners` submit công việc sang thread pool `ai-ci-*` (`AiCiExecutor`) rồi trả về ngay.
   - Vì vậy sau khi gọi `ciOutbox.dispatch()`, test **tuyệt đối không** dùng `Thread.sleep(...)` cố định mà dùng vòng chờ có hạn (`awaitUntil(Duration.ofSeconds(10), ...)` kiểm tra bảng `ai_ci_summaries` hoặc metric `devflow.task.events.consume{eventType="ci.failure_detected", consumer="ai"}`).

### KT-04. Nguyên tắc 4 lớp Oracle (Kiểm chứng thực tế — không dùng mock thay thế dữ liệu)

Đáp ứng yêu cầu *"kiểm tra kết quả lưu thực tế, không chỉ kiểm tra có gọi hàm"*, mọi test case trọng tâm trong T-041 bắt buộc kiểm chứng đồng thời cả 4 lớp bằng chứng sau:

1. **Oracle 1 — HTTP / REST Contract thật:**
   - Webhook đầu vào trả đúng status (`204`, `400`, `401`, `409`, `413`, `415`, `422`) với `application/problem+json` (RFC 7807: `code`, `timestamp`, `correlationId`, `instance`, 0 stack trace) cho các ca bị từ chối.
   - REST đọc sau xử lý với JWT thật:
     - `GET /api/v1/tasks/{taskId}` xác nhận cột mới (`columnId`), `position`, `version`.
     - `GET /api/v1/notifications` và `GET /api/v1/notifications/unread-count` xác nhận assignee thấy đúng thông báo (`type`, `workspaceId`, `referenceId`, `read=false`, `title`, `content`), sau đó `PATCH /api/v1/notifications/{id}/read` đổi `read=true` và giảm `unread-count` về `0`.
     - Người khác (collaborator / outsider) gọi `GET /api/v1/notifications` nhận danh sách rỗng (`items = []`, `count = 0`), và gọi `PATCH /api/v1/notifications/{assigneeNotifId}/read` nhận `404` (chống IDOR).
2. **Oracle 2 — Trạng thái PostgreSQL 17 thật (truy vấn SQL trực tiếp qua `JdbcTemplate`):**
   - Đối chiếu đồng bộ bảng của 4–5 module: `github_webhook_receipts`, `git_commits`, `git_commit_task_links`, `pull_requests`, `git_pull_request_task_links`, `git_domain_event_outbox` (`status='PUBLISHED'`, `attempts`), `tasks` (`column_id`, `position`, `version`, `last_manual_status_changed_at`), `board_git_event_receipts` (`event_id`, `outcome`), `board_audit_events` (`cause='GIT'`, `actor_id IS NULL`, `source_event_id`), `notifications` (`event_id`, `recipient_id`, `workspace_id`, `type`, `reference_id`, `read_at`), `ci_pipelines`, `ci_failures`, `ai_ci_summaries`.
   - Đối với mọi ca từ chối (`NEG-*`), đối chiếu **ảnh chụp trước và sau (before/after snapshot)** của các bảng liên quan để chứng minh **0 dòng bị thêm/sửa/xóa** (không bỏ sót side effect ẩn).
3. **Oracle 3 — STOMP WebSocket thật trên 2 kênh đồng thời:**
   - Kết nối `WebSocketStompClient` thật tới `ws://localhost:{port}/ws` kèm `Authorization: Bearer <jwt>` trong `CONNECT` headers.
   - Chờ xác nhận subscription thực sự đã đăng ký qua `SimpUserRegistry` (tránh race gửi frame trước khi broker hoàn tất SUBSCRIBE!).
   - Kiểm chứng kênh bảng `/topic/boards/{boardId}` nhận `CARD_MOVED` (hoặc `CARD_UPDATED` với `changedFields=["gitActivity"]`) và kênh cá nhân `/user/queue/notifications` nhận đúng `NotificationResponse` cho `assignee` (còn đồng nghiệp không nhận frame trên `/user/queue/notifications`).
4. **Oracle 4 — Observability & Privacy:**
   - Đối chiếu các counter Micrometer tăng đúng (`devflow.gitci.webhook.requests`, `devflow.gitci.outbox.events`, `devflow.board.git.transitions`, `devflow.task.events.dispatch`, `devflow.task.events.consume{consumer="notification"}`, `devflow.task.events.consume{consumer="ai"}`, `devflow.notification.delivered`).
   - Gắn `ListAppender<ILoggingEvent>` trên các logger `io.devflow` trong các bài test nhạy cảm để khẳng định 0 rò rỉ HMAC secret, JWT, GitHub PAT, secret canary hoặc PII email vào log ứng dụng, bảng `notifications` hay `ai_ci_summaries`.

### KT-05. Cô lập dữ liệu, Quản lý Quota, Chống Flake và Khả năng chạy lặp

Để đáp ứng tiêu chí *"Bài test có thể chạy lại với dữ liệu độc lập và kết quả ổn định"*, cần xử lý triệt để 5 nguyên nhân phổ biến gây test chập chờn (flaky):

1. **Làm sạch DB theo đúng đồ thị khóa ngoại trước mỗi test (`@BeforeEach`):**
   - Thực hiện `DELETE` theo thứ tự an toàn (tránh phụ thuộc `TRUNCATE ... CASCADE` có thể gây lock lâu với kết nối nền):
     `repositories` → `ai_ci_summaries` → `notifications` → `board_git_event_receipts` → `board_audit_events` → `board_account_cleanup_jobs` → `boards` → `board_task_key_counters` → `workspace_members` → `workspaces` → `users`.
   - Xác minh tại cuối `@BeforeEach` số dòng của các bảng lõi đã về `0` (trừ dữ liệu fixture vừa khởi tạo) và `board_task_key_counters` sạch nên `T-001`, `T-002`, ... luôn đánh số xác định ở mỗi test.
2. **Dọn sạch trạng thái luồng (`@BeforeEach` và `@AfterEach`):**
   - Luôn gọi `SecurityContextHolder.clearContext()` và `MDC.clear()` trong `@BeforeEach` lẫn `@AfterEach` (đặc biệt quan trọng vì Phát hiện #3 ở `NotificationEventListeners`!).
3. **Tránh đụng trần Quota / Rate-Limit in-memory (`WebhookQuota`, `GitHubIntegrationQuota`, `NotificationRateLimiter`):**
   - `GitHubIntegrationQuota` cấp 60 token/repo (refill 2/giây) và `WebhookQuota` cấp 60 global / 100 per-IP (refill 30/giây global, 5/giây per-IP).
   - **Giải pháp:**
     - Đăng ký sẵn **3–4 repository binding độc lập** trong `@DynamicPropertySource` (`REPO_WS_A`, `REPO_WS_A_EXTRA`, `REPO_WS_B`, `REPO_CI`) và luân phiên theo nhóm bài test để mỗi repo chỉ nhận < 25 webhook/lần chạy suite (dư biên độ > 2,4x so với trần 60).
     - Mỗi test tạo `UUID.randomUUID()` mới cho toàn bộ `userId` (`owner`, `assignee`, `collaborator`, `outsider`, `revoked`), nên `NotificationRateLimiter` và `BoardRateLimiter` (Bucket4j per-user) luôn cấp bucket mới 100% ở mọi test case.
     - Không viết test cố tình đốt cạn `WebhookQuota` global trong cùng Spring context với các test happy path (đã có `GitHubWebhookHttpIntegrationTest` kiểm tra rate limit webhook riêng ở T-021).
4. **Chờ đồng bộ hóa xác định (Zero blind `Thread.sleep`):**
   - Tắt hoàn toàn các scheduler nền (`worker-enabled=false`, `fetch-enabled=false`, `publish-enabled=false`), gọi tường minh `gitOutbox.dispatch()`, `ciFetchWorker.runOne()`, `ciOutbox.dispatch()`.
   - Với các luồng bất đồng bộ thật (`AiCiExecutor`, STOMP broker, `AccountCleanupProcessor`), dùng `CountDownLatch` / `BlockingQueue.poll(5, SECONDS)` / helper `awaitCondition(Duration.ofSeconds(10), ...)` với chu kỳ poll 25ms.
5. **Kiểm định chạy lặp (Repeatability Verification):**
   - Trong bước `P5`, chạy lặp liên tiếp tối thiểu 3 lần lệnh `./gradlew :app:test --tests "io.devflow.integration.*" --rerun-tasks` để chứng minh 0 test phụ thuộc thứ tự hay dữ liệu thừa.

### KT-06. Bảo mật, Phân quyền, Chống IDOR và Privacy xuyên module

Bộ test T-041 phải kiểm chứng bảo mật ở **điểm giao cắt giữa các module** (nơi lỗ hổng thường lọt qua unit test cục bộ):

- **Tenant boundary xuyên 4 module (`gitci → board → auth → notification`):**
  - Tạo song song `WS-A` và `WS-B`, cả hai đều có task mang mã hiển thị **`T-001`**, mỗi workspace có repository binding và thành viên riêng.
  - Webhook ký bằng khóa của `Repo-A` (`WS-A`) nhắc tới `T-001` chỉ được phép tác động tới `T-001` của `WS-A`, gửi thông báo cho `Assignee-A` của `WS-A`; tuyệt đối không chạm vào `T-001` của `WS-B` và `Assignee-B` nhận `0` thông báo.
  - Nếu `WS-A` không có `T-002` nhưng `WS-B` có `T-002`, webhook từ `Repo-A` nhắc tới `T-002` phải phân giải thành `NOT_FOUND` (0 liên kết, 0 chuyển cột, 0 thông báo ở `WS-B`).
- **Thu hồi quyền tức thời (Revoked membership & Revoked JWT `token_version`):**
  - Khi `Revoked-A` bị xóa khỏi `workspace_members` dù vẫn đang là `tasks.assignee_id` của `T-003`:
    - Webhook `push`/`pull_request` tới `T-003` vẫn để Board xử lý theo nghiệp vụ, nhưng `NotificationEventListeners` kiểm `auth.isWorkspaceMember(Revoked-A, WS-A) == false` → **0 thông báo lưu DB**, **0 frame STOMP**.
    - Kết nối STOMP đang mở của `Revoked-A` trên `/topic/boards/{boardA}` bị `BoardOutboundAuthorizationInterceptor` chặn phân phối frame.
- **Bảo vệ kênh STOMP Notification (`NotificationStompAuthorizationInterceptor`):**
  - Trong full `:app` context (nơi cả `StompAuthenticationInterceptor`, `BoardStompAuthorizationInterceptor` và `NotificationStompAuthorizationInterceptor` cùng đăng ký trên `clientInboundChannel` theo thứ tự `@Order`), chứng minh chúng phối hợp sạch sẽ không xung đột:
    - User hợp lệ subscribe được đồng thời `/topic/boards/{boardA}` và `/user/queue/notifications`.
    - Mọi SUBSCRIBE vào `/queue/notifications`, `/queue/**`, hoặc `/user/{otherUserId}/queue/notifications` đều bị từ chối (`ACCESS_DENIED` / đóng kết nối / không nhận được frame của user khác).
- **Privacy & Secret Canary:**
  - Chèn chuỗi canary (ví dụ `ghp_t041_secret_canary_9999`, `dev-private@canary.local`, `<script>alert('xss')</script>`) vào commit message / PR title / CI ZIP log / webhook secret và xác minh:
    - Không chuỗi secret hay email nào lọt vào `notifications.title`/`content`, `board_audit_events`, `ai_ci_summaries`, `git_domain_event_outbox`, hay application logs.
    - Tiêu đề/nội dung thông báo là văn bản thuần an toàn (không chứa mã HTML/script chưa thoát).

### KT-07. Gia cố kiểm tra Ranh giới Module (`:verifyModuleBoundaries` + `ModuleBoundaryArchitectureTest`)

Theo [ARCHITECTURE.md §10](../ARCHITECTURE.md) và [module-boundaries.md](../../.agents/rules/module-boundaries.md), kiến trúc áp đặt 4 luật cứng:

| Luật | Đặc tả | Hiện trạng `build.gradle.kts` | Bổ sung trong T-041 |
|---|---|---|---|
| **L1 — Không `impl -> impl`** | Không module nào ngoài `:app` được phụ thuộc vào `*-impl` | Đã kiểm trong `:verifyModuleBoundaries` | Giữ nguyên và xác minh bằng test âm tính |
| **L2 — `:common` độc lập tuyệt đối** | `:common` không được phụ thuộc bất kỳ subproject nào | Chưa kiểm trong `:verifyModuleBoundaries` | Bổ sung kiểm tra trực tiếp vào `:verifyModuleBoundaries` |
| **L3 — `*-api` chỉ phụ thuộc `:common`** | `:<module>-api` chỉ được phụ thuộc `:common` (cấm phụ thuộc `-impl`, `-api` khác hoặc `:app`) | Chưa kiểm trong `:verifyModuleBoundaries` | Bổ sung kiểm tra trực tiếp vào `:verifyModuleBoundaries` |
| **L4 — Cô lập package `.internal.` và tầng `-api`** | Không file Java nào trong `:<module>-impl/src/main` hoặc `:<module>-api/src/main` được import `io.devflow.<other>.internal.*`; không file nào trong `*-api/src/main` được dùng `@Entity`, `@Repository`, `@Service`, `@RestController`, `@Component` | Chưa kiểm tự động | Thêm `ModuleBoundaryArchitectureTest` trong `:app` quét toàn bộ file `.java` trong `src/main` của `common`, `*-api`, `*-impl` (không phụ thuộc thêm thư viện ngoài, chạy < 1s) |

**Lưu ý trước khi sửa `backend/build.gradle.kts`:** Đã rà soát toàn bộ `build.gradle.kts` của `common`, `*-api` và `*-impl` ở §1.2 — hiện tại không có subproject nào vi phạm L1–L3 (`ai-impl` và `notification-impl` chỉ phụ thuộc `-api` của module khác, hoàn toàn hợp lệ theo bảng Dependency Matrix của `module-boundaries.md` §2). Khi viết `ModuleBoundaryArchitectureTest`, kiểm tra chạy thực tế trên toàn bộ cây `src/main` để chắc chắn 0 false positive trước khi chốt.

### KT-08. Quy trình xử lý lỗi ở Consumer Notification/AI và Phối hợp với Tiến (`@tienlam15`)

Đáp ứng trực tiếp yêu cầu số 4 trong [17_kiem_thu_tich_hop_lien_module.md](17_kiem_thu_tich_hop_lien_module.md):

1. Nếu trong quá trình chạy bộ test T-041 phát hiện lỗi thuộc `notification-impl` hoặc `ai-impl`:
   - Cô lập tối thiểu kịch bản tái hiện lỗi (tên test, đầu vào, DB/Event/STOMP expected vs actual, file và số dòng cụ thể).
   - **Không** tự ý chỉnh sửa sâu logic nghiệp vụ thuộc quyền sở hữu của Tiến trên nhánh T-041 khi chưa thống nhất.
   - Trao đổi trực tiếp với Tiến (`@tienlam15`) để chọn 1 trong 2 phương án:
     - **Phương án A (ưu tiên nếu lỗi chặn AC của T-041):** Tiến vá trên PR riêng hoặc cùng thống nhất bản vá nhỏ có review của Tiến, sau đó T-041 xác nhận test chuyển sang `PASS`.
     - **Phương án B (nếu là chênh lệch hợp đồng đã biết giữa T-025 và T-031 như Phát hiện #2 ở §1.3):** Bộ test T-041 khẳng định chính xác hành vi hợp đồng đang hoạt động hiện tại (để `./gradlew check` luôn xanh 100%), đồng thời ghi rõ giới hạn còn lại và ticket/issue theo dõi trong báo cáo nghiệm thu & bình luận PR.
2. **Hai điểm cụ thể sẽ trao đổi với Tiến ngay ở bước `P0`:**
   - **Điểm 1 (AI CI Log Reader & Task Resolution):** Nối `CiLogFetcher` (T-031) với `CiFailureLogReadRequestedEvent` của `CiFailureLogReader` (T-025) cho tiền tố `ci-log:<uuid>`, và xác định cách gắn `relatedTaskId` (nếu một CI failure gắn 1 task) để `CiFailureSummarizedEvent` tự động kích hoạt thông báo cho assignee.
   - **Điểm 2 (SecurityContext guard trên `TaskStatusChangedEvent` nguồn `GIT`):** Đề xuất cập nhật điều kiện tại [NotificationEventListeners.java:86-87](../../backend/notification-impl/src/main/java/io/devflow/notification/internal/NotificationEventListeners.java#L86-L87) để `TaskStatusChangedEvent` có `cause == StatusChangeCause.GIT` cũng không bao giờ rơi về `currentActor()` từ `SecurityContextHolder`.

### KT-09. Chiến lược Git Branch và Tương thích với T-026

- **Tên nhánh chuẩn theo Trello & `.agents/rules/git-workflow.md`:** `feature/T-041-integration-test-suite`.
- **Quyết định gốc nhánh (`P0`):**
  - Kiểm tra trạng thái PR T-026 (`feature/T-026-git-badges-on-cards`, đang ở `In Review`):
    - Nếu PR T-026 đã merge vào `develop`: tách `feature/T-041-integration-test-suite` trực tiếp từ `develop` mới nhất (bao gồm V1–V20 và `TaskGitActivityChangedEvent` → bật luôn `E2E-10`).
    - Nếu PR T-026 chưa merge: tách `feature/T-041-integration-test-suite` từ `origin/develop` (`09fe624`, V1–V19) để PR T-041 có thể review và merge độc lập; ghi chú `E2E-10` ở trạng thái phụ thuộc T-026 và bổ sung sau khi merge.
- **Quy ước commit:** Conventional Commits (ví dụ: `test(app): add multi-module integration test suite for event bus (T-041)`, `build(backend): tighten verifyModuleBoundaries for common and api modules`).

---

## 4. Thứ tự triển khai và các mốc kiểm soát

| Bước | Công việc cụ thể | Điều kiện ra bước (Exit Gate) |
|---|---|---|
| **P0 — Baseline, Branch & Chốt phối hợp** | Kiểm tra trạng thái PR T-026 và `origin/develop`; tách nhánh `feature/T-041-integration-test-suite`; xác nhận môi trường Java 17 + Docker Desktop + `./gradlew check` baseline đang xanh; trao đổi với Tiến về 2 điểm phối hợp (`KT-08`). | Ghi nhận commit baseline chính xác; nhánh làm việc sạch; chốt rõ cách xử lý contract CI/AI trong phạm vi T-041. |
| **P1 — Dựng hạ tầng Test Support trong `:app`** | Xây dựng `MultiModulePostgresSupport` (singleton PostgreSQL 17, Flyway toàn bộ, `@DynamicPropertySource` đa repo binding, HMAC signer, JWT helper, STOMP client 2 kênh `/topic/boards/{id}` & `/user/queue/notifications`, `FixtureBuilder` và `cleanDatabase()`). | Test khói đầu tiên boot context sạch trên PostgreSQL 17, chạy Flyway đến phiên bản mới nhất, kết nối được cả 2 kênh STOMP và dọn sạch bảng giữa các test. |
| **P2 — Bộ chuỗi vàng Webhook → Board → Notification + Negative/Idempotency** | Triển khai `GitBoardNotificationFlowIntegrationTest` phủ đầy đủ `E2E-01..06`, `E2E-08..12`, `NEG-01..12`, `IDEM-01..10` với đủ 4 lớp oracle (REST, PostgreSQL 17, STOMP 2 kênh, metrics/log). | 100% test trong lớp PASS trên PostgreSQL 17 thật; có bằng chứng rõ task đổi cột, notification lưu cho đúng người và ca bị từ chối không làm đổi DB. |
| **P3 — Bộ Default-Flag, CI/AI/Notification & Manual/Lifecycle** | Triển khai `GitBoardNotificationDefaultFlagIntegrationTest` (`E2E-07`), `CiAiNotificationEventFlowIntegrationTest` (`CI-01..08`), và `LifecycleAndManualEventFlowIntegrationTest` (`LIFE-01..06`). | Toàn bộ 14 hợp đồng `DevFlowEvent` đều có test tích hợp thực tế; không dùng blind `Thread.sleep`; 0 rò rỉ canary vào DB/log. |
| **P4 — Gia cố Ranh giới Module** | Nâng cấp `:verifyModuleBoundaries` trong `backend/build.gradle.kts` (L1–L3) và triển khai `ModuleBoundaryArchitectureTest` (L4 + test âm tính kiểm chứng bộ phát hiện vi phạm). | `./gradlew :verifyModuleBoundaries` và `ModuleBoundaryArchitectureTest` PASS 0 vi phạm; kiểm thử âm tính chứng minh bắt được vi phạm giả lập. |
| **P5 — Kiểm định ổn định (Repeatability) & Full Quality Gates** | Chạy lặp bộ test T-041 **3 lần liên tiếp** (`--rerun-tasks`); kiểm tra thời gian thực thi, `/actuator/health`, `/actuator/prometheus`; chạy toàn bộ `cd backend && ./gradlew :verifyModuleBoundaries check` (và kiểm tra `frontend` nếu có ảnh hưởng). | 3/3 lượt chạy lặp PASS 100% không flake; toàn bộ `./gradlew check` PASS 100% (0 failed, 0 vi phạm boundary). |
| **P6 — Cập nhật Bằng chứng, PR Review & Trello** | Điền đầy đủ cột `Thực tế` ở mục 6 kèm ngày chạy, commit SHA, tên test và số liệu đo được; tạo PR theo Conventional Commits; chuyển thẻ T-041 sang `In Review` và chỉ chuyển `Done` sau khi review & merge vào `develop`. | Toàn bộ `AC-01..AC-10` có bằng chứng thực thi cụ thể; không đánh dấu hoàn thành các case chưa chạy. |

---

## 5. Tiêu chí hoàn thành (Acceptance Criteria)

Hai tiêu chí gốc trên Trello (`AC-01`, `AC-02`) cùng các tiêu chí nghiệm thu rõ ràng và tiêu chuẩn DoD chung bắt buộc phải có bằng chứng kiểm chứng cụ thể trước khi chuyển `In Review` / `Done`:

- [x] **AC-01 (Trello gốc) — `./gradlew check` chạy pass 100% tất cả test:** Toàn bộ test hiện có của các module và toàn bộ bộ test tích hợp liên module mới của T-041 trong `:app` chạy PASS 100% (0 failed, 0 error). *(PASS ngày 28/11/2026: `./gradlew :verifyModuleBoundaries check` — 115 suites / 1.008 tests, 0 failed, 0 error, `BUILD SUCCESSFUL in 7m 37s`; riêng bộ T-041 `io.devflow.integration.*`: 5 suites / 37 test methods PASS 100%, 0 skipped)*
- [x] **AC-02 (Trello gốc) — Zero vi phạm ranh giới module (`:verifyModuleBoundaries`):** `./gradlew :verifyModuleBoundaries` và `ModuleBoundaryArchitectureTest` PASS với 0 vi phạm (`:common` độc lập, `*-api` chỉ phụ thuộc `:common`, 0 `impl -> impl`, 0 import chéo `.internal.`, 0 JPA/Spring implementation lạc vào `-api`). *(PASS ngày 28/11/2026: `:verifyModuleBoundaries` đã gia cố L1–L3 PASS + `ModuleBoundaryArchitectureTest` 2/2 PASS gồm cả negative self-test)*
- [x] **AC-03 (Nghiệm thu bổ sung) — Chứng minh chuỗi vàng bằng dữ liệu thật:** Có bằng chứng test chạy trên PostgreSQL 17 thật + Flyway đầy đủ cho chuỗi `Webhook push/PR → Git event outbox → Board đổi cột → Notification lưu DB`, kiểm chứng đồng thời trạng thái DB (`tasks`, `board_audit_events`, `board_git_event_receipts`, `notifications`), REST API (`/api/v1/tasks/{id}`, `/api/v1/notifications`, `/unread-count`, `/read`) và STOMP realtime (`/topic/boards/{id}`, `/user/queue/notifications`) cho đúng người nhận và không gửi nhầm người khác. *(PASS ngày 28/11/2026: `GitBoardNotificationFlowIntegrationTest` `E2E-01..06, E2E-11..12`)*
- [x] **AC-04 (Nghiệm thu bổ sung) — Ca bị từ chối & trùng lặp không sinh tác động sai:** Các ca chữ ký HMAC sai/thiếu, payload hỏng, sai repository, không tìm thấy task, task thuộc workspace khác, board lưu trữ, assignee bị thu hồi quyền, và webhook/outbox gửi lặp đều được chứng minh bằng before/after DB snapshot là không làm đổi cột sai và không tạo thông báo thừa/sai. *(PASS ngày 28/11/2026: `NEG-01..12`, `IDEM-01..06`)*
- [x] **AC-05 — Bao phủ đầy đủ hệ sinh thái Event Bus liên module:** Ngoài chuỗi Push/PR, bộ test xác minh được hoạt động ghép nối của cấu hình mặc định `auto-transition-enabled=false`, chuỗi `CI failure → AI summarizer → ai_ci_summaries` + contract đọc log/thông báo, thao tác thủ công `task.created`/`task.status_changed(MANUAL)`, và dọn dẹp vòng đời `task.references_removed` / `user.account_deleted` / `workspace.deleted`. *(PASS ngày 28/11/2026: `GitBoardNotificationDefaultFlagIntegrationTest`, `CiAiNotificationEventFlowIntegrationTest` 9/9, `LifecycleAndManualEventFlowIntegrationTest` 4/4)*
- [x] **AC-06 — Cách ly lỗi & an toàn đồng thời:** Chứng minh rollback giao dịch webhook không sinh sự kiện ma; lỗi tạm thời ở Board (`TARGET_FULL`/lock timeout) hoàn tác sạch, không tạo notification giả và phục hồi sau retry; lỗi ở consumer Notification bị cô lập không làm hỏng trạng thái Board; chạy đồng thời nhiều delivery giữ idempotent tuyệt đối. *(PASS ngày 28/11/2026: `IDEM-07..10`)*
- [x] **AC-07 — Bảo mật, Chống IDOR & Privacy xuyên module:** Cô lập chặt tenant `WS-A`/`WS-B`, chặn đăng ký STOMP trái phép (`/queue/**`, `/user/{other}/queue/notifications`), chặn đọc/sửa thông báo chéo user (`404`), và 0 rò rỉ secret/PII canary vào DB, REST response hay application logs. *(PASS ngày 28/11/2026: `NEG-06, NEG-09, NEG-11, NEG-12, CI-04, CI-08`)*
- [x] **AC-08 (Nghiệm thu bổ sung) — Độc lập dữ liệu & Ổn định khi chạy lặp:** Bộ test T-041 không dùng H2, không dùng `Thread.sleep` tùy ý, không đụng trần quota `429`, và chạy lặp liên tiếp tối thiểu 3 lần (`--rerun-tasks`) đều PASS 100% với kết quả nhất quán. *(PASS ngày 28/11/2026: 3/3 lượt `--rerun-tasks` đều PASS 37/37 — Run 1 `02:06`, Run 2 `01:26`, Run 3 `01:26`)*
- [ ] **AC-09 — Phối hợp liên module minh bạch:** Mọi độ lệch/lỗi phát hiện ở consumer `notification-impl` hoặc `ai-impl` (đặc biệt 2 điểm ở §1.3) đều được kiểm chứng bằng test tái hiện rõ ràng, trao đổi với Tiến (`@tienlam15`) và ghi nhận trung thực trong báo cáo kết quả. *(Đã có bằng chứng test tái hiện `CI-01/CI-04/CI-05` & `NEG-10` và ghi nhận minh bạch ở §1.3 / §6; chờ trao đổi chốt hướng xử lý với `@tienlam15` ở Gate G3)*
- [ ] **AC-10 — DoD & Hồ sơ nghiệm thu đầy đủ:** Nhánh `feature/T-041-integration-test-suite`, commit theo Conventional Commits, cập nhật đủ cột `Thực tế` kèm bằng chứng chạy thật trong tài liệu này, PR được review trước khi merge vào `develop` và chỉ chuyển thẻ Trello sang `Done` sau khi hoàn tất merge. *(Đã hoàn tất code, full quality gates và cập nhật cột `Thực tế`; còn bước commit, mở PR review và merge vào `develop`)*

> [!IMPORTANT]
> Không đánh dấu hoàn thành một case chỉ vì test đơn lẻ của module cũ đang xanh hoặc vì mock được gọi (`verify(...)`). Bất kỳ case nào chưa thực thi hoặc bị chặn phụ thuộc phải ghi rõ `CHƯA CHẠY` / `BLOCKED` kèm lý do, tuyệt đối không tính vào tổng số `PASS`.

---

## 6. Kế hoạch testing — Expected và Thực tế

### 6.1. Chiến lược, mức kiểm thử và quy ước ghi kết quả

- **Phân loại mức kiểm thử:**
  - **I (Multi-Module Integration):** Full Spring Boot `:app` (`RANDOM_PORT`) + PostgreSQL 17 Testcontainers + Flyway toàn bộ migration + HTTP/REST thật + STOMP WebSocket thật. Không dùng H2 thay thế PostgreSQL.
  - **C (Cross-Module Event Contract):** Xác minh hợp đồng typed `DevFlowEvent` trực tiếp trên Spring Event Bus thật giữa hai module (khi producer/consumer hoặc bước trung gian đang tách phase, ví dụ `CiFailureLogReadRequestedEvent`, `RiskDeadlineFlaggedEvent`, `CiFailureSummarizedEvent(relatedTaskId)`).
  - **R (Resilience / Concurrency / Repeatability):** Kiểm thử fault injection (trigger DB lỗi, lock timeout, full column, broker lỗi), đa luồng đồng thời với `CountDownLatch`, và chạy lặp `--rerun-tasks`.
  - **A (Architecture / Static Boundary):** Kiểm thử tĩnh Gradle `:verifyModuleBoundaries` và `ModuleBoundaryArchitectureTest` quét toàn bộ cây mã nguồn/phụ thuộc.
- **Quy ước ghi cột `Thực tế`:**
  - `CHƯA CHẠY`: trạng thái khởi tạo ở giai đoạn lập kế hoạch (chưa thực thi code test T-041).
  - Sau khi thực thi, cập nhật bằng `PASS`, `FAIL`, `BLOCKED` hoặc `PARTIAL` kèm: ngày chạy, commit SHA, tên lớp/phương thức test, lệnh chạy và số liệu/oracle quan sát thực tế.
  - Nếu một dòng có nhiều biến thể (ngăn cách bởi dấu `;`), mỗi biến thể phải được chạy thành testcase/parameter riêng; chỉ đánh dấu `PASS` khi **100% biến thể** trong dòng đều đạt.

### 6.2. Fixture dữ liệu và Oracle chuẩn dùng chung

- **Hai Workspace độc lập (Tenant Isolation):**
  - **`WS-A`:**
    - Thành viên hợp lệ: `Owner-A` (`OWNER`), `Assignee-A` (`MEMBER`), `Assignee-A2` (`MEMBER`), `Collab-A` (`MEMBER`).
    - Thành viên bị thu hồi: `Revoked-A` (ban đầu thêm vào `WS-A` để gán task, sau đó xóa khỏi `workspace_members` trước bước kiểm chứng).
    - `Board-A` (hoạt động, `is_archived = false`): 4 cột chuẩn có `status_category` lần lượt `TODO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`.
    - `Board-A-Archived` (`is_archived = true`): 4 cột chuẩn, chứa task lưu trữ.
    - Repositories gắn với `WS-A`: `Repo-A` (`remote_repo_id = "41001"`, `https://github.com/devflow-test/app-a`, key `current` + `previous` có hạn), `Repo-A-Alt` (`remote_repo_id = "41002"`), `Repo-CI` (`remote_repo_id = "41003"`).
  - **`WS-B`:**
    - Thành viên hợp lệ: `Owner-B` (`OWNER`), `Assignee-B` (`MEMBER`).
    - `Board-B` (hoạt động): 4 cột chuẩn `TODO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`.
    - Repository gắn với `WS-B`: `Repo-B` (`remote_repo_id = "42001"`, `https://github.com/devflow-test/app-b`).
- **Tập Task chuẩn trong mỗi bài test sạch:**
  - `Task-A1` (`T-001` trên `Board-A`, cột `TODO`, `assignee = Assignee-A`).
  - `Task-A2` (`T-002` trên `Board-A`, cột `TODO`, `assignee = Assignee-A2`).
  - `Task-Unassigned` (`T-003` trên `Board-A`, cột `TODO`, `assignee = NULL`).
  - `Task-Revoked` (`T-004` trên `Board-A`, cột `TODO`, `assignee = Revoked-A`).
  - `Task-Archived` (`T-005` trên `Board-A-Archived`, cột `TODO`, `assignee = Assignee-A`).
  - `Task-B1` (`T-001` trên `Board-B`, cột `TODO`, `assignee = Assignee-B`) — **cùng mã hiển thị `T-001` nhưng khác workspace** để thử chống rò rỉ chéo tenant.
- **Tập Canary bảo mật / Privacy:**
  - `HMAC_SECRET_CANARY`, `GITHUB_PAT_CANARY = "github_pat_t041_canary_never_leak_999"`, `SIGNED_URL_CANARY = "https://results-receiver.actions.githubusercontent.com/log?sig=t041_secret"`, `EMAIL_CANARY = "private-dev@canary.local"`, `XSS_CANARY = "<script>alert('t041')</script>"`.

---

### 6.3. Bộ 1 — Luồng cốt lõi Webhook Push & PR → Event Bus → Board → Notification (`E2E-01` .. `E2E-12`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| E2E-01 | I | `auto-transition-enabled=true`. Gửi HTTP `POST /api/v1/gitci/webhook/github?repositoryId=Repo-A` ký HMAC hợp lệ (`X-GitHub-Event: push`, commit message `"feat: implement T-001 core flow"`) cho `Task-A1` (`TODO`, giao `Assignee-A`); chạy `gitOutbox.dispatch()` (trên luồng sạch `SecurityContext`). | Webhook trả `204`; `github_webhook_receipts` = `COMPLETED`; `git_commits` + `git_commit_task_links` = 1; `git_domain_event_outbox` = `PUBLISHED` (`attempts=1`); `Task-A1` đổi sang cột `IN_PROGRESS` (`version=1`); `board_git_event_receipts` = `APPLIED`; `board_audit_events` có 1 dòng (`cause='GIT'`, `actor_id IS NULL`, `source_event_id = outbox.id`); bảng `notifications` có đúng **1** dòng cho `Assignee-A` (`type='task.status_changed'`, `workspace_id=WS-A`, `reference_id=Task-A1`, `read_at IS NULL`); `Collab-A` và `Assignee-B` có **0** dòng; `GET /api/v1/tasks/{Task-A1}` xác nhận `columnId=IN_PROGRESS`; `GET /api/v1/notifications` & `/unread-count` của `Assignee-A` trả `count=1`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e01AndE2e11_signedPushWebhookTransitionsTaskPersistsNotificationFansOutToAiAndServesRest` (PostgreSQL 17 + Flyway V1–V19): đủ 4 lớp oracle (`204`, `COMPLETED`, `PUBLISHED`, `IN_PROGRESS`, `APPLIED`, `GIT` audit, 1 notification `task.status_changed` cho `Assignee-A`, 0 cho `Collab-A`/`Assignee-B`, REST `$.content[0].isRead=false` & `unread-count=1`) |
| E2E-02 | I | `auto-transition-enabled=true`. `Task-A1` đang ở `IN_PROGRESS`. Gửi signed webhook `pull_request` (`action=opened`, `title="T-001: Add integration tests"`, `state=open`, `merged=false`) rồi `gitOutbox.dispatch()`. | Webhook trả `204`; `pull_requests` (`status='OPEN'`) + `git_pull_request_task_links` = 1; `git_domain_event_outbox` (`git.pr_opened`) = `PUBLISHED`; `Task-A1` tự chuyển từ `IN_PROGRESS` sang `IN_REVIEW`; `board_git_event_receipts` = `APPLIED`; bảng `notifications` của `Assignee-A` tăng thêm đúng **2** dòng phân biệt: 1 dòng `type='task.status_changed'` và 1 dòng `type='git.pr_opened'` (đều gắn `workspace_id=WS-A`, `reference_id=Task-A1`); `Collab-A` vẫn 0 dòng. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e02AndE2e03AndE2e04AndE2e06_fullGitLifecyclePushPrOpenPrMergeAndNotificationReadFlow` (PR #42 `OPEN`, `IN_PROGRESS→IN_REVIEW`, +2 notifications `task.status_changed` + `git.pr_opened`) |
| E2E-03 | I | `auto-transition-enabled=true`. `Task-A1` đang ở `IN_REVIEW`, đã có PR #42 `OPEN`. Gửi signed webhook `pull_request` (`action=closed`, `state=closed`, `merged=true`, `updated_at` mới hơn) rồi `gitOutbox.dispatch()`. | Webhook trả `204`; `pull_requests.status = 'MERGED'`; `git_domain_event_outbox` (`git.pr_merged`) = `PUBLISHED`; `Task-A1` tự chuyển từ `IN_REVIEW` sang `DONE`; `board_git_event_receipts` = `APPLIED`; bảng `notifications` của `Assignee-A` tăng thêm đúng **2** dòng: `type='task.status_changed'` và `type='git.pr_merged'`; REST `GET /api/v1/notifications` của `Assignee-A` trả đủ danh sách sắp xếp mới nhất trước. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e02AndE2e03AndE2e04AndE2e06_fullGitLifecyclePushPrOpenPrMergeAndNotificationReadFlow` (PR #42 `MERGED`, `IN_REVIEW→DONE`, +2 notifications `task.status_changed` + `git.pr_merged`) |
| E2E-04 | I | Chuỗi vòng đời liên hoàn 3 bước trên cùng `Task-A1` (`TODO`): (1) `push` → `dispatch()` → (2) `pull_request opened` → `dispatch()` → (3) `pull_request merged` → `dispatch()`. | `Task-A1` đi tuần tự `TODO → IN_PROGRESS → IN_REVIEW → DONE` (`version=3`); `git_domain_event_outbox` có 3 dòng `PUBLISHED`; `board_git_event_receipts` có 3 dòng `APPLIED`; `board_audit_events` có 3 dòng `MOVE`/`GIT`; `notifications` của `Assignee-A` có đúng **5** dòng (3 `task.status_changed` + 1 `git.pr_opened` + 1 `git.pr_merged`), không thiếu và không trùng `event_id`; mọi dòng đều có `workspace_id=WS-A` và `reference_id=Task-A1`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e02AndE2e03AndE2e04AndE2e06_fullGitLifecyclePushPrOpenPrMergeAndNotificationReadFlow` (`version=3`, 3 `PUBLISHED`, 3 `APPLIED`, 3 `board_audit_events`, đúng 5 `notifications`) |
| E2E-05 | I | Một commit duy nhất gắn 2 task khác người phụ trách (`message = "fix: T-001 and T-002 together"`, `Task-A1` giao `Assignee-A`, `Task-A2` giao `Assignee-A2`) gửi qua signed `push` webhook rồi `gitOutbox.dispatch()`. | Tạo 1 dòng `git_commits` (`task_id IS NULL` vì multi-task), 2 dòng `git_commit_task_links`, 2 dòng `git_domain_event_outbox` (`git.commit_linked`); cả `Task-A1` lẫn `Task-A2` chuyển sang `IN_PROGRESS`; `Assignee-A` nhận đúng **1** notification có `reference_id = Task-A1`; `Assignee-A2` nhận đúng **1** notification có `reference_id = Task-A2`; tuyệt đối không trộn chéo người nhận. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e05_singleCommitReferencingTwoTasksRoutesNotificationsOnlyToEachTasksAssignee` |
| E2E-06 | I | Sau chuỗi `E2E-01`/`E2E-04`, `Assignee-A` dùng JWT thật gọi lần lượt: `GET /api/v1/notifications?page=0&size=20`, `GET /api/v1/notifications/unread-count`, `PATCH /api/v1/notifications/{id1}/read`, và `POST /api/v1/notifications/read-all`. | Danh sách trả đầy đủ thông báo đã lưu (`$.content`); sau `PATCH /{id1}/read`, chỉ thông báo `id1` có `isRead=true` và `readAt != null`, `unread-count` giảm đúng 1; sau `POST /read-all`, toàn bộ thông báo của `Assignee-A` có `read_at IS NOT NULL` trong DB và `unread-count = 0`; thông báo của user khác không bị ảnh hưởng. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e02AndE2e03AndE2e04AndE2e06_fullGitLifecyclePushPrOpenPrMergeAndNotificationReadFlow` (`count: 5 → 4 → 0`, `updated=4`, `read_at IS NOT NULL` toàn bộ trong DB) |
| E2E-07 | I | Context mặc định production (`devflow.board.git.auto-transition-enabled=false`). Gửi lần lượt signed `push` và `pull_request opened` + `pull_request merged` cho `Task-A1` (`TODO`, giao `Assignee-A`) rồi `gitOutbox.dispatch()`. | Cả 3 outbox row đều `PUBLISHED` (`attempts=1`, không retry/không poison); `Task-A1` giữ nguyên ở `TODO` (`version=0`); `board_git_event_receipts = 0`; `board_audit_events = 0`; `notifications` của `Assignee-A` có đúng **2** dòng (`git.pr_opened` và `git.pr_merged`) và **0** dòng `task.status_changed`; metric `devflow.board.git.transitions{outcome="SKIPPED", reason="DISABLED"}` tăng đúng 3. | PASS (28/11/2026) — `GitBoardNotificationDefaultFlagIntegrationTest.e2e07_defaultDisabledAutoTransitionKeepsBoardStaticWhileDeliveringPrNotificationsCleanly` (0,624s) |
| E2E-08 | I | `auto-transition-enabled=true`. `Task-A1` đã ở `IN_PROGRESS` (sau push #1). Gửi signed `push` #2 (SHA mới, cùng gắn `T-001`) rồi `gitOutbox.dispatch()`. | Commit #2 được lưu vào `git_commits` và `git_commit_task_links` (tổng 2 commit); outbox `PUBLISHED`; `board_git_event_receipts` ghi `outcome='NOOP'`; `Task-A1` giữ nguyên `IN_PROGRESS` và `version` không tăng; `board_audit_events` không tăng; `notifications` của `Assignee-A` giữ nguyên số lượng trước đó (**0** thông báo mới). | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e08AndE2e09_secondPushNoopAndNonTransitionPrActionsDoNotSpamNotifications` |
| E2E-09 | I | `auto-transition-enabled=true`. Đã có PR #42 `OPEN` gắn `T-001`. Gửi signed `pull_request` `action=edited` (đổi tiêu đề giữ `T-001`), rồi `action=closed` với `merged=false`, rồi `action=reopened` (`updated_at` tăng dần) và `gitOutbox.dispatch()`. | Bảng `pull_requests` cập nhật đúng `title`/`status` qua từng bước (`OPEN → CLOSED → OPEN`); **0** sự kiện mới được chèn vào `git_domain_event_outbox`; `Task-A1` không đổi cột; `notifications` không sinh thêm bất kỳ dòng nào cho `edited`, `closed (not merged)` hay `reopened`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e08AndE2e09_secondPushNoopAndNonTransitionPrActionsDoNotSpamNotifications` |
| E2E-10 | I | *(Áp dụng khi baseline có T-026)* Kết nối STOMP client `Collab-A` vào `/topic/boards/{Board-A}` trong 2 trường hợp không chuyển cột: (a) `push` #2 (`NOOP`) khi task đã ở `IN_PROGRESS`; (b) PR `closed` không merge. | Không phát `CARD_MOVED` và không tạo notification `task.status_changed`; `Board-A` vẫn nhận được khung STOMP `CARD_UPDATED` với `changedFields = ["gitActivity"]` và `refresh = ["TASK_LISTS", "TASK_DETAIL"]`. | BLOCKED (28/11/2026) — Nhánh `feature/T-041-integration-test-suite` tách từ `develop` (`09fe624`), chưa bao gồm `TaskGitActivityChangedEvent` của PR T-026 (đang `In Review`); case này đã được kiểm chứng riêng trong `GitCiReadApiAndRealtimeIntegrationTest` trên nhánh T-026 và sẽ kích hoạt sau khi T-026 merge |
| E2E-11 | I | Đo bộ đếm tiêu thụ sự kiện của `ai-impl` (`devflow.task.events.consume{eventType="task.status_changed", consumer="ai", outcome="success"}`) và `notification-impl` (`consumer="notification", outcome="success"`) trong suốt `E2E-01` (`push` làm `Task-A1` đổi cột). | Sau `gitOutbox.dispatch()`, `devflow.task.events.dispatch{eventType="task.status_changed", outcome="success"}` tăng +1; cả `consumer="ai"` lẫn `consumer="notification"` đều tăng +1 — chứng minh 1 sự kiện `TaskStatusChangedEvent` trên Event Bus fan-out thực sự đến đồng thời 2 module khác nhau. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e01AndE2e11_signedPushWebhookTransitionsTaskPersistsNotificationFansOutToAiAndServesRest` (xác nhận cả `consumer="notification"` và `consumer="ai"` đều +1) |
| E2E-12 | I | Mở đồng thời 3 kết nối STOMP thật qua `ws://localhost:{port}/ws`: (1) `Assignee-A` subscribe `/topic/boards/{Board-A}` + `/user/queue/notifications`, (2) `Collab-A` subscribe `/topic/boards/{Board-A}` + `/user/queue/notifications`, (3) `Assignee-B` subscribe `/user/queue/notifications`. Gửi signed `push` cho `Task-A1` → `gitOutbox.dispatch()`. | Cả `Assignee-A` và `Collab-A` cùng nhận frame `CARD_MOVED` trên `/topic/boards/{Board-A}` (`taskId=Task-A1`, `destinationColumnId=IN_PROGRESS`, `actorId=null`); **chỉ duy nhất `Assignee-A`** nhận frame `NotificationResponse` trên `/user/queue/notifications` (`type="task.status_changed"`, `referenceId=Task-A1`, `isRead=false`); `Collab-A` và `Assignee-B` nhận **0** frame trên `/user/queue/notifications` trong cửa sổ chờ. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.e2e12_dualChannelStompDeliversBoardCardMovedToAllMembersAndNotificationOnlyToAssignee` |

---

### 6.4. Bộ 2 — Ca từ chối, Bảo mật, Chống giả mạo & Cô lập Tenant (`NEG-01` .. `NEG-12`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| NEG-01 | I | Gửi webhook `push` nhắc `T-001` với lần lượt 4 biến thể lỗi xác thực: (a) thiếu header `X-Hub-Signature-256`; (b) chữ ký HMAC sai; (c) ký đúng rồi sửa 1 ký tự trong body; (d) `repositoryId` chưa cấu hình binding. | Tất cả trả RFC 7807 `401` (`code="WEBHOOK_UNAUTHORIZED"`, `application/problem+json`, không stack trace); snapshot DB trước/sau trùng khớp 100%: **0** `github_webhook_receipts`, **0** `git_commits`, **0** `git_domain_event_outbox`, `Task-A1` vẫn ở `TODO` (`version=0`), **0** `board_audit_events`, **0** `notifications`, **0** frame STOMP. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg01AndNeg03AndNeg04_invalidSignaturesMismatchedRepoAndMalformedPayloadsCauseZeroSideEffects` (đủ cả 4 biến thể `401 WEBHOOK_UNAUTHORIZED` + zero-side-effect DB snapshot) |
| NEG-02 | I | Cấu hình key rotation trên `Repo-A`: gửi 3 webhook `push` lần lượt ký bằng (a) `current` key, (b) `previous` key còn hạn (`previousValidUntil` ở tương lai), (c) `previous` key đã hết hạn (trên `Repo-A-Alt`). | (a) và (b) trả `204` và kích hoạt trọn vẹn chuỗi Board + Notification; (c) trả `401 WEBHOOK_UNAUTHORIZED` với 0 thay đổi DB ở cả `gitci`, `board` và `notification`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg02_keyRotationAcceptsCurrentAndValidPreviousButRejectsExpiredPrevious` |
| NEG-03 | I | Ký HMAC hợp lệ bằng secret của `Repo-A` (`remote_repo_id = "41001"`), nhưng trong JSON gửi `repository.id = 42001` (của `Repo-B`) hoặc trong `pull_request` gửi `base.repo.id != 41001`. | Trả `401 WEBHOOK_UNAUTHORIZED`; **0** ghi nhận ở `github_webhook_receipts`, `git_commits`/`pull_requests`, `git_domain_event_outbox`, `tasks` và `notifications`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg01AndNeg03AndNeg04_invalidSignaturesMismatchedRepoAndMalformedPayloadsCauseZeroSideEffects` |
| NEG-04 | I | Ký HMAC hợp lệ trên `Repo-A`, gửi lần lượt các payload/header sai chuẩn: (a) JSON hỏng / trailing tokens; (b) `commit.id` toàn 40 số `0`; (c) thiếu `X-GitHub-Delivery`; (d) `Content-Type: text/plain`; (e) body vượt `max-body-bytes`; (f) `size > commits.size()` (`GIT_INCOMPLETE_PUSH`). | Trả đúng mã RFC 7807 tương ứng (`400`, `400`, `400`, `415`, `413`, `422`), không rò rỉ exception nội bộ; 0 dòng được lưu ở `gitci`, `board`, `notification`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg01AndNeg03AndNeg04_invalidSignaturesMismatchedRepoAndMalformedPayloadsCauseZeroSideEffects` (đủ cả 6 biến thể `400/400/400/415/413/422`) |
| NEG-05 | I | Gửi signed webhook `push` và `pull_request opened` trên `Repo-A` với mã task không tồn tại (`"feat: T-999 unknown task"`), chạy `gitOutbox.dispatch()`. | Webhook trả `204` và ghi `github_webhook_receipts = COMPLETED`, `git_commits` / `pull_requests` lưu metadata bình thường (`task_id IS NULL`), nhưng `git_commit_task_links = 0`, `git_pull_request_task_links = 0`, `git_domain_event_outbox = 0`; mọi task trên `Board-A` giữ nguyên cột/version; `board_git_event_receipts = 0`, `board_audit_events = 0`, `notifications = 0`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg05AndNeg06AndNeg07_missingTaskCrossTenantKeyAndArchivedBoardNeverCauseWrongTransitionsOrNotifications` |
| NEG-06 | I | Cô lập chéo tenant: `WS-A` và `WS-B` đều có `T-001` (`Task-A1` ở `WS-A`, `Task-B1` ở `WS-B`); chỉ `WS-B` có `T-006`. Gửi signed webhook `push` trên `Repo-A` (`WS-A`) chứa `"feat: T-001 and T-006"`, chạy `gitOutbox.dispatch()`. | `T-001` chỉ liên kết với `Task-A1` (`WS-A`), `Task-A1` đổi sang `IN_PROGRESS` và chỉ `Assignee-A` nhận 1 thông báo; `T-006` phân giải `NOT_FOUND`; `Task-B1` và `Task-B6` của `WS-B` giữ nguyên `TODO`, **0** dòng link trỏ sang `WS-B`, `Assignee-B` nhận **0** thông báo. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg05AndNeg06AndNeg07_missingTaskCrossTenantKeyAndArchivedBoardNeverCauseWrongTransitionsOrNotifications` |
| NEG-07 | I | Gửi signed webhook `push` và `pull_request opened` trên `Repo-A` nhắc tới `Task-Archived` (`T-005` trên `Board-A-Archived`, `is_archived = true`, giao `Assignee-A`), chạy `gitOutbox.dispatch()`. | `TaskReferenceResolver` đánh dấu `Outcome.INACTIVE` → 0 link, 0 outbox row; `Task-Archived` giữ nguyên `TODO`, 0 `board_audit_events`, 0 `notifications`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg05AndNeg06AndNeg07_missingTaskCrossTenantKeyAndArchivedBoardNeverCauseWrongTransitionsOrNotifications` |
| NEG-08 | I | Gửi signed webhook `push` trên `Repo-A-Alt` nhắc tới `Task-Unassigned` (`T-003`, `assignee_id = NULL`, cột `TODO`), chạy `gitOutbox.dispatch()`. | `Task-Unassigned` vẫn đổi cột thành công (`TODO → IN_PROGRESS`) và ghi đủ `board_git_event_receipts` + `board_audit_events`; `NotificationEventListeners` bỏ qua an toàn (`outcome="skipped"`) → **0** dòng trong `notifications`, **0** lỗi exception. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg08AndNeg09_unassignedTaskAndRevokedMemberTransitionBoardWithoutCreatingNotifications` |
| NEG-09 | I | Xóa `Revoked-A` khỏi `workspace_members` của `WS-A` (trong khi `Task-Revoked` vẫn đang để `assignee_id = Revoked-A`). Kết nối STOMP cũ của `Revoked-A` đang mở. Gửi signed `push` + `pull_request opened` cho `Task-Revoked`, chạy `gitOutbox.dispatch()`. | `Task-Revoked` chuyển cột trên Board bình thường; `NotificationEventListeners` kiểm `AuthApi.isWorkspaceMember(Revoked-A, WS-A) == false` → **0** dòng `notifications` cho `Revoked-A`, **0** frame `/user/queue/notifications`; STOMP `/topic/boards/{Board-A}` từ chối gửi frame tới `Revoked-A`; `Revoked-A` gọi `GET /api/v1/tasks/{Task-Revoked}` bị chặn `404`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg08AndNeg09_unassignedTaskAndRevokedMemberTransitionBoardWithoutCreatingNotifications` (`denied` metric tăng, 0 STOMP frame ở cả 2 kênh, REST `404`, 0 DB notification) |
| NEG-10 | C/I | Kiểm chứng Phát hiện #3 (`SecurityContextHolder` bẩn trên thread phát sự kiện Git): đặt `AuthenticatedActor(Assignee-A)` vào `SecurityContextHolder`, sau đó phát `GitPrOpenedEvent` (`actorId=null`) và đối chiếu với `TaskStatusChangedEvent(cause=GIT, actorId=null)` trên luồng sạch. | (a) Với `GitPrOpenedEvent(actorId=null)`: `NotificationEventListeners` bỏ qua `SecurityContextHolder` và vẫn lưu đúng thông báo `git.pr_opened` cho `Assignee-A`; (b) Với `TaskStatusChangedEvent(cause=GIT, actorId=null)` trên luồng sạch `SecurityContext` luôn giao đủ thông báo `task.status_changed`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg10_gitPrEventIgnoresAmbientSecurityContextAndCleanWorkerDeliversGitStatusNotification` |
| NEG-11 | I | Kiểm thử phân quyền STOMP kết hợp cả 3 interceptor trong `:app`: (a) `Assignee-A` (JWT hợp lệ) cố SUBSCRIBE `/queue/notifications` hoặc `/queue/**`; (b) `Assignee-A` cố SUBSCRIBE `/user/{Assignee-B}/queue/notifications`; (c) `Assignee-B` cố SUBSCRIBE `/topic/boards/{Board-A}`; (d) client dùng JWT đã bị thu hồi `token_version` cố CONNECT `/ws`. | Mọi nỗ lực trái phép (a)–(d) đều bị chặn (`ACCESS_DENIED` / từ chối CONNECT); tuyệt đối không có frame nào rò rỉ sang client trái phép. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg11_stompAuthorizationBlocksRawQueuesForeignUserQueuesForeignBoardsAndRevokedTokenVersion` (đủ cả 5 biến thể bị từ chối) |
| NEG-12 | I | Kiểm thử chống IDOR & Privacy trên Notification REST API: sau khi `Assignee-A` có thông báo `notifA1` từ webhook, `Collab-A` và `Assignee-B` gọi `GET /api/v1/notifications`, `PATCH /api/v1/notifications/{notifA1}/read`, và kiểm tra nội dung `notifA1` khi commit message có chứa `XSS_CANARY` và `EMAIL_CANARY`. | `Collab-A` và `Assignee-B` nhận `content=[]` khi list và nhận `404` khi cố đánh dấu đọc `notifA1` (trạng thái `isRead` của `notifA1` vẫn `false`); nội dung `notifA1` không chứa `EMAIL_CANARY`, `HMAC_SECRET_CANARY` hay thẻ `<script>` chưa thoát. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.neg12_notificationRestEnforcesAntiIdorAndExcludesEmailSecretsAndRawScriptTags` |

---

### 6.5. Bộ 3 — Chống trùng lặp (Idempotency), Thứ tự, Xung đột & Chịu lỗi (`IDEM-01` .. `IDEM-10`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| IDEM-01 | I | Gửi cùng một webhook `push` (cùng `X-GitHub-Delivery`, cùng body, cùng chữ ký) **2 lần liên tiếp**, chạy `gitOutbox.dispatch()` sau mỗi lần. | Cả 2 lần trả `204`; chỉ có **1** `github_webhook_receipts`, **1** `git_commits`, **1** `git_domain_event_outbox`, **1** `board_git_event_receipts`, **1** `board_audit_events`, và **1** dòng `notifications` cho `Assignee-A`; `Task-A1` chỉ đổi cột 1 lần (`version=1`). | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem01AndIdem02AndIdem03_transportReplaySemanticReplayAndDeliveryConflictAreHandledSafely` |
| IDEM-02 | I | Gửi webhook `push` lần 2 với **`X-GitHub-Delivery` mới** nhưng chứa cùng commit SHA/message/timestamp (semantic replay). | Lần 2 trả `204` an toàn (không lỗi `500`); **0** sự kiện mới trong `git_domain_event_outbox`; tổng số `board_git_event_receipts`, `board_audit_events` và `notifications` giữ nguyên như sau lần 1 (không nhân đôi thông báo). | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem01AndIdem02AndIdem03_transportReplaySemanticReplayAndDeliveryConflictAreHandledSafely` |
| IDEM-03 | I | Gửi webhook `push` hợp lệ với `X-GitHub-Delivery = D-1`; sau đó gửi lại cùng `X-GitHub-Delivery = D-1` nhưng payload khác (SHA khác gắn `Task-A2`), ký HMAC hợp lệ. | Lần 2 trả RFC 7807 `409` (`code="WEBHOOK_DELIVERY_CONFLICT"`); `Task-A2` vẫn ở `TODO`, không thêm commit/outbox/notification nào. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem01AndIdem02AndIdem03_transportReplaySemanticReplayAndDeliveryConflictAreHandledSafely` |
| IDEM-04 | I/R | Mô phỏng crash worker outbox sau khi publish xong nhưng trước khi đánh dấu `PUBLISHED`: gửi signed `pull_request opened` → `claimed = gitOutbox.claim()` → phát sự kiện trực tiếp (`events.publishEvent(...)`) → hết hạn lease (`lease_until = now - 1s`) → worker mới gọi `gitOutbox.dispatch()` phát lại đúng immutable event đó. | Sau lần 1, `Task-A1` sang `IN_REVIEW` và `Assignee-A` có 2 thông báo (`task.status_changed` + `git.pr_opened`). Sau khi lease hết hạn và worker mới phát lại cùng event: Board gặp `board_git_event_receipts` (`DUPLICATE`, không phát lại `TaskStatusChangedEvent`), Notification gặp `ON CONFLICT (event_id, recipient_id) DO NOTHING` → outbox chuyển `PUBLISHED`, tổng `notifications` của `Assignee-A` **vẫn đúng 2**. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem04_outboxLeaseExpiryReplayAfterCrashDoesNotDuplicateBoardAuditOrNotifications` |
| IDEM-05 | I | Giao hàng ngược thứ tự cho cùng `Task-A1` (`TODO`, giao `Assignee-A`): gửi trước `pull_request merged` (tại `T0 + 20s`) → `dispatch()`, sau đó gửi `push` (tại `T0`) → `dispatch()`, cuối cùng gửi `pull_request opened` (tại `T0 + 10s`) → `dispatch()`. | Sau bước 1, `Task-A1` lên `DONE` (1 `task.status_changed` + 1 `git.pr_merged`). Bước 2 (`push`) và bước 3 (`PR opened`) không bao giờ kéo lùi `Task-A1` (`NOOP`, `Task-A1` giữ `DONE`); tổng `task.status_changed` vẫn là **1**. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem05_outOfOrderDeliveryMergeBeforePushNeverMovesTaskBackwardOrEmitsExtraStatusNotifications` |
| IDEM-06 | I | Bảo vệ thao tác mở lại thủ công (`last_manual_status_changed_at`): `Task-A1` chuyển `DONE` qua `PR merged` (thời điểm `T1`) → `Owner-A` kéo tay `PATCH /api/v1/tasks/{Task-A1}/move` về `TODO` (ghi watermark `Tm > T1`) → gửi `push` có `committed_at = Tm`, rồi gửi `push` mới có `committed_at = Tm + 5s`. | `push` bằng `Tm` bị giữ ở `TODO` (`STALE_MANUAL`), 0 notification `task.status_changed` mới từ Git; chỉ `push` mới hơn `Tm` mới tự chuyển `Task-A1` sang `IN_PROGRESS` và sinh đúng 1 notification `task.status_changed` mới. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem06_manualReopenWatermarkBlocksStaleGitEventsAndAllowsNewerCommitToAdvance` |
| IDEM-07 | I/R | Cách ly lỗi tạm thời ở Board (`max-per-column = 5`): lấp đầy 5 task vào cột `IN_PROGRESS`, gửi signed `push` cho `Task-A1` (`TODO`, giao `Assignee-A`), chạy `gitOutbox.dispatch()`. Sau đó xóa 1 task đệm, đặt `next_attempt_at <= now` và chạy `gitOutbox.dispatch()` lại. | Lần 1: Board ném `TARGET_FULL`, rollback sạch → `Task-A1` giữ `TODO`, `board_git_event_receipts = 0`, `board_audit_events = 0`, **`notifications = 0`** (tuyệt đối không có thông báo giả!), outbox về `PENDING` (`attempts=1`). Lần 2 sau khi giải phóng chỗ: `Task-A1` sang `IN_PROGRESS`, outbox = `PUBLISHED`, `board_git_event_receipts = 1`, `notifications` của `Assignee-A` có đúng **1** dòng `task.status_changed`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem07_boardTargetFullRollsBackWithoutPhantomNotificationsAndRecoversOnRetry` |
| IDEM-08 | I/R | Cách ly lỗi ở consumer Notification: gắn tạm PostgreSQL trigger `BEFORE INSERT ON notifications` ném exception tổng hợp, gửi signed `push` cho `Task-A1` → `gitOutbox.dispatch()`, rồi gỡ trigger. | Giao dịch Board **vẫn commit thành công**: `Task-A1` ở `IN_PROGRESS`, `board_git_event_receipts = 1`, `board_audit_events = 1`, `git_domain_event_outbox = PUBLISHED`; `NotificationEventListeners` bắt lỗi an toàn, `notifications = 0`, và metric `devflow.task.events.consume{eventType="task.status_changed", consumer="notification", outcome="failure"}` tăng +1. Sự kiện tiếp theo sau khi gỡ trigger hoạt động bình thường. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem08_notificationConsumerFailureIsIsolatedAndDoesNotRollbackBoardOrPoisonOutbox` |
| IDEM-09 | I/R | Lỗi rollback trước commit ở Transaction A (webhook ingestion): gắn tạm trigger lỗi trên `git_domain_event_outbox` trước khi gửi signed `push` cho `Task-A1`. | Webhook trả `503 WEBHOOK_UNAVAILABLE` (RFC 7807); toàn bộ Tx A rollback sạch: `github_webhook_receipts = 0`, `git_commits = 0`, `git_commit_task_links = 0`, `git_domain_event_outbox = 0`, `Task-A1` giữ `TODO`, `notifications = 0`. Sau khi gỡ trigger và gửi lại đúng webhook đó thì toàn chuỗi chạy thành công. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem09_webhookTransactionRollbackBeforeCommitLeavesZeroStateAndAllowsCleanRetry` |
| IDEM-10 | R | Tranh chấp đồng thời (concurrency): chuẩn bị 1 sự kiện `git.commit_linked` và 1 sự kiện `git.pr_opened` của `Task-A1`, dùng `CountDownLatch` thả đồng thời **6 luồng** gọi phát sự kiện song song vào Event Bus. | Không deadlock, không rò rỉ exception chưa xử lý; `Task-A1` kết thúc ở `IN_REVIEW`, `board_git_event_receipts = 2`, `notifications` của `Assignee-A` có đúng 1 `git.pr_opened` và 1–2 `task.status_changed`, danh sách `position` trong mọi cột liên tục `0..N-1`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.idem10_concurrentDuplicateAndDistinctGitEventsRemainIdempotentAcrossBoardAndNotifications` |

---

### 6.6. Bộ 4 — Luồng CI Failure → AI Summarizer → Notification & Contract sự kiện bổ sung (`CI-01` .. `CI-08`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| CI-01 | I | `GitHubActionsReadClient` stub trả về ZIP log lỗi có chứa `GITHUB_PAT_CANARY`, `SIGNED_URL_CANARY`, `EMAIL_CANARY` và dòng lỗi `Error: AssertionError - expected status 200 but got 500`. Gửi signed webhook `workflow_run` (`action=completed`, `status=completed`, `conclusion=failure`, `run_attempt=1`) trên `Repo-CI` → chạy `ciFetchWorker.runOne()` → chạy `ciOutbox.dispatch()` → chờ có hạn (`awaitCondition`) trên `AiCiExecutor`. | Webhook trả `204`; `ci_pipelines.status='FAILED'`; sau `runOne()`, `ci_failures.log_status='READY'`, `raw_log_snippet` chứa lỗi và **không chứa** `GITHUB_PAT_CANARY`/`SIGNED_URL_CANARY`/`EMAIL_CANARY`; sau `dispatch()`, outbox = `PUBLISHED`; `AiEventListeners` thực thi xong qua `DeterministicChatModel` và lưu đúng **1** dòng trong `ai_ci_summaries` (`pipeline_id = internalAttemptUuid`, `source='LLM'`, `summary_markdown` có 3 gạch đầu dòng) + phát `CiFailureSummarizedEvent`. | PASS (28/11/2026) — `CiAiNotificationEventFlowIntegrationTest.ci01AndCi03AndCi08_workflowFailureIngestsRedactsSummarizesIdempotentlyAndLeaksNoSecrets` |
| CI-02 | I | Gửi lần lượt signed webhook `workflow_run` với `conclusion` là `success`; `cancelled`; `skipped`; `timed_out`; và `action=in_progress`. | `completed/*` lưu đúng `ci_pipelines.status` (`SUCCESS`, `CANCELLED`, `SKIPPED`, `TIMED_OUT`), `in_progress` bị `ignored`; **0** dòng `ci_failures`, **0** `ci_log_fetch_jobs`, **0** `ci_failure_event_outbox`, **0** `ai_ci_summaries`, **0** `notifications`. | PASS (28/11/2026) — `CiAiNotificationEventFlowIntegrationTest.ci02_nonFailureAndInProgressWorkflowsNeverTriggerAiOrNotifications` (parameterized 5/5 biến thể PASS) |
| CI-03 | I | Sau `CI-01`, phát lại cùng `CiFailureDetectedEvent` (mô phỏng at-least-once replay từ outbox sau crash). | `CiFailureSummarizerService` nhận ra `pipeline_id` đã tồn tại trong `ai_ci_summaries`, trả `newlyCreated=false`, **không** chèn dòng trùng (vẫn đúng 1 dòng), **không** phát lại `CiFailureSummarizedEvent`. | PASS (28/11/2026) — `CiAiNotificationEventFlowIntegrationTest.ci01AndCi03AndCi08_workflowFailureIngestsRedactsSummarizesIdempotentlyAndLeaksNoSecrets` |
| CI-04 | C/I | Sau khi `ciFetchWorker.runOne()` đặt `log_status='READY'`, phát `CiFailureLogReadRequestedEvent` thật qua Event Bus với 5 biến thể: (a) provenance khớp `CiFailureDetectedEvent`; (b) provenance bị giả mạo `eventId`; (c) user member `Assignee-A` của `WS-A`; (d) user outsider `Assignee-B` của `WS-B`; (e) sau khi `log_expires_at` quá hạn. | (a) và (c) trả `Status.READY` kèm `excerpt` đã lọc (0 secret canary) và `digest` khớp DB; (b) và (d) trả `Status.DENIED` (kiểm chứng `WorkspaceMembershipCheckListener` của `auth-impl` hoạt động thật); (e) trả `Status.EXPIRED`. | PASS (28/11/2026) — `CiAiNotificationEventFlowIntegrationTest.ci04_synchronousCiFailureLogReadEnforcesProvenanceAuthMembershipAndExpiry` (đủ 5/5 biến thể) |
| CI-05 | C/I | Kiểm chứng hợp đồng `CiFailureSummarizedEvent → NotificationEventListeners` với 2 biến thể: (a) `relatedTaskId = null` (hiện trạng phát ra từ `AiEventListeners`); (b) `relatedTaskId = Task-A1` (task giao `Assignee-A`), kèm phát lặp 2 lần cùng sự kiện và biến thể `Task-Unassigned`/`Task-Revoked`. | (a) `NotificationEventListeners` bỏ qua an toàn (`outcome="skipped"`, 0 dòng `notifications`, 0 lỗi); (b) `NotificationEventListeners` lưu đúng **1** dòng `type='ci.failure_summarized'` cho `Assignee-A` (`reference_id = Task-A1`, `workspace_id = WS-A`), phát lặp không nhân đôi, và bỏ qua khi task chưa giao hoặc assignee đã bị gỡ khỏi workspace. | PASS (28/11/2026) — `CiAiNotificationEventFlowIntegrationTest.ci05AndCi06_ciFailureSummarizedAndRiskDeadlineFlaggedContractsDeliverDeduplicatedNotifications` |
| CI-06 | C/I | Kiểm chứng hợp đồng `RiskDeadlineFlaggedEvent → NotificationEventListeners`: (a) gắn `Task-A1` (giao `Assignee-A`, `Severity.HIGH`); (b) phát lặp cùng sự kiện; (c) cảnh báo cấp sprint (`taskId = null`, `sprintId != null`); (d) gắn `Task-Revoked`. | (a) Lưu đúng **1** dòng `notifications` (`type='risk.deadline_flagged'`, `recipient_id=Assignee-A`, `reference_id=Task-A1`, `workspace_id=WS-A`) và đẩy STOMP `/user/queue/notifications`; (b) chống trùng giữ nguyên 1 dòng; (c) và (d) bỏ qua an toàn (0 dòng). | PASS (28/11/2026) — `CiAiNotificationEventFlowIntegrationTest.ci05AndCi06_ciFailureSummarizedAndRiskDeadlineFlaggedContractsDeliverDeduplicatedNotifications` (xác nhận cả DB lẫn STOMP `/user/queue/notifications`) |
| CI-07 | I | Rerun workflow CI thất bại: attempt 1 `failure` → attempt 2 `failure` trên cùng `provider_run_id` nhưng khác `commit_sha`; chạy `ciFetchWorker` + `ciOutbox` cho cả 2. | Tạo 2 `ci_pipelines` độc lập (khác internal attempt UUID), 2 `ci_failures`, 2 `ci_failure_event_outbox`, và **2** dòng `ai_ci_summaries` tương ứng đúng 2 attempt UUID mà không ghi đè nhau. | PASS (28/11/2026) — `CiAiNotificationEventFlowIntegrationTest.ci07_failedRerunAttemptsProduceTwoIndependentPipelinesAndAiSummaries` |
| CI-08 | I | Kiểm tra Privacy/Canary xuyên 4 module trên `CI-01`: gắn `ListAppender` vào logger `io.devflow` và quét chuỗi trong `ci_failures`, `ai_ci_summaries`, application logs. | Tuyệt đối 0 xuất hiện của `GITHUB_PAT_CANARY`, `SIGNED_URL_CANARY`, `SECRET_CI_CURRENT`, hay `EMAIL_CANARY` trong mọi bảng đích và toàn bộ log ứng dụng phát sinh. | PASS (28/11/2026) — `CiAiNotificationEventFlowIntegrationTest.ci01AndCi03AndCi08_workflowFailureIngestsRedactsSummarizesIdempotentlyAndLeaksNoSecrets` |

---

### 6.7. Bộ 5 — Luồng Thủ công Board & Dọn dẹp vòng đời đa module (`LIFE-01` .. `LIFE-06`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| LIFE-01 | I | `Owner-A` (JWT thật) gọi `POST /api/v1/columns/{TODO}/tasks` tạo task mới giao cho `Assignee-A`, trong khi `Assignee-A` mở STOMP `/user/queue/notifications`. | API trả `201`; Board phát `TaskCreatedEvent` sau commit; `notifications` lưu đúng **1** dòng cho `Assignee-A` (`type='task.created'`, `workspace_id=WS-A`, `reference_id=newTaskId`), `Owner-A` nhận 0 thông báo; `Assignee-A` nhận frame STOMP `/user/queue/notifications`; `devflow.task.events.consume{eventType="task.created", consumer="ai", outcome="success"}` tăng +1. | PASS (28/11/2026) — `LifecycleAndManualEventFlowIntegrationTest.life01AndLife02_manualTaskCreationNotifiesAssigneeAndSelfActionsOrSameColumnReorderEmitZeroNotifications` |
| LIFE-02 | I | Tự thao tác (self-action): (a) `Assignee-A` tự tạo task gán cho chính mình qua `POST /api/v1/columns/{IN_PROGRESS}/tasks`; (b) `Assignee-A` tự kéo thẻ của mình sang `IN_REVIEW` qua `PATCH /api/v1/tasks/{id}/move`; (c) `Collab-A` reorder `Task-A1` trong **cùng cột** `TODO`. | Cả 3 thao tác trả `201`/`200`; `NotificationEventListeners` loại trừ tự thông báo ở (a) và (b), còn (c) không phát `TaskStatusChangedEvent` → tổng số dòng mới trong `notifications` = **0**. | PASS (28/11/2026) — `LifecycleAndManualEventFlowIntegrationTest.life01AndLife02_manualTaskCreationNotifiesAssigneeAndSelfActionsOrSameColumnReorderEmitZeroNotifications` |
| LIFE-03 | I | `Collab-A` (JWT thật, khác `Assignee-A`) gọi `PATCH /api/v1/tasks/{Task-A1}/move` kéo `Task-A1` từ `TODO` sang `IN_REVIEW`. | API trả `200`; `Task-A1.last_manual_status_changed_at` được cập nhật; `TaskStatusChangedEvent(cause=MANUAL, actorId=Collab-A)` phát sau commit; `notifications` lưu đúng **1** dòng `type='task.status_changed'` cho `Assignee-A`, **0** dòng cho `Collab-A`; cả `Assignee-A` và `Collab-A` nhận STOMP `CARD_MOVED` trên `/topic/boards/{Board-A}` (`actorId=Collab-A`). | PASS (28/11/2026) — `LifecycleAndManualEventFlowIntegrationTest.life03_collaboratorManualMoveNotifiesAssigneeUpdatesWatermarkAndBroadcastsBoardStomp` |
| LIFE-04 | I | Chuẩn bị commit gắn đồng thời `Task-A1` và `Task-A2` (2 link trong `git_commit_task_links` + 2 dòng `PENDING` trong `git_domain_event_outbox`), và PR gắn `Task-A1`. `Owner-A` gọi `DELETE /api/v1/tasks/{Task-A1}`. | Board phát `TaskReferencesRemovedEvent({Task-A1})` trong cùng transaction xóa; `GitTaskReferenceCleanup` xóa sạch link của `Task-A1` trong `git_commit_task_links`, `git_pull_request_task_links` và xóa outbox row `PENDING` của `Task-A1`, đồng thời khôi phục scalar mirror `git_commits.task_id = Task-A2`; chạy `gitOutbox.dispatch()` sau đó chỉ di chuyển `Task-A2` (0 lỗi, 0 tác động lên `Task-A1`). | PASS (28/11/2026) — `LifecycleAndManualEventFlowIntegrationTest.life04_deletingTaskCleansGitLinksAndPendingOutboxWhilePreservingSharedCommitLink` |
| LIFE-05 | I | Chuẩn bị dữ liệu cho `memberToErase` trong `WS-A`: được giao `Task-A1`, đã bình luận trên `Task-A1`, có audit log trên `Board-A`, và có thông báo trong `notifications`. Gọi REST thật `DELETE /api/v1/auth/me` với mật khẩu xác nhận. | Sau commit và cleanup: `users` không còn `memberToErase`; bảng `notifications` tự động xóa sạch mọi dòng của `memberToErase` (0 dòng nhờ FK `ON DELETE CASCADE`); `tasks.assignee_id` của `Task-A1` về `NULL`; tác giả bình luận đổi sang `DeletedUser.ID` (nội dung bình luận giữ nguyên); `board_account_cleanup_jobs = 0`. | PASS (28/11/2026) — `LifecycleAndManualEventFlowIntegrationTest.life05AndLife06_accountDeletionAndSoleWorkspaceDeletionCleanUpBoardGitCiAndNotifications` |
| LIFE-06 | I | Chuẩn bị `wsSolo` đầy đủ dữ liệu ở 3 module (`boardSolo`/`taskSolo`, `repoSolo` có commit/link/CI pipeline/failure/fetch job `PENDING`). Gọi REST thật `DELETE /api/v1/auth/me` với `soloOwner` (chủ sở hữu duy nhất của `wsSolo`). | `AccountDeletionService` xóa `wsSolo` và phát `WorkspaceDeletedEvent`; `GitTaskReferenceCleanup` xóa `repoSolo` (cascade sạch `git_commits`, `ci_pipelines`, `ci_log_fetch_jobs` của `wsSolo`); `AccountDeletionEventListener` của `board-impl` xóa sạch `boardSolo`/`taskSolo`; toàn bộ dữ liệu của `WS-A` vẫn nguyên vẹn 100%. | PASS (28/11/2026) — `LifecycleAndManualEventFlowIntegrationTest.life05AndLife06_accountDeletionAndSoleWorkspaceDeletionCleanUpBoardGitCiAndNotifications` |

---

### 6.8. Bộ 6 — Ranh giới Module, Độ ổn định (Repeatability), Observability & Quality Gates (`ARCH-01` .. `ARCH-07`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| ARCH-01 | A | Chạy `./gradlew :verifyModuleBoundaries` sau khi gia cố thêm kiểm tra `:common -> nothing` và `:X-api -> :common only`. | Task PASS với exit code `0`, 0 vi phạm trên toàn bộ 12 subprojects (`common`, 5 `-api`, 5 `-impl`, `app`). | PASS (28/11/2026) — `backend/build.gradle.kts` `:verifyModuleBoundaries` PASS 0 vi phạm trên cả 3 lượt `--rerun-tasks` lẫn full `check` |
| ARCH-02 | A | Chạy `ModuleBoundaryArchitectureTest`: quét toàn bộ file `.java` trong `src/main` của `common`, `*-api`, `*-impl` kiểm tra import, package và annotation. | 0 file trong `*-impl` hoặc `*-api` import `io.devflow.<other>.internal.*`; 0 file trong `*-api` chứa `@Entity`, `@Repository`, `@Service`, `@RestController`, `@Component`; 0 file trong `common` import bất kỳ `io.devflow.<module>.*`. | PASS (28/11/2026) — `ModuleBoundaryArchitectureTest.arch02_productionSourcesObeyStrictModuleImportAndApiPurityRules` (0,064s, 0 vi phạm) |
| ARCH-03 | A | Kiểm thử âm tính (negative self-test) trong `ModuleBoundaryArchitectureTest` trên chuỗi mã nguồn giả lập trong bộ nhớ: (a) `board-impl` import `io.devflow.gitci.internal.GitDomainEventOutbox`; (b) `board-api` gắn `@Entity`; (c) `common` import `io.devflow.auth.api.AuthApi`; (d) `board-api` import `io.devflow.auth.api.AuthApi`. | Hàm validator của `ModuleBoundaryArchitectureTest` phát hiện và báo lỗi chính xác cả 4 mẫu vi phạm (chứng minh rule kiểm tra không bị rỗng/bỏ lọt). | PASS (28/11/2026) — `ModuleBoundaryArchitectureTest.arch03_validatorReliablyDetectsSyntheticBoundaryViolations` (4/4 mẫu vi phạm bị bắt chính xác) |
| ARCH-04 | R | Chạy lặp toàn bộ bộ test T-041 **3 lần liên tiếp** bằng `./gradlew :verifyModuleBoundaries :app:test --tests "io.devflow.integration.*" --rerun-tasks` trên cùng máy. | Cả 3 lần chạy đều PASS 100% (0 failed, 0 flaky `429` quota, 0 lệch dữ liệu hay timeout STOMP/async). | PASS (28/11/2026) — 3/3 lượt chạy liên tiếp EXIT=0 (37/37 tests PASS, 0 failed, 0 skipped): Run 1 `02:06`, Run 2 `01:26`, Run 3 `01:26` |
| ARCH-05 | I | Kiểm tra `/actuator/prometheus` và `MeterRegistry` sau khi chạy chuỗi liên module (`E2E-01`). | Xuất đủ các chuỗi metrics chính (`devflow_gitci_webhook_requests_total`, `devflow_gitci_outbox_events_total`, `devflow_board_git_transitions_total`, `devflow_task_events_dispatch_total`, `devflow_task_events_consume_total`, `devflow_notification_delivered_total`); không có tag cardinality cao (không chứa `workspaceId`, `taskId`, `userId`, `sha`, `deliveryId`). | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.arch05AndArch06_actuatorHealthIsUpAndPrometheusExportsLowCardinalityCrossModuleMetrics` |
| ARCH-06 | I | Gọi `GET /actuator/health` trên `:app` trong test tích hợp (PostgreSQL 17 thật + `DeterministicChatModel` + mail health disabled). | Trả `200 OK` với `status = "UP"`, `components.db.status = "UP"`, `components.aiProvider.status = "UP"`. | PASS (28/11/2026) — `GitBoardNotificationFlowIntegrationTest.arch05AndArch06_actuatorHealthIsUpAndPrometheusExportsLowCardinalityCrossModuleMetrics` |
| ARCH-07 | I/A | Chạy đầy đủ quality gate backend: `cd backend && ./gradlew :verifyModuleBoundaries check` (bao gồm toàn bộ test hồi quy từ T-001..T-040B và bộ test T-041 mới). | Exit code `0`; 100% suites & test PASS (0 failed, 0 error, 0 vi phạm ranh giới module); tổng thời gian chạy nằm trong ngưỡng chấp nhận được trên local/CI. | PASS (28/11/2026) — `./gradlew.bat :verifyModuleBoundaries check` EXIT=0, `BUILD SUCCESSFUL in 7m 37s`: **115 suites / 1.008 tests**, 0 failed, 0 error (1 skipped pre-existing trong `CiWorkflowWebhookHttpIntegrationTest`; riêng T-041 `io.devflow.integration.*`: 5 suites / 37 tests, 0 failed, 0 skipped) |
