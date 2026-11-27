# T-026 — Kế hoạch triển khai hiển thị Git và trạng thái CI trên thẻ Kanban

**Milestone 3 · Phụ trách:** Xuân Lê (`@xuanle2`).  
**Ngày khảo sát và lập kế hoạch:** 27/11/2026 (Asia/Saigon).  
**Baseline mã nguồn:** nhánh `develop` tại commit `09fe624` (đã tích hợp T-020..T-025, T-031, T-040B, T-048).  
**Phạm vi:** `frontend` + bổ sung hợp đồng đọc & tín hiệu realtime ở `gitci`, `board`, `common` (tuân thủ nghiêm module boundaries).  
**Branch triển khai:** `feature/T-026-git-badges-on-cards`.  
**Trạng thái:** Đã hoàn tất triển khai backend + frontend (P0–P6), vượt qua toàn bộ automated quality gates (`./gradlew :verifyModuleBoundaries check`: 114 suites / 1.012 tests, 0 failed; `npm run lint`: 0 error/warning; `npm run build`: PASS; `npm test`: 24 files / 173 tests PASS), và hoàn tất smoke E2E trực tiếp trên trình duyệt thật (PostgreSQL 17 + Spring Boot `:8080` + Vite `:5173`, gửi 6 webhook GitHub ký HMAC thật, kiểm chứng realtime STOMP không cần F5, `TaskDetailModal` giữ nguyên bản nháp, kéo thả thẻ có đủ 3 badge, bàn phím `Tab`, mobile `360x800` và `/actuator/prometheus`). Sẵn sàng tạo PR review (chỉ còn diễn tập rollback vận hành `OPS-08` và smoke thêm qua Nginx Docker `:3000` nếu cần).

[Yêu cầu gốc](16_hien_thi_git_va_ci_tren_task.md) · [Thẻ Trello T-026](https://trello.com/c/e49E75p2/31-t-026-render-git-badges-commit-links-and-ci-status-on-frontend-task-cards) · [Tổng quan nhiệm vụ](00_nhiemvu.md) · [Plan realtime T-018](09_frontend_dong_bo_board_realtime_plan.md) · [Plan tự chuyển cột T-024](14_tu_dong_chuyen_trang_thai_task_plan.md) · [Plan lỗi CI T-025](15_tiep_nhan_loi_ci_plan.md).

---

## 1. Mục tiêu, hiện trạng mã nguồn và phạm vi

### 1.1. Kết quả cần đạt

Khi nhìn vào bảng Kanban, lập trình viên biết ngay từng thẻ công việc đã có bao nhiêu commit liên kết, Pull Request đang ở trạng thái nào và bản build/test CI gần nhất có đang lỗi hay không. Bấm trực tiếp vào từng huy hiệu sẽ mở tab mới tới đúng commit, Pull Request hoặc lần chạy GitHub Actions tương ứng mà không làm gián đoạn thao tác kéo thả hay mở nhầm cửa sổ chi tiết task. Task chưa có dữ liệu Git/CI giữ giao diện sạch, tuyệt đối không bị gắn cảnh báo sai.

Hai tiêu chí nghiệm thu gốc được đọc trực tiếp từ thẻ Trello T-026 (`6aaabc3cf7d793d8c543d55c`):

- Thẻ Kanban hiển thị trực quan thông tin Git.
- Click vào icon mở tab mới tới đúng commit/PR trên GitHub.

Các thiết kế về Read API, thuật toán tổng hợp CI, migration bổ sung `head_sha`, tín hiệu realtime, degraded mode và tiêu chí chất lượng SaaS dưới đây là **kế hoạch kỹ thuật đề xuất để đạt chuẩn production**, chưa phải tính năng đang chạy ở baseline và không thay thế checklist gốc trên Trello.

### 1.2. Kết quả đối chiếu trực tiếp từ mã nguồn (`develop` @ `09fe624`)

Bảng dưới ghi nhận hiện trạng quan sát từ source tại thời điểm khảo sát (không phải bằng chứng test runtime):

| Thành phần hiện tại | Hiện trạng quan sát trong mã nguồn | Ý nghĩa và tác động tới T-026 |
|---|---|---|
| [BoardColumnView.tsx](../../frontend/src/components/board/BoardColumnView.tsx) | `TaskCard` đang là hàm nội bộ (chưa export) ở dòng 294–513; thẻ có hàng 1 (key + priority + drag handle), hàng 2 (title `<button>`), hàng 3 (assignee + dueDate), hàng 4 ("Chuyển cột"). Chưa có file `TaskCard.tsx` riêng và chưa có badge Git/CI. | Tách `TaskCard` thành component riêng `frontend/src/components/board/TaskCard.tsx` (đúng tên trong thẻ Trello), đặt dải badge Git/CI làm hàng độc lập giữa hàng 3 và hàng 4 (không lồng `<a>` trong `<button>`). |
| [BoardPage.tsx](../../frontend/src/pages/BoardPage.tsx), [boardSyncCoordinator.ts](../../frontend/src/board/boardSyncCoordinator.ts), [boardMutation.ts](../../frontend/src/realtime/boardMutation.ts) | Đã có luồng tải board/cột phân trang (`size=100`, tối đa 50 trang/cột), STOMP `/topic/boards/{boardId}`, dedup eventId (1.000 ID/5 phút), reconcile khi reconnect/focus/periodic 30–35s. `parseBoardMutationMessage` chấp nhận `CARD_UPDATED`, `changedFields` khớp `/^[A-Za-z][A-Za-z0-9_]{0,39}$/` và `refresh` gồm `TASK_LISTS`, `TASK_DETAIL`. | Tích hợp tải & đồng bộ Git/CI summaries theo `boardId`/`workspaceId`/`sessionGeneration` và tập `taskId` đã tải. Nếu nhận `CARD_UPDATED` chỉ đổi `gitActivity`, refresh gọn phần Git/CI mà không hủy drag đang diễn ra hay refetch toàn bộ cột. |
| [AuthProvider.tsx](../../frontend/src/auth/AuthProvider.tsx) | Dòng 177–178 & 224–227 định nghĩa `const mutation = method !== 'GET'`: mọi phương thức khác `GET` (kể cả `POST`) sẽ **không** tự động retry sau khi refresh JWT khi gặp `401` mà ném `ApiError(..., uncertain = true)`. | **Phát hiện quan trọng:** Read API hàng loạt cho Git/CI nên dùng `GET` có giới hạn chunk (tối đa 50 UUID/lần gọi, URL ~1,9 KiB) thay vì `POST`, để tận dụng nguyên vẹn cơ chế refresh JWT single-flight + 1 lần retry của `AuthProvider` mà không phải sửa code Auth. |
| [TaskDetailModal.tsx](../../frontend/src/components/board/TaskDetailModal.tsx) | Đã có chỉnh sửa 5 trường (`If-Match` theo `task.version`), Markdown preview, assignee picker phân trang và comments. Chưa có khu vực xem danh sách commit/PR/CI. | Bổ sung khu vực chỉ đọc (read-only) "Hoạt động Git & CI" khi task có nhiều commit/PR/lần chạy CI, độc lập hoàn toàn với bản nháp form và `If-Match`. |
| [Icons.tsx](../../frontend/src/components/common/Icons.tsx), [UiPrimitives.tsx](../../frontend/src/components/common/UiPrimitives.tsx), [package.json](../../frontend/package.json) | Đã có `GitBranchIcon`, `AlertTriangleIcon`, `AlertCircleIcon`, `CheckCircleIcon`, `Badge`; frontend dùng React 19.1, TypeScript 5.8, TailwindCSS 4, `@dnd-kit/react`, `@stomp/stompjs`, và đã có sẵn `vitest 3.2.4` + `jsdom 26.1.0`. | Bổ sung SVG icon đồng bộ (`GitCommitIcon`, `GitPullRequestIcon`, `GitMergeIcon`, `ExternalLinkIcon`, `XCircleIcon`) ngay trong `Icons.tsx` — không cần thêm dependency ngoài. |
| [V6__gitci_schema.sql](../../backend/app/src/main/resources/db/migration/V6__gitci_schema.sql), [V16__git_task_associations.sql](../../backend/app/src/main/resources/db/migration/V16__git_task_associations.sql), [V19__ci_failure_ingestion.sql](../../backend/app/src/main/resources/db/migration/V19__ci_failure_ingestion.sql) | Bảng liên kết chuẩn là `git_commit_task_links` và `git_pull_request_task_links` (N-N, có index theo `task_id`); `git_commits.task_id` và `pull_requests.task_id` chỉ là scalar mirror (NULL khi 1 commit/PR gắn ≥2 task). `ci_pipelines` có index `(repository_id, commit_sha)` và unique `(repository_id, pipeline_id, run_attempt)`. `ci_failures.related_task_id` hiện **luôn NULL** (chủ đích của T-025). `pull_requests` hiện **chưa lưu `head_sha`**. Migration cao nhất là V19. | Tuyệt đối truy vấn từ hai bảng link V16, không đọc scalar mirror. Nối CI với task bằng `(repository_id, commit_sha)` thay vì `related_task_id` hay tên branch; bổ sung migration **V20** thêm cột nullable `pull_requests.head_sha` để bao phủ cả trường hợp task chỉ gắn qua tiêu đề/branch PR mà commit không chứa `T-XXX`. |
| [GitCiApi.java](../../backend/gitci-api/src/main/java/io/devflow/gitci/api/GitCiApi.java), [GitCiService.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitCiService.java), [gitci-impl/build.gradle.kts](../../backend/gitci-impl/build.gradle.kts) | `gitci-impl` chưa có REST Read Controller nào cho người dùng đăng nhập (`GitCiApi.findFailure` đã `@Deprecated` và ném `UnsupportedOperationException`). `gitci-impl/build.gradle.kts` chưa khai báo `spring-security-core`, `spring-boot-starter-validation`, `bucket4j-core`. | Xây dựng Read Controller/Service trong `gitci-impl` với đầy đủ xác thực JWT, kiểm membership qua `WorkspaceMembershipCheckRequestedEvent` (tái sử dụng pattern của `CiFailureLogReader`), Jakarta validation, rate limiter và RFC 7807 `ProblemDetail`. |
| [GitWebhookIngestion.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitWebhookIngestion.java), [CiWorkflowIngestion.java](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/CiWorkflowIngestion.java) | `GitWebhookIngestion.repositoryBase()` đã validate chặt URL GitHub `https://github.com/<owner>/<repo>`. `CiWorkflowIngestion` chỉ persist `workflow_run` khi `action == "completed"` (có test hồi quy `WH-04` kiểm tra non-completed bị `ignored`). `git_commits` không lưu cột `commit_url`; `ci_pipelines` không lưu `run_url`. | Dựng URL chuẩn hóa phía server từ `repositoryBase` + `sha` / `pr_number` / `(pipeline_id, run_attempt)`. Giữ nguyên hành vi `WH-04` của T-025 để không phá regression; Read API và UI vẫn ánh xạ đầy đủ mọi enum `ci_pipelines.status` (`FAILED`, `SUCCESS`, `RUNNING`, `PENDING`, `TIMED_OUT`, `CANCELLED`...). |
| [TaskListItem.java](../../backend/board-api/src/main/java/io/devflow/board/api/TaskListItem.java), [GitTaskTransitionService.java](../../backend/board-impl/src/main/java/io/devflow/board/internal/GitTaskTransitionService.java), [BoardRealtimeBroadcaster.java](../../backend/board-impl/src/main/java/io/devflow/board/internal/BoardRealtimeBroadcaster.java) | `TaskListItem` thuần dữ liệu Board. `GitTaskTransitionService` chỉ broadcast `CARD_MOVED` khi thực sự đổi cột (`APPLIED`); khi `NOOP`, `DISABLED` (`auto-transition-enabled` mặc định `false`), `STALE_MANUAL`, PR `closed` không merge, hoặc CI hoàn tất (`FAILED`/`SUCCESS`), **không có frame STOMP nào được phát**. | Bổ sung typed event nhẹ `TaskGitActivityChangedEvent` trong `:common` để `gitci-impl` báo sau commit và `board-impl` broadcast `CARD_UPDATED` (`changedFields=["gitActivity"]`) lên `/topic/boards/{boardId}`, giúp badge cập nhật realtime < 1 giây ngay cả khi task không đổi cột. |
| [SecurityConfig.java](../../backend/auth-impl/src/main/java/io/devflow/auth/internal/SecurityConfig.java), [BoardRequestCorrelationFilter.java](../../backend/board-impl/src/main/java/io/devflow/board/internal/BoardRequestCorrelationFilter.java) | `SecurityConfig` đã bảo vệ `.anyRequest().authenticated()` (chỉ mở `POST /api/v1/gitci/webhook/github` và `/api/v1/*/health`). `BoardRequestCorrelationFilter` chạy toàn cục ở `HIGHEST_PRECEDENCE` gắn `X-Correlation-ID` vào MDC. | Mọi endpoint đọc `/api/v1/gitci/workspaces/**` tự động yêu cầu JWT hợp lệ và thừa hưởng `correlationId` trong MDC mà không cần sửa `SecurityConfig` của Auth. |
| [AiEventListeners.java](../../backend/ai-impl/src/main/java/io/devflow/ai/internal/AiEventListeners.java), [V14__ai_ci_summaries.sql](../../backend/app/src/main/resources/db/migration/V14__ai_ci_summaries.sql) | AI lưu tóm tắt lỗi CI trong bảng riêng `ai_ci_summaries` và phát `CiFailureSummarizedEvent` với `relatedTaskId = null`; hiện `board` và `gitci` chưa có listener ghi projection `ci_failures.ai_summary`. | T-026 không tự đọc chéo bảng `ai_ci_summaries` hay bịa tóm tắt AI; phần hiển thị bản tóm tắt AI trên thẻ/modal tách khỏi phạm vi T-026. |

Tài liệu kiến trúc và đặc tả đối chiếu: [ARCHITECTURE.md](../ARCHITECTURE.md) §2–§4, §6, §8, §9.2, §10; [PRODUCT_SPEC.md](../PRODUCT_SPEC.md) §5.2a, §6, §8; [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md) Phase 3 & §5; [module-boundaries.md](../../.agents/rules/module-boundaries.md); [coding-conventions.md](../../.agents/rules/coding-conventions.md).

### 1.3. Phạm vi, ngoài phạm vi và các điều kiện phụ thuộc

**Trong phạm vi T-026:**
- Tách `TaskCard.tsx` và dựng bộ huy hiệu Git/CI (`TaskGitBadges.tsx`) hiển thị số lượng commit liên kết, PR chính (kèm trạng thái và số lượng PR khác nếu > 1) và trạng thái CI (cảnh báo đỏ nổi bật khi lỗi, trạng thái đạt/đang chạy/khác chính xác).
- Mở tab mới an toàn (`target="_blank" rel="noopener noreferrer"`) tới đúng commit, Pull Request hoặc lần chạy GitHub Actions trên GitHub.
- Bổ sung Read API được phân quyền trong `gitci-impl`: API đọc theo lô cho board (`GET /api/v1/gitci/workspaces/{workspaceId}/task-summaries`) và API chi tiết hoạt động Git/CI cho một task (`GET /api/v1/gitci/workspaces/{workspaceId}/tasks/{taskId}/activity`).
- Migration **V20** bổ sung cột nullable `pull_requests.head_sha` (kèm cập nhật trích xuất tại `GitWebhookIngestion`) để liên kết chính xác CI của PR ngay cả khi commit không ghi mã task.
- Bổ sung typed event `TaskGitActivityChangedEvent` trong `:common` + listener nhẹ ở `board-impl` để phát STOMP invalidation `CARD_UPDATED` (`changedFields=["gitActivity"]`) giúp UI cập nhật ngay khi có Git/CI mới mà không phá kéo thả.
- Bổ sung mục chỉ đọc "Hoạt động Git & CI" trong `TaskDetailModal.tsx` để người dùng xem và bấm vào từng commit, từng PR và lịch sử chạy CI gần nhất khi task có nhiều mục.
- Đầy đủ unit test, component test, PostgreSQL 17 integration/query-plan test, kiểm tra module boundaries và E2E thực tế.

**Ngoài phạm vi T-026:**
- Hiển thị nội dung tóm tắt lỗi do AI tạo (T-031) hoặc đọc raw log CI từ trình duyệt (raw log CI chỉ dành cho pipeline nội bộ có redaction theo T-025).
- Thay đổi policy `completed`-only của webhook `workflow_run` (giữ nguyên hợp đồng `WH-04` của T-025).
- Tự kích hoạt rerun workflow GitHub Actions, tạo/gỡ liên kết commit/PR thủ công từ UI, hoặc hỗ trợ GitLab.
- Thêm bộ lọc/sắp xếp board theo trạng thái CI (để mở thành cải tiến riêng sau khi xác nhận UX lõi).
- Ghi đè hoặc tự động chuyển cột từ trạng thái CI (chuyển cột theo Git thuộc phạm vi T-024).

| Gate | Điều kiện cần chốt trước khi nghiệm thu | Phụ trách |
|---|---|---|
| G1 — Hợp đồng Read API & V20 | Chốt schema JSON batch/activity, quy tắc tổng hợp CI/PR, migration V20 additive không sửa V1–V19, giữ nguyên `WH-04` T-025. | Xuân (`gitci-impl`) |
| G2 — Bảo mật & ranh giới | Kiểm membership qua `WorkspaceMembershipCheckRequestedEvent`, tenant filter `r.workspace_id`, rate limit, ProblemDetail, không có `impl -> impl` (`:verifyModuleBoundaries`). | Xuân (`gitci-impl`, `board-impl`) |
| G3 — Realtime không gây giật UI | `TaskGitActivityChangedEvent` phát sau commit; `CARD_UPDATED` chỉ chứa `gitActivity` không hủy DnD và không refetch toàn cột không cần thiết. | Xuân (`common`, `board-impl`, `frontend`) |
| G4 — Degraded mode & tương thích | Khi GitCI Read API lỗi/chậm/429, Board vẫn load/kéo thả/lưu task bình thường; `TaskDetailModal` giữ nguyên flow `If-Match`/draft. | Xuân (`frontend`) |
| G5 — Kiểm chứng & bằng chứng | Đầy đủ test tự động backend/frontend, `EXPLAIN ANALYZE` PostgreSQL 17, smoke E2E thật qua Vite & Docker Nginx với webhook GitHub ký HMAC. | Xuân + reviewer |

---

## 2. Kế hoạch nghiệp vụ — mô tả luồng bằng ngôn ngữ

### NV-01. Mở bảng Kanban với task có và chưa có hoạt động Git/CI

1. Thành viên đăng nhập và mở một board thuộc workspace của mình. Bảng và các thẻ công việc hiện lên ngay từ dữ liệu Board mà không phải chờ truy vấn Git/CI hoàn tất.
2. Sau khi danh sách task hiển thị, hệ thống tự động tải tóm tắt Git/CI theo nhóm cho các thẻ đang có trên bảng và gắn huy hiệu vào đúng từng thẻ.
3. Thẻ công việc **chưa có** commit, Pull Request hay lần chạy CI nào được giữ nguyên bố cục gọn gàng: không hiện số "0 commit", không hiện khung rỗng và tuyệt đối không gắn cảnh báo lỗi CI giả.
4. Thẻ chỉ có commit (chưa có PR, chưa có CI) chỉ hiện huy hiệu commit; thẻ chỉ có PR (chưa có commit khớp mã task) chỉ hiện huy hiệu PR — mỗi loại thông tin xuất hiện độc lập theo dữ liệu thực tế.

### NV-02. Xem và mở commit liên kết từ thẻ Kanban

1. Khi một task có ít nhất một commit liên kết, thẻ hiển thị huy hiệu mang biểu tượng commit kèm tổng số commit (ví dụ: biểu tượng commit + `3`).
2. Di chuột hoặc focus bằng bàn phím vào huy hiệu sẽ hiện chú giải ngắn gồm mã rút gọn 7 ký tự, dòng đầu tiêu đề commit mới nhất và tên repository.
3. Bấm vào huy hiệu commit sẽ mở **tab trình duyệt mới** đi thẳng tới trang chi tiết commit mới nhất trên GitHub.
4. Nếu task có nhiều hơn 1 commit và người dùng muốn xem các commit cũ hơn, họ mở cửa sổ chi tiết task (mục "Hoạt động Git & CI") để thấy danh sách các commit gần nhất theo thứ tự mới trước và bấm mở từng commit riêng lẻ trên GitHub.

### NV-03. Xem và mở Pull Request liên kết với trạng thái trực quan

1. Khi một task có ít nhất một Pull Request liên kết, thẻ hiển thị huy hiệu mang biểu tượng PR kèm số PR chính (ví dụ: `#42`). Nếu task có nhiều hơn một PR (ví dụ một PR đã đóng và một PR đang mở, hoặc hai PR ở hai repo), thẻ hiển thị thêm phần đếm bổ sung (ví dụ `#42 +1`).
2. Màu sắc và nhãn chú giải phản ánh đúng trạng thái hiện tại của PR chính:
   - **Đang mở (`OPEN`):** tông xanh lá rõ ràng, thể hiện mã đang chờ review/merge.
   - **Đã merge (`MERGED`):** tông tím đặc trưng của Git merge.
   - **Đã đóng chưa merge (`CLOSED`):** tông xám trung tính để không gây nhầm với PR đang mở hoặc đã merge.
3. Khi task có nhiều PR, hệ thống luôn chọn PR đáng chú ý nhất làm PR chính hiển thị ngoài thẻ theo thứ tự ưu tiên: **Đang mở → Đã merge → Đã đóng** (cùng trạng thái chọn PR cập nhật gần nhất).
4. Bấm vào huy hiệu PR sẽ mở **tab trình duyệt mới** đi thẳng tới trang Pull Request tương ứng trên GitHub. Các PR còn lại (nếu có) đều xem và mở được trong cửa sổ chi tiết task.

### NV-04. Cảnh báo đỏ khi CI thất bại và hiển thị trạng thái build/test

1. Khi lần build/test CI hiện hành liên quan đến task kết thúc với kết quả **thất bại (`FAILED`)**, thẻ hiển thị nổi bật huy hiệu cảnh báo **màu đỏ** kèm biểu tượng cảnh báo và chữ `CI lỗi` (không chỉ dùng màu để người khiếm thị màu vẫn phân biệt được). Nếu có nhiều workflow cùng lỗi trên commit đó, chú giải cho biết rõ số workflow đang lỗi.
2. Khi lần chạy lại (`rerun`) của chính workflow đó thành công, hoặc lập trình viên đẩy commit sửa lỗi mới và CI trên commit mới **thành công (`SUCCESS`)**, cảnh báo đỏ biến mất và thay bằng huy hiệu trạng thái đạt (`CI đạt`, tông xanh lá nhẹ).
3. Nếu hệ thống ghi nhận kết quả khác như **đang chạy (`RUNNING`/`PENDING`)**, **quá thời gian (`TIMED_OUT`)**, **cần hành động (`ACTION_REQUIRED`)** hoặc **đã hủy (`CANCELLED`)**, huy hiệu hiển thị đúng nhãn trạng thái trung thực (ví dụ `CI đang chạy`, `CI quá hạn`, `CI đã hủy`), không đánh đồng thành `CI lỗi` hay `CI đạt`.
4. Bấm vào huy hiệu CI sẽ mở **tab trình duyệt mới** tới đúng trang lần chạy (attempt) trên GitHub Actions để lập trình viên kiểm tra chi tiết job/step ngay trên GitHub.

### NV-05. Xử lý chính xác các tình huống phức tạp (nhiều task, nhiều workflow, nhiều repo)

1. **Một commit gắn nhiều task** (ví dụ commit message ghi `T-010 T-011`): cả hai thẻ `T-010` và `T-011` đều tăng số commit, trỏ đúng tới commit đó và phản ánh đúng kết quả CI chạy trên commit đó.
2. **Nhiều workflow trên cùng commit** (ví dụ workflow `Lint` đạt nhưng `Backend Tests` lỗi): thẻ vẫn hiện cảnh báo đỏ `CI lỗi` và bấm vào mở đúng workflow `Backend Tests` bị lỗi.
3. **Nhiều repository cùng gắn một task** (ví dụ `frontend` CI đạt nhưng `backend` CI lỗi): tổng hợp số commit và PR trên toàn bộ các repo của workspace gắn với task; nếu bất kỳ repo nào đang có CI lỗi trên commit mới nhất của nó, thẻ vẫn hiện cảnh báo đỏ `CI lỗi` và dẫn tới lần chạy lỗi.
4. **Rerun trên commit cũ không làm sai trạng thái mới:** nếu commit C1 bị lỗi, sau đó lập trình viên đẩy commit C2 sửa lỗi và CI của C2 đã đạt, thì dù có ai bấm chạy lại workflow cũ trên C1 bị lỗi, thẻ vẫn giữ trạng thái `CI đạt` của commit mới nhất C2.
5. **Không nối nhầm theo tên branch:** nếu hai task ở hai thời điểm khác nhau từng dùng chung tên branch (như `develop` hoặc một nhánh bị xóa rồi tạo lại), kết quả CI chỉ được nối theo đúng mã băm commit (SHA) của task, không bao giờ làm thẻ cũ bị gắn cảnh báo sai.

### NV-06. Cập nhật tự động khi có Git/CI mới (kể cả khi task không đổi cột)

1. Khi lập trình viên push commit đầu tiên làm task tự nhảy từ `To Do` sang `In Progress` (T-024), mọi thành viên đang xem board thấy đồng thời thẻ chuyển cột và huy hiệu commit xuất hiện.
2. Khi lập trình viên push commit thứ hai lúc task đã ở `In Progress`, khi tính năng tự chuyển cột đang tắt, khi PR bị đóng không merge, hoặc khi CI vừa chạy xong (báo lỗi đỏ hoặc chạy lại thành xanh) mà **thẻ không hề đổi cột**, bảng vẫn tự động nhận tín hiệu và làm mới huy hiệu trên thẻ mà không cần bấm F5.
3. Nếu kết nối realtime tạm gián đoạn rồi nối lại, hoặc người dùng chuyển từ tab khác quay lại bảng, hệ thống tự động đối soát lại tóm tắt Git/CI để hiển thị trạng thái mới nhất.

### NV-07. Tương tác mượt mà với kéo thả, lọc, phân trang và cửa sổ chi tiết

1. Bấm vào các huy hiệu Commit / PR / CI chỉ mở tab mới tới GitHub — tuyệt đối **không** vô tình mở cửa sổ chi tiết task và **không** kích hoạt thao tác kéo thẻ.
2. Khi người dùng đang giữ chuột kéo một thẻ khác mà có thông báo cập nhật Git/CI tới, thao tác kéo thả không bị hủy giữa chừng.
3. Khi lọc thẻ theo từ khóa, người phụ trách hoặc độ ưu tiên, các thẻ khớp lọc giữ nguyên huy hiệu Git/CI; xóa lọc hiện lại đầy đủ mà không mất dữ liệu.
4. Khi bấm nút "Tải tiếp" ở một cột có nhiều hơn 100 thẻ, hệ thống tự động tải bổ sung huy hiệu Git/CI cho các thẻ vừa nạp thêm.
5. Trên bảng đã lưu trữ (`archived`), người dùng vẫn xem và bấm mở liên kết Git/CI bình thường ở chế độ chỉ đọc.

### NV-08. Chịu lỗi an toàn, bảo mật dữ liệu và dọn dẹp vòng đời

1. Nếu dịch vụ đọc Git/CI tạm thời chậm, quá tải (`429`) hoặc gặp lỗi mạng (`5xx`/timeout), toàn bộ bảng Kanban, thao tác kéo thả và chỉnh sửa chi tiết task vẫn hoạt động bình thường; hệ thống hiển thị thông báo nhẹ không chặn thao tác kèm nút thử tải lại thông tin Git/CI.
2. Khi người dùng chuyển workspace, chuyển board, đăng xuất hoặc bị thu hồi quyền thành viên workspace, toàn bộ dữ liệu Git/CI đang lưu trong bộ nhớ trình duyệt được xóa ngay lập tức; phản hồi mạng đến muộn của board cũ bị loại bỏ.
3. Khi task, repository hoặc workspace bị xóa, liên kết Git/CI tương ứng được dọn sạch phía máy chủ và không còn xuất hiện trên bất kỳ màn hình nào.

---

## 3. Kế hoạch kỹ thuật — phân tích kiến trúc và các yếu tố triển khai

### KT-01. Kiến trúc liên module & lựa chọn Topology Read API

Theo [ARCHITECTURE.md](../ARCHITECTURE.md) §3 & §10 và [module-boundaries.md](../../.agents/rules/module-boundaries.md): `board` sở hữu bảng/cột/task (không sở hữu dữ liệu Git/CI), còn `gitci` sở hữu `repositories`, `git_commits`, `git_commit_task_links`, `pull_requests`, `git_pull_request_task_links`, `ci_pipelines`, `ci_failures`. Cấm tuyệt đối `impl -> impl` và truy vấn bảng chéo module.

Hai phương án thiết kế đã được cân nhắc:

| Tiêu chí | Phương án A (Được chọn): Read API riêng trong `gitci-impl` | Phương án B (Loại bỏ): Nhúng `gitSummary` vào `TaskListItem` của `board-impl` qua event đồng bộ |
|---|---|---|
| Tách biệt miền sở hữu (§3) | **Sạch tuyệt đối:** `board-api` không phải chứa DTO Git/CI; mỗi module quản trị dữ liệu của mình. | Làm phình `TaskListItem` của `board-api` bằng cấu trúc miền Git/CI. |
| Độ trễ & cách ly lỗi (§8) | Board CRUD (`GET /columns/{id}/tasks`) giữ nguyên P95 < 200ms; GitCI lỗi/chậm không làm sập hay chặn Kanban. | Mọi lệnh tải cột, kéo thả, sắp xếp đều kéo thêm truy vấn GitCI trong cùng request Board; GitCI lỗi kéo cả cột lỗi. |
| Chi phí truy vấn khi thao tác | Chỉ gọi khi load/sync board hoặc khi có tín hiệu `gitActivity`; kéo thả tay không cần đọc lại GitCI. | Mỗi lần kéo thả/reconcile cột đều chạy lại truy vấn tổng hợp GitCI dù không có commit/CI mới. |
| Lộ trình tách service (§8) | Sẵn sàng trích `gitci` thành microservice độc lập — frontend đã gọi trực tiếp `/api/v1/gitci/...`. | Tạo lời gọi đồng bộ chéo service trên hot-path đọc bảng Kanban. |

**Luồng kiến trúc tổng thể (Phương án A + Realtime Invalidation qua Event Bus):**

```text
[GitHub Webhook] ──► [GitHubWebhookController (HMAC)] ──► [GitWebhookIngestion / CiWorkflowIngestion]
                                                                  │
                                                         (Transaction commit)
                                                                  │
                                            afterCommit: publish TaskGitActivityChangedEvent (:common)
                                                                  │
                                                                  ▼
                                                         [Spring Event Bus]
                                                                  │
                                                                  ▼
                                               [BoardGitActivityRealtimeListener (board-impl)]
                                                 (read-only resolve boardId/columnId + tenant check)
                                                                  │
                                                                  ▼
                                               [BoardRealtimeBroadcaster] ──► STOMP /topic/boards/{boardId}
                                                  (CARD_UPDATED, changedFields=["gitActivity"])
                                                                  │
                                                                  ▼
[Frontend BoardPage] ──(1) Load/Reconcile Board──► GET /api/v1/boards/{id} + /columns/{id}/tasks
                     ──(2) Batch Git/CI (chunks ≤50)──► GET /api/v1/gitci/workspaces/{wsId}/task-summaries?taskIds=...
                     ──(3) Modal Drill-down──────────► GET /api/v1/gitci/workspaces/{wsId}/tasks/{taskId}/activity
                                                                  │
                                                        [GitCiReadController (gitci-impl)]
                                                          ├─ JWT (SecurityConfig) + AuthenticatedActor
                                                          ├─ WorkspaceMembershipCheckRequestedEvent -> Auth
                                                          ├─ Bucket4j per-actor read rate limiter
                                                          └─ 3 set-based SQL queries (scoped by r.workspace_id)
```

### KT-02. Hợp đồng REST API trong `gitci-impl` và quyết định `GET` có giới hạn chunk

**Tại sao dùng `GET` (tối đa 50 UUID/lần gọi) thay vì `POST`:**
Qua đọc [AuthProvider.tsx](../../frontend/src/auth/AuthProvider.tsx#L177-L227), `request()` phân loại `mutation = method !== 'GET'`. Nếu endpoint đọc dùng `POST`, khi access token vừa hết hạn trong lúc đồng bộ nền/focus tab, `AuthProvider` sẽ **không** tự động retry sau refresh mà ném lỗi 401 với cờ `uncertain = true`. Với `GET` giới hạn tối đa **50 distinct UUIDs/request**:
- Độ dài query string tối đa `50 * 37 = 1.850` ký tự → tổng đường dẫn ~`1,93 KiB`, thấp hơn rất nhiều giới hạn mặc định 8 KiB của Tomcat (`maxHttpHeaderSize`), Nginx (`large_client_header_buffers`) và các CDN/proxy phổ biến.
- Tuân thủ ngữ nghĩa HTTP `GET` an toàn/idempotent, hưởng trọn cơ chế single-flight refresh + 1 retry của `AuthProvider` mà không phải chỉnh sửa mã nguồn của module Auth.
- Task UUID là định danh ngẫu nhiên không mang PII hay secret; cả Vite và Nginx đều đã thiết lập `Referrer-Policy: no-referrer`.

#### Endpoint 1 — Đọc tóm tắt Git/CI theo lô cho bảng Kanban
- **Đường dẫn:** `GET /api/v1/gitci/workspaces/{workspaceId}/task-summaries?taskIds=<uuid1>,<uuid2>,...`
- **Validation đầu vào:**
  - `workspaceId`: UUID hợp lệ (sai định dạng → `400 INVALID_REQUEST`).
  - `taskIds`: bắt buộc có mặt, chấp nhận cả dạng phẩy `taskIds=a,b` hoặc lặp `taskIds=a&taskIds=b`, loại trùng giữ thứ tự; phải chứa từ **1 đến 50** UUID chuẩn RFC 4122 (rỗng, > 50 distinct IDs hoặc có phần tử sai UUID → `400 VALIDATION_FAILED`).
- **Response `200 OK`:** Trả về danh sách các task **có ít nhất một dữ liệu Git hoặc CI** thuộc `workspaceId` (task không có Git/CI hoặc không thuộc workspace không nằm trong `items`, tránh phình payload và chống dò tồn tại ID chéo workspace):

```json
{
  "workspaceId": "11111111-1111-4111-8111-111111111111",
  "items": [
    {
      "taskId": "22222222-2222-4222-8222-222222222222",
      "commitCount": 3,
      "latestCommit": {
        "sha": "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
        "shortSha": "a1b2c3d",
        "messagePreview": "feat(board): T-026 render git and ci badges",
        "url": "https://github.com/devflow/app/commit/a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
        "committedAt": "2026-11-27T12:10:00Z",
        "repositoryName": "devflow/app"
      },
      "prCount": 2,
      "openPrCount": 1,
      "primaryPr": {
        "prNumber": 42,
        "title": "T-026: Render Git badges and CI status on Kanban cards",
        "status": "OPEN",
        "url": "https://github.com/devflow/app/pull/42",
        "sourceBranch": "feature/T-026-git-badges-on-cards",
        "updatedAt": "2026-11-27T12:15:00Z",
        "repositoryName": "devflow/app"
      },
      "ci": {
        "status": "FAILED",
        "providerConclusion": "failure",
        "failedRunCount": 1,
        "runId": "987654321",
        "runAttempt": 2,
        "commitSha": "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
        "repositoryName": "devflow/app",
        "url": "https://github.com/devflow/app/actions/runs/987654321/attempts/2",
        "finishedAt": "2026-11-27T12:18:00Z",
        "staleForLatestCommit": false
      }
    }
  ]
}
```

#### Endpoint 2 — Chi tiết hoạt động Git/CI của một task (cho `TaskDetailModal`)
- **Đường dẫn:** `GET /api/v1/gitci/workspaces/{workspaceId}/tasks/{taskId}/activity?commitsLimit=20&prsLimit=20&ciRunsLimit=10`
- **Validation:** `commitsLimit` ∈ `[1..50]` (mặc định `20`), `prsLimit` ∈ `[1..50]` (mặc định `20`), `ciRunsLimit` ∈ `[1..20]` (mặc định `10`). Ngoài khoảng trả `400 VALIDATION_FAILED`.
- **Response `200 OK`:** Trả về:
  - `summary`: cấu trúc giống item của Endpoint 1 (hoặc `null` nếu task chưa có dữ liệu Git/CI trong workspace).
  - `totalCommits`, `commits[]` (sắp xếp `committedAt DESC, createdAt DESC, id DESC`, giới hạn `commitsLimit`).
  - `totalPullRequests`, `pullRequests[]` (sắp xếp ưu tiên `OPEN > MERGED > CLOSED`, rồi `updatedAt DESC, prNumber DESC`, giới hạn `prsLimit`).
  - `totalCiRuns`, `ciRuns[]` (chỉ giữ `MAX(run_attempt)` của từng `(repository_id, pipeline_id)` gắn với các SHA của task, sắp xếp theo thời điểm commit liên quan giảm dần rồi thời điểm cập nhật CI giảm dần, giới hạn `ciRunsLimit`).

**Quy tắc dựng URL an toàn phía server (`GitHubUrlBuilder`):**
Tái sử dụng đúng ràng buộc chặt của `GitWebhookIngestion.repositoryBase(url)` (`https`, host chính xác `github.com`, không port/userinfo/query/fragment, path `/owner/repo`, bỏ `/` cuối và `.git`):
- **Commit URL:** `<repoBase>/commit/<sha>` (chỉ dựng khi `sha` khớp `^([0-9a-f]{40}|[0-9a-f]{64})$` và không toàn 0).
- **PR URL:** ưu tiên dựng chuẩn `<repoBase>/pull/<prNumber>` (khi `prNumber > 0`), đối chiếu hợp lệ thay vì tin mù chuỗi tự do trong DB.
- **CI Run Attempt URL:** `<repoBase>/actions/runs/<pipelineId>/attempts/<runAttempt>` (chỉ dựng khi `pipelineId` khớp `^[1-9][0-9]*$` và `runAttempt > 0`; ngược lại trả `url = null`).
- Nếu vì lý do dữ liệu legacy nào đó `repository_url` không hợp lệ chuẩn HTTPS GitHub, trả `url = null` và ghi metric cảnh báo cấu hình thay vì phát URL không an toàn hoặc làm lỗi cả response `500`.

### KT-03. Chính sách liên kết CI ↔ Task, Migration V20 và thuật toán tổng hợp

#### 1. Tại sao nối bằng `(repository_id, commit_sha)` và bổ sung `pull_requests.head_sha` (V20)
- `ci_failures.related_task_id` luôn `NULL` theo thiết kế T-025 (vì một lần chạy CI có thể tương ứng commit gắn nhiều task và webhook CI có thể tới trước push).
- **Cấm nối theo tên `branch`:** tên nhánh có thể tái sử dụng (`develop`, `main`, nhánh xóa đi tạo lại), gây gắn nhầm lỗi CI của công việc khác lên thẻ cũ.
- Nguồn SHA liên kết với một `task_id` trong cùng `repository_id` (thuộc `workspace_id`) là **hợp (UNION)** của hai tập đã xác minh:
  1. `git_commit_task_links l JOIN git_commits c ON c.id = l.commit_id` → `(c.repository_id, c.sha, c.committed_at, c.created_at, c.id)`.
  2. `git_pull_request_task_links pl JOIN pull_requests pr ON pr.id = pl.pull_request_id` (khi `pr.head_sha IS NOT NULL`) → `(pr.repository_id, pr.head_sha, COALESCE(pr.provider_updated_at, pr.updated_at), pr.created_at, pr.id)`.
- **Migration mới `V20__pull_request_head_sha.sql`** (không sửa V1–V19):
  - `ALTER TABLE pull_requests ADD COLUMN head_sha VARCHAR(64);`
  - `ALTER TABLE pull_requests ADD CONSTRAINT ck_pull_requests_head_sha CHECK (head_sha IS NULL OR (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$' AND head_sha !~ '^0+$'));`
  - `CREATE INDEX idx_pull_requests_repo_head_sha ON pull_requests(repository_id, head_sha) WHERE head_sha IS NOT NULL;`
  - Cập nhật `GitWebhookIngestion.pullRequest(...)`: đọc `pull_request.head.sha` (nếu có mặt thì validate cùng chuẩn `sha(...)` — 40/64 hex lowercase không toàn zero; giữ tương thích nếu fixture test cũ không gửi trường `head.sha` hoặc cập nhật kiểm chứng rõ ràng trong test; khi `head.sha` hợp lệ thì ghi vào `pull_requests.head_sha` ở cả `INSERT` và `ON CONFLICT DO UPDATE`).
  - Cột `head_sha` cho phép `NULL` nên migration V20 hoàn toàn additive, không phá dữ liệu cũ và tương thích rollback với image T-025.

#### 2. Thuật toán tổng hợp deterministic cho từng thẻ

1. **Tổng số commit & Commit mới nhất:**
   - Đếm `COUNT(DISTINCT c.id)` qua `git_commit_task_links` JOIN `git_commits` JOIN `repositories` (`r.workspace_id = :workspaceId`). Tuyệt đối không dùng cột mirror `git_commits.task_id`.
   - Commit mới nhất chọn theo thứ tự cố định: `c.committed_at DESC, c.created_at DESC, c.id DESC`.
   - `messagePreview`: lấy dòng đầu tiên trước `\r`/`\n`, lọc ký tự điều khiển ISO, chuẩn hóa khoảng trắng đầu/cuối, cắt tối đa 140 Unicode code points (thêm `…` nếu bị cắt).

2. **Tổng số PR & PR chính (`primaryPr`):**
   - Đếm `COUNT(DISTINCT pr.id)` và `openPrCount = COUNT(DISTINCT pr.id) FILTER (WHERE pr.status = 'OPEN')` qua `git_pull_request_task_links` JOIN `pull_requests` JOIN `repositories`.
   - PR chính chọn theo thứ tự ưu tiên tường minh:
     - Nhóm 1: `status = 'OPEN'`
     - Nhóm 2: `status = 'MERGED'`
     - Nhóm 3: `status = 'CLOSED'`
     - Tie-break trong cùng nhóm: `COALESCE(pr.provider_updated_at, pr.updated_at) DESC, pr.pr_number DESC, pr.id DESC`.

3. **Tổng hợp trạng thái CI (`ci`):**
   - **Bước 1 — Loại bỏ attempt cũ của cùng workflow run:** Trên các `ci_pipelines` khớp `(repository_id, commit_sha)` của task, gom nhóm theo `(repository_id, pipeline_id)` và chỉ giữ bản ghi có `run_attempt` lớn nhất (`ORDER BY run_attempt DESC`). Nhờ vậy, khi attempt 1 `FAILED` và attempt 2 `SUCCESS`, chỉ còn `SUCCESS`.
   - **Bước 2 — Chọn mốc SHA đại diện theo từng repository:** Với mỗi `repository_id` gắn với task, xác định SHA mới nhất **có ít nhất một bản ghi CI** theo thứ tự thời gian commit/PR giảm dần (`sha_time DESC, sha_created_at DESC, sha DESC`).
     - So sánh với SHA liên kết mới nhất tổng thể của repo đó: nếu tồn tại commit mới hơn chưa có kết quả CI nào, gán cờ `staleForLatestCommit = true` (vẫn giữ trạng thái CI gần nhất cho tới khi commit mới chạy xong, tránh xóa sớm cảnh báo lỗi chưa được chứng minh đã sửa).
     - Ngược lại, nếu commit mới nhất C2 đã có kết quả CI (`SUCCESS`), mọi lần rerun thất bại trên commit cũ C1 sẽ không được chọn vì C2 mới hơn C1.
   - **Bước 3 — Tổng hợp đa workflow (trên SHA đại diện) và đa repository (trên toàn task):** Áp dụng bảng trọng số mức độ ưu tiên tường minh (không dùng `enum.ordinal()`):
     1. `FAILED` (mức cao nhất — luôn kích hoạt cảnh báo đỏ)
     2. `TIMED_OUT`
     3. `ACTION_REQUIRED`
     4. `RUNNING`
     5. `PENDING`
     6. `CANCELLED`
     7. `SUCCESS`
     8. `NEUTRAL`, `SKIPPED`, `STALE`, `UNKNOWN`
     - Tie-break trong cùng mức độ: `COALESCE(p.provider_updated_at, p.finished_at, p.created_at) DESC, p.pipeline_id DESC, p.run_attempt DESC`.
   - **Bước 4 — `failedRunCount`:** Đếm số workflow `(repository_id, pipeline_id)` đang có trạng thái `FAILED` trên các SHA đại diện của task (≥ 1 khi `ci.status == FAILED`, = 0 trong các trường hợp khác).
   - **Giữ nguyên hành vi T-025 (`WH-04`):** `CiWorkflowIngestion` tiếp tục chỉ persist `action = completed` để toàn bộ test T-025 không bị regression; nếu bảng `ci_pipelines` có sẵn hoặc sau này nhận `RUNNING`/`PENDING`, hợp đồng đọc và UI vẫn ánh xạ chính xác.

### KT-04. Bảo mật, phân quyền, chống IDOR, Rate Limiting và Privacy

1. **Xác thực & phân quyền workspace:**
   - Mọi route `/api/v1/gitci/workspaces/**` đi qua `SecurityFilterChain` (JWT bắt buộc; thiếu/sai/hết hạn/revoked `token_version` trả `401`).
   - Bổ sung vào `backend/gitci-impl/build.gradle.kts` 3 dependency chuẩn (giống `board-impl`): `org.springframework.boot:spring-boot-starter-validation`, `org.springframework.security:spring-security-core`, `com.bucket4j:bucket4j-core:8.10.1`. Tuyệt đối không thêm dependency tới bất kỳ `-impl` module nào khác.
   - Trích `userId` từ `SecurityContextHolder` (`AuthenticatedActor` thuộc `:common`), đặt vào `MDC` (`userId`) và kiểm quyền thành viên workspace bằng `WorkspaceMembershipCheckRequestedEvent` (fail-closed: `NOT_MEMBER` → `404 WORKSPACE_NOT_FOUND` nhất quán với chính sách chống dò ID của Board; lỗi/0 hoặc >1 decision → `503 SERVICE_UNAVAILABLE`).
2. **Chống IDOR & cô lập tenant ở tầng SQL:**
   - Mọi truy vấn đọc đều bắt buộc `JOIN repositories r ON r.id = ... WHERE r.workspace_id = :workspaceId`.
   - Nếu thành viên Workspace A truyền `taskIds` thuộc Workspace B, kết quả chỉ trả về tập rỗng (giống hệt task chưa có Git), không để lộ sự tồn tại hay metadata của Workspace B.
3. **Rate limiting & giới hạn đầu vào:**
   - Tạo `GitCiReadRateLimiter` (Bucket4j per-actor token bucket, mặc định cấu hình `devflow.gitci.read.rate-limit.capacity=120`, `refill-seconds=60`, tự dọn idle buckets sau 2 chu kỳ giống `BoardRateLimiter`). Vượt ngưỡng trả RFC 7807 `429 RATE_LIMIT_EXCEEDED` kèm header `Retry-After` và property `retryAfterSeconds`.
   - Các tham số `taskIds` (>50, rỗng, trùng lặp xử lý an toàn sau khi giới hạn raw count ≤ 100 phần tử để chống query string abuse) và `limit` bị chặn chặt ở tầng controller/validator.
   - Endpoint đọc đã xác thực JWT không yêu cầu Turnstile (đúng chính sách `AGENTS.md` §4: Turnstile chỉ áp dụng cho form public chưa đăng nhập).
4. **Xử lý lỗi RFC 7807 (`ProblemDetail`):**
   - Thêm `GitCiReadControllerAdvice` (`@Order(Ordered.HIGHEST_PRECEDENCE)`, giới hạn `assignableTypes = GitCiReadController.class` để không ảnh hưởng response riêng của `GitHubWebhookController`), luôn trả `application/problem+json` với `code`, `correlationId`, `timestamp`, `instance`, tuyệt đối không rò rỉ stack trace, SQL hay tên bảng/cột nội bộ.
5. **Quyền riêng tư (Privacy & PII):**
   - DTO đọc T-026 **tuyệt đối không** trả `author_email`, `webhook_secret_ref`, `raw_log_snippet`, `raw_log_reference`, `error_summary` hay raw webhook payload.
   - Các trường văn bản hiển thị (`messagePreview`, `title`, `sourceBranch`, `repositoryName`) được loại bỏ ký tự điều khiển ở backend và render hoàn toàn bằng React text node ở frontend (cấm `dangerouslySetInnerHTML`).

### KT-05. Hiệu năng SQL & kế hoạch kiểm chứng chỉ mục (P95 < 200ms)

- Toàn bộ Endpoint 1 chạy trong `@Transactional(readOnly = true)` với `SET LOCAL statement_timeout = '3s'`, không giữ khóa ghi (`FOR UPDATE`).
- Dùng **đúng 3 truy vấn tập hợp (set-based)** cho cả lô tối đa 50 `taskIds` (tuyệt đối không có vòng lặp N+1 theo từng task):
  1. **Commits query:** lọc `l.task_id = ANY(:taskIds)` JOIN `git_commits c` JOIN `repositories r` (`r.workspace_id = :workspaceId`), dùng CTE/window function (`COUNT(*) OVER (PARTITION BY l.task_id)` + `ROW_NUMBER() OVER (PARTITION BY l.task_id ORDER BY c.committed_at DESC, c.created_at DESC, c.id DESC)`) để lấy trong 1 lượt cả tổng số commit lẫn commit mới nhất của từng task.
  2. **PRs query:** lọc `pl.task_id = ANY(:taskIds)` JOIN `pull_requests pr` JOIN `repositories r` (`r.workspace_id = :workspaceId`), dùng window function lấy tổng số PR, số PR `OPEN` và `primaryPr` theo thứ tự `CASE pr.status WHEN 'OPEN' THEN 1 WHEN 'MERGED' THEN 2 ELSE 3 END`.
  3. **CI query:** tạo CTE tập `(task_id, repository_id, sha, sha_time, sha_created_at)` từ hai bảng link V16 (`git_commit_task_links` + `git_pull_request_task_links` có `head_sha`), JOIN `ci_pipelines p` theo `(p.repository_id, p.commit_sha)`, rút gọn theo `DISTINCT ON (p.repository_id, p.pipeline_id) ... ORDER BY p.run_attempt DESC`, sau đó tổng hợp trong Java (hoặc SQL window) theo thuật toán KT-03 trên tập kết quả đã rất nhỏ (chỉ gồm các run mới nhất của tối đa 50 task).
- **Chỉ mục đã kiểm tra:**
  - `idx_git_commit_links_task(task_id)` (V16) — có sẵn.
  - `idx_git_pr_links_task(task_id)` (V16) — có sẵn.
  - `idx_ci_pipelines_repo_commit(repository_id, commit_sha)` (V6) — có sẵn.
  - `idx_pull_requests_repo_head_sha(repository_id, head_sha) WHERE head_sha IS NOT NULL` — bổ sung ở V20.
- Yêu cầu kiểm chứng bằng test `EXPLAIN (ANALYZE, BUFFERS)` trên PostgreSQL 17 Testcontainers với tối thiểu 100 tasks, 1.000 commits, 200 PRs, 500 pipelines thuộc 2 workspaces để xác nhận sử dụng Index Scan/Bitmap Index Scan và thời gian thực thi P95 < 200ms.

### KT-06. Đồng bộ Realtime khi có Git/CI mới

#### 1. Hợp đồng sự kiện mới trong `:common`
Thêm record `TaskGitActivityChangedEvent` trong `io.devflow.common.event` (`implements DevFlowEvent`, hằng số `EventTypes.TASK_GIT_ACTIVITY_CHANGED = "git.task_activity_changed"`):
- Trường: `UUID eventId`, `UUID workspaceId`, `UUID repositoryId`, `Set<UUID> taskIds` (immutable, 1..100 phần tử), `ActivityKind kind` (`COMMIT_LINKED`, `PR_UPDATED`, `CI_UPDATED`), `Instant occurredAt`.
- Không mang PII, nội dung commit/PR, URL hay log.

#### 2. Phát sự kiện sau commit trong `gitci-impl`
- Trong `GitWebhookIngestion.persist(...)`: thu thập các `taskId` có liên kết commit mới, hoặc thuộc PR vừa thêm mới / cập nhật (bao gồm cả tập task bị gỡ khỏi PR khi sửa title, để UI cập nhật giảm badge!). Nếu tập khác rỗng, đăng ký `TransactionSynchronization.afterCommit()` phát `TaskGitActivityChangedEvent` (chia batch ≤ 100 nếu cần; bắt `RuntimeException` ghi log/metric để lỗi broadcast best-effort không bao giờ ảnh hưởng HTTP 204 của webhook).
- Trong `CiWorkflowIngestion.persist(...)`: khi insert hoặc update trạng thái `ci_pipelines` thành công, truy vấn nhanh các `task_id` liên kết tới `(repository_id, attempt.sha())` qua `git_commit_task_links` và `git_pull_request_task_links` (giới hạn 100); nếu có task liên quan, đăng ký `afterCommit()` phát `TaskGitActivityChangedEvent(kind = CI_UPDATED)`.
- Vì phát ở `afterCommit()`, khi frontend nhận STOMP và gọi ngay API đọc Git/CI, dữ liệu mới đã chắc chắn commit vào PostgreSQL.

#### 3. Broadcast STOMP trong `board-impl`
- Thêm `BoardGitActivityRealtimeListener` trong `board-impl`:
  - Lắng nghe `@EventListener` đồng bộ `TaskGitActivityChangedEvent` (bọc try/catch an toàn để không cản trở luồng caller).
  - Kiểm tra metadata hợp lệ; chạy một truy vấn read-only (không khóa Board!) lấy `(t.id, t.column_id, c.board_id)` từ `tasks t JOIN columns c JOIN boards b` với điều kiện `t.id IN (:taskIds) AND b.workspace_id = :workspaceId` (chống rò rỉ chéo workspace).
  - Gom theo `boardId`: nếu 1 task trong 1 board → gửi `BoardMutationMessage(v1, CARD_UPDATED, taskId, columnId, affectedColumnIds=[columnId], changedFields=["gitActivity"], refresh=[TASK_LISTS, TASK_DETAIL], actorId=null)`; nếu nhiều task trong cùng board → gửi 1 message cho mỗi `boardId` với `taskId = null`, `affectedColumnIds = distinct columnIds`, `changedFields = ["gitActivity"]`, `refresh = [TASK_LISTS, TASK_DETAIL]` để tránh bão frame STOMP.
  - Dùng `BoardRealtimeBroadcaster.broadcast(...)` hiện có (giữ nguyên `BoardOutboundAuthorizationInterceptor`, kiểm quyền subscribe/delivery, timeout 100ms và metrics `devflow.board.realtime.publications`).
- **Tương thích ngược schema v1:** Không thay đổi `BoardMutationType` hay `BoardRefreshTarget`; `changedFields = ["gitActivity"]` khớp hoàn toàn regex validator `/^[A-Za-z][A-Za-z0-9_]{0,39}$/` đang chạy trong `boardMutation.ts`.

#### 4. Cơ chế xử lý tinh gọn ở Frontend (`BoardPage.tsx`)
- Khi `handleBoardMutation` nhận `CARD_UPDATED` mà `changedFields.length > 0 && changedFields.every(f => f === 'gitActivity')`:
  - **Không** đánh dấu `structural = true` → không làm gián đoạn thao tác kéo thả đang diễn ra và không kích hoạt `syncBlocked`.
  - **Không** gọi lại `GET /columns/{id}/tasks` (vì tên/cột/vị trí task không đổi).
  - Đưa `message.data.taskId` (hoặc toàn bộ taskId đang tải của các `affectedColumnIds`) vào hàng đợi làm mới Git/CI (debounce 150ms) và tăng `gitActivityRefreshVersion` nếu `selectedTaskId` bị ảnh hưởng.
- Khi có `CARD_MOVED` (ví dụ T-024 tự chuyển cột), full reconcile, reconnect STOMP, tab focus/visible, nút "Thử đồng bộ", định kỳ 30–35s, hoặc bấm "Tải tiếp": cùng điều phối làm mới Git/CI summaries cho các taskId đang hiển thị. Nhờ vậy, ngay cả khi một frame STOMP bị rơi (giới hạn best-effort của simple broker), UI vẫn tự hội tụ.

### KT-07. Kiến trúc Frontend, tách `TaskCard.tsx`, UI/UX và Accessibility

#### 1. Cấu trúc tệp dự kiến trong `frontend/src/`

| Tệp | Trách nhiệm |
|---|---|
| `types/gitci.ts` | Kiểu TypeScript cho `CiStatus`, `PullRequestStatus`, `TaskGitCommitSummary`, `TaskGitPrSummary`, `TaskGitCiSummary`, `TaskGitSummary`, `TaskGitSummaryBatchResponse`, `TaskGitActivityResponse` và hàm validator runtime (`parseTaskGitSummaryBatch`, `parseTaskGitActivity`, `isSafeGitHubUrl`). |
| `api/gitciApi.ts` | Wrapper gọi `GET /api/v1/gitci/workspaces/{workspaceId}/task-summaries` (tự động chia chunk ≤ 50 UUIDs, chạy song song có giới hạn concurrency 3, hỗ trợ `AbortSignal`) và `GET /api/v1/gitci/workspaces/{workspaceId}/tasks/{taskId}/activity`. |
| `hooks/useBoardGitSummaries.ts` | Quản lý `Map<taskId, TaskGitSummary>`, trạng thái `loading`/`error`/`stale`, thế hệ request (`boardId`, `workspaceId`, `sessionGeneration`), hủy request cũ khi đổi ngữ cảnh/logout, hỗ trợ `refreshAll(taskIds)`, `refreshSubset(taskIds)` (cho realtime & "Tải tiếp"), và tự xóa cache khi `401`/`403`/`404` hoặc đổi workspace. |
| `components/board/TaskCard.tsx` | Tách từ `BoardColumnView.tsx` (giữ nguyên toàn bộ hành vi DnD, priority stripe, assignee, dueDate, quick move menu) + nhận prop tùy chọn `gitSummary?: TaskGitSummary` và render `<TaskGitBadges />`. |
| `components/board/TaskGitBadges.tsx` | Hiển thị dải huy hiệu Commit / PR / CI nhỏ gọn, xử lý định dạng nhãn, tooltip, màu sắc đạt tương phản WCAG, validate URL an toàn trước khi render `<a>`, chặn sự kiện lan truyền để không kích hoạt drag/modal. |
| `components/board/TaskGitActivitySection.tsx` | Phần read-only trong `TaskDetailModal.tsx` hiển thị danh sách commit, PR và lịch sử CI gần nhất của task (có loading/empty/error/retry riêng, tự làm mới khi `gitActivityRefreshVersion` tăng, không làm ảnh hưởng dirty draft). |
| `components/common/Icons.tsx` | Bổ sung `GitCommitIcon`, `GitPullRequestIcon`, `GitMergeIcon`, `XCircleIcon`, `ExternalLinkIcon` đồng bộ kích thước/stroke hiện hành. |
| `components/board/BoardColumnView.tsx` | Import `TaskCard` từ `./TaskCard`, truyền `gitSummariesByTaskId?.get(task.id)`. |
| `pages/BoardPage.tsx` | Gắn `useBoardGitSummaries`, nối luồng `gitActivity` STOMP / full reconcile / "Tải tiếp", hiển thị thanh thông báo lỗi nhẹ không chặn thao tác nếu tải Git/CI thất bại. |

#### 2. Quy tắc hiển thị & tương tác trên `TaskCard.tsx`

- **Điều kiện hiển thị:** Chỉ render `<TaskGitBadges />` khi `gitSummary` tồn tại và `(commitCount > 0 || prCount > 0 || ci !== null)`. Khi không có dữ liệu, không tạo thêm phần tử DOM rỗng.
- **Thứ tự và tạo hình huy hiệu (`flex flex-wrap items-center gap-1.5 mt-2.5`):**
  1. **Commit badge** (khi `commitCount > 0`):
     - Icon `GitCommitIcon` + `<span className="tabular-nums">{commitCount}</span>`.
     - Màu trung tính gọn gàng (`bg-slate-100 text-slate-700 ring-slate-200/90 hover:bg-indigo-50 hover:text-indigo-700 hover:ring-indigo-200`).
     - Nếu `isSafeGitHubUrl(latestCommit?.url)` → `<a href={latestCommit.url} target="_blank" rel="noopener noreferrer">`; ngược lại `<span>`.
     - `aria-label`: `"{commitCount} commit liên kết — mở commit mới nhất {shortSha} trên GitHub trong tab mới"`.
     - `title`: `"{shortSha}: {messagePreview} ({repositoryName})"` (thêm ghi chú nếu `commitCount > 1`).
  2. **PR badge** (khi `prCount > 0` và có `primaryPr`):
     - Icon `GitMergeIcon` (khi `MERGED`) hoặc `GitPullRequestIcon` (`OPEN`/`CLOSED`) + `#{primaryPr.prNumber}` + (nếu `prCount > 1`: `+{prCount - 1}`).
     - Tông màu theo trạng thái:
       - `OPEN`: `bg-emerald-50 text-emerald-700 ring-emerald-200/90 hover:bg-emerald-100/80`
       - `MERGED`: `bg-violet-50 text-violet-700 ring-violet-200/90 hover:bg-violet-100/80`
       - `CLOSED`: `bg-slate-100 text-slate-600 ring-slate-200/90 hover:bg-slate-200/70`
     - `aria-label`: `"Pull Request #{prNumber} ({trạng thái tiếng Việt}) trên GitHub — mở trong tab mới"` (kèm tổng số PR nếu > 1).
     - `title`: `"PR #{prNumber} ({trạng thái}): {title} ({repositoryName})"`.
  3. **CI status badge** (khi `ci !== null`):
     - `FAILED`: nền đỏ nổi bật `bg-rose-50 text-rose-700 ring-rose-300/90 font-bold hover:bg-rose-100/80`, icon `AlertTriangleIcon` + chữ `CI lỗi` (hoặc `CI lỗi (2)` nếu `failedRunCount > 1`).
     - `SUCCESS`: `bg-emerald-50 text-emerald-700 ring-emerald-200/80`, icon `CheckCircleIcon` + chữ `CI đạt`.
     - `RUNNING` / `PENDING`: `bg-sky-50 text-sky-700 ring-sky-200/90`, icon `LoadingSpinner` + chữ `CI đang chạy`.
     - `TIMED_OUT` / `ACTION_REQUIRED`: `bg-amber-50 text-amber-800 ring-amber-200/90`, icon `AlertCircleIcon` + chữ `CI quá hạn` / `CI cần xử lý`.
     - `CANCELLED` / `SKIPPED` / `NEUTRAL` / `STALE` / `UNKNOWN`: `bg-slate-100 text-slate-600 ring-slate-200/90`, icon `InfoIcon` + nhãn ngắn tương ứng (`CI đã hủy`, `CI bỏ qua`, `CI khác`).
     - Nếu `isSafeGitHubUrl(ci.url)` → render `<a href={ci.url} target="_blank" rel="noopener noreferrer">`; ngược lại render `<span>`.
     - Nếu `ci.staleForLatestCommit === true`, bổ sung giải thích trong `title` ("Kết quả của commit trước; commit mới nhất chưa có kết quả CI").
- **Phòng chống xung đột sự kiện & Accessibility:**
  - Trên mỗi link badge gắn `onClick={(e) => e.stopPropagation()}` và `onPointerDown={(e) => e.stopPropagation()}` để không kích hoạt drag hoặc click card.
  - Kích thước vùng chạm tối thiểu `min-h-6` với padding thoáng (`px-2 py-0.5`), hiển thị rõ `focus-visible:ring-2 focus-visible:ring-indigo-500`.
  - Mọi link đều có `target="_blank" rel="noopener noreferrer"` (chống `window.opener` tabnabbing và không gửi `Referer`).
  - Validator client `isSafeGitHubUrl(raw)`: parse bằng `new URL(raw)`, chỉ cho phép `protocol === 'https:'`, `hostname === 'github.com'`, `username === ''`, `password === ''`, `port === ''`, và đường dẫn bắt đầu bằng `/<owner>/<repo>/(commit|pull|actions/runs)/`.

### KT-08. Observability, Logging và Chiến lược Rollout / Rollback

- **Metrics Micrometer (tag cardinality thấp — cấm dùng UUID/SHA/URL làm tag):**
  - `devflow.gitci.read.requests` — tags: `endpoint` (`task_summaries` | `task_activity`), `outcome` (`success` | `denied` | `invalid` | `rate_limited` | `error`), `status` (`200` | `400` | `404` | `429` | `503` | `500`).
  - `devflow.gitci.read.duration` — timer theo `endpoint`.
  - `devflow.gitci.read.batch.size` — distribution summary số `taskIds` trong mỗi request batch.
  - `devflow.gitci.activity.events` — counter theo `kind` (`COMMIT_LINKED` | `PR_UPDATED` | `CI_UPDATED`), `outcome` (`published` | `error` | `broadcast` | `skipped`).
- **Structured Logging:** Ghi log JSON với `correlationId` và `userId` từ MDC, ghi `endpoint`, `batchSize`, `outcome`, `durationMs` — tuyệt đối không log JWT, commit message, PR title, author email hay danh sách raw URL.
- **Rollout & Rollback an toàn:**
  1. Triển khai migration **V20** (additive nullable `pull_requests.head_sha`) trước.
  2. Triển khai backend (`common` + `gitci-impl` + `board-impl`). Client frontend cũ vẫn hoạt động bình thường vì schema STOMP giữ nguyên v1 và `TaskListItem` không đổi.
  3. Triển khai frontend mới (`TaskCard.tsx`, `useBoardGitSummaries`, `TaskDetailModal`).
  4. **Rollback:** Nếu cần hạ frontend, backend vẫn hoàn toàn ổn định; nếu cần hạ backend về bản T-025, V20 không phá bảng cũ và frontend tự degrade an toàn (Board vẫn hiển thị, kéo thả và sửa task bình thường khi endpoint GitCI trả 404/503).

---

## 4. Thứ tự triển khai và các mốc kiểm soát

| Bước | Công việc cụ thể | Điều kiện ra bước (Exit Gate) |
|---|---|---|
| **P0 — Baseline & Branch** | Kiểm tra `develop` sạch, tách nhánh `feature/T-026-git-badges-on-cards`, xác nhận `./gradlew check` và `frontend/ npm test` hiện đang xanh. | Ghi nhận commit baseline; môi trường build/test sẵn sàng. |
| **P1 — Migration V20 & `head_sha`** | Tạo `V20__pull_request_head_sha.sql`, cập nhật `GitWebhookIngestion` lưu `pull_request.head.sha` có validation; viết test migration V19 → V20 & DB trống trên PostgreSQL 17. | Migration V20 chạy sạch, không sửa V1–V19, toàn bộ test T-020..T-025 vẫn PASS. |
| **P2 — GitCI Read API & Aggregation** | Bổ sung dependencies chuẩn cho `gitci-impl`, xây dựng `GitCiReadController`, `GitCiReadService`, `GitHubUrlBuilder`, `GitCiReadRateLimiter`, `GitCiReadControllerAdvice`, metrics. | Đầy đủ unit test (tổng hợp PR/CI, URL builder, sanitizer) + integration test PostgreSQL 17 (IDOR, auth, 400/404/429, `EXPLAIN ANALYZE` P95 < 200ms). |
| **P3 — Realtime Invalidation** | Thêm `TaskGitActivityChangedEvent` trong `:common`, phát `afterCommit` ở `GitWebhookIngestion` & `CiWorkflowIngestion`, thêm `BoardGitActivityRealtimeListener` trong `board-impl`. | Integration test xác nhận commit thứ 2 (`NOOP`), PR closed, `auto-transition=false`, CI fail/pass đều phát STOMP `CARD_UPDATED` (`gitActivity`) đúng tenant; `:verifyModuleBoundaries` PASS. |
| **P4 — Frontend Data & Sync** | Tạo `types/gitci.ts` (kèm runtime parser + `isSafeGitHubUrl`), `api/gitciApi.ts` (chunk ≤ 50, concurrency 3, abort), `hooks/useBoardGitSummaries.ts`, nối vào `BoardPage.tsx` (initial load, `gitActivity` STOMP không hủy drag, full reconcile, "Tải tiếp", degraded mode). | Unit test cho parser, chunking, stale-response guard, error/degraded mode, logout cleanup PASS. |
| **P5 — UI `TaskCard.tsx` & Modal** | Tách `TaskCard.tsx` từ `BoardColumnView.tsx`, tạo `TaskGitBadges.tsx`, bổ sung icon trong `Icons.tsx`, thêm `TaskGitActivitySection.tsx` read-only trong `TaskDetailModal.tsx`. | Component test Vitest + jsdom PASS đầy đủ badge/link/click-isolation/accessibility/modal; `npm run build` và `npm run lint` 0 error/0 warning. |
| **P6 — Kiểm thử E2E, Hardening & PR** | Chạy toàn bộ ma trận test mục 6 (bao gồm E2E thật qua Vite và Docker Nginx với webhook GitHub ký HMAC), điền kết quả thực tế + bằng chứng, hoàn thiện PR theo Conventional Commits và cập nhật Trello khi sẵn sàng review. | Toàn bộ AC đạt tiêu chí nghiệm thu, không còn lỗi P0/P1; `./gradlew check`, `:verifyModuleBoundaries`, `npm run build`, `npm run lint`, `npm test` đều PASS. |

---

## 5. Tiêu chí hoàn thành (Acceptance Criteria)

Hai tiêu chí gốc trên Trello (`AC-01`, `AC-02`) và các tiêu chí chất lượng production bắt buộc phải có bằng chứng kiểm chứng cụ thể trước khi chuyển sang In Review / Done:

- [x] **AC-01 (Trello gốc) — Hiển thị trực quan thông tin Git/CI trên thẻ:** `TaskCard.tsx` hiển thị rõ ràng số lượng commit liên kết, biểu tượng + số PR theo màu trạng thái (`OPEN`/`MERGED`/`CLOSED` + bộ đếm `+N` khi nhiều PR), và cảnh báo đỏ nổi bật (`CI lỗi`) khi CI liên quan thất bại. *(PASS: `TaskCard.test.tsx`, `BoardPage.gitci.test.tsx` và E2E trình duyệt thật trên `Sprint T-026 Live`: Commit `2`, PR `#42` xanh lá → tím `Đã merge`, `CI lỗi` đỏ → `CI đạt` xanh lá; `T-002`/`T-003` rỗng 0 badge)*
- [x] **AC-02 (Trello gốc) — Mở tab mới đúng commit/PR/CI trên GitHub:** Click vào huy hiệu commit, PR hoặc CI mở tab mới (`target="_blank" rel="noopener noreferrer"`) đi đúng tới commit, Pull Request hoặc attempt GitHub Actions tương ứng; bấm huy hiệu không kích hoạt drag hay mở nhầm modal. *(PASS: `TaskCard.test.tsx` + `TaskGitActivitySection.test.tsx` + DOM thực tế trên trình duyệt xác nhận đủ `href`, `target="_blank"`, `rel="noopener noreferrer"`, tách khỏi `<button>` tiêu đề và `stopPropagation` gọi `onOpenTask` 0 lần)*
- [x] **AC-03 — Chính xác dữ liệu & không gắn cảnh báo sai:** Số commit, PR chính và trạng thái CI khớp tuyệt đối dữ liệu backend (từ bảng link V16 và `head_sha` V20); task chưa có Git/CI không hiện badge hay cảnh báo giả; rerun thành công hoặc commit mới build xanh tự động xóa cảnh báo đỏ; không nối nhầm theo tên branch. *(PASS: `GitHubUrlBuilderAndAggregatorTest` 30/30, `GitCiReadApiAndRealtimeIntegrationTest` 7/7, `TaskCard.test.tsx` 4/4)*
- [x] **AC-04 — Cập nhật tự động khi có Git/CI mới:** Commit mới (kể cả không chuyển cột hoặc khi `auto-transition-enabled=false`), thay đổi PR và kết quả CI mới cập nhật lên thẻ qua STOMP invalidation (`CARD_UPDATED` `gitActivity`) mà không hủy thao tác drag đang diễn ra, đồng thời tự hội tụ qua reconnect/focus/tải lại/phân trang "Tải tiếp". *(PASS: backend STOMP integration test + `BoardPage.gitci.test.tsx` + E2E thật 6 webhook HMAC cập nhật live không F5; đã bổ sung đồng bộ `gitActivityRefreshVersion` khi nhận `CARD_MOVED` từ Git transition)*
- [x] **AC-05 — Kiến trúc & ranh giới module:** Zero `impl -> impl` import, zero truy vấn DB chéo module; liên kết realtime đi qua `DevFlowEvent` trong `:common`; `./gradlew :verifyModuleBoundaries` PASS 0 vi phạm. *(PASS ngày 27/11/2026)*
- [x] **AC-06 — Bảo mật, phân quyền, chống IDOR & Privacy:** Read API yêu cầu JWT + kiểm membership workspace qua event Auth, cô lập tenant tại SQL (`r.workspace_id`), áp dụng rate limit (`429`), validation (`400`), RFC 7807 `ProblemDetail` không lộ stack trace; zero rò rỉ `author_email`/`raw_log_snippet`/`webhook_secret_ref`; URL và văn bản chống XSS/scheme độc hại cả backend lẫn frontend. *(PASS: `GitCiReadApiAndRealtimeIntegrationTest`, `GitCiReadServiceUnitTest`, `gitci.test.ts`, `TaskCard.test.tsx`)*
- [x] **AC-07 — Hiệu năng & Degraded Mode:** Batch API không có N+1 query, `EXPLAIN ANALYZE` trên PostgreSQL 17 đạt P95 < 200ms; khi GitCI API lỗi/chậm/429/offline, Board vẫn tải, lọc, kéo thả và sửa task bình thường. *(PASS: CI query 9,49 ms, batch 50 task P50 = 45,41 ms, P95 = 57,51 ms, max = 62,86 ms; Prometheus live max 44 ms; `BoardPage.gitci.test.tsx` & `useBoardGitSummaries.test.tsx`)*
- [x] **AC-08 — Trải nghiệm chi tiết, Accessibility & Responsive:** Mục read-only "Hoạt động Git & CI" trong `TaskDetailModal` cho phép xem và mở từng commit/PR/CI mà không ảnh hưởng bản nháp/`If-Match`; bàn phím, screen reader (`aria-label`, nhãn chữ kèm màu), màn hình 360px và zoom 200% hiển thị sạch không tràn. *(PASS: `TaskGitActivitySection.test.tsx` + E2E trình duyệt thật: modal giữ nguyên bản nháp khi CI đổi live, `Tab` hiện vòng focus indigo trên cả 3 badge, mobile `360x800` `body=344,8px` không tràn/cắt)*
- [x] **AC-09 — Migration & vận hành:** Migration V20 additive không sửa migration cũ, giữ nguyên toàn bộ test hồi quy T-020..T-025 (bao gồm `WH-04`), có Micrometer metrics với tag cardinality thấp và structured logging không chứa PII/secret. *(PASS: `PullRequestHeadShaMigrationTest` 2/2 + `./gradlew check` 114 suites / 1.012 tests, 0 failed + scrape thật `/actuator/prometheus`)*
- [ ] **AC-10 — Quality Gates & Hồ sơ nghiệm thu:** `./gradlew check`, `./gradlew :verifyModuleBoundaries`, `npm run build`, `npm run lint`, `npm test` PASS 100%; các case bắt buộc trong mục 6 được thực thi và cập nhật cột `Thực tế` kèm bằng chứng cụ thể; PR được review trước khi merge và chuyển Done. *(Toàn bộ quality gates & smoke E2E Vite PASS; còn bước review PR trước khi merge vào `develop` và chuyển Done)*

---

## 6. Kế hoạch testing — Expected và Thực tế

### 6.1. Chiến lược, mức kiểm thử và quy ước ghi kết quả

- **Mức kiểm thử:**
  - **U (Unit):** JUnit 5 (backend: `GitHubUrlBuilder`, thuật toán tổng hợp PR/CI, message preview sanitizer) và Vitest (frontend: `parseTaskGitSummaryBatch`, `isSafeGitHubUrl`, chunking `gitciApi`, `useBoardGitSummaries`).
  - **C (Component):** Vitest + jsdom kiểm chứng `TaskCard.tsx`, `TaskGitBadges.tsx`, `BoardColumnView.tsx`, `TaskGitActivitySection.tsx`, `TaskDetailModal.tsx` và tích hợp `BoardPage.tsx`.
  - **I (Integration):** Spring Boot + PostgreSQL 17 Testcontainers + Flyway đến V20, gọi HTTP thật qua `SecurityFilterChain`, kiểm tra SQL, `EXPLAIN ANALYZE`, STOMP broadcaster và `TaskReferencesRemovedEvent`/`WorkspaceDeletedEvent`. Không dùng H2 thay thế PostgreSQL.
  - **E (End-to-End):** Backend + PostgreSQL + Frontend chạy qua Vite dev server và Docker Nginx, gửi webhook GitHub (`push`, `pull_request`, `workflow_run`) ký HMAC thật và kiểm tra trực tiếp trên trình duyệt.
  - **M (Manual / Accessibility / Visual):** Kiểm tra bàn phím, screen reader labels, tương phản màu, viewport 360px, zoom 200%, và kiểm chứng mở tab mới trên trình duyệt thật.
  - **O (Operational / Resilience):** Fault injection (503/timeout/429/mất mạng/JWT hết hạn), rollback V20, kiểm tra `/actuator/prometheus` và log JSON.
- **Quy ước cột `Thực tế`:**
  - `CHƯA CHẠY`: chưa thực thi cho T-026 (trạng thái hiện tại ở bước lập plan).
  - Khi chạy xong thay bằng `PASS`, `FAIL`, `BLOCKED` hoặc `PARTIAL` kèm ngày chạy, commit SHA, môi trường, lệnh/tên test và đường dẫn bằng chứng.
  - Mỗi biến thể ngăn cách bằng dấu `;` trong một dòng phải có test/parameter riêng; chỉ đánh dấu `PASS` khi **mọi biến thể** trong dòng đều đạt.

### 6.2. Fixture dữ liệu và Oracle nghiệm thu dùng chung

- **Tenant & Repository:**
  - `WS-A` (thành viên `User-A1`, `User-A2`) có 2 repository đã kết nối: `repo-fe` (`https://github.com/devflow/web`) và `repo-be` (`https://github.com/devflow/api.git`).
  - `WS-B` (thành viên `User-B1`, không thuộc `WS-A`) có `repo-b` (`https://github.com/other/repo`) và task `T-001` riêng để kiểm thử chống IDOR.
  - `User-Revoked`: từng thuộc `WS-A`, bị gỡ khỏi workspace trong lúc đang mở board.
- **Các nhóm Task kiểm thử trong `WS-A`:**
  - `Task-Empty`: 0 commit, 0 PR, 0 CI.
  - `Task-SingleCommit`: 1 commit liên kết, 0 PR, 0 CI.
  - `Task-MultiCommit`: 5 commits (thời gian khác nhau, có cả commit gắn đồng thời với `Task-Shared`).
  - `Task-PrStates`: tập 3 task có PR `OPEN`, `MERGED`, `CLOSED`, và `Task-MultiPr` có đồng thời 1 PR `CLOSED` (mới hơn), 1 PR `MERGED`, 1 PR `OPEN` (cũ hơn) để kiểm chứng thứ tự ưu tiên `OPEN > MERGED > CLOSED`.
  - `Task-CiFail`: commit mới nhất có workflow `Tests` `FAILED` attempt 1.
  - `Task-CiRerunPass`: cùng run, attempt 1 `FAILED` → attempt 2 `SUCCESS`.
  - `Task-CiMultiWorkflow`: trên cùng commit mới nhất có `Lint` = `SUCCESS` và `Tests` = `FAILED`.
  - `Task-CiFixedByNewCommit`: commit C1 (10:00) `FAILED` → commit C2 (10:15) `SUCCESS` (+ biến thể rerun C1 lúc 10:20 bị `FAILED`).
  - `Task-CiPendingNewCommit`: commit C1 (10:00) `FAILED` → commit C2 (10:15) chưa có CI run nào.
  - `Task-CiPrHeadOnly`: 0 commit gắn mã task, nhưng có PR gắn `T-XXX` qua title với `head_sha = S1` và `ci_pipelines` ghi `FAILED` cho `S1`.
  - `Task-CiMultiRepo`: `repo-fe` CI `SUCCESS`, `repo-be` CI `FAILED`.
  - `Task-CiOtherStatuses`: các kết quả `TIMED_OUT`, `ACTION_REQUIRED`, `CANCELLED`, `SKIPPED`, `NEUTRAL`, `STALE`, `UNKNOWN`, `RUNNING`, `PENDING`.
  - `Task-Malicious`: commit message chứa `<script>alert(1)</script>`, ký tự điều khiển `\u0000`/`\u0007`, tiếng Việt + emoji dài > 200 ký tự; dữ liệu DB test giả lập `pr_url` không hợp lệ (`javascript:alert(1)`, `http://evil.example/pull/1`).
- **Oracle nghiệm thu:**
  - API trả đúng số đếm, đúng primary PR, đúng trạng thái CI, đúng URL HTTPS `github.com`, tuyệt đối không có `author_email` hay `raw_log_snippet`.
  - DOM `TaskCard` chỉ render dải badge khi có dữ liệu; mỗi link có `href` khớp API, `target="_blank"`, `rel="noopener noreferrer"`; bấm link không gọi `onOpenTask` và không bắt đầu drag.

### 6.3. Bộ 1 — Backend Read API, Tổng hợp dữ liệu & Migration V20 (`API-01` .. `API-14`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| API-01 | I | Migrate V1 → V20 trên DB trống; migrate V19 → V20 trên DB đã có sẵn `pull_requests` legacy | Flyway chạy sạch không lỗi; dòng PR cũ có `head_sha = NULL`; CHECK từ chối `head_sha` sai định dạng/toàn zero; partial index tồn tại | PASS (27/11/2026) — `PullRequestHeadShaMigrationTest` (2/2 PASS trên PostgreSQL 17 Testcontainers): V1→V20 & V19→V20 sạch, legacy `NULL`, CHECK SQLState `23514`, index `idx_pull_requests_repo_head_sha` tồn tại |
| API-02 | I | Gửi webhook `pull_request` (`opened`, `synchronize`, `closed` merged) có `head.sha` hợp lệ và sai định dạng | `head_sha` hợp lệ được lưu & cập nhật khi `synchronize`; `head.sha` sai định dạng/toàn zero bị từ chối `400` theo chuẩn webhook | PARTIAL (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest` xác minh lưu `head_sha` hợp lệ khi `opened`/`closed` & DB CHECK từ chối sai định dạng; chưa tách case HTTP riêng cho `synchronize` và `head.sha` lỗi ở webhook |
| API-03 | I | Gọi batch `GET /task-summaries` với hỗn hợp `Task-Empty`, `Task-SingleCommit`, `Task-MultiCommit`, `Task-Shared` | `Task-Empty` không có trong `items`; `Task-MultiCommit` trả `commitCount = 5` và đúng `latestCommit` theo `committed_at DESC`; cả hai task trong commit multi-task đều có `commitCount >= 1` và đúng URL `/commit/<sha>` | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.aggregatesCommitsPrsCiRerunsHeadShaAndActivityEndpointsAccurately` + dataset 10 commit/task: `task3` rỗng bị bỏ qua, multi-task V16 & ordering đúng |
| API-04 | U/I | `Task-PrStates` (`OPEN`, `MERGED`, `CLOSED`) và `Task-MultiPr` (nhiều PR khác trạng thái & nhiều PR cùng trạng thái) | `primaryPr` tuân thủ tuyệt đối `OPEN > MERGED > CLOSED` rồi `updated_at DESC, pr_number DESC`; `prCount` và `openPrCount` chính xác; URL dạng `https://github.com/<owner>/<repo>/pull/<number>` | PASS (27/11/2026) — `GitHubUrlBuilderAndAggregatorTest.explicitCiSeverityAndPrPriorityAreDeterministic` & `GitCiReadApiAndRealtimeIntegrationTest.aggregatesCommitsPrsCiRerunsHeadShaAndActivityEndpointsAccurately` (PR #12 `OPEN` cũ hơn thắng #11 `MERGED` và #10 `CLOSED`) |
| API-05 | I | `Task-CiFail` (1 workflow `FAILED`) và `Task-CiRerunPass` (attempt 1 `FAILED`, attempt 2 `SUCCESS` cùng `pipeline_id`) | `Task-CiFail` trả `ci.status = FAILED`, `failedRunCount = 1`, URL trỏ `/actions/runs/<runId>/attempts/1`; `Task-CiRerunPass` trả `ci.status = SUCCESS`, `failedRunCount = 0`, URL trỏ `/attempts/2` | PASS (27/11/2026) — `GitHubUrlBuilderAndAggregatorTest.rerunAttemptTwoSuccessSupersedesAttemptOneFailure` & `GitCiReadApiAndRealtimeIntegrationTest.aggregatesCommitsPrsCiRerunsHeadShaAndActivityEndpointsAccurately` (run 501 & 601 attempt 2 `SUCCESS` xóa `FAILED`) |
| API-06 | U/I | `Task-CiMultiWorkflow` (cùng commit: `Lint = SUCCESS`, `Tests = FAILED`; và ngược lại cả 2 `FAILED`) | Trả `ci.status = FAILED`, `failedRunCount = 1` (hoặc `2`), `ci.url` trỏ đúng workflow `FAILED` | PASS (27/11/2026) — `GitHubUrlBuilderAndAggregatorTest.multiWorkflowOnSameCommitFlagsFailureWithExactFailedRunCount` (`failedRunCount = 1` và `2`, đúng `runId` lỗi) |
| API-07 | U/I | `Task-CiFixedByNewCommit` (C1 `FAILED` → C2 `SUCCESS` → rerun C1 `FAILED`) | Giữ nguyên `ci.status = SUCCESS` theo commit mới nhất C2, `staleForLatestCommit = false`; rerun lỗi của C1 không làm hồi sinh cảnh báo đỏ | PASS (27/11/2026) — `GitHubUrlBuilderAndAggregatorTest.newerGreenCommitWinsOverOlderFailedCommitEvenAfterOldRerun` & `GitCiReadApiAndRealtimeIntegrationTest.handlesCiBeforePushNoBranchLeakageStaleCommitAndNewCommitFixOverOldRerun` |
| API-08 | U/I | `Task-CiPendingNewCommit` (C1 `FAILED` → C2 chưa có CI) | Vẫn trả `ci.status = FAILED` (theo C1) kèm `staleForLatestCommit = true` để không xóa sớm lỗi chưa được xác nhận đã sửa | PASS (27/11/2026) — `GitHubUrlBuilderAndAggregatorTest.preservesFailureWithStaleFlagWhenNewerCommitHasNoCiYet` & `GitCiReadApiAndRealtimeIntegrationTest.handlesCiBeforePushNoBranchLeakageStaleCommitAndNewCommitFixOverOldRerun` |
| API-09 | I | `Task-CiPrHeadOnly` (0 commit link, có PR link với `head_sha = S1`, `ci_pipelines` cho `S1` = `FAILED`); và trường hợp CI webhook đến trước push webhook | `Task-CiPrHeadOnly` trả `commitCount = 0`, `prCount = 1`, `ci.status = FAILED` đúng `S1`; khi CI đến trước push rồi push đến sau, API tự nối trả đúng CI | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest` xác minh cả PR-head-only (`shaPrOnly`, run 701) lẫn CI-before-push (`shaBeforePush`, run 901) |
| API-10 | I | Hai task khác nhau từng dùng chung tên branch `feature/reuse`, chỉ commit của Task 2 có CI `FAILED` | Chỉ Task 2 có `ci.status = FAILED`; Task 1 không bị gắn nhầm cảnh báo CI theo tên branch | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.handlesCiBeforePushNoBranchLeakageStaleCommitAndNewCommitFixOverOldRerun` (T-001 giữ `SUCCESS`, T-002 nhận `FAILED`) |
| API-11 | U/I | `Task-CiMultiRepo` (`repo-fe` `SUCCESS`, `repo-be` `FAILED`) và mọi giá trị `Task-CiOtherStatuses` (`TIMED_OUT`, `ACTION_REQUIRED`, `RUNNING`, `PENDING`, `CANCELLED`, `SKIPPED`, `NEUTRAL`, `STALE`, `UNKNOWN`) | Multi-repo trả `FAILED` trỏ đúng `repo-be`; từng trạng thái khác được trả đúng enum gốc, không bị ép về `FAILED` hay `SUCCESS` | PASS (27/11/2026) — `GitHubUrlBuilderAndAggregatorTest.multiRepoAggregatesFailureAcrossRepositories` + `GitCiReadApiAndRealtimeIntegrationTest.preservesAllSupportedCiStatusesVerbatimFromDatabase` (đủ 9/9 trạng thái non-SUCCESS/FAILED) |
| API-12 | U | `GitHubUrlBuilder` với `.git` suffix, trailing `/`, host lạ, `http://`, userinfo, port, SHA lỗi, `pipeline_id` không phải số dương | Hợp lệ sinh URL canonical `https://github.com/...`; bất kỳ biến thể sai chuẩn nào đều trả `null` an toàn, không ném `500` | PASS (27/11/2026) — `GitHubUrlBuilderAndAggregatorTest` (buildsCanonicalGitHubUrlsAndRejectsUntrustedInputs + 11 biến thể `rejectsMaliciousRepositoryUrls`) |
| API-13 | I | `GET /tasks/{taskId}/activity` với `commitsLimit`, `prsLimit`, `ciRunsLimit` mặc định và tùy chỉnh (1, 20, 50) trên task có 35 commits, 5 PRs, 15 CI runs | Trả đủ `totalCommits=35`, `totalPullRequests=5`, `totalCiRuns=15`, mảng bị cắt đúng limit theo thứ tự xác định; task chưa có Git trả `summary=null` và các mảng rỗng | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.aggregatesCommitsPrsCiRerunsHeadShaAndActivityEndpointsAccurately` (`commitsLimit=1`, `prsLimit=2`, `ciRunsLimit=1`, tổng số & mảng rỗng của `task3` khớp) |
| API-14 | I/O | `EXPLAIN (ANALYZE, BUFFERS)` + benchmark batch 50 `taskIds` trên PostgreSQL 17 (100 tasks, 1.000 commits, 200 PRs, 500 pipelines, 2 workspaces) | Đúng 3 truy vấn SQL (không N+1), sử dụng index của `git_commit_task_links`/`git_pull_request_task_links`/`ci_pipelines`, thời gian thực thi P95 < 200ms | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.queryPlanUsesIndexesAndBatchLatencyStaysUnder200MsOnRealisticDataset` (5.500 commits, 2.750 pipelines, 100 PRs, 50 tasks): xác minh `idx_git_commit_links_task` & `idx_ci_pipelines_repo_commit`, CI SQL 9,49 ms, HTTP 20 lượt min = 36,12 ms, P50 = 45,41 ms, P95 = 57,51 ms, max = 62,86 ms < 200 ms |

### 6.4. Bộ 2 — Bảo mật, Phân quyền, Chống IDOR, Validation và Privacy (`SEC-01` .. `SEC-12`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| SEC-01 | I | Gọi `GET /task-summaries` và `GET /tasks/{id}/activity` không có JWT; JWT sai chữ ký; JWT hết hạn; JWT đã bị thu hồi `token_version` (T-008) | Đều bị chặn `401 Unauthorized`, không rò rỉ dữ liệu hay chạy SQL nghiệp vụ | PARTIAL (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest` xác minh thiếu JWT `401` và JWT bị thu hồi `token_version` `401`; chữ ký sai/hết hạn được phủ bởi `JwtAuthenticationFilterTest` trong `auth-impl` |
| SEC-02 | I | `User-B1` (không thuộc `WS-A`) gọi API với `workspaceId = WS-A` | Trả RFC 7807 `404` (`WORKSPACE_NOT_FOUND`, giống chống dò ID của Board), 0 dữ liệu bị lộ | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.enforcesAuthMembershipAntiIdorValidationPrivacyAndArchivedReadability` (`404`, `application/problem+json`, `code=WORKSPACE_NOT_FOUND`) |
| SEC-03 | I | `User-A1` (thuộc `WS-A`) gọi `GET /workspaces/WS-A/task-summaries?taskIds=<taskOfWsB>` và `GET /workspaces/WS-A/tasks/<taskOfWsB>/activity` | Không trả bất kỳ commit/PR/CI nào của `WS-B`; phản hồi giống hệt task không có Git (chống IDOR và existence oracle) | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.enforcesAuthMembershipAntiIdorValidationPrivacyAndArchivedReadability` (`items=[]` và `summary=null, totalCommits=0` cho `taskB`) |
| SEC-04 | I | Sự kiện `WorkspaceMembershipCheckRequestedEvent` giả lập không có listener hoặc trả lỗi | Fail-closed trả RFC 7807 `503 SERVICE_UNAVAILABLE`, tuyệt đối không mặc định cho phép đọc | PASS (27/11/2026) — `GitCiReadServiceUnitTest.membershipCheckFailsClosedWith503WhenNoListenerAmbiguousOrException` (0 phản hồi, 2 phản hồi và exception đều trả `503 SERVICE_UNAVAILABLE`) |
| SEC-05 | I | `taskIds` thiếu tham số; rỗng; có phần tử không phải UUID; vượt 50 distinct UUIDs (51); hoặc > 100 phần tử raw lặp | Trả RFC 7807 `400 VALIDATION_FAILED` / `INVALID_REQUEST`, `Content-Type: application/problem+json`, có `code` & `correlationId`, không stack trace | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.enforcesAuthMembershipAntiIdorValidationPrivacyAndArchivedReadability` (thiếu param, non-UUID, 51 UUID -> `400 VALIDATION_FAILED`, `application/problem+json`) |
| SEC-06 | I | `workspaceId`/`taskId` sai UUID; `commitsLimit=0`, `commitsLimit=51`, `prsLimit=-1`, `ciRunsLimit=21` | Đều trả RFC 7807 `400`, không chạm DB | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest` (`commitsLimit=51`, `ciRunsLimit=0` -> `400 VALIDATION_FAILED`); `GitCiReadControllerAdvice` chuyển type mismatch UUID sang `400 INVALID_REQUEST` |
| SEC-07 | I | Gửi burst vượt ngưỡng `devflow.gitci.read.rate-limit.capacity` từ `User-A1`, đồng thời gửi 1 request từ `User-A2` | `User-A1` nhận RFC 7807 `429 RATE_LIMIT_EXCEEDED` + header `Retry-After` + `retryAfterSeconds`; `User-A2` vẫn nhận `200 OK` (cô lập per-actor) | PASS (27/11/2026) — `GitCiReadServiceUnitTest.rateLimiterReturns429ProblemDetailWithRetryAfterAndIsolatesActors` + `GitHubUrlBuilderAndAggregatorTest.readRateLimiterIsolatesPerActorAndReturnsPositiveRetryAfter` |
| SEC-08 | I | Commit trong DB có sẵn `author_email = dev@secret.local`, `repositories.webhook_secret_ref = secret-ref-1`, `ci_failures.raw_log_snippet = TOP_SECRET_LOG` | Kiểm tra toàn bộ JSON body của cả 2 endpoint không chứa `author_email`, `webhook_secret_ref`, `raw_log_snippet`, `raw_log_reference`, hay `error_summary` | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.aggregatesCommitsPrsCiRerunsHeadShaAndActivityEndpointsAccurately` xác nhận JSON không chứa `private@secret.local`, `TOP_SECRET_LOG_LINE`, `RAW_ERR`, `test:t026` |
| SEC-09 | I/C | `Task-Malicious` có commit message/PR title chứa `<script>alert(1)</script>`, `<img onerror=...>`, ký tự điều khiển `\u0000`–`\u001F` | Backend lọc ký tự điều khiển và cắt độ dài an toàn; Frontend render text thuần, không tạo thẻ `<script>`/`<img>` hay thực thi JS | PASS (27/11/2026) — `GitHubUrlBuilderAndAggregatorTest.sanitizesControlCharsAndTruncatesUnicodeCleanly` + `GitCiReadApiAndRealtimeIntegrationTest` (lọc `\u0007`) + `TaskCard.test.tsx` (0 thẻ `<script>`/`<img>` được tiêm vào DOM) |
| SEC-10 | U/C | API trả `url` độc hại (`javascript:alert(1)`, `data:text/html,...`, `http://github.com/...`, `https://evil.com/github.com/...`, `https://user:pass@github.com/...`) | `isSafeGitHubUrl` từ chối toàn bộ; UI không render `href` nguy hiểm (fallback `<span>` không click được) | PASS (27/11/2026) — `frontend/src/types/gitci.test.ts` (17 URL không hợp lệ bị từ chối) + `TaskCard.test.tsx` (`javascript:alert(1)` và `null` hạ cấp thành `<span>`) |
| SEC-11 | U | `parseTaskGitSummaryBatch` nhận JSON hỏng, thiếu field, `commitCount = -1`, `prNumber = 0`, `status = "UNKNOWN_HACK"`, mảng quá lớn | Loại bỏ item/trường không hợp lệ một cách an toàn, không throw unhandled crash làm trắng board | PASS (27/11/2026) — `frontend/src/types/gitci.test.ts` (6/6 PASS) + `useBoardGitSummaries.test.tsx` (401/403/404 xóa sạch cache) |
| SEC-12 | I | Đọc Git/CI trên board `archived = true` bởi thành viên hợp lệ vs non-member | Thành viên hợp lệ đọc `200 OK`; non-member bị chặn `404` | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.enforcesAuthMembershipAntiIdorValidationPrivacyAndArchivedReadability` (`taskArchived` trả `200` cho `userA1`, `userB1` bị chặn `404`) |

### 6.5. Bộ 3 — Đồng bộ Realtime, Vòng đời State và Xung đột (`RT-01` .. `RT-10`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| RT-01 | I/E | Webhook push gắn commit đầu tiên cho task ở `To Do` khi `auto-transition-enabled=true` | Board chuyển thẻ sang `In Progress` (`CARD_MOVED`), phát `gitActivity`, client cập nhật cả vị trí cột lẫn huy hiệu `1 commit` không cần F5 | PASS (27/11/2026) — Backend `GitTaskTransitionIntegrationTest` + E2E trình duyệt thật trên `Sprint T-026 Live` (`http://localhost:5173`): gửi webhook `push` ký HMAC -> `T-001` tự rời `To Do` và cập nhật badge commit live không F5 |
| RT-02 | I/E | Webhook push commit thứ 2 khi task đã ở `In Progress` (`NOOP`), và trường hợp `auto-transition-enabled=false` (`DISABLED`) | Không đổi cột; `TaskGitActivityChangedEvent` phát sau commit → STOMP gửi `CARD_UPDATED` (`changedFields=["gitActivity"]`) → badge cập nhật từ `1` lên `2` trong < 1s trên 2 trình duyệt | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest` xác minh `DISABLED` phát `CARD_UPDATED(gitActivity)` + E2E trình duyệt thật gửi `push` thứ 2 (`NOOP`) -> `devflow_gitci_activity_events_total{kind="COMMIT_LINKED",outcome="broadcast"} = 1.0`, badge trên `T-001` tăng lên `2` live không F5 |
| RT-03 | I/E | Webhook PR `opened` → `edited` (đổi title) → `closed` (không merge) → `reopened` → `closed` (merged) | Mỗi thay đổi hợp lệ đều cập nhật đúng trạng thái/màu/tiêu đề PR trên thẻ và modal (kể cả khi `closed` không merge không chuyển cột) | PASS (27/11/2026) — Backend xác minh `closed` không merge phát `CARD_UPDATED(gitActivity)` + E2E trình duyệt thật gửi PR #42 `opened` (`In Review`, pill xanh lá `Đang mở`) -> `closed` merged (`Done`, pill tím `Đã merge`) live không F5; đã sửa bổ sung tăng `gitActivityRefreshVersion` khi nhận `CARD_MOVED` lúc modal đang mở |
| RT-04 | I/E | Webhook `workflow_run` `completed/failure` trên commit gắn task → sau đó attempt 2 `completed/success` | Ngay khi attempt 1 commit, thẻ hiện cảnh báo đỏ `CI lỗi` qua STOMP; khi attempt 2 `success` commit, cảnh báo đỏ tự đổi sang `CI đạt` không cần F5 | PASS (27/11/2026) — Backend integration + E2E trình duyệt thật: `workflow_run #8001` attempt 1 `failure` -> hiện cảnh báo đỏ `CI lỗi` live; attempt 2 `success` -> tự đổi sang xanh lá `CI đạt` (`/attempts/2`) live cả trong `TaskDetailModal` lẫn trên thẻ |
| RT-05 | I | Rollback giao dịch webhook trước commit (giả lập lỗi ghi receipt) | Không phát `TaskGitActivityChangedEvent` và không broadcast STOMP giả | PARTIAL (27/11/2026) — Cả `GitWebhookIngestion` và `CiWorkflowIngestion` đều chỉ phát sự kiện trong `TransactionSynchronization.afterCommit()`; chưa có test ép rollback riêng |
| RT-06 | I | `TaskGitActivityChangedEvent` chứa hỗn hợp `taskId` của `WS-A` và `taskId` của `WS-B` dưới `workspaceId = WS-A` | `BoardGitActivityRealtimeListener` chỉ broadcast lên board thuộc `WS-A`; board của `WS-B` nhận 0 frame | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.broadcastsGitActivityStompForDisabledNoopPrClosedAndCiUpdatesWithTenantIsolation` (`broadcaster` gọi đúng 1 lần cho `boardA`/`task1`, 0 lần cho `boardB`) |
| RT-07 | C | Người dùng đang giữ drag thẻ X trên `BoardPage` thì STOMP nhận `CARD_UPDATED` với `changedFields=["gitActivity"]` | Thao tác drag thẻ X **không bị hủy**, không gọi lại `GET /columns/{id}/tasks`; chỉ gọi gọn `GET /task-summaries` cho phạm vi liên quan | PASS (27/11/2026) — `frontend/src/pages/BoardPage.gitci.test.tsx` xác nhận `CARD_UPDATED(gitActivity)` cập nhật `CI lỗi` → `CI đạt` mà không gọi lại `/columns/{id}/tasks` và không đi vào `scheduleBoardSync` hủy drag |
| RT-08 | C | Đổi nhanh Board 1 → Board 2, đổi Workspace A → B, hoặc Logout khi request `GET /task-summaries` của Board 1 đang treo rồi mới trả về | Request cũ bị `AbortController` hủy và bị `generationRef` loại bỏ; badge của Board 1/WS-A không bao giờ xuất hiện trên Board 2/WS-B; cache sạch sau logout | PASS (27/11/2026) — `frontend/src/hooks/useBoardGitSummaries.test.tsx` (3/3 PASS): phản hồi chậm của Board A bị bỏ qua sau khi chuyển sang Board B / WS B / `sessionGeneration` mới |
| RT-09 | C/E | Cột có > 100 task (2 trang); người dùng bấm "Tải tiếp" nạp thêm 40 task | Tự động gọi bổ sung `GET /task-summaries` cho 40 task mới (chunk ≤ 50) và gộp vào map mà không xóa badge của 100 task đầu | PARTIAL (27/11/2026) — Đã nối `loadColumn` → `refreshSubsetGitSummaries(newlyPagedIds)` ở `BoardPage.tsx` và xác minh subset merge ở `useBoardGitSummaries.test.tsx`; chưa chạy E2E cột > 100 task |
| RT-10 | C/E | Rớt mạng hoặc mất 1 frame STOMP trong khi webhook CI lỗi chạy xong; sau đó nối mạng lại / focus tab / hết chu kỳ đối soát 30–35s | Reconnect / focus / periodic reconcile tự động làm mới Git/CI summaries và hiển thị đúng cảnh báo đỏ `CI lỗi` | PARTIAL (27/11/2026) — Đã nối `reconcileBoardSnapshot` → `gitFullRefreshVersion` → `refreshAllGitSummaries` ở `BoardPage.tsx`; chưa chạy E2E giả lập rớt mạng thật |

### 6.6. Bộ 4 — Giao diện `TaskCard`, Liên kết tab mới, `TaskDetailModal`, Accessibility và Responsive (`UI-01` .. `UI-14`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| UI-01 | C | Render `TaskCard` cho `Task-Empty` (`gitSummary = undefined` và `gitSummary` toàn 0/null) | Không render hàng badge Git/CI, không hiện "0 commit", không hiện icon cảnh báo lỗi | PASS (27/11/2026) — `frontend/src/components/board/TaskCard.test.tsx` (`task-git-badges` = null, không có text `CI lỗi`) |
| UI-02 | C | Render `TaskCard` cho `Task-SingleCommit` (1) và `Task-MultiCommit` (5) | Hiện icon commit + `1` / `5`; `<a href="https://github.com/..." target="_blank" rel="noopener noreferrer">`; tooltip có short SHA & message preview | PASS (27/11/2026) — `frontend/src/components/board/TaskCard.test.tsx` (đúng `href`, `target="_blank"`, `rel="noopener noreferrer"`, tooltip `shortSha: messagePreview`) |
| UI-03 | C | Render `TaskCard` cho PR `OPEN`, `MERGED`, `CLOSED` và `Task-MultiPr` (3 PR) | Đúng icon (`GitPullRequestIcon` vs `GitMergeIcon`), đúng tông màu xanh lá/tím/xám, hiển thị `#42` hoặc `#42 +2`, `href` trỏ đúng PR chính trên GitHub | PASS (27/11/2026) — `frontend/src/components/board/TaskCard.test.tsx` (đủ 3 trạng thái `OPEN`/`MERGED`/`CLOSED`, đúng tông emerald/violet/slate, nhãn `#42 +1`) |
| UI-04 | C | Render `TaskCard` cho `Task-CiFail` (1 và 2 workflow lỗi) | Hiện cảnh báo màu đỏ rõ rệt (`bg-rose-50 text-rose-700`), icon cảnh báo + chữ `CI lỗi` / `CI lỗi (2)`, link mở đúng `/actions/runs/<id>/attempts/<n>` | PASS (27/11/2026) — `frontend/src/components/board/TaskCard.test.tsx` (`data-ci-status="FAILED"`, `CI lỗi (2)`, class `rose`, đúng URL attempt) |
| UI-05 | C | Render `TaskCard` cho CI `SUCCESS`, `RUNNING`, `PENDING`, `TIMED_OUT`, `ACTION_REQUIRED`, `CANCELLED`, `SKIPPED`, `UNKNOWN` | Mỗi trạng thái hiển thị đúng nhãn & tông màu riêng; tuyệt đối không gắn tông đỏ `CI lỗi` cho `CANCELLED`/`SKIPPED`/`SUCCESS` | PASS (27/11/2026) — `frontend/src/components/board/TaskCard.test.tsx` (xác minh đủ 10 trạng thái non-FAILED: `SUCCESS`, `RUNNING`, `PENDING`, `TIMED_OUT`, `ACTION_REQUIRED`, `CANCELLED`, `SKIPPED`, `NEUTRAL`, `STALE`, `UNKNOWN`) |
| UI-06 | C | Render `TaskCard` khi `ci.staleForLatestCommit = true` và khi `ci.url = null` | Tooltip ghi chú rõ kết quả thuộc commit trước; khi `url = null` render `<span>` không có `href` hỏng | PASS (27/11/2026) — `frontend/src/components/board/TaskCard.test.tsx` (tooltip chứa `commit mới nhất chưa có CI`; URL `null`/không hợp lệ render `<span>`) |
| UI-07 | C/M | Click chuột trái và kích hoạt phím `Enter` vào từng huy hiệu Commit / PR / CI trên `TaskCard` | Mở đúng URL ở tab mới; `onOpenTask` gọi **0 lần**; không bắt đầu drag; click vào tiêu đề thẻ vẫn mở `TaskDetailModal` bình thường | PASS (27/11/2026) — `TaskCard.test.tsx` xác minh click 3 badge gọi `onOpenTask` 0 lần & click tiêu đề mở modal; DOM trình duyệt thật xác minh `[data-testid="task-git-badges"]` nằm độc lập ngoài `<button>` tiêu đề với đầy đủ `href` GitHub, `target="_blank"`, `rel="noopener noreferrer"` |
| UI-08 | C/M | Kéo thả thẻ có đầy đủ 3 badge giữa các cột và reorder trong cột (hồi quy T-016) | Drag handle và kéo thẻ hoạt động mượt không giật; sau khi thả, thẻ giữ đủ badge ở cột mới | PASS (27/11/2026) — E2E trình duyệt thật: kéo `T-001` bằng drag handle từ `Done` sang `In Progress` (`Done: 0`, `In Progress: 1`), giữ nguyên đủ 3 badge (`2`, `#42`, `CI đạt` `/attempts/2`) |
| UI-09 | C | Lọc board theo từ khóa, người phụ trách, độ ưu tiên rồi bấm "Xóa lọc" | Các thẻ khớp lọc giữ đúng badge; sau khi xóa lọc mọi thẻ hiện lại đủ badge mà không phải tải lại vô ích | PASS (27/11/2026) — `frontend/src/pages/BoardPage.gitci.test.tsx` (lọc `T-026` giữ nguyên badge `CI đạt`, 0 lượt gọi thêm `/task-summaries`) |
| UI-10 | C | Mở `TaskDetailModal` cho `Task-MultiCommit` + `Task-MultiPr` + `Task-CiFail` | Mục read-only "Hoạt động Git & CI" liệt kê đủ các commit, PR, CI runs gần nhất, từng dòng mở tab mới đúng GitHub URL; trạng thái loading/empty/error độc lập | PASS (27/11/2026) — `frontend/src/components/board/TaskGitActivitySection.test.tsx` (đủ danh sách CI/PR/Commit, 3 link `target="_blank" rel="noopener noreferrer"`, báo lỗi 503 + nút "Thử tải lại" hoạt động) |
| UI-11 | C | Trong `TaskDetailModal` đang có bản nháp sửa tiêu đề (dirty), có tín hiệu `gitActivity` mới làm mới mục Git & CI | Mục Git & CI tự cập nhật danh sách mới; bản nháp form, `If-Match` version và ô nhập bình luận được giữ nguyên không bị ghi đè | PASS (27/11/2026) — `frontend/src/components/board/TaskGitActivitySection.test.tsx` (tăng `gitActivityRefreshVersion` đổi `CI lỗi` → `CI đạt`, giữ nguyên bản nháp tiêu đề & bình luận) |
| UI-12 | C/M | Mở `TaskCard` trên board `archived = true` | Thẻ ở chế độ chỉ đọc (không kéo thả), nhưng các huy hiệu Git/CI vẫn hiển thị và bấm mở GitHub bình thường | PASS (27/11/2026) — `frontend/src/components/board/TaskCard.test.tsx` (`disabled=true` khóa drag handle nhưng giữ nguyên link GitHub hoạt động) |
| UI-13 | M | Kiểm tra bàn phím (`Tab`, `Shift+Tab`, `Enter`) và screen reader / cấu trúc DOM (`TaskCard`) | Không lồng `<a>` trong `<button>`; thứ tự focus hợp lý; vòng focus rõ (`focus-visible`); `aria-label` mô tả đầy đủ bằng tiếng Việt và không chỉ truyền đạt thông tin bằng màu | PASS (27/11/2026) — `TaskCard.test.tsx` + E2E trình duyệt thật: `button a, a button` = 0, bấm `Tab` đi lần lượt `2` → `#42` → `CI đạt` có vòng focus indigo rõ ràng (`focus-visible:ring-2 focus-visible:ring-indigo-500`) và `aria-label` tiếng Việt đầy đủ |
| UI-14 | M | Kiểm tra giao diện ở viewport mobile `360x800` và desktop zoom `200%` với thẻ có đủ priority `URGENT`, assignee tên dài, dueDate trễ và cả 3 badge Git/CI | Các huy hiệu tự xuống dòng gọn trong thẻ (`flex-wrap`), không tràn ngang viewport, không đè chữ hay cắt nút "Chuyển cột" | PASS (27/11/2026) — E2E trình duyệt thật ở `360x800`: `body = 344,8px <= 360px` (0 tràn ngang), thẻ `288px`, 3 badge nằm gọn trong vùng `256,8px` không đè chữ, nút `Chuyển cột` hiển thị đủ không bị che khuất |

### 6.7. Bộ 5 — Chịu lỗi (Degraded Mode), Vòng đời dữ liệu và Quality Gates (`OPS-01` .. `OPS-10`)

| ID | Mức | Thiết lập / Đầu vào | Expected | Thực tế |
|---|---|---|---|---|
| OPS-01 | C/O | `GET /task-summaries` trả `503`, `500`, `429` hoặc timeout mạng | Toàn bộ board, cột, thẻ, bộ lọc, kéo thả và `TaskDetailModal` vẫn hoạt động bình thường (`syncBlocked` không bị khóa); hiện thông báo nhẹ không chặn thao tác kèm nút "Thử tải lại Git/CI" | PASS (27/11/2026) — `frontend/src/pages/BoardPage.gitci.test.tsx` + `useBoardGitSummaries.test.tsx` (board hiển thị đủ thẻ, xuất hiện `gitci-degraded-banner` kèm nút "Thử tải lại") |
| OPS-02 | C/O | Đã tải được badge, sau đó lần refresh kế tiếp gặp `503`/offline | Giữ lại badge gần nhất cùng dấu hiệu chờ đồng bộ (không xóa trắng thành "chưa có Git"); khi bấm thử lại thành công thì cập nhật mới | PASS (27/11/2026) — `frontend/src/hooks/useBoardGitSummaries.test.tsx` (503 & 429 giữ nguyên `commitCount = 3`, bật `stale = true`, hiện thông báo kèm `retryAfterSeconds`) |
| OPS-03 | C/I | Access token hết hạn đúng lúc gọi `GET /task-summaries` | `AuthProvider` tự refresh single-flight và retry `GET` 1 lần thành công `200 OK` (không báo lỗi `uncertain` như mutation) | PASS (27/11/2026) — `gitciApi.ts` dùng hoàn toàn `GET` chunk ≤ 50 UUIDs, kết hợp `AuthProvider.test.tsx` (9/9 PASS) xác minh single-flight refresh + retry cho `GET` |
| OPS-04 | I | Xóa task đã có commit, PR và CI liên quan (`DELETE /api/v1/tasks/{id}`) | `TaskReferencesRemovedEvent` xóa sạch `git_commit_task_links` & `git_pull_request_task_links`; gọi lại API đọc trả rỗng cho `taskId` đó; các task khác chung commit vẫn còn liên kết | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest.cleansUpAssociationsOnTaskAndWorkspaceDeletionAndRecordsLowCardinalityMetrics` (`task1` rỗng sau xóa, `task2` còn nguyên) |
| OPS-05 | I | Xóa workspace (`WorkspaceDeletedEvent`) hoặc thu hồi thành viên (`User-Revoked`) | Xóa workspace cascade sạch toàn bộ repositories/commits/PRs/CI; `User-Revoked` nhận `404` ở lần đọc kế tiếp và UI xóa sạch cache Git/CI | PASS (27/11/2026) — `GitCiReadApiAndRealtimeIntegrationTest` xác nhận `WorkspaceDeletedEvent` xóa sạch `repositories = 0`; `useBoardGitSummaries.test.tsx` xác nhận `404` xóa sạch cache |
| OPS-06 | I/O | Chạy trên board có 130 task cùng lúc | Frontend tự chia thành 3 chunk `GET` (50 + 50 + 30) với concurrency tối đa 3; tổng hợp chính xác 100% badge không thiếu thẻ nào | PASS (27/11/2026) — `frontend/src/api/gitciApi.test.ts` (4/4 PASS): 130 task chia đúng 3 chunk 50+50+30, `maxActive <= 3`, merge đủ kết quả |
| OPS-07 | I/O | Kiểm tra `/actuator/prometheus` và log ứng dụng sau các request đọc + lỗi `400`/`404`/`429` + broadcast `gitActivity` | Có đủ metrics `devflow.gitci.read.*` & `devflow.gitci.activity.events` không chứa tag cardinality cao (`workspaceId`/`taskId`/`sha`); log JSON có `correlationId`/`userId` và không rò rỉ JWT/PII/message | PASS (27/11/2026) — Integration tests + scrape thật `GET /actuator/prometheus` sau E2E xác nhận `devflow_gitci_read_requests_total`, `devflow_gitci_read_duration_seconds_max` (0,044s / 0,060s), `devflow_gitci_read_batch_size`, `devflow_gitci_activity_events_total` không chứa tag cardinality cao |
| OPS-08 | O | Diễn tập tương thích ngược migration V20 (chạy bản build trước V20 trên schema đã migrate V20; chạy frontend mới khi endpoint GitCI trả 404) | Backend cũ vẫn chạy bình thường trên schema V20; Frontend mới degrade an toàn không crash | CHƯA CHẠY (diễn tập vận hành trước phát hành; frontend degrade khi 404 đã kiểm ở `useBoardGitSummaries.test.tsx`) |
| OPS-09 | I | Chạy đầy đủ quality gates: `cd backend && ./gradlew :verifyModuleBoundaries && ./gradlew check` (gồm toàn bộ regression T-010..T-025, `WH-04`) và `cd frontend && npm run lint && npm run build && npm test` | 0 lỗi biên dịch, 0 vi phạm module boundaries, 0 cảnh báo lint, 100% test PASS | PASS (27/11/2026) — `./gradlew.bat :verifyModuleBoundaries check` PASS (114 suites / 1.012 tests, 0 failed, 0 boundary violations, 8m16s); `npm run lint` 0 lỗi/cảnh báo; `npm run build` PASS (385 modules); `npm test` PASS (24 files / 173 tests) |
| OPS-10 | E | Smoke E2E trên stack thật qua cả Vite (`:5173`) và Docker Compose Nginx (`:3000`): gửi webhook GitHub ký HMAC (`push` → `pull_request opened` → `workflow_run failure` → `workflow_run success` attempt 2 → `pull_request merged`) trên trình duyệt thật | Thẻ Kanban hiển thị lần lượt badge commit, PR xanh, cảnh báo đỏ `CI lỗi`, đổi sang `CI đạt`, PR tím `MERGED`; bấm từng badge mở tab mới đúng URL trên GitHub; chụp/lưu đủ bằng chứng | PARTIAL (27/11/2026) — Đã PASS toàn bộ chuỗi 6 webhook GitHub ký HMAC thật trên stack PostgreSQL 17 + Spring Boot `:8080` + Vite `:5173` với trình duyệt thật (badge commit `2`, PR xanh `#42` → tím `Đã merge`, cảnh báo đỏ `CI lỗi` → xanh lá `CI đạt`, modal giữ nguyên bản nháp, DnD & mobile `360x800`); chưa chạy lặp lại qua container Nginx `:3000` (`frontend/nginx.conf` đã proxy sẵn `/api/` và `/ws`) |
