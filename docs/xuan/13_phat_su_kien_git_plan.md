# T-023 — Kế hoạch triển khai phát sự kiện commit và Pull Request

**Trạng thái 09/11/2026:** Code đã triển khai trên `feature/T-023-git-domain-events`, full check/build/lint PASS; GitHub thật đã nghiệm thu đủ ba loại event bằng PR #4, redelivery merged 204/dedup PASS; không cần người dùng cung cấp thêm dữ liệu demo. [Báo cáo Actual và giới hạn coverage](13_phat_su_kien_git_actual.md) · [Vận hành](13_phat_su_kien_git_operations.md). Chưa Done/merge.

**Milestone 3 · Owner:** Xuan Le · **Phạm vi:** `gitci-impl`, hợp đồng `common`, migration và kiểm thử tích hợp trong `app`. **Phụ thuộc:** T-021 nhận webhook có xác thực, T-022 lưu liên kết Git–task. **Nhánh khi triển khai:** `feature/T-023-git-domain-events` trên baseline tích hợp đủ hai ticket. [Phạm vi T-023](13_phat_su_kien_git.md) · [Checklist đồng bộ](00_all-tasks.md#t-023-implement-domain-event-publishing-gitcommitlinked-gitpr_opened-gitpr_merged)

## 1. Hiện trạng và mục tiêu

| Thành phần | Hiện trạng tại `191d6a5` | Việc T-023 cần làm |
|---|---|---|
| Nhận webhook | `GitHubWebhookController` và `GitHubWebhookService` xác thực raw body, kiểm tra repo, hạn mức, khóa repo và chống trùng receipt. Receipt, Git row và liên kết cùng transaction. | Giữ giao thức HTTP. Ghi thêm yêu cầu phát sự kiện trong transaction đó. |
| Tìm task và lưu Git | `GitWebhookIngestion` resolve key theo workspace bằng typed event, lưu nhiều liên kết qua bảng V15. Commit đã có SHA giữ tập link đầu tiên. PR cập nhật theo `provider_updated_at` và `source_fingerprint`. | Chọn sự kiện từ kết quả lưu thật. Giữ action PR và trạng thái trước đó để phân biệt mở, merge và cập nhật metadata. |
| Event Bus | `common` đã có `GitCommitLinkedEvent`, `GitPrOpenedEvent`, `GitPrMergedEvent`, tất cả implement `DevFlowEvent`. `GitWebhookIngestion` hiện dùng publisher cho request resolve task. | Dùng lại ba kiểu sự kiện và `EventTypes` hiện có. Phát qua `ApplicationEventPublisher`. |
| Module nhận | `GitActivityEventListener` của Board đã đăng ký ba loại và đang ghi log. Notification đã nhận hai sự kiện PR, dùng `actorId` và có cơ chế chống trùng khi ghi thông báo. | Nghiệm thu giao nhận typed event và tương thích với listener hiện hữu. Logic chuyển cột, history và STOMP thuộc [T-024](14_tu_dong_chuyen_trang_thai_task.md). |

Phạm vi nghiệp vụ lấy từ [bản đồng bộ Trello](00_all-tasks.md), [kiến trúc §4](../ARCHITECTURE.md#4-domain-event-catalog), [đặc tả sản phẩm §5.2](../PRODUCT_SPEC.md#52-developer-specialized--ai-features) và [kế hoạch tổng thể](../IMPLEMENTATION_PLAN.md). Baseline T-022 và bằng chứng trước đó nằm trong [báo cáo triển khai T-022](12_lien_ket_commit_va_task_actual.md).

## 2. Kế hoạch nghiệp vụ

1. **Developer đẩy commit.** DevFlow tìm task hợp lệ trong workspace của repo theo quy tắc đã có. Khi một commit mới được lưu cùng hai liên kết hợp lệ, DevFlow báo hai sự kiện liên kết, mỗi task một sự kiện. Hai commit khác nhau cùng liên kết một task tạo hai sự kiện riêng. Board dùng chúng làm tín hiệu tiến độ ở T-024.
2. **Developer mở PR.** Nếu webhook mở PR đến đúng phiên bản và PR có task hợp lệ, DevFlow báo PR đã mở cho từng task. Nếu lúc mở chưa có task, DevFlow vẫn lưu PR. Các lần sửa tiêu đề, đồng bộ commit hoặc mở lại có thể đổi dữ liệu liên kết theo T-022.
3. **Reviewer merge PR.** DevFlow báo PR đã merge cho các task hợp lệ của phiên bản merge. Nếu lúc mở PR gắn task A rồi đổi sang B trước lúc merge, A nhận sự kiện mở, B nhận sự kiện merge. Nếu PR bị đóng mà chưa merge, DevFlow lưu trạng thái đã đóng và số sự kiện merge bằng 0. Webhook merge đến trước webhook mở vẫn có thể tạo sự kiện merge khi hợp lệ.
4. **Webhook gửi lại hoặc đến trễ.** Cùng một hoạt động Git chỉ tạo một bản ghi cần phát cho mỗi task. Webhook cũ hoặc xung đột phiên bản không sinh sự kiện mới. Task thiếu, đã xóa, đã lưu trữ hoặc chỉ tồn tại ở workspace khác bị loại khi resolve. Dữ liệu Git cũ đã lưu trước khi bật T-023 vẫn đọc được. Khi một PR cũ còn mở được merge hợp lệ sau khi bật, lần merge đó vẫn được xử lý.
5. **Hệ thống gặp lỗi.** Nếu chưa lưu thành công, toàn bộ lần nhận webhook được hủy để có thể gửi lại. Nếu đã lưu và tiến trình dừng trước khi phát, DevFlow lấy lại sự kiện từ hàng đợi trong PostgreSQL. Người vận hành theo dõi lỗi nhận webhook và lỗi phát Event Bus riêng. Với webhook GitHub báo thất bại, dùng chức năng redelivery hoặc quy trình khôi phục đã cấu hình. [GitHub về redelivery](https://docs.github.com/en/webhooks/testing-and-troubleshooting-webhooks/redelivering-webhooks)

## 3. Kế hoạch kỹ thuật

### KT-01. Chuẩn hóa và chọn sự kiện từ dữ liệu đã ghi

- Đi qua `POST /api/v1/gitci/webhook/github?repositoryId=<UUID>` với HMAC, binding repo, quota và giới hạn hiện có. Giữ resolver đồng bộ theo workspace của repo. Dùng association V15 làm nguồn task, kể cả khi cột tương thích `task_id` là `NULL` do có nhiều liên kết. Với fork PR, workspace và metadata repo lấy từ base repo đã xác thực.
- Trong `GitWebhookIngestion`, tạo ứng viên `git.commit_linked` sau khi insert commit và insert association tương ứng đều thực sự tác động một dòng. Nhánh conflict của `INSERT ... ON CONFLICT DO NOTHING` phải dừng trước khi dùng UUID vừa sinh để ghi link. Commit cũ giữ nguyên liên kết và quy tắc đối chiếu message/timestamp. Giữ nguyên precedence MESSAGE/TITLE/BRANCH của T-022.
- Giữ `action` trong dữ liệu PR đã chuẩn hóa và đọc thêm `status` từ snapshot cũ. Áp dụng bộ lọc stale, equal-version và conflict trước khi chọn sự kiện. `MERGED` là trạng thái cuối trong mô hình GitHub này. Snapshot mới hơn nhưng đòi quay từ `MERGED` về `OPEN` hoặc `CLOSED` được bỏ qua có metric. `CLOSED` có thể sang `OPEN` với `reopened` hợp lệ theo T-022.

| Đầu vào sau kiểm tra phiên bản | Kết quả ghi Git | Sự kiện đề xuất |
|---|---|---|
| Commit mới có N association hợp lệ | Lưu commit và N link | N `git.commit_linked` |
| PR `opened`, lần đầu lưu PR này | Lưu `OPEN` và tập link của snapshot | Một `git.pr_opened` mỗi link |
| PR `closed`, `merged=true`, PR chưa tồn tại hoặc trước đó chưa `MERGED` | Lưu `MERGED` và tập link lúc merge | Một `git.pr_merged` mỗi link |
| `edited`, `synchronize`, `reopened`, `closed` với `merged=false` | Cập nhật snapshot/link nếu hợp lệ | 0 |
| Bản mở đến sau khi đã có snapshot khác, bản lặp, stale hoặc merge của PR đã `MERGED` | Theo chính sách phiên bản và trạng thái | 0 |
| Snapshot hợp lệ có 0 link | Vẫn lưu Git theo T-022 | 0 |

Quyết định nghiệp vụ đề xuất cần review khi triển khai là chỉ báo lần mở được hệ thống ghi nhận khi tạo PR row. Ví dụ `edited` đến trước rồi mới tới `opened` thì không phát bù sự kiện mở. Cách này giữ nguyên ý nghĩa của sự kiện và tránh tạo thông báo lịch sử từ các PR đã có trước rollout. [GitHub webhook payloads](https://docs.github.com/en/webhooks/webhook-events-and-payloads#pull_request) · [Điều kiện nhận biết merge](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#running-your-pull_request-workflow-when-a-pull-request-merges)

### KT-02. Hợp đồng và nguồn dữ liệu

| Kiểu sự kiện trong `common` | Payload dùng khi phát |
|---|---|
| `GitCommitLinkedEvent` | `taskId` UUID đã resolve, `commitSha` đã validate, `repo` theo `owner/repo` từ URL repo đã chuẩn hóa, `author` là GitHub username của commit author nếu có và hợp lệ, `occurredAt` từ timestamp commit đã lưu. Nếu username thiếu hoặc không hợp lệ, `author=null`. |
| `GitPrOpenedEvent` | `taskId`, `prUrl` dựng từ base URL đã cấu hình và PR number đã validate, `repo`, `actorId` tùy chọn, `occurredAt` từ `pull_request.created_at`. |
| `GitPrMergedEvent` | Cùng cấu trúc PR opened, `occurredAt` từ `pull_request.merged_at` của snapshot merge. |

- Cho hai loại PR, nếu timestamp chuyên biệt thiếu hoặc `null`, dùng `updated_at` đã validate để tương thích fixture/payload cũ. Nếu timestamp chuyên biệt có mặt nhưng sai kiểu hoặc định dạng trên action tương ứng, trả `400` và không ghi dữ liệu. Dùng giá trị thời gian đã chuẩn hóa của provider cho mọi lần phát lại. Giữ nguyên constructor cũ cho caller hiện hữu.
- `actorId` là UUID DevFlow của người mở hoặc người thực hiện merge. Chỉ ánh xạ qua liên kết danh tính GitHub đã được xác minh nếu có. Baseline chưa thấy nguồn liên kết này trong Auth nên kế hoạch dùng `actorId=null`, ghi rõ phần tự loại thông báo cho chính actor còn phụ thuộc dữ liệu mapping. Không suy từ email, Git username, principal HTTP hoặc PR author thay người merge. Không đưa email, token, raw body, nội dung commit hay tiêu đề PR vào sự kiện.

### KT-03. Transaction và phục hồi phát sự kiện

Đề xuất dùng transactional outbox do GitCI sở hữu. Hook `afterCommit` thuần có ít mã hơn nhưng mất sự kiện nếu process dừng giữa commit và lệnh publish. Outbox giữ yêu cầu phát sau restart và tách thời gian chạy listener khỏi SLA của webhook.

1. **Ghi cùng transaction.** Thêm migration Flyway tiếp theo sau V15 tại thời điểm triển khai. Ghi outbox cùng receipt và Git/link sau khi quyết định được snapshot thắng. Row có ID, repo nội bộ, task UUID, event type, khóa SHA hoặc PR number, dữ liệu cần để dựng record, trạng thái, lần thử, lịch thử lại và lease. Unique `(repository_id, event_type, git_object_key, task_id)` bảo vệ ghi đồng thời. FK nội bộ tới `repositories` dùng cascade. `task_id` là soft reference. Không backfill sự kiện từ Git row cũ. Batch insert để xử lý tối đa 2.000 cặp commit–task lý thuyết theo giới hạn hiện có là 100 commit × 20 key/commit và 100 key phân biệt/delivery.
2. **Claim rồi publish.** Worker claim batch giới hạn bằng `FOR UPDATE SKIP LOCKED` trong transaction ngắn và chốt lease/token trước khi gọi bus. Dựng typed event từ snapshot đã ghi và gọi `ApplicationEventPublisher` ngoài transaction claim và ingest. Trả `204` sau khi receipt, Git và outbox commit, dù worker chưa phát. Consumer Notification hiện có `fallbackExecution=true` và ghi qua transaction riêng, nên test phải đi qua listener đó. Cập nhật `PUBLISHED` theo đúng claim token sau khi lời gọi bus trả về. Lỗi quan sát được chuyển sang retry có backoff, jitter, giới hạn lần thử và trạng thái `FAILED` có cảnh báo. Lease hết hạn cho phép worker khác nhận lại. Requeue theo ID nội bộ giữ nguyên payload, khóa và thời điểm nghiệp vụ. [Spring Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
3. **Ranh giới bảo đảm.** Một logical event có một bản ghi outbox. Việc gọi bus theo mô hình at-least-once khi phục hồi, vì crash sau publish và trước khi đánh dấu có thể gây phát lại. `PUBLISHED` xác nhận lời gọi bus đã trả về, còn lỗi được listener tự bắt cần theo dõi qua metric của consumer. Notification phải giữ một thông báo theo khóa dedup sẵn có khi cùng payload được phát lại. Bàn giao T-024 yêu cầu consumer chuyển trạng thái idempotent, dùng transaction của Board và không lùi trạng thái vì thứ tự giao nhận giữa worker, retry hoặc repo khác nhau.
4. **Vòng đời dữ liệu.** Bổ sung cleanup outbox theo task/board trong flow xóa typed event của T-022, repo/workspace dùng cascade. Worker bỏ qua claim đã mất hoặc row đã bị xóa khi kiểm tra trước publish. Consumer vẫn phải xử lý task biến mất trong cửa sổ race giữa kiểm tra và publish. Dọn `PUBLISHED` theo batch sau thời hạn vận hành đề xuất 7 ngày. `PENDING`/retry/`FAILED` cần quy trình xử lý riêng và vẫn được xóa theo vòng đời task/repo. Sau khi dọn receipt/outbox, unique commit và quy tắc vòng đời PR tiếp tục ngăn tạo lại logical event.

### KT-04. Quan sát, rollout và trình tự thực hiện

1. Chốt các quyết định ở KT-01 đến KT-03, đặc biệt quy tắc PR mở đến trễ, dữ liệu tác giả và thời hạn giữ outbox. Xác nhận thứ tự migration với nhánh tích hợp rồi triển khai lựa chọn sự kiện và ghi outbox.
2. Thêm publisher, cơ chế retry và cleanup, cập nhật event catalog/kiến trúc. Đo số enqueue đã commit, số publish/retry/failed theo event type/outcome, độ sâu hàng đợi và tuổi bản ghi pending lâu nhất. Dùng ID tương quan nội bộ trong log khi cần. Dùng tag có tập giá trị cố định, không dùng repo URL, SHA, task ID, delivery ID hay tác giả làm nhãn. Rà log của listener Board hiện hữu vì đang ghi metadata repo/PR.
3. Thêm test theo §5. So sánh thời gian HTTP và lock với baseline trên cùng fixture, gồm batch lớn nhất và worker bị chặn. Giữ giới hạn transaction/lock/statement hiện tại `6s/2s/4s` và xác minh request thành công trong cửa sổ GitHub `10s` ở staging. Giữ `ProblemDetail` hiện tại cho lỗi HTTP, dùng lỗi tạm thời `503` khi ghi outbox thất bại do DB. [GitHub webhook best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)
4. Áp migration trước khi bật producer. Trong rollout nhiều instance, xác nhận tất cả instance tiếp nhận webhook đã chạy bản biết ghi outbox trước khi công bố tính năng hoạt động. Dùng công tắc dừng worker nếu cần khắc phục và tiếp tục ghi outbox để giữ backlog. Khi bật lại, kiểm tra backlog giảm và không còn item lỗi chưa xử lý. Bàn giao contract, semantics retry và thứ tự event cho Board và Notification.

## 4. Tiêu chí hoàn thành

- [x] **AC-01 · Trello.** Sự kiện có đúng `taskId`, repo metadata và `commitSha` hoặc `prUrl` theo loại. Test khẳng định thời điểm nghiệp vụ, tác giả/actor tùy chọn và khả năng gọi constructor cũ. **Bằng chứng:** EVT-01 đến EVT-10.
- [x] **AC-02 · Trello.** Webhook ký HMAC đi từ HTTP đến listener trên Spring Event Bus, listener nhận đúng ba loại qua `ApplicationEventPublisher`. **Bằng chứng:** EVT-01, EVT-05, EVT-06, RUN-01, RUN-06.
- [x] **AC-03.** Rollback không tạo business event. Replay, xung đột, request song song và receipt hết hạn không tạo logical event trùng. Event chỉ được ghi cho task hợp lệ trong workspace của binding. **Bằng chứng:** REP-01 đến REP-12, SEC-01 đến SEC-06.
- [x] **AC-04.** Restart/retry khôi phục được bản ghi đã commit. Crash sau publish được xử lý với payload ổn định và Notification không tạo thông báo trùng. Có metric và quy trình xử lý `FAILED`. **Bằng chứng:** RUN-01 đến RUN-08.
- [x] **AC-05.** Migration sạch và nâng cấp dữ liệu cũ thành công. `:verifyModuleBoundaries`, `check` và frontend build pass theo DoD repo. Bảng Actual có kết quả từng case, ngày, commit, môi trường và đường dẫn báo cáo. **Bằng chứng:** REL-01 đến REL-04. [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung)

## 5. Kế hoạch testing — Expected và Actual

### 5.1. Fixture và cách đo

- Dùng JUnit 5 cho selector/contract, `@SpringBootTest` với HTTP thật, Flyway và PostgreSQL 17 Testcontainers cho transaction, idempotency và migration. Tái sử dụng fixture của `GitCommitLinkHttpIntegrationTest` và `GitHubWebhookHttpIntegrationTest`. Dùng secret giả, repo nội bộ R1 thuộc workspace A, R2 thuộc B, hai workspace cùng có `T-001` nhưng khác UUID, thêm A2, task archived và task đã xóa. Dùng fixture fork có head repo khác base.
- Mỗi case HTTP ký đúng bytes và đặt `X-GitHub-Event`, `X-GitHub-Delivery`, `X-Hub-Signature-256`. Assert mã HTTP, trạng thái receipt, Git row, tập association, số outbox và tập payload listener nhận. Probe chỉ đếm ba business event, tách khỏi event resolve. Với test bất đồng bộ, tắt polling nền và gọi một vòng worker có kiểm soát hoặc chờ điều kiện với timeout. Dùng barrier/latch cho race, đồng hồ điều khiển được cho backoff, lease và retention. Không dùng database Compose chứa dữ liệu làm việc.
- **`Chưa chạy`** nghĩa là case T-023 chưa được thực thi. Khi chạy, thay bằng `PASS`, `FAIL`, `PARTIAL` (chỉ một phần biến thể có bằng chứng) hoặc `BLOCKED`, kèm số liệu quan sát và tham chiếu báo cáo. Bằng chứng T-022 được ghi riêng ở §5.6.

### 5.2. Phát đúng sự kiện và payload

| ID | Đầu vào / thao tác | Expected | Actual T-023 |
|---|---|---|---|
| EVT-01 | Push SHA mới, message có `T-001`, branch có `T-002`. | `204`, 1 link đến A1, 1 commit event có đúng UUID, SHA, repo, author và timestamp. | PASS — commit payload/precedence; HTTP suite và hồi quy T-022. |
| EVT-02 | Push một SHA có `T-001 T-0001 T-002`; push thêm SHA khác tham chiếu A1. | SHA đầu có 2 event với UUID khác nhau, scalar `task_id=NULL`. SHA sau có 1 event đến A1. | PASS — 2 event cho SHA đầu, 1 cho SHA sau, scalar NULL. |
| EVT-03 | Message không có key và branch hợp lệ. Chạy biến thể message chỉ có key không resolve. | Biến thể đầu 1 event qua fallback. Biến thể sau 0 event, dù branch có key hợp lệ. | PASS — branch fallback và unresolved không fallback. |
| EVT-04 | Push chỉ có author email, username thiếu, username có ký tự điều khiển, và push từ tác giả khác `sender`. | Dữ liệu hợp lệ vẫn ghi. `author` vắng khi thiếu/không hợp lệ, tác giả hợp lệ lấy từ commit. Email vắng trong payload/outbox/log. | PASS — author/sender tách biệt, missing/control username null, email/log sentinel loại. |
| EVT-05 | Mở PR fork mới, title có A1 và A2; chèn `html_url` giả. | `204`, 2 opened event, đúng UUID workspace A, URL chuẩn từ base repo, `created_at` và `actorId` theo contract. | PASS — fork, hai link, URL chuẩn, created_at, actor null. |
| EVT-06 | Mở PR gắn A1, sửa title sang A2 rồi merge. | Chuỗi event gồm opened(A1), merged(A2), không có event ở bước sửa. Merge dùng `merged_at`. | PASS — opened(A1), merged(A2), metadata không phát. |
| EVT-07 | Đóng PR chưa merge, sau đó `reopened` rồi `synchronize` và `edited`. | DB cập nhật theo từng snapshot hợp lệ, 0 opened/merged event mới ở bốn bước. | PASS — close/reopen/sync/edited không phát thêm. |
| EVT-08 | Mở PR có 0 task, chỉnh PR thêm key rồi merge. | 0 event mở, 0 event sửa, 1 event merge cho task được resolve ở lần merge. | PASS — không link lúc mở; merge phát cho link mới. |
| EVT-09 | PR thiếu hoặc có `null` timestamp chuyên biệt. Chạy thêm biến thể kiểu sai và chuỗi thời gian lỗi. | Fallback về `updated_at` ở hai biến thể đầu. `400` và rollback mọi bảng ở biến thể lỗi. | PASS — missing/null fallback; invalid type/format 400. |
| EVT-10 | Gọi constructor hiện hữu và canonical, round-trip payload outbox. PR opener khác merger, chưa có mapping Auth. | Kiểu, tên và trường event tương thích. Cả hai PR event dùng `actorId=null` khi thiếu mapping, thời điểm/metadata ổn định qua replay. | PASS — constructor commit/PR cũ, canonical, stable snapshot và actor null. |

### 5.3. Trùng, thứ tự và transaction

| ID | Đầu vào / thao tác | Expected | Actual T-023 |
|---|---|---|---|
| REP-01 | Cùng delivery/body hai lần. Cùng body với delivery ID khác. | Replay `204`, 1 receipt hoàn tất, số link/outbox/event không tăng. | PASS — replay HTTP và staging không tăng logical event. |
| REP-02 | Cùng delivery ID với body khác nhưng ký hợp lệ. | `409 WEBHOOK_DELIVERY_CONFLICT`, không ghi dữ liệu hay phát event từ body thứ hai. | PASS — delivery conflict 409 trong hồi quy T-021. |
| REP-03 | Cùng repo và SHA qua hai delivery song song có branch khác nhau, hoặc gửi lại sau khi receipt/outbox đã dọn. | Tập link đầu tiên được giữ, mỗi cặp SHA–task có một logical event. | PASS — concurrent SHA và purge receipt/outbox không phát lại. |
| REP-04 | Một push chứa một commit mới và SHA đã có với metadata mâu thuẫn. | `409 GIT_COMMIT_CONFLICT`, không có phần ghi hoặc event mới từ cả delivery. | PASS — immutable conflict rollback; hồi quy HTTP T-022. |
| REP-05 | PR cùng version và fingerprint. Sau đó gửi version bằng nhau với fingerprint khác. | Bản giống `204` với 0 event mới. Bản khác `409 GIT_PR_VERSION_CONFLICT`, snapshot/link cũ giữ nguyên. | PASS — same version no-op, conflict 409. |
| REP-06 | Merge hợp lệ đến trước opened cũ. Sau đó gửi snapshot `OPEN` có `updated_at` mới hơn. | 1 merged event. Cả hai bản mở không tạo opened event và PR giữ `MERGED`. | PASS — merge-first, opened cũ và mới không phát, giữ MERGED. |
| REP-07 | Merge cùng PR bằng delivery/body khác, `updated_at` mới hơn. Chạy lại sau khi dọn receipt/outbox. | Không có merged event thứ hai, PR giữ trạng thái cuối. | PASS — newer merge và sau purge vẫn 0 event mới. |
| REP-08 | Chèn lỗi sau association đầu tiên và lỗi sau outbox insert, trước commit receipt. Gửi lại khi hết lỗi. | Lần lỗi `503`, các bảng không có phần ghi mới và probe nhận 0. Redelivery thành công đủ đúng số link và event. | PASS — association đầu/outbox/receipt fault rollback, redelivery phục hồi. |
| REP-09 | Resolver thiếu phản hồi hoặc repo lock timeout, sau đó phục hồi. | `503`, 0 receipt hoàn tất và 0 event trước khi phục hồi. Retry thành công đúng một lần về mặt nghiệp vụ. | PASS — zero/double resolver không enqueue, lock timeout 503/0 outbox, retry phục hồi. |
| REP-10 | DB có commit legacy và PR legacy. Thử replay commit, PR đã `MERGED`, PR `OPEN` với version hợp lệ rồi merge, PR có version `NULL`. | Không phát bù sự kiện cũ. PR `OPEN` nhận một merge mới. PR thiếu version giữ `409 GIT_PR_VERSION_REQUIRED` như baseline. | PASS — legacy integrity, không backfill, merge mới hợp lệ, version NULL 409. |
| REP-11 | `edited` đến trước `opened` của cùng PR. Thử bản mở cũ hơn và bản có `updated_at` mới hơn. | PR row đầu được tạo khi xử lý `edited`. Cả hai biến thể sau đều có 0 opened event. Bản cũ không đổi snapshot. | PASS — edited trước opened cũ/mới không phát bù. |
| REP-12 | Hai delivery khác nhau cùng merge một PR, gửi song song vào hai instance dùng chung DB. | Nếu một request bị lock timeout, gửi lại sau khi lock nhả. Kết quả cuối có đúng một logical merged event mỗi task. | PASS — hai backend process, 2 delivery/body khác, 1 merged logical event. |

### 5.4. Bảo mật, giới hạn và vệ sinh dữ liệu

| ID | Đầu vào / thao tác | Expected | Actual T-023 |
|---|---|---|---|
| SEC-01 | HMAC sai/thiếu, body sửa sau khi ký, repo UUID hợp lệ nhưng không khớp binding, base repo ID sai. Thử thêm selector sai định dạng. | Chữ ký hoặc binding sai trả `401`. Selector sai định dạng trả `400`. Không có Git row, link, receipt mới hay event. | PASS — HMAC/signature tamper, binding/base, selector invalid/duplicate; 0 outbox. |
| SEC-02 | JSON lỗi, merged sai kiểu, PR number không khớp. Gửi thêm media type sai, quá cỡ và vượt quota. | Lỗi tương ứng `400`, `415`, `413`, `429`; response `ProblemDetail` không lộ dữ liệu và 0 event mới. | PASS — malformed/type/number/media/body/quota guards, 0 outbox, safe errors. |
| SEC-03 | Repo A gửi key trùng với workspace B, key chỉ B có, task archive hoặc bị xóa. | Chỉ task active hợp lệ ở A có link/event. Payload không có UUID của workspace B. | PASS — scope A/B, archived/deleted/missing bị loại. |
| SEC-04 | Tag push, push xóa nhánh, push rỗng, PR action ngoài scope, ping và `workflow_run`. | Giữ mã hiện hành: các ca ignore `204`, ping mới `200`, `workflow_run` hợp lệ `503` handler chưa sẵn sàng. 0 sự kiện Git nghiệp vụ. | PASS — ignore/ping/workflow_run giữ protocol, 0 business event. |
| SEC-05 | 100 commit × 20 link hợp lệ, rồi biến thể 101 commit, 21 key trên một nguồn, trên 100 key phân biệt hoặc push thiếu commit. | Ca hợp lệ tạo đúng 2.000 outbox và HTTP trong ngưỡng. Ca vượt giới hạn trả `422` và không ghi một phần. | PASS — 2.000 pairs 509ms; overflow atomic, hồi quy distinct/incomplete. |
| SEC-06 | Xóa task/board/repo/workspace khi outbox còn pending, gồm ca đang claim và ca xóa rollback. | Cleanup theo scope. Row đã xóa trước publish không được worker phát. Rollback giữ outbox. Với race đã phát, consumer xử lý task biến mất an toàn. | PASS — task/board/workspace/repo claimed cleanup, rollback/cascade, claim xóa không phát. |

### 5.5. Worker, tích hợp và phát hành

| ID | Đầu vào / thao tác | Expected | Actual T-023 |
|---|---|---|---|
| RUN-01 | Tạm dừng worker, gửi webhook và quan sát từ connection khác trước/sau commit, sau đó chạy worker. | Trước commit không thấy outbox và probe nhận 0. Sau commit thấy row pending, HTTP `204`, worker phát đúng type và chuyển `PUBLISHED`. | PASS — connection khác không thấy trước commit; sau 204 worker phát đúng type. |
| RUN-02 | Dừng app sau commit, trước publish. Khởi động lại với cùng DB. | Row còn nguyên và được worker phát sau restart, không cần webhook mới. | PASS — restart container thật: 3 PENDING còn nguyên rồi chuyển PUBLISHED. |
| RUN-03 | Hai worker cùng claim. Mô phỏng worker chết và hết lease, sau đó worker cũ ghi kết quả muộn. | Claim còn hạn thuộc một worker. Worker mới nhận lại sau hết hạn và token cũ không ghi đè kết quả mới. | PASS — SKIP LOCKED/lease/token tests và hai replica staging. |
| RUN-04 | Listener kiểm thử ném lỗi, bật lại trước khi hết retry. Chạy thêm biến thể hết retry rồi requeue. | Backoff và số lần thử đúng, event được phát sau phục hồi. Ca hết retry vào `FAILED` và có cảnh báo. Requeue giữ cùng snapshot. | PASS — backoff, exhaustion FAILED, requeue và payload ổn định. |
| RUN-05 | Mô phỏng crash sau publish, trước update `PUBLISHED`, dùng Notification listener thật với assignee hợp lệ. | Bus có thể nhận hai lượt cùng payload. DB Notification chỉ có một bản ghi cho assignee và logical event đó. | PASS — crash-after-publish trigger, bus 2 lượt, Notification 1 row. |
| RUN-06 | Gửi ba loại event qua producer thật. Giữ Board listener hiện hữu. PR có task được giao cho thành viên hợp lệ, sau đó thử assignee không còn là thành viên. | Probe nhận đúng ba loại. Notification nhận hai event PR và chỉ ghi cho recipient hợp lệ. Chuyển cột/STOMP nghiệm thu theo T-024. | PASS — ba typed event, Notification thật, former member bị loại. |
| RUN-07 | Chặn listener sau khi worker bắt đầu, gửi tiếp webhook hợp lệ. Đọc metric và kiểm tra log có sentinel secret, email và body. | ACK của webhook độc lập với listener. Backlog/retry có tín hiệu đo. Log/metric không chứa các sentinel. | PASS — listener latch không chặn ACK, gauge/tag/log sentinel scan. |
| RUN-08 | Dọn row `PUBLISHED` đủ tuổi và giữ các row pending/failed, mô phỏng downstream retry đảo thứ tự. | Cleanup đúng trạng thái và giới hạn batch. Giao nhận có thể đảo thứ tự, metadata thời gian giữ nguyên để consumer áp dụng policy. | PARTIAL — retention/1.000 batch/snapshot PASS; downstream ordering thuộc T-024. |
| REL-01 | Flyway trên DB mới và nâng cấp từ V15 có receipt, commit, PR, link legacy. | Migrate/validate pass, dữ liệu legacy giữ nguyên, outbox ban đầu rỗng. | PASS — clean V16/upgrade V15/validate, outbox rỗng, legacy giữ nguyên. |
| REL-02 | Chạy boundary, backend check và frontend build. | Lệnh exit 0, suite bắt buộc có báo cáo và không bị skip vì thiếu Docker/JDK. | PASS — 741 tổng JUnit XML, 0 failure/error/skip; boundary/build/lint exit 0. |
| REL-03 | Repo GitHub staging, hai task thử, push → mở PR → sửa tiêu đề → merge, PR khác chỉ đóng, redeliver một delivery. | Lưu mã HTTP và số event trước/sau replay. Commit/open/merge khớp kỳ vọng, ca sửa/đóng không thêm event nghiệp vụ. | PASS — GitHub push/open/edit/merged PR #4, closed PR #3 và merged redelivery 204/dedup. [Evidence](13_github_demo_actual.md). |
| REL-04 | Rollout staging, tạm dừng và bật lại worker với delivery tiếp tục vào. | Outbox tích lũy khi dừng, xả backlog khi bật, không còn bản ghi lỗi chưa xử lý. | PASS — GitHub thật tạo 3 PENDING, bật/restart hai replica xả đủ, 0 backlog/FAILED. |

### 5.6. Actual baseline và bằng chứng khi nghiệm thu

| Hạng mục | Kết quả quan sát được | Giới hạn bằng chứng |
|---|---|---|
| Code tại `191d6a5` | Ba record `common` đã có. `GitWebhookIngestion` chỉ publish `TaskReferencesResolveRequestedEvent` và chưa phát business Git event. | Đối chiếu tĩnh trong lúc lập plan. |
| Báo cáo JUnit sẵn có ngày 08/11/2026 | `GitPrEventsTest` 2/2, `GitCommitLinkHttpIntegrationTest` 15/15, `NotificationEventIntegrationTest` 33/33. XML có 0 failure/error/skip trong ba suite. | Bằng chứng baseline trước T-023, không dùng để đánh dấu PASS cho case T-023. |
| Chạy lại baseline ngày 09/11/2026 | Wrapper dừng trước khi chạy test vì không mở được file lock Gradle trong `C:\Users\ADMIN\.gradle`. `docker info` cũng báo từ chối quyền truy cập Docker Desktop Linux Engine. | Các case ở §5.2–§5.5 chưa chạy. |
| Phạm vi ticket | API Trello trả `ENOTFOUND api.trello.com`. Plan đối chiếu mô tả và AC từ bản đồng bộ trong repo. | Chưa xác minh trạng thái thẻ trực tiếp. |

Lệnh chạy sau triển khai trên môi trường có JDK toolchain 17 và Docker truy cập được. Hai tên suite `GitDomainEvent...` dưới đây là tên đề xuất cho test sẽ viết.

```powershell
Set-Location backend
.\gradlew.bat :common:test :gitci-impl:test :notification-impl:test --console=plain
.\gradlew.bat :app:test --tests '*GitDomainEvent*' --tests '*GitCommitLinkHttpIntegrationTest' --tests '*GitHubWebhookHttpIntegrationTest' --console=plain
.\gradlew.bat :verifyModuleBoundaries check --console=plain
Set-Location ../frontend
npm run build
```

Lưu JUnit XML/HTML ở `backend/<module>/build/test-results/test/` và `backend/<module>/build/reports/tests/test/`. Với từng case, ghi commit, ngày, profile, đầu vào đã ẩn dữ liệu nhạy cảm, HTTP/outbox/event count quan sát được và đường dẫn bằng chứng. Với staging, dùng delivery ID đã rút gọn và số liệu đã tổng hợp. Ghi rõ lỗi và lần retest nếu Expected khác Actual.

### 5.7. Kết quả triển khai ngày 09/11/2026

Full backend check/boundary PASS với 741 tổng test trong XML (app 293, gitci-impl 37), không failure/error/skip. Frontend build/lint PASS. Staging PostgreSQL riêng và hai backend process xác nhận restart, pause/drain, replay và concurrent HTTP: 7 PUBLISHED, 4 Notification, 0 backlog/failed/incomplete receipt, task column/version giữ nguyên. Ca tối đa 2.000 outbox đo 509ms ở full check cuối. Chi tiết assertions, PARTIAL, lịch sử lỗi/retest và đường dẫn evidence ở [Actual](13_phat_su_kien_git_actual.md).

Chốt lựa chọn KT-01–03: không phát bù opened đến trễ, author chỉ là username đã validate, actorId null khi thiếu mapping xác minh, retention PUBLISHED 7 ngày, at-least-once có lease/token và retry tối đa 8. Không mở rộng sang chuyển cột T-024.

Người dùng đã cho phép demo và đối chiếu ảnh UI. REL-03/04 PASS sau khi nghiệm thu lại merge bằng PR #4: đúng opened(A), merged(B), provider merged_at và merged redelivery 204 không trùng Notification. Tổng cuối staging 17 PUBLISHED/8 Notification, gồm 10 event/4 Notification GitHub thật; 0 backlog/FAILED. Webhook khôi phục, staging dừng giữ dữ liệu. Không cần người dùng tìm thêm delivery #2. [Evidence cuối](13_github_demo_actual.md). Peer review/merge code theo workflow dự án; không tự đánh dấu Trello Done.
