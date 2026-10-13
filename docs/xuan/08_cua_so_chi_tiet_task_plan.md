# T-017 — Plan triển khai cửa sổ chi tiết task

**Task gốc:** [08_cua_so_chi_tiet_task.md](08_cua_so_chi_tiet_task.md). **Trello:** [T-017: Build Task Detail Modal (Markdown description, priority, assignee)](https://trello.com/c/Waxoy1hP/23-t-017-build-task-detail-modal-markdown-description-priority-assignee).

**Milestone:** 2. **Owner:** Xuân Lê. **Phạm vi chính:** `frontend/`; phối hợp Board và Auth để hoàn thiện các hợp đồng còn thiếu. **Branch triển khai:** `feature/T-017-task-detail-modal`. **Ngày lập:** 11/10/2026, GMT+7. **Ngày triển khai:** 13/10/2026, GMT+7.

**Trạng thái:** Đã triển khai trên branch feature nhưng chưa mở PR/review/merge. `npm run build`, `npm run lint`, `./gradlew.bat check` và module boundary verification pass. Browser E2E, accessibility, performance measurement và nhiều matrix case chưa được thực thi; xem phần Thực tế. Baseline được đọc tại commit `3883c93` trên `feature/T-016-kanban-drag-and-drop`. Card Trello được đọc trực tiếp; chuyển In Progress bị API trả HTTP 401 nên trạng thái/checklist chưa cập nhật.

## 1. Căn cứ, hiện trạng và phạm vi

### 1.1. Yêu cầu và nguồn đối chiếu

Hai acceptance criteria gốc trên card:

- Render Markdown preview mượt mà.
- Lưu các thay đổi thành công qua API và cập nhật lại thẻ trên board.

Mô tả card yêu cầu mở modal khi click card, chỉnh title, mô tả Markdown, assignee, due date, priority và có khu vực bình luận. Task nội bộ yêu cầu bình luận kết nối API thật khi bật chức năng gửi; backend còn thiếu phải được hoàn thiện/thống nhất, không nghiệm thu bằng ô nhập giả.

| Nguồn | Nội dung áp dụng |
|---|---|
| [ARCHITECTURE](../ARCHITECTURE.md), mục 2–6, 9.2, 10 | Board sở hữu task/comment; Auth sở hữu identity/membership; backend liên module qua typed `DevFlowEvent`, không gọi internals hoặc repository chéo |
| [PRODUCT_SPEC](../PRODUCT_SPEC.md), mục 5.1, 6, 8 | Chi tiết task, assignee, hạn, ưu tiên; comments là should-have ở cấp sản phẩm nhưng đã được nêu trong card này; validation, privacy, resilience, CRUD P95 < 200 ms |
| [IMPLEMENTATION_PLAN](../IMPLEMENTATION_PLAN.md), Phase 2 | Modal với Markdown editor, priority, assignee, due date; triển khai trên API task thật |
| [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) | Build/lint, test chức năng và tích hợp phù hợp, boundaries, PR/review và evidence |
| [Plan T-013](04_api_quan_ly_va_di_chuyen_task_plan.md), [plan T-016](07_giao_dien_kanban_keo_tha_plan.md) | PUT thay toàn bộ nội dung task, Turnstile, trạng thái lưu chưa xác định, board lọc/phân trang và mutation đang chờ |
| [T-018](09_frontend_dong_bo_board_realtime.md), [T-026](16_hien_thi_git_va_ci_tren_task.md) | T-017 chuẩn bị điểm refresh detail; STOMP client và Git/CI badges thuộc task khác |
| [Frontend skill](../../.agents/skills/frontend-dev/SKILL.md), [quy ước code](../../.agents/rules/coding-conventions.md), [git workflow](../../.agents/rules/git-workflow.md) | React 19, TypeScript strict, Tailwind 4, API relative paths, phân tách API/hook/UI, branch và Conventional Commits |

### 1.2. Hiện trạng đọc từ source

Các đường dẫn dưới đây tính từ root repository. Đây là khảo sát source, không phải kết quả test runtime.

| Thành phần | Đã có ở baseline | Khoảng trống/hệ quả |
|---|---|---|
| `frontend/src/pages/BoardPage.tsx` | Board/task thật, filter, DnD handle riêng, menu di chuyển, pending/syncBlocked, generation guard | `TaskCard` còn nằm trong page; chưa có hành động mở detail hoặc modal; cần tránh click sau drag mở nhầm |
| `frontend/src/types/board.ts` | `TaskListItem`, enum priority/category, `ApiError` | Task list không có description/createdAt/boardId/workspaceId; phải GET detail, không dùng card làm toàn bộ form |
| `frontend/src/api/boardApi.ts` | Board/column/task list, move/reorder | Chưa có wrapper GET/PUT task detail, members hoặc comments |
| `frontend/src/auth/AuthProvider.tsx` | Bearer request, refresh single-flight, GET retry một lần, mutation không replay, timeout mặc định 10 giây | Parser chỉ nhận chuỗi `application/json`, chưa nhận `application/problem+json`; truyền signal ngoài làm mất tác dụng timeout nội bộ; cần sửa khi tích hợp modal |
| `frontend/src/hooks/useTurnstileToken.ts` | Token mới theo thao tác, reset/execute, timeout, site key public | Dùng chung cơ chế, tránh hai widget cùng tranh token; challenge phải tương tác được khi modal đang khóa focus |
| `frontend/package.json` | React 19.1, TS 5.8, DnD; scripts build/lint | Chưa có Markdown renderer hoặc bộ unit/component/E2E test; không ghi lệnh test chưa tồn tại là đã chạy |
| `TaskController`, `TaskResponse`, `UpdateTaskRequest` | GET `/api/v1/tasks/{id}`; PUT cùng đường dẫn; payload đầy đủ 5 trường; PUT có Turnstile guard | Không có PATCH content, ETag/If-Match hoặc version chống ghi đè stale edit |
| `TaskManagementService` | Title <= 255 code points; description <= 10.000 code points; assignee thuộc workspace; dueDate là Instant; board lock, archived guard; audit và `CARD_UPDATED` sau commit | Board lock tuần tự hóa ghi nhưng không phát hiện người sửa từ bản cũ. Sanitizer `Jsoup.clean(..., Safelist.none())` cần kiểm chứng giữ newline, code và round-trip Markdown |
| `BaseEntity`, `TaskEntity` | UUID, createdAt/updatedAt | Không có `@Version`; updatedAt không tự trở thành concurrency precondition |
| `AuthController` | Login/refresh/me, danh sách workspace của người hiện tại | Chưa có endpoint danh sách thành viên workspace; không thể dựng picker đầy đủ từ các assignee đang xuất hiện trên board |
| `CommentEntity`, `CommentRepository`, migration V2 | Bảng `task_comments`, author UUID, content, timestamp, FK cascade task; repository đọc toàn danh sách | Chưa có REST/service/DTO phân trang bình luận; entity có sẵn không có nghĩa API đã sẵn sàng |
| Board security/metrics | Membership qua event, 404 khi không được đọc; quota actor, payload task mặc định 65.536 bytes, ProblemDetail, metrics theo route template | Các API mới phải áp dụng cùng chuẩn; filter size hiện tại chưa khớp đường dẫn `/tasks/{id}/comments` |
| T-015/T-018 | Backend có `CARD_UPDATED`, refresh targets TASK_LISTS/TASK_DETAIL; frontend STOMP chưa có | Cập nhật board cùng màn hình làm trong T-017; đồng bộ nhiều browser là tích hợp T-018, không cam kết đã có |

### 1.3. Phạm vi và các gate phụ thuộc

**Trong phạm vi:** mở/đóng detail; tải dữ liệu chuẩn; xem và sửa title/description/priority/assignee/due date; Markdown preview an toàn; dirty draft; lưu và cập nhật card; lỗi và đối soát; comments đọc/gửi thật; keyboard/mobile; các kiểm thử tương ứng.

**Ngoài phạm vi:** tạo/xóa task, sửa cột/trạng thái ngay trong modal, labels tùy ý, attachment, mentions, rich text/WYSIWYG, edit/delete comment, activity-history UI, Git/CI, notifications mới và STOMP client. Priority là enum hiện có, không tự tạo hệ thống tag mới.

| Gate | Chủ sở hữu/phần bàn giao | Điều kiện để nghiệm thu đầy đủ |
|---|---|---|
| G1 — API members | Auth, phối hợp Tiến | Public REST có quyền workspace, phân trang/tìm kiếm và DTO tên/ID tối thiểu; frontend không đọc Auth DB/internal |
| G2 — API comments | Board, Xuân | GET phân trang và POST thật, validation/authorization/quota/Turnstile/size-limit, audit/metrics và test tích hợp |
| G3 — Stale edit | Board + frontend | Precondition kiểm tra atomically trong transaction; UI giữ draft khi conflict. GET trước PUT ở frontend không đủ chống race |
| G4 — Markdown round-trip | Board + frontend | Chứng minh sanitizer lưu/đọc không phá Markdown hợp lệ; sửa trong Board nếu test cho thấy mất dữ liệu |
| G5 — Môi trường kiểm thử | Local/CI | Có backend + PostgreSQL và chế độ Turnstile xác định; browser E2E chạy API thật, không dùng mock thay bằng chứng tích hợp |

G1–G4 là phần phối hợp cần làm rõ trong bước đầu triển khai, không phải API đang tồn tại. Có thể phát triển UI bằng fixture trong lúc chờ, nhưng full T-017 chưa Done nếu picker/comments vẫn giả hoặc còn lỗi mất dữ liệu. Nếu chia ticket phụ, ghi ID/người phụ trách/evidence vào đây; chỉ giảm phạm vi nghiệm thu khi có quyết định rõ ràng và cập nhật tài liệu/card.

## 2. Kế hoạch nghiệp vụ — mô tả luồng sử dụng

### 2.1. Mở và xem task

1. Người dùng vào board được cấp quyền, bấm tiêu đề/vùng nội dung thẻ để mở cửa sổ chi tiết. Người dùng bàn phím mở bằng nút tên task; handle kéo và menu di chuyển có hành động riêng.
2. Cửa sổ cho biết đang tải; chỉ cho chỉnh sửa khi dữ liệu chi tiết đã tải đúng task. Lỗi có nút thử lại; task bị xóa hoặc mất quyền có thông báo chung và đường quay lại board.
3. Hiển thị title, mô tả, priority, người phụ trách, thời điểm hạn và thông tin thời gian tạo/cập nhật. Board archived xem được nhưng không sửa/gửi bình luận. Cột/category chỉ hiển thị để tránh trộn thao tác đổi vị trí vào lưu nội dung.
4. Modal giữ nguyên ngữ cảnh board và bộ lọc. Đổi board/workspace hoặc rời trang phải qua xử lý bản nháp, không để response task cũ xuất hiện trong cửa sổ task mới.

### 2.2. Chỉnh sửa nội dung

1. Người dùng sửa các trường trong một bản nháp riêng; card trên board vẫn phản ánh dữ liệu đã lưu. Nút Lưu chỉ bật khi có thay đổi hợp lệ và không có mutation xung đột.
2. Title bắt buộc có nội dung; description có thể trống; priority chọn một trong LOW/MEDIUM/HIGH/URGENT. Hiển thị lỗi cạnh trường và giới hạn ký tự phù hợp tiếng Việt/emoji.
3. Mô tả có chế độ Viết/Xem trước. Hỗ trợ đoạn văn, tiêu đề, danh sách, nhấn mạnh, link, code inline và code block; preview không thực thi HTML/script hoặc tự tải ảnh theo dõi từ nội dung.
4. Người phụ trách chọn từ thành viên workspace, có “Chưa giao”. Thành viên bị xóa/không còn resolve tên vẫn có nhãn thay thế cho giá trị đang lưu; không tự xóa assignee khi danh sách tải lỗi.
5. Hạn được nhập bằng ngày + giờ, có nhãn múi giờ đang dùng. Cho phép xóa hạn và hạn trong quá khứ; hiển thị quá hạn để người dùng biết, không tự đặt quy tắc cấm mà backend chưa có.

### 2.3. Lưu và phản ánh lên board

1. Khi bấm Lưu, kiểm tra form, lấy token bot protection mới nếu được bật rồi gửi một yêu cầu cập nhật. Khóa gửi trùng và các thao tác di chuyển/sắp xếp xung đột trong cùng board.
2. Nếu thành công, lấy dữ liệu trả về làm chuẩn; title/priority/assignee/due date trên card thay đổi ngay, không cần F5. Modal giữ mở, xóa dấu bản nháp chưa lưu và thông báo đã lưu.
3. Nếu thay đổi làm task không còn khớp bộ lọc, card được ẩn theo filter nhưng modal vẫn hiển thị task vừa lưu, kèm giải thích. Không mất task hoặc cộng trừ tổng số sai.
4. Nếu server từ chối, giữ bản nháp và hiển thị hướng xử lý. Nếu mạng đứt hoặc không xác định server đã lưu hay chưa, đối soát dữ liệu mới; không tự gửi lại nội dung.
5. Nếu người khác sửa task trong lúc đang soạn, thông báo có phiên bản mới. Cho xem dữ liệu mới và giữ nội dung đang viết để người dùng chủ động hợp nhất; không ghi đè ngầm thay đổi của người khác.

### 2.4. Đóng cửa sổ và bảo vệ bản nháp

1. Chưa sửa gì: nút Đóng, Escape hoặc click nền đều đóng và trả focus về thẻ/nút đã mở.
2. Có sửa nội dung hoặc bình luận chưa gửi: hỏi “Tiếp tục chỉnh sửa” hoặc “Bỏ thay đổi”; nếu form hợp lệ có thể chọn “Lưu rồi đóng”. Hủy xác nhận giữ nguyên dữ liệu và focus.
3. Khi đang gửi hoặc đối soát: thông báo trạng thái và chặn đóng/đổi task gây hiểu nhầm. Nếu người dùng buộc tải lại/tab đóng, không hứa hủy được mutation đã tới server.
4. Cảnh báo rời trang chỉ bật khi có draft; draft không được lưu lâu dài vào localStorage/sessionStorage, log hoặc analytics. Sau logout/mất quyền, xóa dữ liệu nhạy cảm khỏi state.

### 2.5. Bình luận

1. Hiển thị bình luận theo thời gian tăng dần, có tác giả và thời gian, tải thêm khi còn dữ liệu. Loading/rỗng/lỗi là các trạng thái khác nhau.
2. Người dùng nhập bình luận dạng văn bản thuần, gửi bằng nút rõ ràng; Enter vẫn xuống dòng. Bình luận trống không gửi; gửi thành công mới thêm vào danh sách và xóa ô nhập.
3. Bình luận độc lập với nút Lưu task: gửi bình luận không tự lưu bản nháp task, lưu task không tự gửi bình luận. Hai mutation vẫn dùng chung khóa ghi để tránh tranh token.
4. Lỗi gửi giữ nội dung; kết quả chưa rõ phải đọc lại trước khi cho gửi tiếp. Không tạo bình luận giả hoặc hiển thị thành công khi API chưa sẵn sàng.

## 3. Kế hoạch kỹ thuật — hợp đồng và thiết kế

### 3.1. Hợp đồng task hiện có và phần mở rộng

| Thao tác | Endpoint/response | Quy tắc sử dụng |
|---|---|---|
| Đọc detail — hiện có | `GET /api/v1/tasks/{taskId}` → `TaskResponse` | Có Bearer; xác thực task thuộc board/workspace đang xem trước khi gắn vào state |
| Lưu nội dung — hiện có | `PUT /api/v1/tasks/{taskId}` → `TaskResponse` | Gửi đầy đủ `title`, `description`, `priority`, `assigneeId`, `dueDate`; không gửi columnId/position/category |
| Bot protection — hiện có | `X-Turnstile-Token` trên PUT | Token mới cho từng lần gửi; secret chỉ ở backend; không replay token sau refresh401 |
| Chống stale — cần bổ sung | ETag trên GET/PUT response; `If-Match` trên PUT | ETag opaque gắn revision task; frontend gửi đúng giá trị server trả, không tự dựng từ giờ máy khách |

`TaskResponse` hiện có: `id`, `columnId`, `boardId`, `workspaceId`, `title`, `description`, `priority`, `assigneeId`, `dueDate`, `position`, `statusCategory`, `createdAt`, `updatedAt`. Bổ sung type detail riêng, không mở rộng `TaskListItem` thành DTO giả đầy đủ. Các giá trị nullable gửi JSON `null` khi xóa; không dùng omission để mô phỏng PATCH trên PUT.

**Validation và thời gian:** title 1..255 Unicode code points sau chuẩn hóa, description <= 10.000 code points, priority enum bắt buộc, assignee UUID hoặc null. Kiểm tra serialized UTF-8 body vì ký tự, escape JSON và code points khác byte size; giữ server làm nguồn quyết định. dueDate ISO-8601 UTC (`Instant`), năm 0001..9999, backend chuẩn hóa microseconds. Editor dùng local date/time có giây; lưu UTC, giữ nguyên chuỗi gốc khi người dùng không sửa để không làm mất độ chính xác. Khi đổi timezone/DST, thời gian không tồn tại/không rõ phải được phát hiện và giải thích; không tự nhảy ngày. UTC display là fallback rõ ràng nếu UI chưa hỗ trợ chọn timezone.

### 3.2. API members — đề xuất cần bàn giao từ Auth

- Đề xuất `GET /api/v1/auth/workspaces/{workspaceId}/members?page=0&size=20&q=...`, DTO `items[{userId,fullName,avatarUrl}],page,size,totalElements,totalPages`; đường dẫn/tên field phải chốt với Auth trước khi viết client.
- Chỉ thành viên workspace được đọc; query trim, giới hạn độ dài, pagination size 1..100, thứ tự ổn định. Không đưa email/token/role nhạy cảm vào picker nếu không cần.
- Picker tìm kiếm debounce khoảng 250 ms, hủy/bỏ response query cũ, tải thêm, không dùng first page như toàn workspace. Cache chỉ trong phiên và theo workspace; xóa khi logout/đổi workspace.
- Giá trị assignee hiện tại ngoài trang đầu cần resolve qua contract được Auth cung cấp hoặc dùng nhãn UUID dự phòng; không POST yêu cầu xem user tùy ý, không tự tạo endpoint chưa bàn giao.
- Board tiếp tục kiểm membership assignee server-side qua event hiện có. Client membership list không phải nguồn cấp quyền và không thay được kiểm tra IDOR.

### 3.3. API bình luận — đề xuất bổ sung trong Board

| Thao tác | Contract đề xuất, chưa tồn tại | Quy tắc |
|---|---|---|
| Đọc | `GET /api/v1/tasks/{taskId}/comments?page=0&size=20` → `items,page,size,totalElements,totalPages` | DTO item `{id,taskId,authorId,content,createdAt,updatedAt}`; sort `createdAt ASC,id ASC`; page>=0, size 1..100 |
| Gửi | `POST /api/v1/tasks/{taskId}/comments` body `{content}` → 201, Location + item | Tác giả lấy từ authenticated actor; không nhận authorId của client; content 1..5.000 code points sau trim, plain text; token Turnstile/quota như task writes |

- Tạo request/response DTO ở `board-api`; controller/service/repository ở `board-impl`. Mở rộng repository bằng truy vấn phân trang, không gọi hàm trả toàn bộ list cho REST.
- Kiểm membership từ task→board→workspace cho cả GET/POST, archived read-only; POST theo board lock protocol, FK và transaction. Comment bị xóa theo task; tên tác giả không resolve được hiển thị nhãn dự phòng, không lộ dữ liệu workspace khác.
- Giới hạn payload comment đề xuất 32 KiB, kiểm cả chunked request; mở rộng size filter và đăng ký interceptor/security cho route mới. Jakarta validation + domain validation; lỗi RFC 7807, không trả stack trace.
- Audit ghi actor/action/resource và tên field, không đưa nội dung bình luận vào log. Metrics dùng route template/method/status, không gắn UUID hoặc content làm tag.
- Trước mắt POST một lần, không retry tự động. Response mất sau commit: refetch/dedupe theo ID và đối chiếu thời gian/nội dung chỉ để hỗ trợ xác nhận; nội dung trùng không chứng minh request nào tạo ra. Nếu chưa xác định, giữ draft và cảnh báo nguy cơ gửi trùng. Muốn retry tự động cần idempotency key được persist/unique server-side trong thay đổi riêng, không giả định đã hỗ trợ.
- Bình luận mới của người khác cập nhật bằng nút tải lại trong T-017; subscribe/invalidation tự động phối hợp T-018. Không tự dùng `task.status_changed` cho việc gửi bình luận; nếu thêm event phải cập nhật catalog và consumer rõ ràng.

### 3.4. Chống ghi đè và giới hạn của baseline

1. Bổ sung revision riêng trên task bằng migration mới; không sửa migration V2 đã phát hành hoặc thêm field chung lên mọi entity. Chọn revision tăng atomically cho các write task liên quan; không coi board lock/updatedAt là optimistic concurrency đã có.
2. GET trả strong ETag revision; PUT kiểm If-Match trong cùng transaction đang giữ board lock rồi mới ghi. Revision stale trả 412 `TASK_CHANGED`; thiếu precondition trả 428 sau khi rollout cập nhật client. Hợp đồng này là thay đổi API cần cập nhật T-013/OpenAPI và test compatibility; không bật bắt buộc trước khi các client liên quan sẵn sàng.
3. Trong thời gian rollout, client modal luôn gửi If-Match; không có ETag thì chỉ đọc, báo dependency chưa sẵn sàng. Không fallback sang PUT vô điều kiện. Các client cũ còn ghi vô điều kiện phải được ghi rõ là rủi ro trước khi nghiệm thu production.
4. Conflict giữ `baseSnapshot`, `draft` và `latestSnapshot`, hiển thị các trường khác nhau. Bản đầu có thể yêu cầu người dùng tải bản mới và áp lại nội dung mong muốn; chưa cần auto-merge. Chỉ lưu tiếp với ETag mới sau lựa chọn rõ ràng.
5. Khi task đổi cột hoặc board load lại, tìm bằng task ID và columnId mới. Không đem position cũ trong snapshot để đặt lại card. Sự kiện T-018 sau này không được ghi đè draft đang dirty.

### 3.5. Markdown an toàn và dữ liệu lưu

- Dùng textarea + tab preview, chưa cần editor nặng. Đánh giá renderer AST tương thích React 19/TS và bản vá hiện hành trong bước triển khai; dự kiến nhóm `react-markdown`/GFM nếu cần, kiểm tài liệu chính thức/lockfile/audit trước khi thêm. Không dùng `dangerouslySetInnerHTML` hoặc bật raw HTML plugin.
- Hỗ trợ link HTTP/HTTPS hợp lệ; chặn `javascript:`, `data:`, `file:` và URL không hợp lệ; link tab mới có `rel="noopener noreferrer"`. Ảnh ngoài hiển thị dạng nhãn/link an toàn, không tự request ảnh; iframe/embed/SVG/event handler không render.
- Preview memo theo description, debounce tối đa khoảng 150 ms nếu cần; không gửi Markdown lên server theo từng phím. Không thêm highlight plugin hoặc xử lý bất đồng bộ không giới hạn chỉ để đạt preview.
- Tạo fixture round-trip trước khi sửa sanitizer: newline, Unicode, fenced code có `<T>`, `&`, `>`, quote, danh sách, link, raw HTML độc hại. GET sau PUT phải giữ nội dung hợp lệ và cú pháp code, đồng thời không thực thi payload nguy hiểm.
- Nếu Jsoup hiện tại phá newline/escape code, sửa chính sách sanitization trong Board với kiểm thử hồi quy: phân biệt text/code literal và HTML thật, giữ Markdown source hợp lệ, loại/chặn HTML nguy hiểm và render với allowlist. Không xóa mọi dấu `<...>` trong code để rồi coi là Markdown đúng; không hạ bảo vệ XSS cho toàn bộ task API.
- Sau lưu dùng canonical response từ server để cập nhật base/draft. Nếu server chuẩn hóa đáng kể, thông báo rõ; không hiện bản preview chưa lưu như nội dung đã persist.

### 3.6. Cấu trúc frontend dự kiến

| File/thành phần | Trách nhiệm |
|---|---|
| `types/task.ts` | TaskDetail, UpdateTaskInput, Comment/Page, Member/Page và mapping DTO đã chốt |
| `api/taskApi.ts` | GET/PUT detail, đọc ETag/header, comments; mọi URL relative `/api/v1/...` |
| `api/workspaceApi.ts` | Member contract từ Auth, pagination/search |
| `hooks/useTaskDetail.ts` | Load generation, base/draft, dirty, validate, save/conflict/unknown/reconcile |
| `components/common/Modal.tsx` | Portal/dialog, focus, scroll lock, đóng/xác nhận, cleanup |
| `components/board/TaskDetailModal.tsx` | Form chi tiết, kết nối các section, hiển thị trạng thái |
| `MarkdownEditor.tsx`, `AssigneePicker.tsx`, `TaskComments.tsx` trong `components/board/` | Preview an toàn; picker; comments và draft độc lập |
| `BoardPage.tsx`/`TaskCard.tsx` | selectedTaskId, nút mở detail, apply confirmed summary/invalidate cột, khóa mutation dùng chung |
| `AuthProvider.tsx`, `useTurnstileToken.ts` | Parse ProblemDetail đúng media type, timeout/cancel, expose response metadata, challenge tương tác trong modal |

Đây là thiết kế đề xuất; không refactor toàn board hoặc thay state library chỉ để khớp tên file. Không thêm token store/API client song song. App hiện chưa dùng query cache framework; state hook đủ cho phạm vi này nếu các nguồn chuẩn và lifecycle rõ ràng.

### 3.7. State, race và lưu dữ liệu

```text
closed -> loading -> ready(clean) -> editing(dirty) -> validating -> acquiringToken -> saving
                      |                                  |                         |
                      +-> close                          +-> editing(error)        +-> ready(saved)
                                                                                   +-> conflict(draft retained)
                                                                                   +-> reconciling -> ready / unresolved
```

- Selection dùng `{boardId,taskId,generation}`; response chỉ apply khi cả ba còn khớp và phiên còn hợp lệ. Cleanup abort GET và bỏ response cũ kể cả transport không hủy được.
- `baseSnapshot` là server data + ETag, `draft` là form, `dirtyFields` tính từ normalized values; giữ null/empty rõ ràng. Không đẩy draft vào board state và không PUT khi no-op.
- Save dùng pessimistic form: chỉ cập nhật card khi PUT xác nhận thành công; do đó lỗi validation không cần rollback toàn board. Nếu dùng feedback optimistic cục bộ, chỉ rollback đúng task/field của operation đó, không khôi phục snapshot cả board.
- Mutation lock chung ở BoardPage/coordinator: move, reorder, PUT task, POST comment không chạy chồng; khóa đồng bộ ngay trong handler bằng ref/operation ID để double click trong cùng tick không lọt. Modal vẫn đọc được khi list chưa tải đủ, nhưng không cho save lúc board còn mutation chưa đối soát.
- PUT thành công: map TaskResponse → TaskListItem, upsert theo ID ở đúng cột hiện tại; giữ metadata phân trang/tổng và trạng thái load. Nếu vị trí/cột khác với local, refetch cột cũ/mới; không chèn task chưa tải vào trang sai thứ tự. Reapply filter bằng dữ liệu canonical.
- Board/card refresh thất bại sau PUT200 không đổi thành “Lưu thất bại” hoặc phục hồi draft cũ; hiển thị đã lưu, board chờ đồng bộ và khóa thao tác vị trí cho tới reconcile.
- Đọc lại detail sau timeout/5xx/401 mutation: nếu các trường khớp canonical draft thì báo đã xác nhận; nếu khác, giữ draft, hiển thị latest và yêu cầu quyết định. Không suy ra chắc chắn request chưa lưu chỉ vì dữ liệu khác (có thể người khác đã sửa sau đó).
- Sửa API request để trả data + metadata khi cần ETag, nhận `application/problem+json` và JSON chuẩn; compose timeout với caller abort. Retry GET có giới hạn, không retry PUT/POST tự động, không retry vô hạn khi refresh thất bại. Khi triển khai qua CORS, expose ETag nếu khác origin; đường đi proxy same-origin là mặc định.

### 3.8. Accessibility, responsive và bảo mật giao diện

- Modal có accessible name/description, focus trap, Escape và trả focus; nền inert và không kéo board khi modal mở. Ưu tiên native dialog nếu đáp ứng browser mục tiêu, nếu dùng custom portal phải test đầy đủ focus/scroll. Tránh button lồng button; title là nút mở detail, DnD handle/menu độc lập.
- Nếu task bị filter ẩn sau save, trả focus về vùng/nút board hợp lệ thay vì node đã unmount. Confirmation dialog và Turnstile challenge không bị focus trap/stacking context che.
- Ở 360 px viewport modal gần full screen, scroll nội dung riêng, nút Lưu/Đóng luôn tới được; code block cuộn ngang trong preview, title/link dài không làm tràn trang; hỗ trợ zoom 200%, reduced motion, contrast và bàn phím.
- Không log draft, comment, token, email; không gửi toàn error body lên analytics. UI lỗi người dùng xem được chỉ chứa thông điệp an toàn + correlationId nếu có. Dọn cache/state khi mất quyền/logout.
- Backend vẫn quyết định quyền; board archived và nonmember phải bị từ chối cả khi gọi API trực tiếp. Public member/avatar URL cũng cần scheme policy và fallback; không load URL tùy ý từ nội dung task.

## 4. Kế hoạch triển khai theo bước

| Bước | Công việc | Đầu ra/điều kiện chuyển bước |
|---|---|---|
| 1 | Đối chiếu branch/API đang chạy, đọc skill backend nếu có sửa backend; thống nhất G1–G4 và owner; khảo sát test tooling | Contract members/comments/precondition/Markdown, ticket phụ nếu cần, fixtures và cách chạy rõ ràng |
| 2 | Hoàn thiện backend phụ thuộc: members ở Auth; comments, revision/ETag, Markdown regression trong Board | API thật, validation/security/metrics/audit, migration và integration tests; không import impl chéo |
| 3 | Thêm DTO/API/hook detail, sửa response parser và timeout composition | Load/cancel, headers/precondition, form state, error/reconcile chạy được với contract |
| 4 | Modal accessible + mở từ card, chống click sau drag, close/dirty guard | Mở đúng task, focus/scroll/keyboard/mobile, archived read-only |
| 5 | Editor/preview, picker paginated, due date, validation và save | Năm trường lưu đúng, conflict giữ draft, card/filter cập nhật canonical |
| 6 | Comments đọc/gửi/phân trang; chia sẻ mutation lock và Turnstile | Không fake success, không duplicate do double click/retry, draft độc lập |
| 7 | Chạy bộ test dưới đây, build/lint/backend checks, smoke qua Vite và Nginx | Điền Thực tế có evidence, sửa failure, không suy diễn PASS từ task cũ |
| 8 | Review tài liệu/ảnh demo/PR, cập nhật checklist sau khi chứng minh | PR đúng scope; In Review khi đã mở PR, Done sau review/merge và DoD |

Branch triển khai theo card, base có đủ T-013/T-016; nếu develop chưa chứa dependency thì ghi rõ stacked branch/PR hoặc tích hợp dependency trước, không âm thầm quay về backend stub. Không tạo branch/commit/push/PR trong yêu cầu chỉ lập plan này.

## 5. Tiêu chí hoàn thành

Hai tiêu chí gốc cần có evidence riêng; các mục dưới đây là diễn giải nghiệm thu, chưa phải checklist đã ghi lên Trello.

- [ ] **AC01 — Markdown:** preview đúng bộ cú pháp hỗ trợ, không XSS/tải ảnh theo dõi, round-trip không phá nội dung; đạt mục tiêu độ trễ ở mục 6.3.
- [ ] **AC02 — Lưu và cập nhật card:** năm trường persist qua API thật, mở lại/reload vẫn đúng; card/filter cập nhật không cần F5, không mất vị trí/tổng phân trang.
- [ ] **AC03 — Lifecycle:** mở đúng task, response cũ không ghi đè task mới, close/dirty guard và trạng thái loading/empty/error đầy đủ.
- [ ] **AC04 — Assignee/thời gian:** picker có members thật và phân trang, bỏ giao được, server từ chối người ngoài workspace; due date không lệch múi giờ và không tự đổi khi chưa sửa.
- [ ] **AC05 — Comments:** tải/gửi/đọc lại thật, phân trang ổn định, đúng tác giả từ phiên; lỗi giữ nội dung và không gửi trùng ngầm. Nếu G2 chưa xong, ghi blocked, không đánh dấu full T-017 Done.
- [ ] **AC06 — Integrity/resilience:** không ghi đè stale edit, double submit, PUT full replacement sai null; xử lý 401/404/409/412/413/428/429/5xx/offline/unknown, giữ draft và đối soát đúng.
- [ ] **AC07 — Security:** server validation/authorization/archived/quota/Turnstile thực thi; lỗi không lộ internals, không log PII/nội dung/token, module boundaries pass.
- [ ] **AC08 — UX/accessibility:** keyboard/focus/scroll, confirmation, Turnstile challenge, responsive 360 px và zoom 200% pass; DnD T-016 không regression.
- [ ] **AC09 — Quality/evidence:** test phù hợp pass, build/lint/backend check/boundaries đạt; bằng chứng runtime, performance, PR/review; dependency còn thiếu được xử lý, không chuyển PASS bằng fixture.

## 6. Kế hoạch testing

### 6.1. Chiến lược và quy ước kết quả

- **Unit:** normalization/code-point/UTF-8 size, due-date round-trip, dirty comparison, DTO mapping, conflict/reconcile transitions, dedupe/comment ordering.
- **Component:** modal keyboard/focus/dirty confirmation; preview XSS/link/image policy; picker pagination/race; submit/Turnstile/error/unknown; tích hợp state board có filter và pagination.
- **Backend integration:** PostgreSQL thật qua môi trường test phù hợp; GET/PUT preconditions và concurrency, membership/archived, comments paging/cascade, sanitize/size/quota/audit/metrics. Không dùng H2 thay bằng chứng khóa/FK PostgreSQL.
- **Browser E2E:** user đăng nhập bằng API thật, mở card/sửa/lưu/mở lại; members và comments thật, negative permissions, network fault; kiểm qua Vite và Docker Nginx. Mock/fault injection để tạo lỗi, không thay bằng chứng persistence.
- **Manual/performance:** screen reader/keyboard/mobile/zoom; capture preview latency và API P95 với điều kiện đo xác định; ghi tool/browser/máy/dataset vào report.
- Frontend hiện chưa có test runner: bước 1 bổ sung tooling phù hợp, dự kiến Vitest + React Testing Library và Playwright sau khi kiểm tương thích. Script và config phải có trong repo trước khi đưa lệnh vào CI; build/lint không thay các bộ test này.

**Thực tế:** `CHƯA CHẠY` là chưa thực thi; `PASS` phải kèm kết quả quan sát/evidence; `FAIL` ghi expected khác actual và bug; `BLOCKED` ghi dependency/môi trường. Mỗi biến thể được ngăn bằng dấu “;” trong một dòng phải được chạy riêng và ghi sub-result; dòng chỉ PASS khi tất cả biến thể đạt. Không lấy kết quả build/test của T-016 làm kết quả T-017.

Ma trận có **81 ID test duy nhất**; nhiều ID chứa các biến thể biên/negative phải tách khi chạy. Đối chiếu tiêu chí để tránh chỉ kiểm happy path:

| Tiêu chí | Các nhóm test chính |
|---|---|
| AC01 — Markdown | MD01–MD06, SE07, UX03, QA05 |
| AC02 — Lưu/card | FD01–FD06, SV01–SV08, QA04 |
| AC03 — Lifecycle | OP01–OP12, SE08, UX05 |
| AC04 — Assignee/hạn | AS01–AS05, DT01–DT04, SE01 |
| AC05 — Comments | CM01–CM10, SE02–SE06, QA03 |
| AC06 — Integrity/resilience | SV03–SV17, SE07–SE08, QA03 |
| AC07 — Security | OP06/OP11/OP12, MD03–MD05, AS05, CM05/CM09, SE01–SE09, QA02 |
| AC08 — UX/accessibility | OP02/OP07–OP10, SE04, UX01–UX05 |
| AC09 — Quality/evidence | QA01–QA07 và kết quả các nhóm còn lại |

### 6.2. Dữ liệu, môi trường và evidence

- Workspace A: user A/B thành viên, user X ngoài workspace; workspace B và task riêng để kiểm IDOR; member bị gỡ giữa lúc sửa. Board active/archived; task thường, nullable, no-description, từng priority, moved/deleted; danh sách thành viên > 1 trang và comments > 2 trang có trùng timestamp.
- Unicode Việt/emoji, title 0/1/255/256 code points; description 0/10.000/10.001; comment 0/1/5.000/5.001; JSON escape và multi-byte để vượt giới hạn bytes. Ngày leap/non-leap, gần nửa đêm, UTC/GMT+7/timezone DST, null, năm ngoài giới hạn.
- Fixture Markdown hợp lệ và độc hại; response chậm/đảo thứ tự; 400/401/403/404/409/412/413/428/429/500/503, non-JSON, malformed JSON, disconnect trước/sau commit, expired/failed Turnstile.
- Ghi branch/commit, backend/frontend version, UTC hoặc GMT+7 rõ ràng, Turnstile enabled/disabled, browser, dataset, command exit code và đường dẫn screenshot/trace/report. Dùng dữ liệu test giả; redact token/PII khỏi HAR, screenshot, log và report.
- Mỗi case có bước tái hiện ngắn trong cột “Thiết lập/thao tác”; khi automation chia thành test nhỏ vẫn giữ ID để trace AC. Reset fixture giữa case, không phụ thuộc thứ tự chạy hoặc dữ liệu production.

### 6.3. Mục tiêu đo và giới hạn

- Preview: ở description tối đa 10.000 code points, sau 20 lần sửa liên tiếp, P95 từ input tới preview ổn định <= 200 ms; không có long task > 100 ms lặp lại do render. Đây là budget nghiệm thu đề xuất, không phải số đã đo.
- Đường GET/PUT core: mục tiêu dự án P95 < 200 ms, đo ít nhất 200 request sau warm-up, tải đồng thời 10 users trên dataset ghi rõ. Tách thời gian user giải Turnstile/network public khỏi server CRUD; báo cả latency end-to-end để không che chi phí thật.
- Modal mở từ click có loading phản hồi trong <= 100 ms; dữ liệu detail phụ thuộc mạng, không đặt số “mượt” bằng cảm giác. Đo ở desktop Chromium và ít nhất một cấu hình mobile throttled, ghi cấu hình cụ thể.
- Không có request mỗi keystroke description; số request members theo debounce/page; không refetch mọi task của board sau mỗi thay đổi một field. Không tuyên bố realtime < 1 giây trong T-017 khi T-018 chưa triển khai.

### 6.4. Ma trận test — mở, đóng và lifecycle

| ID | Thiết lập/thao tác | Expected | Thực tế |
|---|---|---|---|
| OP01 | Click title task A; mở bằng Enter/Space | Mở đúng task, GET detail, không đổi route board/filter | CHƯA CHẠY |
| OP02 | Drag handle; hủy drag; dùng menu Di chuyển | Không mở modal ngoài ý muốn; DnD vẫn đúng | CHƯA CHẠY |
| OP03 | GET chậm; GET lỗi rồi retry | Loading tức thời; form không sửa stale data; retry khôi phục đúng task | CHƯA CHẠY |
| OP04 | Mở A rồi B nhanh, response A về sau | Chỉ B xuất hiện, A không ghi đè và không còn pending state cũ | CHƯA CHẠY |
| OP05 | GET thành công nhưng boardId/workspaceId khác ngữ cảnh | Không gắn vào form, báo không truy cập được đúng ngữ cảnh | CHƯA CHẠY |
| OP06 | Task deleted/nonmember/malformed UUID | 404 hoặc 400 theo contract, thông báo an toàn, không lộ task khác | CHƯA CHẠY |
| OP07 | Close button; Escape; backdrop khi clean | Đóng, unlock scroll, trả focus đúng opener | CHƯA CHẠY |
| OP08 | Dirty rồi close bằng ba cách; hủy confirmation | Hỏi trước khi bỏ; tiếp tục sửa giữ toàn bộ draft/focus | CHƯA CHẠY |
| OP09 | Dirty chọn Bỏ; chọn Lưu rồi đóng thành công/lỗi | Bỏ chỉ khi xác nhận; lưu xong mới đóng, lỗi giữ modal/draft | CHƯA CHẠY |
| OP10 | Đổi board/workspace, navigation/back, refresh/tab close khi dirty | Guard/cảnh báo phù hợp; không lưu nháp vào storage hoặc hiển thị ở workspace mới | CHƯA CHẠY |
| OP11 | Logout/mất quyền khi đang mở; unmount/StrictMode remount | Dọn state/GET/focus/scroll, không render PII hoặc apply response muộn | CHƯA CHẠY |
| OP12 | Xem task thuộc archived board | Detail đọc được, form/comment gửi bị khóa; API direct cũng từ chối write | CHƯA CHẠY |

### 6.5. Ma trận test — form, Markdown, assignee và hạn

| ID | Thiết lập/thao tác | Expected | Thực tế |
|---|---|---|---|
| FD01 | Mở task đủ trường; task các optional null | Form khớp GET, null có trạng thái rõ; không PUT do vừa mount | CHƯA CHẠY |
| FD02 | Title rỗng/whitespace/HTML-only; 1/255/256 code points Việt/emoji | Chặn invalid, biên hợp lệ lưu được; server cũng kiểm độc lập client | CHƯA CHẠY |
| FD03 | Description null/trống; 10.000/10.001 code points | Xóa đúng null/empty policy, giữ nội dung hợp lệ, quá giới hạn không gửi hoặc server400 | CHƯA CHẠY |
| FD04 | Chọn từng priority; sửa request thành enum lạ/null | Bốn mức round-trip đúng, invalid server400, không silently đổi MEDIUM | CHƯA CHẠY |
| FD05 | Chỉ sửa title, các trường nullable giữ nguyên; xóa description/assignee/dueDate | PUT đủ 5 trường, không vô tình xóa trường chưa chỉnh, null clear đúng | CHƯA CHẠY |
| FD06 | Sửa rồi đưa về giá trị gốc; bấm Lưu khi no-op | Dirty trở clean theo policy, không gửi PUT hoặc lấy token | CHƯA CHẠY |
| MD01 | Preview headings/lists/emphasis/inline code/fenced code/link | Render đúng tập cú pháp đã công bố, chuyển tab không mất draft | CHƯA CHẠY |
| MD02 | PUT→GET→mở lại Markdown có newline, `<T>`, ampersand, quote, tiếng Việt | Cú pháp/literal code giữ đúng, không double-escape hoặc flatten newline | PARTIAL: backend integration xác nhận lưu nguyên source Markdown chứa HTML-looking text; full Unicode/code round-trip và browser preview chưa chạy. |
| MD03 | script, onclick, iframe, SVG, raw HTML, Markdown lồng payload | Không thực thi code/DOM nguy hiểm cả preview lẫn đọc lại | CHƯA CHẠY |
| MD04 | Link javascript/data/file/malformed; HTTP/HTTPS tab mới | Scheme nguy hiểm bị chặn, link hợp lệ có rel bảo vệ | CHƯA CHẠY |
| MD05 | Markdown ảnh ngoài/tracking URL; code block dài/unclosed | Không tự tải ảnh, không request theo dõi, UI không crash/tràn viewport | CHƯA CHẠY |
| MD06 | Tối đa description, gõ nhanh 20 lần, preview liên tục | Đạt budget mục 6.3; không request save theo phím, không stale preview | CHƯA CHẠY |
| AS01 | Load members > 1 trang, tìm người ở trang sau | Picker đọc API workspace thật, tải thêm/tìm kiếm chọn được đúng userId | CHƯA CHẠY |
| AS02 | Search A→B nhanh, response A tới sau; đổi workspace | B hiển thị đúng, query cũ/cache workspace cũ không lẫn | CHƯA CHẠY |
| AS03 | Unassigned; current assignee ngoài trang đầu; tên/avatar null/lỗi | Giữ ID đã lưu, nhãn dự phòng rõ, có thể bỏ giao; không tự reset khi load lỗi | CHƯA CHẠY |
| AS04 | Member API lỗi/empty/retry | Phân biệt lỗi với không có member; không dựng thành viên giả, giá trị hiện tại còn nguyên | CHƯA CHẠY |
| AS05 | Gán user ngoài workspace; user vừa bị gỡ | Server từ chối, draft giữ nguyên, không lộ danh bạ workspace khác | CHƯA CHẠY |
| DT01 | UTC/GMT+7 gần nửa đêm; lưu rồi mở lại/reload | Cùng thời điểm, nhãn timezone rõ, không lệch ngày | CHƯA CHẠY |
| DT02 | Chỉ sửa field khác khi dueDate có microseconds | Gửi nguyên dueDate cũ, không truncate do editor | CHƯA CHẠY |
| DT03 | Xóa hạn; hạn quá khứ; 29/02 năm nhuận/không nhuận | Null và hạn quá khứ lưu được; ngày không hợp lệ bị chặn | CHƯA CHẠY |
| DT04 | Thời gian DST thiếu/trùng; năm ngoài 0001..9999; ISO sai | Giải thích/không tự nhảy giờ; invalid bị server từ chối, không crash | CHƯA CHẠY |

### 6.6. Ma trận test — lưu, đồng bộ board và cạnh tranh

| ID | Thiết lập/thao tác | Expected | Thực tế |
|---|---|---|---|
| SV01 | Sửa riêng từng field và cả 5 field, PUT200, reload/mở lại | Persist đúng, dùng canonical response, card cập nhật tức thời không F5 | PASS backend integration: PUT task, đổi assignee/dueDate/priority/title và clear optional; frontend browser/reload chưa chạy. |
| SV02 | Server trim/sanitize/normalize thời gian khác draft | Modal/card theo canonical; thông báo chuẩn hóa đáng kể, không falsely dirty | CHƯA CHẠY |
| SV03 | Double click/Enter lặp trong cùng tick; save khi token/request đang chờ | Một mutation, nút pending rõ, không tranh token hoặc save trùng | CHƯA CHẠY |
| SV04 | Save khi move/reorder pending; mở modal rồi thử kéo board | Mutation khóa chung, giữ đúng thứ tự và snapshot | CHƯA CHẠY |
| SV05 | Title/priority/assignee đổi làm mất/khớp filter | Filter áp lại đúng, modal giữ mở, không sai total hoặc task trùng | CHƯA CHẠY |
| SV06 | Board cột chưa tải đủ; task di chuyển server trong lúc mở | Detail không phụ thuộc list đầy đủ; sync đúng cột/ID, không chèn sai page/position | CHƯA CHẠY |
| SV07 | PUT200 rồi refetch board lỗi | Báo task đã lưu, giữ canonical, board chờ sync; không rollback thành chưa lưu | CHƯA CHẠY |
| SV08 | PUT400/409 BOARD_BUSY/archived hoặc invalid assignee | Thông báo đúng hướng xử lý, giữ draft, card cũ đúng; không retry vô hạn | CHƯA CHẠY |
| SV09 | Hai user cùng base, B lưu trước A; race ngay trước commit A | If-Match stale trả412 atomically, B không bị ghi đè; A giữ draft/latest | CHƯA CHẠY |
| SV10 | Conflict: tải bản mới, áp lại thay đổi và lưu | Chỉ gửi ETag mới sau lựa chọn, latest không ghi đè draft ngầm | CHƯA CHẠY |
| SV11 | GET thiếu ETag; PUT thiếu/sai If-Match sau rollout | Modal không PUT vô điều kiện; server428/412 theo contract | CHƯA CHẠY |
| SV12 | Network timeout/disconnect trước commit; sau commit | Không replay PUT, GET reconcile; phân biệt đã xác nhận và chưa rõ | CHƯA CHẠY |
| SV13 | PUT5xx hoặc malformed success body; reconcile cũng lỗi | Giữ draft/status chưa rõ, khóa mutation xung đột, cho retry đọc có giới hạn | CHƯA CHẠY |
| SV14 | PUT401 access expired, refresh thành công/thất bại | Không replay mutation/token; đối soát hoặc về login, không loop | CHƯA CHẠY |
| SV15 | GET401 refresh thành công; nhiều GET401 đồng thời | Retry GET tối đa một lần, refresh single-flight, không duplicate apply | CHƯA CHẠY |
| SV16 | Task xóa/mất quyền/board archived giữa GET và PUT | Server từ chối, ngừng edit đúng trạng thái, không phục hồi task từ stale state | CHƯA CHẠY |
| SV17 | T-018 invalidation giả lập khi form clean/dirty/saving | Clean refresh được; dirty/saving giữ draft và báo latest, không overwrite | CHƯA CHẠY |

### 6.7. Ma trận test — bình luận

| ID | Thiết lập/thao tác | Expected | Thực tế |
|---|---|---|---|
| CM01 | Comments loading/empty/error/retry | Trạng thái riêng rõ, lỗi không biến thành không có comment | CHƯA CHẠY |
| CM02 | Hơn hai trang, trùng timestamp; append mới rồi refresh | Sort createdAt/id ổn định, dedupe ID, không thiếu/trùng sau tải lại | CHƯA CHẠY |
| CM03 | Gửi comment hợp lệ, mở lại task | POST201, tác giả phiên đúng, một comment persist, draft xóa sau xác nhận | CHƯA CHẠY |
| CM04 | Rỗng/whitespace; 1/5.000/5.001 code points; newline | Invalid bị client/server chặn, biên hợp lệ giữ format plain text | CHƯA CHẠY |
| CM05 | Nội dung HTML/script/link; giả authorId/taskId trong body | Render text an toàn, không thực thi/tin tác giả client, đúng task path | CHƯA CHẠY |
| CM06 | Double submit; request chậm; lỗi400/429/5xx | Không gửi trùng tự động, draft còn khi lỗi, retry theo trạng thái | CHƯA CHẠY |
| CM07 | Commit thành công nhưng mất response; comments GET cũng lỗi | Không tự POST lại, giữ trạng thái chưa rõ và cảnh báo; không falsely báo chưa gửi | CHƯA CHẠY |
| CM08 | Task draft dirty rồi gửi comment; save task khi comment draft dirty | Hai draft độc lập, không tự lưu/gửi chéo, đóng hỏi cho cả hai | CHƯA CHẠY |
| CM09 | Nonmember đọc/gửi; archived gửi; task deleted trước POST | 404/409 theo contract, không leak nội dung, không comment mồ côi | CHƯA CHẠY |
| CM10 | User/author không resolve; xóa task có comments ở fixture | Nhãn dự phòng; DB cascade đúng, privacy policy được kiểm chứng | CHƯA CHẠY |

### 6.8. Ma trận test — bảo mật, API client và khả năng tiếp cận

| ID | Thiết lập/thao tác | Expected | Thực tế |
|---|---|---|---|
| SE01 | Sửa taskId/workspaceId/member query sang workspace khác bằng API trực tiếp | Quyền server thực thi, không IDOR, không lộ tên/nội dung | CHƯA CHẠY |
| SE02 | Turnstile bật: thiếu/hết hạn/reuse/invalid token | Server từ chối, UI giữ draft, lần gửi mới lấy token mới | CHƯA CHẠY |
| SE03 | Missing site key/provider timeout; chế độ dev disabled | Production fail closed có thông báo; dev config rõ không thay bảo vệ server | CHƯA CHẠY |
| SE04 | Turnstile yêu cầu tương tác khi modal/confirmation đang mở | Challenge nhìn thấy/tương tác được, không focus deadlock hoặc tranh widget | CHƯA CHẠY |
| SE05 | Vượt actor quota, Retry-After; caller đổi ID để né quota | 429, hướng dẫn thời gian thử lại, không tự spam request | CHƯA CHẠY |
| SE06 | UTF-8/JSON escape vượt task/comment byte limit, cả chunked body | Server413 trước parse/write, client giải thích đúng; không lọt route comments | CHƯA CHẠY |
| SE07 | application/problem+json; application/json; HTML error; JSON hỏng | Parse an toàn, hiển thị thông điệp/correlationId đúng, không stack trace | CHƯA CHẠY |
| SE08 | Caller abort + timeout10s; refresh xong response task cũ | Cả cancel và timeout có hiệu lực, cleanup và generation ngăn stale apply | CHƯA CHẠY |
| SE09 | Quan sát storage/log/metrics/trace sau lỗi và logout | Không draft/comment/token/PII trong log/artifact; state sạch, metrics không UUID tags | CHƯA CHẠY |
| UX01 | Tab/Shift+Tab, Escape, screen reader labels/error/status | Focus nằm trong modal, labels rõ, error được thông báo, nền không tương tác | CHƯA CHẠY |
| UX02 | Save làm card biến mất theo filter rồi close | Focus chuyển về board control hợp lệ, không rơi vào body/element đã xóa | CHƯA CHẠY |
| UX03 | 360/768/1440 px; zoom200%; code/title/link dài | Nút tới được, scroll đúng vùng, không tràn viewport | CHƯA CHẠY |
| UX04 | Touch scroll/IME tiếng Việt/reduced motion | Không kích drag/save nhầm, dấu tiếng Việt đúng, hiệu ứng tuân setting | CHƯA CHẠY |
| UX05 | Mở/đóng 30 lần, chuyển task/board liên tục | Không listener/widget/scroll-lock leak, focus ổn định, request không tăng vô hạn | CHƯA CHẠY |

### 6.9. Ma trận test — chất lượng, hiệu năng và tích hợp

| ID | Thiết lập/thao tác | Expected | Thực tế |
|---|---|---|---|
| QA01 | Unit/component suites + frontend build/lint | Suites meaningful pass, TypeScript/lint sạch; report gắn commit | PARTIAL: `npm run build` PASS; `npm run lint` PASS. Chưa có frontend unit/component runner hoặc browser tests. |
| QA02 | Backend check và verifyModuleBoundaries sau thay đổi | Tests/checks pass, không impl→impl, migration mới chạy DB mới/nâng cấp | PASS: `./gradlew.bat check` thành công, gồm `:verifyModuleBoundaries` và test suite backend hiện có. |
| QA03 | PostgreSQL integration: stale writes đồng thời, comments/assignee/FK, rollback | Không lost update/data orphan; audit/revision chỉ thay khi commit đúng | PARTIAL: backend check và integration API hiện có pass; test race stale writes/comments/new members endpoint chưa có. |
| QA04 | Browser E2E qua Vite và Nginx, reload board deep link | Login/detail/save/members/comments thật pass, route/proxy/headers đúng | CHƯA CHẠY: chưa mở browser hoặc xác nhận qua backend/Nginx thật. |
| QA05 | Đo GET/PUT 200 request, 10 users; preview/modal budget mục6.3 | P95/budget đạt hoặc ghi FAIL rõ môi trường, không lấy build size làm latency | CHƯA ĐO. Bundle app 388.75 kB/125.69 kB gzip; modal 177.12 kB/53.95 kB gzip; đây không phải latency. |
| QA06 | Dependency review/audit, artifact inspection, regression T-016 | Không unresolved vulnerability ảnh hưởng runtime; không leak secret; DnD/filter/paging đúng | PARTIAL: npm install báo 0 vulnerabilities; build/lint pass. Browser regression/security artifact review chưa chạy. |
| QA07 | Đối chiếu PR/checklist/evidence và các gate G1–G5 | Hai AC gốc có demo/report, dependency xong, review/merge theo DoD | PARTIAL: code và API phụ thuộc đã có; chưa tạo PR, browser evidence hoặc review/merge. |

## 7. Cách chạy kiểm tra khi triển khai

Các lệnh dưới đây là kế hoạch, chưa được chạy cho T-017 ở bước lập tài liệu này.

```powershell
# Trong frontend/
npm run build
npm run lint

# Trong backend/ (khi hoàn thiện backend phụ thuộc)
.\gradlew.bat check
.\gradlew.bat :verifyModuleBoundaries

# Tại root, kiểm cấu hình Compose
docker compose config --quiet
```

Unit/component/E2E chạy theo scripts được thêm ở bước 1 và ghi chính xác vào phần Thực tế sau khi có config. Trước E2E xác nhận health backend/PostgreSQL, API members/comments/preconditions và cấu hình Turnstile. Nếu Docker/local không dùng được, ghi BLOCKED và dùng môi trường integration được cho phép; không tự nhận kết quả giả lập là pass hệ thống thật.

Sau mỗi lần chạy: điền từng ID/subcase, commit, ngày giờ, kết quả quan sát, evidence/bug ID; chạy lại case lỗi và regression bị ảnh hưởng. Chưa cần tạo test code hoặc chạy build/backend checks cho việc chỉ thêm file plan.

## 8. Rủi ro và quyết định cần theo dõi

| Rủi ro | Hướng xử lý/điểm kiểm chứng |
|---|---|
| Picker hoặc comments API chưa bàn giao | G1/G2 có owner/contract và test API thật trước full DoD; không dùng fixture làm kết quả nghiệm thu |
| Mở modal sửa stale snapshot rồi ghi đè user khác | G3 precondition atomically; case SV09/SV10 và PostgreSQL concurrency là gate |
| Sanitizer làm hỏng Markdown code/newline | G4 round-trip fixtures, sửa hẹp trong Board, test XSS và dữ liệu cũ song song |
| Turnstile nằm ngoài focus trap hoặc nhiều widget | Một cơ chế token/coordinator, mount challenge tương tác trong lớp modal, test SE04 |
| Timeout/body parser che lỗi thật hoặc replay mutation | Sửa client chung, hỗ trợ ProblemDetail/ETag/cancel, unknown state và no auto replay |
| Thay precondition làm hỏng client cũ | Rollout đồng bộ, xác định caller, compatibility tests; không bật bắt buộc một phía |
| Board refetch/filter làm mất draft hoặc sai vị trí | State riêng theo task ID; patch canonical summary/invalidate cột, không restore cả board |
| Scope lớn hơn frontend thuần do dependency | Chia ticket phụ có liên kết nếu cần; vẫn ghi rõ phần còn thiếu và chưa đánh dấu full T-017 Done |

## 9. Kết quả thực tế sau triển khai

- Đã thêm modal chi tiết mở từ task card; chỉnh title, Markdown, priority, assignee, due date; có comments thật, pagination, trạng thái lỗi/tải và cảnh báo draft khi đóng.
- Frontend cập nhật card từ canonical task response; dùng ETag/version để từ chối lưu stale, giữ draft và cho hợp nhất từng field với bản mới. Markdown source lưu nguyên văn; preview không bật raw HTML và không tải ảnh ngoài.
- Auth có endpoint members workspace phân quyền, tìm kiếm tên và pagination. Board có API comments phân trang, gửi theo actor phiên, quota/Turnstile, giới hạn payload, audit và realtime invalidation task detail. Migration V5 thêm version task.
- Integration tests hiện có được cập nhật cho If-Match và Markdown source. `npm run build` PASS; `npm run lint` PASS; `./gradlew.bat check` PASS (tests hiện có và module boundaries). Build chia thành app bundle 388.75 kB (125.69 kB gzip) và modal chunk 177.08 kB (53.94 kB gzip). NPM install báo 0 vulnerabilities.
- Trong 81 ID: SV01 PASS ở backend API integration; MD02 PARTIAL; QA01/QA03/QA06/QA07 PARTIAL; QA02 PASS; QA04 chưa chạy; QA05 chưa đo. Các case UI/browser/accessibility/security mới vẫn `CHƯA CHẠY` cho tới khi có evidence riêng.
- Chưa chạy browser E2E qua Vite/Nginx, chưa đo performance, chưa mở PR hoặc hoàn tất review/merge. Thử chuyển card sang In Progress theo workflow nhưng Trello trả HTTP 401 `unauthorized card permission requested`; cần cập nhật trạng thái/checklist sau khi quyền Trello cho phép và có evidence nghiệm thu.
