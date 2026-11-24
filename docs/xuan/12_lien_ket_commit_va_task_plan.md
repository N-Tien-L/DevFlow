# T-022 — Kế hoạch liên kết commit, branch và PR với task

**Milestone 3 · Phụ trách:** Xuân Lê (@xuanle2) · **Ngày đối chiếu:** 02/11/2026, Asia/Saigon.
**Trạng thái cập nhật 08/11/2026:** ĐÃ TRIỂN KHAI parser, task key, resolver, commit/PR persistence, cleanup và UI taskKey. Full backend check PASS (708 tests, 0 failures), frontend build/lint PASS; Trello đã cập nhật In Progress và 2/2 AC gốc; demo GitHub thật đã PASS, review/merge còn chờ. [Evidence demo](12_github_demo_actual.md). [Actual/evidence](12_lien_ket_commit_va_task_actual.md) · [Hướng dẫn vận hành](12_huong_dan_lien_ket_commit_task.md).

[Task gốc](12_lien_ket_commit_va_task.md) · [Trello T-022](https://trello.com/c/noosB1vt/27-t-022-build-regex-parser-to-link-git-commits-branch-names-to-task-ids) · [Tổng quan](00_nhiemvu.md) · [Vận hành T-021](11_huong_dan_webhook_github.md).

## 1. Mục tiêu, căn cứ và hiện trạng

### 1.1. Kết quả cần đạt và phạm vi

Từ webhook GitHub đã xác thực, nhận biết mã như `T-003`, `#T-010` trong commit message, tên branch hoặc tiêu đề PR; tìm đúng task trong workspace của repository; lưu dữ liệu Git và liên kết có thể truy vết, không lặp và không lẫn tenant. Nội dung Git là dữ liệu không tin cậy, không phải lệnh cho hệ thống hoặc bằng chứng định danh người dùng DevFlow.

Hai AC gốc vừa đọc từ Trello: trích xuất đúng mã trong `feat(auth): T-003 setup jwt`; bỏ qua an toàn commit không có định dạng task ID. Card ở Backlog, checklist chưa đánh dấu. Nhánh dự kiến khi bắt đầu code: `feature/T-022-git-commit-regex-parser`. Yêu cầu lập plan ngày 02/11 chỉ tạo tài liệu. Theo yêu cầu triển khai ngày 08/11, phạm vi đề xuất bên dưới đã được đưa vào code; tiến độ và gate còn lại xem Actual/evidence.

Phạm vi T-022 gồm parser thuần, policy hợp nhất nguồn, resolver theo workspace, lưu commit/PR và liên kết, ghép handler với cổng T-021, cùng test. Task key và bảng nhiều liên kết là **mở rộng kỹ thuật đề xuất**, cần chốt trước khi triển khai chứ chưa phải yêu cầu gốc đã được nghiệm thu. Việc phát `git.commit_linked`/PR events thuộc [T-023](13_phat_su_kien_git.md); tự chuyển cột thuộc [T-024](14_tu_dong_chuyen_trang_thai_task.md); CI thuộc T-025; hiển thị badge và API đọc phục vụ UI thuộc [T-026](16_hien_thi_git_va_ci_tren_task.md).

### 1.2. Đối chiếu nguồn và khoảng trống

| Nguồn đã đọc | Hiện trạng và tác động tới plan |
|---|---|
| [ARCHITECTURE](../ARCHITECTURE.md), mục 2/4/6/9.2/10; AGENTS.md | Board sở hữu task, GitCI sở hữu Git; giao tiếp liên module qua typed `DevFlowEvent`; không truy cập internal hoặc bảng Board từ GitCI |
| [PRODUCT_SPEC](../PRODUCT_SPEC.md), mục 5.2a/8; [IMPLEMENTATION_PLAN](../IMPLEMENTATION_PLAN.md), Phase 3/DoD | Tên branch/commit dùng mã task; chất lượng gồm validation, tenant isolation, idempotency, privacy, observability và kiểm thử |
| [TaskEntity](../../backend/board-impl/src/main/java/io/devflow/board/internal/entity/TaskEntity.java), [TaskResponse](../../backend/board-api/src/main/java/io/devflow/board/api/TaskResponse.java), [V2](../../backend/app/src/main/resources/db/migration/V2__board_schema.sql) | Task dùng UUID; chưa có trường `T-xxx` hay sequence theo workspace. Không thể biến chuỗi T-003 thành UUID bằng regex |
| [BoardApi](../../backend/board-api/src/main/java/io/devflow/board/api/BoardApi.java), [BoardService](../../backend/board-impl/src/main/java/io/devflow/board/internal/BoardService.java) | Chỉ có tìm bằng UUID hoặc danh sách workspace; không có resolver mã task. Comment cũ nói gọi API đồng bộ không thay thế quy tắc event hiện hành |
| [WorkspaceMembershipCheckRequestedEvent](../../backend/common/src/main/java/io/devflow/common/event/WorkspaceMembershipCheckRequestedEvent.java) | Có tiền lệ request/decision event đồng bộ, phải đúng một phản hồi và fail closed; dùng làm mẫu contract, không dùng membership của sender GitHub |
| [GitHubWebhookService](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitHubWebhookService.java) | Đã có HMAC/binding/envelope/receipt cho ping. Push và PR còn trả 503; transaction `storePing` chưa có handler tổng quát. Không giả định đã có `VerifiedGitHubWebhook` chỉ vì plan T-021 từng đề xuất |
| [GitCiService](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitCiService.java) | Stub; chưa có commit parser/entity/repository GitCI hoặc luồng liên kết thật |
| [V6](../../backend/app/src/main/resources/db/migration/V6__gitci_schema.sql) | Unique repo+SHA và repo+PR number; mỗi hàng chỉ có một `task_id` nullable. Không đủ biểu diễn nhiều task mà không mất dữ liệu |
| [V13 receipt](../../backend/app/src/main/resources/db/migration/V13__github_webhook_receipts.sql) | Dedupe delivery và digest hữu hạn; không thay thế unique Git business keys hoặc durability liên module |
| [GitCommitLinkedEvent](../../backend/common/src/main/java/io/devflow/common/event/GitCommitLinkedEvent.java), [DevFlowEvent](../../backend/common/src/main/java/io/devflow/common/event/DevFlowEvent.java) | Event đã có nhưng chưa chứng minh producer chạy. DevFlowEvent thực tế là interface; contract mới implement interface, không sao chép template class cũ |

Baseline là working tree hiện tại trên `feature/T-021-webhook-hmac-controller`, có thay đổi T-021 chưa commit trong checkout và nhánh PR T-021 riêng từ develop. Khi bắt đầu T-022 phải lấy baseline tích hợp đã review; việc thấy file trong checkout không chứng minh T-021 đã merge. Migration mới chỉ chọn version sau khi kiểm tra nhánh tích hợp; hiện có V1–V13, không sửa lại V2/V6/V13.

## 2. Kế hoạch nghiệp vụ — diễn tả luồng bằng ngôn ngữ

### NV-01. Team nhận biết mã task

Mỗi task cần có một mã ổn định, hiển thị được cho developer, trong phạm vi workspace. Developer dùng đúng mã này trong nội dung Git. Các mã T-022 trên Trello là mã ticket của dự án, không tự động trở thành mã task trong database DevFlow. Task được chuyển cột, đổi tiêu đề hay đổi người làm vẫn giữ mã cũ; mã đã xóa không cấp lại cho task khác.

### NV-02. Liên kết commit từ nội dung trực tiếp

Developer push commit `feat(auth): T-003 setup jwt`. Sau khi xác minh webhook và repository, hệ thống trích mã T-003, tìm trong workspace của repository rồi gắn commit với task tìm được. Cùng SHA ở repository khác là một bản ghi Git riêng; không lấy nhầm task từ workspace khác dù có cùng mã.

### NV-03. Dùng tên branch làm nguồn dự phòng

Commit không có mã nhưng nằm trên `feature/T-010-board` có thể liên kết với T-010. Nếu commit đã nhắc T-003 thì mã branch không được tự gắn thêm T-010. Commit chứa mã đúng định dạng nhưng chưa tồn tại cũng không tự chuyển sang mã branch: hệ thống giữ tình trạng chưa giải quyết, tránh gắn nhầm nhiệm vụ.

### NV-04. Nhận biết PR

PR dùng mã trong title; nếu title không có mã thì xem source branch. Không dùng target branch, tên repository, body PR, sender hoặc mã trong metadata không thuộc allowlist để tự mở rộng liên kết. PR từ fork vẫn chỉ giải task ở workspace của repository đích đã đăng ký; không sử dụng repository của người gửi để chọn tenant.

### NV-05. Nhiều mã, mã sai hoặc không tìm thấy task

Một nội dung có thể tham chiếu nhiều task; mã lặp chỉ tạo một liên kết. Task hợp lệ được liên kết độc lập; mã không tồn tại hoặc task đã bị xóa không tạo liên kết giả. Không có mã là trường hợp bình thường: vẫn có thể lưu commit hợp lệ để giữ lịch sử Git, nhưng không có quan hệ task và không tự chuyển cột. Lỗi hệ thống tra cứu khác với kết quả “không tìm thấy”: lỗi hạ tầng phải được retry, không bỏ qua như thành công.

### NV-06. Gửi lại, force push và chỉnh sửa PR

Gửi lại webhook không tăng số commit hay liên kết. Một commit đã gắn theo branch A không bị đổi nhiệm vụ chỉ vì cùng SHA được push lên branch B. PR có title/source branch được sửa có thể thay tập liên kết theo phiên bản mới hơn; webhook cũ đến muộn không được khôi phục liên kết cũ. Force push hoặc xóa branch không tự xóa lịch sử commit/task. T-022 không dùng hoạt động này để chuyển trạng thái task.

### NV-07. Khắc phục lỗi và nghiệm thu

Khi DB hoặc Board resolver lỗi, không xác nhận xử lý xong; retry phải xử lý lại được mà không tạo trùng. Nếu task được tạo sau lần nhận commit, T-022 không tự quét lịch sử để liên kết lại; yêu cầu reconcile/backfill liên kết là phạm vi bổ sung cần thiết kế riêng. Người vận hành chỉ xem reason code/correlation và metadata tối thiểu, không xem secret hay toàn bộ payload qua log.

## 3. Kế hoạch kỹ thuật và quyết định cần chốt

### KT-01. Định danh task — gate trước khi viết linker

Đề xuất **Board sở hữu mã số task theo workspace**, số 1–9999, render tối thiểu ba chữ số: T-001…T-9999. Parser nhận ba hoặc bốn chữ số ASCII; T-003 và T-0003 cùng số 3, canonical T-003. T-000/T-0000 không hợp lệ. Chưa hỗ trợ số trên 9999; hết dải phải báo lỗi cấp mã rõ, không quay vòng hoặc cấp trùng. Nếu muốn độ dài lớn hơn phải sửa contract/parser cùng test trước khi bật.

Board bổ sung bảng/field mapping task UUID ↔ workspace+task number, unique `(workspace_id, task_number)` và unique task UUID; FK nội bộ tới task với cleanup phù hợp. Workspace ID trên mapping phải khớp workspace qua task→column→board; Board kiểm tra khi tạo, backfill và khi resolve. Bộ đếm theo workspace được tăng atomically trong transaction tạo task, không dùng `MAX+1`, không tái sử dụng mã sau xóa. Chuyển task xuyên workspace chưa là luồng được hỗ trợ: phải từ chối hoặc có thiết kế đổi mã và cleanup riêng.

Backfill task cũ theo thứ tự ổn định `(created_at, id)` trong từng workspace, có dry-run, kiểm count/unique và kiểm dải trước khi migrate; mã được sinh không mặc nhiên khớp mã Trello. Không dò mã bằng title/description hoặc thứ tự hiển thị. Cần đưa mã vào response Board và hướng dẫn sử dụng tối thiểu; UI trưng bày hoàn chỉnh có thể giao ticket phối hợp, nhưng không nghiệm thu end-to-end nếu developer không có cách biết mã đúng. Đây là thay đổi Board/API/schema ngoài AC regex gốc, phải thể hiện trong PR và review với chủ module.

### KT-02. Parser thuần, grammar và giới hạn

- Service stateless `TaskReferenceParser` trong GitCI; không truy cập DB, network, SecurityContext hoặc phát event. Trả tập canonical có thứ tự xuất hiện và metadata nguồn tối thiểu; không trả toàn bộ input vào log.
- Grammar nhận `T-` hoa, `#` tùy chọn, đúng 3–4 chữ số ASCII. Không tự uppercase, Unicode-normalize hoặc đổi dấu nối khác thành `-`; khoảng trắng bên ngoài là separator nhưng không chấp nhận `T- 003`.
- Ranh giới token phải loại match con trong `AT-003`, `T-00345`, `T-003abc`, `foo_T-003`, `##T-003`. Chấp nhận `/`, `-` và dấu câu bên ngoài để đọc `feature/T-010-board`, `(T-003)`, `#T-003`. Chính sách đề xuất dùng Unicode letter/number và `_`, `#` làm ký tự chặn bên trái; Unicode letter/number và `_` chặn bên phải. Viết test boundary trước khi chốt Pattern, không dùng riêng `T-\d{3,4}` rồi nhận substring sai.
- Dùng Pattern compile một lần, Matcher cục bộ; tránh nested quantifier và backtracking theo input. Chưa parse URL, HTML hoặc Markdown theo semantic: mã hợp lệ trong chuỗi commit/title vẫn là tham chiếu theo convention, không xem đó là bằng chứng quyền hạn.
- Giới hạn đề xuất: commit message 65.536 byte UTF-8 (khớp V6), branch và PR title 255 ký tự, tối đa 20 mã khác nhau mỗi nguồn và 100 mã trong một delivery. Vượt bound thì từ chối cả delivery bằng 413/422 đã chốt ở handler, không cắt chuỗi rồi liên kết một phần; không truncate message trước parse để mất mã cuối. Regex không thay thế kiểm byte/array/depth của JSON.

### KT-03. Policy nguồn và dữ liệu chuẩn hóa

| Đối tượng | Ưu tiên và kết quả |
|---|---|
| Một commit trong push | Mã trong toàn bộ `commit.message`; chỉ nếu tập mã hợp lệ rỗng mới dùng branch. Mã đúng syntax nhưng unresolved vẫn chặn fallback |
| Push branch | Chỉ ref bắt đầu chính xác `refs/heads/`; bỏ prefix một lần. Không suy ra task từ tag ref, `refs/pull/*` hoặc chuỗi trông giống branch |
| PR | Title trước, `pull_request.head.ref` dự phòng; không parse `base.ref`, body hoặc SHA để đoán task |
| Nhiều commit cùng push | Áp policy độc lập cho từng SHA; không gắn mã của commit A sang commit B |
| PR sửa title | Tính lại liên kết ở snapshot mới hơn; lưu version GitHub và fingerprint nguồn đã chuẩn hóa để phân biệt duplicate/conflict |
| Branch-only | Có thể parse để làm nguồn dự phòng, không dựng commit giả từ `after` khi không có commit record hợp lệ |

Đầu vào linker phải là envelope nội bộ tạo sau HMAC/binding/envelope validation của T-021, gồm repository/workspace ID từ DB, event/delivery/digest, data snapshot có defensive copy. Không nhận cờ `verified=true` của client. Refactor `storePing` thành transaction xử lý webhook tổng quát, giữ nguyên behavior ping và các guard. Không thêm route public bỏ qua T-021 hoặc alias legacy `/webhooks/git`.

### KT-04. Resolver theo workspace qua typed request/decision event

Không gọi `BoardApi` trực tiếp từ GitCI dù task gốc có nói “public board-api”. Theo AGENTS/ARCHITECTURE hiện hành, đề xuất `TaskReferencesResolveRequestedEvent` trong common, implement `DevFlowEvent`, khai báo tên trong EventTypes. Input chỉ workspace từ binding, tập canonical bounded và correlation ID; Board listener đồng bộ tra cứu theo workspace và trả mapping key→UUID/outcome.

Contract phải có đúng một response snapshot bất biến; zero listener, hai listener, exception hoặc trả UUID ngoài workspace → unavailable và fail closed. `NOT_FOUND`/`INACTIVE` khác `UNAVAILABLE`; không biến outage thành một map rỗng. Listener không `@Async`, không network, không phát domain notification và không đọc Auth bằng sender GitHub. Xác thực integration cho phép xử lý repository, không chứng minh sender có tài khoản DevFlow.

Batch resolve, tránh query từng commit/key. Board chỉ trả UUID, key và eligibility, không title, email hoặc metadata task ở workspace khác. Loại task dưới board archived; task ở cột DONE vẫn được tham chiếu nhưng T-022 không đổi cột. Có cùng mã ở nhiều workspace là hợp lệ; nếu bất thường nhiều task cho cùng mã trong một workspace thì fail closed, không chọn hàng đầu tiên.

### KT-05. Schema GitCI và nhiều liên kết

Đề xuất migration mới cho `git_commit_task_links(commit_id, task_id, source_kind, created_at)` và `git_pull_request_task_links(pull_request_id, task_id, source_kind, created_at)`, unique parent+task; FK chỉ tới parent do GitCI sở hữu, ON DELETE CASCADE. Task UUID là soft reference; không FK hoặc JPA relationship xuyên module. Index task_id phục vụ tra cứu theo task và cleanup. Source kind là allowlist MESSAGE/BRANCH hoặc TITLE/BRANCH; chưa cần lưu raw matched text hay offset.

V6 `task_id` không đủ cho nhiều task: đề xuất giữ cột tương thích tạm thời, mirror UUID khi có đúng một link, NULL khi zero/multiple; bảng link là nguồn thật. Các consumer mới không dùng riêng scalar; mô tả chuyển đổi/backfill và loại cột cũ ở migration sau. Nếu scope được chốt chỉ cho một link, nhiều mã phải có outcome AMBIGUOUS được test, tuyệt đối không chọn mã đầu tiên. Không triển khai cả hai policy tùy tiện trong cùng release.

Commit được upsert theo `(repository_id, sha)`; SHA full lowercase 40/64 hex, không all-zero, không abbreviated. Giữ giới hạn text theo V6, timestamp chuẩn UTC; không tin author_email hoặc sender là DevFlow user. Mặc định không lưu email tác giả nếu không cần; schema cho phép NULL. Commit không có mã vẫn có row, zero links. Không tự tải commit URL/compare URL từ payload.

PR upsert theo `(repository_id, pr_number)` với PR number dương, source/target branch và title bounded, provider_updated_at bắt buộc cho policy ordering. Chỉ action đã chốt (`opened`, `reopened`, `edited`, `synchronize`, `closed`) đi vào persistence; closed+merged=true mới ghi MERGED, không coi closed đơn thuần là merge. PR không chứa mã vẫn lưu metadata hợp lệ. Không tự mở PR production handler cho action chưa có policy.

URL được tạo từ binding repository đã kiểm tra host GitHub.com và PR number/SHA hợp lệ, không lấy URL tùy ý hoặc base URL của fork để phục vụ UI. Không truy cập URL đó trong callback; GitHub API token không cần cho parser/persistence baseline này.

### KT-06. Transaction, concurrency và vòng đời

```text
T-021 guard + HMAC + repository binding
  -> validate toàn delivery + parse bounded + chọn nguồn
  -> transaction: recheck/lock repository binding + claim receipt
       -> Board resolve batch qua typed event đồng bộ
       -> upsert Git rows + ghi link idempotent
       -> completed receipt
  -> commit -> ACK 204
```

- Giữ transaction synchronous, timeout/lock timeout hữu hạn, không executor tiếp tục ghi sau khi báo lỗi. Receipt completed và Git/link rows cùng transaction; mọi lỗi DB/resolver/runtime trước commit phải rollback cả lô. Một item malformed làm rollback delivery; mã không tìm thấy task là kết quả bình thường và không rollback các item hợp lệ khác.
- `INSERT ... ON CONFLICT` hoặc cơ chế upsert đúng PostgreSQL, không catch unique violation rồi query tiếp trên transaction đã aborted. Hai delivery khác ID/digest nhưng cùng SHA vẫn được business-key dedupe. Không chỉ dựa retention bảy ngày của receipt.
- Với commit đã tồn tại: cùng dữ liệu immutable là no-op. Metadata mâu thuẫn không overwrite im lặng; trả conflict và không đổi receipt cũ. Khác branch không tự chuyển task của commit đã lưu; link nguồn BRANCH lần nhận đầu được giữ. Replay không dùng để reconcile task từng chưa tồn tại.
- PR dùng so sánh `provider_updated_at` trong update atomic; older snapshot ignored; bằng timestamp và cùng fingerprint là duplicate, khác fingerprint là conflict cần retry/đối chiếu, không dùng delivery timestamp hay thứ tự nhận làm phiên bản provider. Snapshot mới thay tập links trong cùng transaction; T-023 phải xử lý semantic link-added/link-removed khi được ghép.
- Board phải giữ eligibility task ổn định đến commit: trong listener transaction chung, khóa board rồi task theo UUID tăng dần, phối hợp thứ tự lock hiện có. GitCI không tự SELECT bảng Board. Test task delete/archive đồng thời; nếu không giữ được consistency thì không bật handler trước khi chốt race policy.
- Khi task/workspace bị xóa, GitCI cleanup bằng typed event do module chủ sở hữu phát; nếu chưa có task-deleted contract thì đưa bổ sung vào scope review. Xóa link/tẩy PII phù hợp và đọc kết quả phải revalidate eligibility; không xóa Git history chỉ vì branch bị xóa. Không cam kết soft reference tự có toàn vẹn như FK.
- Không phát business Git events T-023 trong T-022; resolver control event không phải `git.commit_linked`. Để handoff, service trả snapshot các link thay đổi sau persist. Ghép outbox/idempotent consumer trước khi cam kết tác động liên module không mất; Spring event bus không tự durable.

### KT-07. Provider edge cases, lỗi và vận hành

Push `deleted=true` hoặc tag push được xác thực rồi ignored, không dùng all-zero `after` tạo commit. Empty commits có thể ignored; payload có dấu hiệu danh sách không đầy đủ hoặc vượt max batch thì không ACK như đã ingest toàn bộ. Baseline không fetch phần thiếu bằng GitHub API; trả lỗi rõ và có runbook recovery. Giới hạn đề xuất 100 commits/delivery; phải đối chiếu payload thực và policy incomplete list trước rollout. Không hứa nhận đủ mọi lịch sử chỉ nhờ webhook.

PR từ fork: binding theo repository đích, validate `base.repo.id` khớp; head repo không là tenant authority. Branch/source bị thiếu hoặc action/body không tương ứng → 400. Payload có key trùng, số quá dài, invalid UTF-8 và trailing JSON tiếp tục do T-021 chặn. Không mutate raw bytes trước HMAC. Không tái dùng secret đã xuất hiện trong chat; demo cần secret mới tại môi trường chạy.

| Tình huống | Kết quả đề xuất và ảnh hưởng |
|---|---|
| HMAC/repository không hợp lệ | 401 theo T-021; không parser/resolver/receipt thành công |
| JSON/envelope/field bắt buộc sai | 400, error code cố định, rollback |
| Vượt body hoặc semantic batch/text/key limit | 413 hoặc 422 tương ứng, không ghi một phần; response không echo input |
| Không có mã hoặc task không tìm thấy | 204 sau commit metadata Git hợp lệ; zero liên kết cho mã đó |
| Delivery/business duplicate | 204, không tăng row/link, không phát business event |
| Metadata conflict/equal-version PR conflict | 409, không overwrite |
| Board resolver/DB/lock unavailable | 503 + Retry-After; rollback, operator redelivery; không hứa GitHub tự retry |
| Handler chưa ghép/feature tắt | 503, không ACK giả |

Giữ ProblemDetail/correlation của T-021. Metrics có counter parsed/resolved/unresolved/duplicate/conflict/rejected và timer parse/resolve/persist, chỉ label allowlist nguồn/outcome; không repo/task/code/IP/email hoặc input làm label. Log reason, count, duration, correlation; không raw message/title/ref, signature, payload hoặc secret. Timeout DB và quota phải đo lại khi batch resolve làm callback dài hơn; mục tiêu hồi đáp dưới tám giây trong fixture chuẩn, chưa là SLA.

## 4. Trình tự triển khai và bàn giao

| Bước | Công việc | Gate / bằng chứng |
|---|---|---|
| K1 | Chốt task key, canonical/zero-padding, multi-link và source policy với Board/GitCI | Quyết định schema/contract; không giả định mã Trello là UUID DevFlow |
| K2 | Board mapping/allocator/backfill và typed batch resolver trong common/Board | Migration trên DB clone, tests tenant/concurrency và đúng một decision |
| K3 | Parser thuần và mapper push/PR, limits, policy | Unit fixtures đầy đủ; không cần secret/API key thật |
| K4 | GitCI persistence/association migration và receipt transaction tổng quát | PostgreSQL unique/upsert/rollback/version ordering tests |
| K5 | Ghép handler sau T-021; chạy HTTP security + failure injection | Commit-before-ACK, HMAC regression, no event/task move từ T-022 |
| K6 | Full check, performance sample và demo repo riêng nếu đủ cấu hình | Actual từng case, không dùng test T-021 cũ làm PASS T-022 |
| K7 | PR vào develop, review boundary/DDL/privacy, handoff T-023/T-024/T-026 | Bằng chứng CI + review/merge rồi cập nhật Trello theo DoD |

Artifact dự kiến: `TaskReferenceParser`, `GitReferenceSourcePolicy`, mapper/verified envelope, link service và persistence trong GitCI; typed resolver event trong common; key allocator/lookup listener trong Board; migrations mới tại app và tests. Không thêm JPA vào `*-api`, không làm frontend Git badge hoặc CI log ingest trong task này. Nếu task-key prerequisite không được duyệt, chỉ có thể nghiệm thu parser thuần theo AC gốc; phải ghi rõ full linking chưa hoàn tất.

## 5. Tiêu chí hoàn thành

### 5.1. AC gốc

- [x] AC01: `feat(auth): T-003 setup jwt` cho đúng mã T-003; chuỗi không có mã không gây exception hoặc liên kết giả.
- [x] AC02: Commit không chứa định dạng task ID được bỏ qua an toàn về liên kết; policy fallback branch và lưu metadata được mô tả/test, không tự chuyển task.

### 5.2. Gate bổ sung cho chức năng liên kết hoàn chỉnh

- [ ] AC03: Có task key bền vững và resolver thật; backfill, concurrent allocation, zero-padding và dải số được nghiệm thu.
- [ ] AC04: Nguồn commit/branch/PR rõ, dedupe và multi-link không làm mất mã hoặc liên kết chéo commit.
- [ ] AC05: Workspace lấy từ repository binding; foreign/ambiguous/inactive tasks fail closed; task delete/archive race được kiểm chứng.
- [ ] AC06: Data constraints/soft references/privacy/cleanup nhất quán; migration forward không sửa migration cũ.
- [ ] AC07: Receipt, Git rows và link rows atomic; duplicates/concurrent/reordered deliveries/replay ngoài TTL không tạo sai dữ liệu.
- [ ] AC08: Bounded payload/regex/batch/query/timeout; lỗi an toàn, metric cardinality hữu hạn; không lưu/log credential hoặc PII không cần.
- [ ] AC09: HTTP thật + PostgreSQL thật chứng minh positive/negative path; không gọi chéo impl, không ghi Board state hay phát business Git event trong T-022.
- [ ] AC10: Bộ test đạt, `gradlew check`/boundary và frontend build/lint theo DoD đạt trên commit cuối; PR review và squash merge develop; Actual/evidence đầy đủ rồi mới Done.

## 6. Kế hoạch testing — Expected và Actual

### 6.1. Harness và nguyên tắc

**Toàn bộ case dưới đây có Actual ban đầu là CHƯA CHẠY.** Việc đọc code/Trello và kiểm tra tài liệu không là runtime test. Khi triển khai cập nhật từng case bằng PASS/FAIL/BLOCKED/SKIPPED, thời gian, commit, fixture, số đo và đường dẫn report; không đánh dấu PASS từ expected.

Unit dùng JUnit5 parameterized tests và clock giả cho version/retention; fixture deterministic. Integration app RANDOM_PORT đi qua security/filter/HMAC thật; test PostgreSQL17 Testcontainers, Flyway production migrations, không thay upsert/lock tests bằng H2. Fixture gồm workspace A/B có cùng mã task, repository A/B có cùng remote repo ID nhưng secret khác, board archived, task DONE, task đã xóa, repo fork và task không tồn tại.

Với reject: assert handler/resolver call phù hợp với điểm chặn, business events=0 và before/after Git/link/receipt không thay đổi. Với success: kiểm chính xác tập UUID/source và count, receipt completed sau commit; T-022 không đổi task column/version hoặc tạo notification. Resolver control event được tính riêng, không coi “zero events” là cấm event tra cứu.

### 6.2. Parser và policy nguồn

| ID | Đầu vào / tình huống | Expected | Actual |
|---|---|---|---|
| RX01 | `feat(auth): T-003 setup jwt` | Một mã T-003, AC gốc | CHƯA CHẠY |
| RX02 | `fix #T-010`, branch `feature/T-010-board`, PR `(T-010)` | Cùng canonical T-010 | CHƯA CHẠY |
| RX03 | Mã 3/4 chữ số: T-001, T-9999 | Nhận đủ hai biên | CHƯA CHẠY |
| RX04 | T-003 và T-0003 lặp cùng chuỗi | Một canonical T-003 | CHƯA CHẠY |
| RX05 | T-000, T-0000, T-01, T-10000, T-00345 | Không match con/mã zero | CHƯA CHẠY |
| RX06 | AT-003, T-003abc, foo_T-003, ##T-003 | Không match theo boundary policy | CHƯA CHẠY |
| RX07 | t-003, T–003, T- 003, chữ số Unicode | Không normalize thành mã hợp lệ | CHƯA CHẠY |
| RX08 | Chuỗi Việt/emoji có dấu câu phân cách T-003; chữ cái Unicode dính token | Case phân cách nhận; token dính chữ bị loại | CHƯA CHẠY |
| RX09 | Null/empty/whitespace/không có mã | Tập rỗng, không exception | CHƯA CHẠY |
| RX10 | Nhiều dòng, mã đầu/cuối, T-001 và T-002 | Trích đủ, giữ thứ tự ổn định, không lặp | CHƯA CHẠY |
| RX11 | Message đúng 65.536 byte và vượt một byte; multibyte | Biên đúng nhận, vượt reject trước parse/persist | CHƯA CHẠY |
| RX12 | Branch/title 255 và 256 ký tự; 20/21 mã unique | Bound đúng; không cắt input để nhận một phần | CHƯA CHẠY |
| RX13 | Input dài có hàng nghìn prefix gần-match | Hoàn tất trong budget, không catastrophic backtracking | CHƯA CHẠY |
| RX14 | Parser được gọi đồng thời nhiều input | Không trộn tập mã giữa request | CHƯA CHẠY |
| SP01 | Commit có mã A, branch mã B | Chỉ A | CHƯA CHẠY |
| SP02 | Commit không mã, branch mã B | B từ BRANCH | CHƯA CHẠY |
| SP03 | Commit mã hợp lệ không tồn tại, branch mã B tồn tại | Unresolved commit key; không fallback B | CHƯA CHẠY |
| SP04 | Title PR có mã A, head branch B, base branch C | Chỉ A | CHƯA CHẠY |
| SP05 | Title không mã, head branch B | B, không parse body/base branch | CHƯA CHẠY |
| SP06 | Hai commit khác mã trong cùng push | Mỗi commit có tập link riêng | CHƯA CHẠY |
| SP07 | Tag ref, branch delete, empty commits | Ignored có reason; không tạo commit từ zero SHA | CHƯA CHẠY |

### 6.3. Task key, resolver và tenant isolation

| ID | Tình huống | Expected | Actual |
|---|---|---|---|
| RS01 | Cấp mã đồng thời trong cùng workspace | Unique, tăng atomically, không MAX+1 race | CHƯA CHẠY |
| RS02 | Cùng số trong A/B; repo thuộc A | Chỉ UUID ở A | CHƯA CHẠY |
| RS03 | Nhiều board trong A cùng yêu cầu mã | Một namespace workspace, không trùng | CHƯA CHẠY |
| RS04 | Backfill task cũ; chạy migration lại/validate | Mapping ổn định, constraint đúng, không đổi title/column | CHƯA CHẠY |
| RS05 | Số cuối 9999 và lần cấp tiếp; xóa rồi cấp | Báo hết dải; không tái dùng mã xóa | CHƯA CHẠY |
| RS06 | Key thiếu/task xóa/board archived/task DONE | Missing/inactive không link; DONE hợp lệ, không đổi trạng thái | CHƯA CHẠY |
| RS07 | Không listener, hai listener, listener exception | 503, rollback; không rỗng giả | CHƯA CHẠY |
| RS08 | Resolver trả foreign UUID hoặc mapping sai key | Reject/fail closed, zero links | CHƯA CHẠY |
| RS09 | Tập A hợp lệ + B missing + A lặp | Một link A, B unresolved, không lặp | CHƯA CHẠY |
| RS10 | Task delete/archive khi resolve và persist | Lock protocol nhất quán, không commit link vừa được coi eligible nhưng đã invalid | CHƯA CHẠY |
| RS11 | Task chuyển column/title trong cùng workspace | Key không đổi, resolver vẫn đúng | CHƯA CHẠY |
| RS12 | Task chuyển xuyên workspace hoặc mapping bị sửa trái phép | Từ chối/không trả cross-workspace mapping | CHƯA CHẠY |
| RS13 | Commit sender/author trùng email người dùng | Không tự gán quyền/actorId | CHƯA CHẠY |

### 6.4. Persistence, transactions và provider ordering

| ID | Tình huống | Expected | Actual |
|---|---|---|---|
| DB01 | Commit mới, zero/one/multiple task keys | Một Git row; tập link đúng; scalar mirror đúng policy | CHƯA CHẠY |
| DB02 | Duplicate delivery và duplicate body khác delivery ID | 204, row/link không tăng | CHƯA CHẠY |
| DB03 | Cùng SHA, payload/delivery khác; nhiều instance đồng thời | Một commit mỗi repo, links unique; no unique-error transaction corruption | CHƯA CHẠY |
| DB04 | Cùng SHA ở hai repo/workspace | Hai Git rows độc lập, không trộn task | CHƯA CHẠY |
| DB05 | Cùng SHA từ branch A rồi B | Không tự đổi tập liên kết đã lưu | CHƯA CHẠY |
| DB06 | Commit SHA invalid/zero/abbreviated; timestamp lỗi; field quá bound | Reject toàn lô, không receipt completed | CHƯA CHẠY |
| DB07 | Item thứ hai malformed hoặc lỗi ghi sau link đầu tiên | Rollback mọi commit/link/receipt trong delivery | CHƯA CHẠY |
| DB08 | Lỗi trước commit; crash/timeout; retry | Không ACK giả, retry ghi đúng một lần | CHƯA CHẠY |
| DB09 | Commit xong nhưng response mất, redelivery | Dedupe, không gọi nghiệp vụ lại | CHƯA CHẠY |
| DB10 | Hết retention receipt rồi replay cùng Git data | Business unique keys vẫn chống trùng | CHƯA CHẠY |
| DB11 | Sửa title PR A→B ở version mới | Thay links atomic; không còn link A nếu không xuất hiện | CHƯA CHẠY |
| DB12 | Snapshot PR cũ tới sau mới | Không lùi metadata/status/link | CHƯA CHẠY |
| DB13 | PR cùng timestamp cùng fingerprint/khác fingerprint | Duplicate/no-op hoặc 409, không last-writer-wins ngẫu nhiên | CHƯA CHẠY |
| DB14 | PR từ fork, base repo khác binding hoặc head repo giả tenant | Base hợp lệ mới resolve theo workspace binding; mismatch reject | CHƯA CHẠY |
| DB15 | PR closed merged=false/true và action lạ | CLOSED/MERGED đúng; action chưa hỗ trợ ignored hoặc not-ready theo policy | CHƯA CHẠY |
| DB16 | Task/workspace/repo deletion cleanup | Xóa association phù hợp; cascade chỉ trong GitCI; không PII tồn đọng trái policy | CHƯA CHẠY |
| DB17 | Push thiếu danh sách đầy đủ hoặc vượt batch 100/101 | Không ghi thiếu rồi báo đã ingest toàn bộ; lỗi có reason và recovery | CHƯA CHẠY |
| DB18 | Deadlock/lock timeout/DB unavailable | 503 hữu hạn, rollback và retry được | CHƯA CHẠY |

### 6.5. HTTP, bảo mật, giới hạn và hồi quy

| ID | Tình huống | Expected | Actual |
|---|---|---|---|
| IT01 | Signed push qua HTTP thật, resolver/persistence thật | 204 sau commit; links đúng UUID, task state không đổi | CHƯA CHẠY |
| IT02 | Signed PR open/edit/closed qua HTTP | Metadata/link theo version; chưa phát business event | CHƯA CHẠY |
| IT03 | Signature sai/thiếu, body tamper, repo mismatch | 401 theo T-021, không gọi resolver hoặc ghi Git | CHƯA CHẠY |
| IT04 | Body oversized/chunked, charset sai, JSON duplicate/trailing/deep | Guard reject bounded; không tăng heap tùy ý/không lộ input | CHƯA CHẠY |
| IT05 | JWT rác/cookie/Turnstile header trên callback; legacy route | HMAC vẫn quyết định; route khác giữ auth | CHƯA CHẠY |
| IT06 | Resolver giả làm response success nhưng thiếu key eligibility | Contract reject, không liên kết tùy ý | CHƯA CHẠY |
| IT07 | Commit chứa HTML/script/SQL/URL hoặc text giống prompt | Chỉ dữ liệu, bind SQL params; không chạy lệnh/fetch URL; không raw log | CHƯA CHẠY |
| IT08 | Secret/email/message/title độc nhất trong fixture lỗi | Không xuất hiện trong ProblemDetail/log/metric labels | CHƯA CHẠY |
| IT09 | 100 commits, 100 keys tổng; vượt limit; nhiều request đồng thời | Query bounded batch, rollback khi quá bound; quota/in-flight có tác dụng | CHƯA CHẠY |
| IT10 | Đo parse/DB/total trên fixture chuẩn và tải nhỏ | Ghi P50/P95/max, query count/heap; target callback dưới 8s; không suy ra SLA | CHƯA CHẠY |
| IT11 | Body ngừng gửi và gửi nhỏ giọt qua proxy/direct backend | Đo giới hạn thật; ghi gap absolute upload timeout, không gọi idle timeout là tổng budget | CHƯA CHẠY |
| IT12 | Hồi quy ping, Auth/Board, account cleanup, boundary | Behavior cũ giữ đúng; full suite trên commit cuối đạt | CHƯA CHẠY |
| IT13 | GitHub repo demo: tạo task/key, push, PR, redeliver | DB Git/link đúng, task chưa tự chuyển bởi T-022; evidence che dữ liệu nhạy cảm | CHƯA CHẠY |

### 6.6. Lệnh và bằng chứng cần lưu

Unit/module tests tại gitci-impl, board-impl và common; HTTP/PostgreSQL tại app. Khi có implementation, chạy trong backend:

```powershell
.\gradlew.bat :gitci-impl:test :board-impl:test :common:test :verifyModuleBoundaries
.\gradlew.bat :app:test --tests '*GitCommitLink*' --tests '*TaskReference*'
.\gradlew.bat check
```

Tên test glob chỉ là dự kiến, phải thay bằng class thực khi viết test và xác nhận không có “no tests found”. Chạy `npm run build` và `npm run lint` trong frontend theo DoD, đặc biệt khi thêm task key vào DTO/UI. Kiểm proxy config/HTTP riêng nếu chạm proxy; build frontend không chứng minh timeout webhook.

Evidence gồm JUnit XML/HTML, version JDK/PG, commit, cấu hình limit/timeout, fixture IDs giả, before/after Git/link/receipt và task state, event/control-event counts, query plan/count, đo concurrency/ordering và mẫu lỗi đã che dữ liệu. Không lưu full payload chứa PII, signature hoặc secret vào report. Demo thật cần repo quản trị được, task keys thật, HTTPS và secret mới cấu hình tại môi trường; thiếu chúng chỉ BLOCKED demo, không cản unit/Testcontainers. Không cần API token cho baseline parser.

## 7. Baseline lập plan và rủi ro bàn giao

Bảng ngày 02/11 dưới đây được giữ làm lịch sử. Actual triển khai 08/11, kết quả test, các lần fail/retest và gate chưa đạt nằm trong [báo cáo implementation](12_lien_ket_commit_va_task_actual.md). Các ô CHƯA CHẠY ở ma trận ban đầu không thay thế báo cáo Actual mới.

| Hạng mục | Actual ngày 02/11/2026 |
|---|---|
| Đọc dự án | Đã đối chiếu tài liệu kiến trúc/sản phẩm/roadmap, task 12–14/16, Board UUID/schema/API, GitCI service/schema/receipt và typed event mẫu |
| Trello | Đã đọc card T-022 ở Backlog và hai AC chưa check; không cập nhật card trong yêu cầu lập plan |
| Artifact | Chỉ tạo file plan này; không triển khai task key, parser, migration, resolver, handler hoặc persistence T-022 |
| RX/SP/RS/DB/IT | Tất cả CHƯA CHẠY; kết quả T-020/T-021 không là Actual T-022 |
| Backend/frontend checks | Không chạy trong đợt viết tài liệu; chỉ kiểm cấu trúc tài liệu và liên kết cục bộ |
| Demo/review/merge | Chưa thực hiện cho T-022; task-key policy, nhiều liên kết và request/decision contract là các gate thiết kế cần chốt |

| Rủi ro | Hướng xử lý khi triển khai |
|---|---|
| Chưa có task key nhưng tưởng regex đã tìm ra task UUID | Dừng gate linking cho tới khi Board mapping/allocator/lookup thật đạt test |
| Mở rộng nhiều module ngoài ticket regex gốc | Ghi rõ scope và review Board/common migrations, không âm thầm sửa schema cũ |
| T-021 chưa tích hợp handler tổng quát | Ghép transaction/receipt thật trước khi bật push/PR, không ACK trước persist |
| Một `task_id` scalar làm mất nhiều tham chiếu | Chốt association và compatibility policy hoặc explicit ambiguity; không first-match |
| Dedupe receipt hữu hạn và PR event đảo thứ tự | Git business unique constraints, provider version check và transaction tests |
| Cleanup/event bus thiếu durability | Typed cleanup và revalidation; T-023 thiết kế outbox/idempotency trước cam kết side effects bền vững |
| Secret demo đã lộ hoặc tunnel đổi URL | Không đưa credential thật vào plan/test; cấu hình mới và verify HTTPS khi demo |

Mẫu cập nhật sau test: `Case ID | commit/môi trường | Expected | Actual + PASS/FAIL/BLOCKED | evidence | retest`. Khi fail phải ghi cả lần sửa và lần chạy lại, không chỉ thay ô Actual thành PASS. Done theo DoD yêu cầu triển khai, kiểm thử và review/merge, không đồng nghĩa với việc có tài liệu plan.

## 8. Nguồn kỹ thuật

- [GitHub webhook events/payloads](https://docs.github.com/en/webhooks/webhook-events-and-payloads): nguồn đối chiếu event families và envelope; đọc ngày 02/11/2026. Khi code phải kiểm schema/action và tình huống payload thiếu dữ liệu theo provider, không suy từ tên event đơn lẻ.
- Các giới hạn 20 mã/nguồn, 100 mã hoặc commits/delivery, policy source precedence, mã task và association trong tài liệu này là đề xuất DevFlow, không phải mặc định của GitHub. Chốt và đo lại trước rollout.
