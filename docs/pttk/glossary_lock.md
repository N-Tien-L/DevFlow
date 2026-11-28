# Bảng Khóa Mã Số & Thuật Ngữ Dùng Chung (`glossary_lock.md`)

> **Mục đích:** Đây là nguồn đối chiếu duy nhất cho toàn bộ mã định danh (`FR`, `NFR`, `BR`, `UC`, `SC`, `ADR`) và tên gọi kỹ thuật trên nhánh `docs/pttk-report`. Cả 3 thành viên **bắt buộc dùng đúng mã trong tệp này** để Phần 3, Phần 4, Phần 7, Phần 8 và Ma trận truy vết RTM (Phần 12) khớp nhau 100%.

---

## 1. Tên Chính thức 5 Module Hệ thống

| Mã module | Tên hiển thị tiếng Việt | Subproject Gradle | Quyền sở hữu dữ liệu chính |
|---|---|---|---|
| `auth` | Module Xác thực & Không gian làm việc | `auth-api`, `auth-impl` | `users`, `workspaces`, `workspace_members`, `verification_tokens`, `workspace_invitations`, `personal_access_tokens` |
| `board` | Module Bảng Kanban & Công việc | `board-api`, `board-impl` | `boards`, `columns`, `tasks`, `task_comments`, `task_tags`, `task_tag_mappings`, `board_audit_events`, `task_audit_events`, `board_account_cleanup_jobs` |
| `gitci` | Module Tích hợp Git & CI/CD | `gitci-api`, `gitci-impl` | `repositories`, `git_commits`, `pull_requests`, `ci_pipelines`, `ci_failures`, `github_webhook_receipts`, `git_task_associations`, `git_domain_event_outbox` |
| `ai` | Module Trợ lý AI & MCP Server | `ai-api`, `ai-impl` | `ai_conversations`, `ai_messages`, `ai_ci_summaries` |
| `notification` | Module Thông báo Thời gian thực | `notification-api`, `notification-impl` | `notifications` |

---

## 2. Danh sách Yêu cầu Chức năng (`FR-01` – `FR-22`)

| Mã | Tên yêu cầu chức năng | Tác nhân chính | Module | MoSCoW |
|---|---|---|---|---|
| `FR-01` | Đăng ký tài khoản kèm xác thực email (token tạm), chống bot Turnstile, chặn email rác/DNS MX | Lập trình viên | `auth` | M |
| `FR-02` | Đăng nhập, làm mới phiên bằng Stateless JWT (Access/Refresh token), xem hồ sơ `/me` | Tất cả | `auth` | M |
| `FR-03` | Quên mật khẩu, đặt lại mật khẩu qua email và thu hồi tức thì toàn bộ phiên cũ (`token_version`) | Tất cả | `auth` | M |
| `FR-04` | Tạo Workspace, mời thành viên qua email, chấp nhận lời mời và phân quyền (`OWNER`, `ADMIN`, `MEMBER`) | Chủ Workspace / Lead | `auth` | M |
| `FR-05` | Xóa tài khoản theo quyền xóa dữ liệu cá nhân (GDPR) và dọn/ẩn danh hóa dữ liệu liên module | Tất cả | `auth`, `board`, `notification` | S |
| `FR-06` | Tạo, sửa, xóa Board và các cột trạng thái (Backlog, To Do, In Progress, In Review, Done) | Chủ Workspace / Lead | `board` | M |
| `FR-07` | Tạo, sửa, xóa Task/Card (tiêu đề, mô tả Markdown, người phụ trách, hạn chót, độ ưu tiên, nhãn) | Lập trình viên / Lead | `board` | M |
| `FR-08` | Kéo thả chuyển cột và sắp xếp thứ tự Task trên bảng Kanban có kiểm soát ghi đè đồng thời (`version`) | Lập trình viên / Lead | `board` | M |
| `FR-09` | Đồng bộ thay đổi bảng Kanban thời gian thực qua WebSocket STOMP (`/topic/boards/{boardId}`) | Hệ thống | `board` | M |
| `FR-10` | Bình luận trên Task, tìm kiếm và lọc Task theo từ khóa, người phụ trách, độ ưu tiên, cột | Tất cả | `board` | S |
| `FR-11` | Ghi nhận lịch sử thao tác (Audit Trail) cho các thay đổi trên Board và Task | Hệ thống / Lead | `board` | S |
| `FR-12` | Tiếp nhận GitHub/GitLab Webhook an toàn có xác thực chữ ký HMAC SHA-256 và chống xử lý trùng | Hệ thống Git/CI | `gitci` | M |
| `FR-13` | Tự động bóc tách mã công việc (`T-XXX`) từ tên nhánh, commit message, tiêu đề PR để liên kết với Task | Hệ thống | `gitci`, `board` | M |
| `FR-14` | Tự động chuyển cột Task theo sự kiện Git (Commit → In Progress, PR Opened → In Review, PR Merged → Done) | Hệ thống | `gitci`, `board` | M |
| `FR-15` | Tiếp nhận sự kiện lỗi pipeline CI/CD, lưu trữ nhật ký lỗi và gắn trạng thái CI lên thẻ Task/PR | Hệ thống Git/CI | `gitci`, `board` | M |
| `FR-16` | AI tự động tóm tắt nguyên nhân gốc (root cause) của lỗi CI/CD sau khi che secret và cắt ngắn log | Hệ thống / AI | `ai`, `gitci` | M |
| `FR-17` | Trợ lý AI phân rã yêu cầu/mô tả lỗi thành danh sách subtask đề xuất kèm nút 1-click tạo thẻ | Lập trình viên / Lead | `ai`, `board` | M |
| `FR-18` | Hỏi đáp dự án bằng ngôn ngữ tự nhiên trong ChatPanel thời gian thực (có kiểm tra membership) | Lập trình viên / Lead | `ai`, `board` | M |
| `FR-19` | Cảnh báo chủ động các Task có nguy cơ trễ hạn dựa trên tiến độ và thời hạn còn lại | Hệ thống / AI | `ai`, `notification` | S |
| `FR-20` | Cấp Personal Access Token (PAT) và cung cấp cổng MCP Server (SSE) cho IDE (Cursor, Claude Code) | Lập trình viên / AI Agent | `auth`, `ai` | S |
| `FR-21` | Gửi thông báo in-app thời gian thực theo từng người dùng (`/user/queue/notifications`) và quản lý trạng thái đã đọc | Hệ thống / Tất cả | `notification` | M |
| `FR-22` | Xem bảng thống kê tiến độ dự án cơ bản (số task theo trạng thái, tỷ lệ hoàn thành) | Lead / Lập trình viên | `board` | S |

---

## 3. Danh sách Yêu cầu Phi chức năng (`NFR-01` – `NFR-10`, ISO/IEC 25010)

| Mã | Đặc tính ISO/IEC 25010 | Yêu cầu định lượng của DevFlow |
|---|---|---|
| `NFR-01` | Hiệu năng (Performance) | 95% yêu cầu REST API CRUD cốt lõi phản hồi < 200 ms; tải bảng Kanban 200 thẻ < 1.5 giây |
| `NFR-02` | Khả năng mở rộng (Scalability) | Hỗ trợ ≥ 300 người dùng đồng thời trên 1 instance Spring Boot (HikariCP + Caffeine), kiến trúc sẵn sàng mở rộng Redis và bóc tách module |
| `NFR-03` | Tính sẵn sàng (Availability) | Mục tiêu SLO ≥ 99.5%/tháng; cung cấp `/actuator/health` cho liveness/readiness probe |
| `NFR-04` | Bảo mật (Security) | Stateless JWT + `token_version`, BCrypt, HTTPS/TLS 1.2+, Cloudflare Turnstile, Bucket4j rate-limit, HMAC SHA-256 webhook, kiểm tra membership chống IDOR (OWASP Top 10) |
| `NFR-05` | Khả dụng (Usability) | Lập trình viên tạo Workspace, bảng Kanban và liên kết task đầu tiên trong < 3 phút; điểm SUS mục tiêu ≥ 75 |
| `NFR-06` | Khả năng truy cập (Accessibility) | Tuân thủ WCAG 2.1 mức AA (độ tương phản ≥ 4.5:1, hỗ trợ điều hướng bàn phím thay thế kéo thả) |
| `NFR-07` | Khả năng bảo trì (Maintainability) | 0 vi phạm ranh giới module qua `./gradlew :verifyModuleBoundaries`; độ bao phủ unit test nghiệp vụ lõi ≥ 75% |
| `NFR-08` | Độ tin cậy & Phục hồi (Reliability) | Lỗi API chuẩn RFC 7807 (`ProblemDetail`) không lộ stack trace; Resilience4j timeout/retry/circuit breaker cho LLM và email; có `DeterministicChatModel` dự phòng offline |
| `NFR-09` | Khả năng khôi phục dữ liệu (Recoverability) | Sao lưu PostgreSQL định kỳ + PITR; RPO ≤ 15 phút, RTO ≤ 4 giờ |
| `NFR-10` | Quyền riêng tư & Tuân thủ (Compliance) | Hỗ trợ xóa/ẩn danh hóa dữ liệu người dùng (GDPR Right to Erasure), lưu nhật ký kiểm toán ≥ 12 tháng, log JSON kèm MDC không chứa PII/secret |

---

## 4. Quy tắc Nghiệp vụ (`BR-01` – `BR-10`)

| Mã | Nội dung quy tắc nghiệp vụ |
|---|---|
| `BR-01` | Email đăng ký phải được chuẩn hóa chữ thường, không thuộc danh sách tên miền dùng một lần (disposable email), có bản ghi DNS MX hợp lệ và phải xác thực qua token tạm trước khi kích hoạt. |
| `BR-02` | Khi đặt lại mật khẩu thành công, `users.token_version` tự động tăng lên 1; mọi JWT cũ và kết nối WebSocket STOMP hiện tại của người dùng bị thu hồi ngay lập tức. |
| `BR-03` | Người dùng chỉ được xem hoặc thao tác trên Board, Task, AI Q&A và MCP Tool thuộc các Workspace mà họ là thành viên hợp lệ (kiểm tra phía server qua `workspace.membership_check_requested`). |
| `BR-04` | Cập nhật vị trí/trạng thái Task áp dụng khóa lạc quan (`version`); nếu phiên bản không khớp, hệ thống từ chối với lỗi xung đột (`409 Conflict` chuẩn RFC 7807). |
| `BR-05` | Chỉ chấp nhận payload Webhook từ GitHub/GitLab khi chữ ký HMAC SHA-256 hợp lệ và `deliveryId` chưa từng được xử lý (idempotent qua `github_webhook_receipts`). |
| `BR-06` | Liên kết tự động giữa Git và Task nhận diện mã công việc theo biểu thức chính quy `(T-\d{3,4})` hoặc `(#T-\d{3,4})` trong tên nhánh, commit message hoặc tiêu đề PR. |
| `BR-07` | Quy tắc chuyển cột tự động theo Git: Commit liên kết chuyển `To Do` → `In Progress`; PR mở chuyển sang `In Review`; PR được merge chuyển sang `Done`; không tự lùi trạng thái của thẻ đã `Done`. |
| `BR-08` | Trợ lý AI chỉ đóng vai trò đề xuất, tuyệt đối không tự ý ghi trực tiếp vào bảng cơ sở dữ liệu của `board`; việc tạo task từ phân rã AI bắt buộc có thao tác xác nhận của người dùng. |
| `BR-09` | Trước khi gửi log CI/CD tới nhà cung cấp LLM bên ngoài, hệ thống bắt buộc lọc/che khóa bí mật (token, mật khẩu, API key) và cắt gọn log trong giới hạn kích thước cho phép. |
| `BR-10` | Khi xóa tài khoản (`user.account_deleted`), hệ thống không làm mất dữ liệu nhóm: gán `tasks.assignee_id = NULL`, thay tác giả comment/audit bằng `DeletedUser.ID` ("Người dùng đã xóa") và xóa cascade thông báo cá nhân. |

---

## 5. Danh mục Use Case (`UC01` – `UC12`)

| Mã | Tên Use Case | Tác nhân chính | Ánh xạ FR |
|---|---|---|---|
| `UC01` | Đăng ký tài khoản và xác thực Email | Lập trình viên | `FR-01` |
| `UC02` | Đăng nhập, quản lý phiên và đặt lại mật khẩu | Tất cả | `FR-02`, `FR-03` |
| `UC03` | Quản lý Workspace và mời thành viên | Chủ Workspace / Lead | `FR-04`, `FR-05` |
| `UC04` | Quản lý Board và cột trạng thái | Chủ Workspace / Lead | `FR-06` |
| `UC05` | Tạo, cập nhật, bình luận và kéo thả Task (đồng bộ real-time) | Lập trình viên / Lead | `FR-07`, `FR-08`, `FR-09`, `FR-10`, `FR-11` |
| `UC06` | Tiếp nhận Git Webhook và tự động cập nhật trạng thái Task | Hệ thống Git/CI | `FR-12`, `FR-13`, `FR-14` |
| `UC07` | Tiếp nhận lỗi CI/CD và tự động tóm tắt nguyên nhân gốc bằng AI | Hệ thống Git/CI, AI | `FR-15`, `FR-16` |
| `UC08` | Phân rã yêu cầu thành Subtasks bằng AI và tạo thẻ 1-click | Lập trình viên / Lead | `FR-17` |
| `UC09` | Hỏi đáp ngữ cảnh dự án qua ChatPanel thời gian thực | Lập trình viên / Lead | `FR-18` |
| `UC10` | Truy vấn dự án và lỗi CI từ IDE thông qua MCP Server | AI Coding Agent (IDE) | `FR-20` |
| `UC11` | Nhận thông báo thời gian thực và cảnh báo rủi ro trễ hạn | Lập trình viên / Lead | `FR-19`, `FR-21` |
| `UC12` | Xem bảng thống kê tiến độ dự án | Lead / Lập trình viên | `FR-22` |

---

## 6. Danh mục Màn hình (`SC-01` – `SC-10`)

| Mã | Màn hình | Tệp Frontend tương ứng |
|---|---|---|
| `SC-01` | Đăng nhập | `LoginPage.tsx` |
| `SC-02` | Đăng ký (tích hợp Cloudflare Turnstile & kiểm tra mật khẩu) | `RegisterPage.tsx` |
| `SC-03` | Xác thực Email | `EmailVerificationPage.tsx` |
| `SC-04` | Quên mật khẩu & Đặt lại mật khẩu | `ForgotPasswordPage.tsx`, `ResetPasswordPage.tsx` |
| `SC-05` | Chọn & Tạo Workspace | `WorkspaceSelectionPage.tsx`, `WorkspaceCreatePage.tsx` |
| `SC-06` | Chấp nhận lời mời vào Workspace | `InvitationAcceptPage.tsx` |
| `SC-07` | Bảng Kanban Dự án (kèm bộ lọc, kéo thả, Git/CI badges) | `BoardPage.tsx` |
| `SC-08` | Cửa sổ Chi tiết Task (Mô tả Markdown, Bình luận, Lịch sử, Tóm tắt CI) | `TaskDetailModal.tsx` |
| `SC-09` | Khung Trợ lý AI Hội thoại (ChatPanel & Action Card 1-click) | `ChatPanel.tsx` |
| `SC-10` | Trung tâm Thông báo (NotificationBell dropdown) & Cài đặt tài khoản (PAT, Xóa tài khoản) | `NotificationBell` *(kế hoạch)*, Account Settings |

---

## 7. Danh mục Quyết định Kiến trúc (`ADR-001` – `ADR-006`)

| Mã | Quyết định kiến trúc | Trạng thái |
|---|---|---|
| `ADR-001` | Chọn kiến trúc **Modular Monolith** (Java 17 / Spring Boot 3.4.5) thay vì Microservices phân tán | Chấp nhận |
| `ADR-002` | Áp dụng mô hình **Database-per-Module** trên 1 cụm PostgreSQL 17, dùng **Soft Reference UUID** giữa các module | Chấp nhận |
| `ADR-003` | Tự xây dựng xác thực **Stateless JWT + `token_version`** bằng Spring Security thay vì dùng Supabase Auth | Chấp nhận |
| `ADR-004` | Giao tiếp liên module hoàn toàn qua **In-Process Domain Event Bus (`DevFlowEvent`)** kết hợp Outbox cho luồng Git | Chấp nhận |
| `ADR-005` | Hợp nhất **AI Service (`ai-impl`)** thành một bộ não duy nhất phục vụ đồng thời Web Chat STOMP và MCP Server | Chấp nhận |
| `ADR-006` | Dùng bộ nhớ đệm in-memory (**Caffeine / Bucket4j**) ở giai đoạn đầu, sẵn sàng chuyển sang **Redis** khi scale nhiều instance; không triển khai Kubernetes | Chấp nhận |
