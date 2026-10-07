# T-013 — Plan triển khai API quản lý và di chuyển task

**Task gốc:** [04_api_quan_ly_va_di_chuyen_task.md](04_api_quan_ly_va_di_chuyen_task.md).

**Trello:** [T-013 — Implement REST Endpoints for Task CRUD & Reorder / Move Operations](https://trello.com/c/77BVUDYF/19-t-013-implement-rest-endpoints-for-task-crud-reorder-move-operations).

**Milestone:** 1 / Sprint 2. **Owner:** Xuân Lê. **Branch triển khai theo card:** `feature/T-013-task-crud-move-endpoints`.

**Phụ thuộc:** T-011 và T-012. **Bàn giao tiếp:** T-014 (event), T-015/T-017 (UI), T-018 (realtime).

**Ngày lập/cập nhật:** 07/10/2026. **Trạng thái triển khai:** Đã triển khai backend Task CRUD/move trên nhánh `feature/T-013-task-crud-move-endpoints`; `backend` `check` và `:verifyModuleBoundaries` đạt, 159 test pass, 0 fail/error/skip. Chưa commit/push/tạo PR. Ma trận bên dưới chỉ đánh dấu phần có test thực tế; concurrency, UAT thủ công, benchmark và một số biến thể chưa chạy vẫn để riêng.

## 1. Mục tiêu, nguồn yêu cầu và hiện trạng

### 1.1. Mục tiêu

Lưu toàn bộ vòng đời task Kanban vào PostgreSQL: tạo, đọc, sửa nội dung, xóa, đổi thứ tự trong cột và chuyển sang cột khác. Mọi thao tác phải đúng workspace, bảo toàn dữ liệu và thứ tự; lỗi hoặc request cạnh tranh không được để task bị mất, nhân đôi hoặc nằm ở hai cột.

### 1.2. Nguồn và phạm vi

| Nguồn đã đối chiếu | Vai trò trong plan |
|---|---|
| Yêu cầu của người dùng trong chat | Tạo plan cùng cấp, sau đó yêu cầu triển khai T-013; không yêu cầu commit/push hoặc tạo PR |
| [Task gốc](04_api_quan_ly_va_di_chuyen_task.md) | CRUD, các trường task, move/reorder, quyền và transaction |
| Card Trello, đọc trực tiếp ngày 07/10/2026 | `POST /columns/{id}/tasks`, `PUT /tasks/{id}`, `DELETE /tasks/{id}`, `PATCH /tasks/{id}/move`; hai acceptance criteria gốc |
| [ARCHITECTURE](../ARCHITECTURE.md), mục 2, 4, 6, 9.2, 10 | Module ownership, event bus, database và NFR; schema thực tế xem migration V2 |
| [PRODUCT_SPEC](../PRODUCT_SPEC.md), mục 5.1, 8 | Task details, security, audit, giới hạn payload và CRUD P95 < 200 ms |
| [IMPLEMENTATION_PLAN](../IMPLEMENTATION_PLAN.md), Phase 2 và DoD | API cốt lõi, phụ thuộc event/realtime và quality gates |
| [Plan T-012](03_api_quan_ly_board_va_column_plan.md), [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) | Policy quyền/archive, giao thức khóa chung, kiểm thử và review |

Hai acceptance criteria gốc:

- Tạo, sửa, xóa task thành công.
- API move xử lý cả đổi vị trí trong cột và chuyển sang cột khác.

Các giới hạn số lượng, hợp đồng đọc/phân trang, semantics PUT, status lỗi và chính sách chuyển cột dưới đây là **đề xuất kỹ thuật của plan**, không phải checklist đã thêm vào Trello. Tài liệu đính kèm được dùng làm nguồn phạm vi; yêu cầu hiện tại của người dùng là tạo plan.

Trong phạm vi: REST Task, DTO, service/repository, validation, quyền, transaction/locking, audit/metrics/rate limit, migration cần thiết và bộ test. Ngoài phạm vi: CRUD comment/tag, UI kéo thả, tìm kiếm nâng cao, WebSocket, tự động chuyển task từ Git/CI và triển khai publisher nghiệp vụ T-014. Giữ nguyên comment/tag khi sửa hoặc move; kiểm chứng cleanup khi xóa task.

### 1.3. Hiện trạng đọc từ repository

| Thành phần | Thực tế tại thời điểm lập plan | Hệ quả |
|---|---|---|
| `TaskEntity`, `TaskPriority` trong board-impl | Có column, title, description, position, priority, assignee UUID, dueDate `Instant`, comments và timestamps | Tái sử dụng mapping T-011; không tạo entity Task thứ hai |
| Migration `V2__board_schema.sql` | Position không âm; priority LOW/MEDIUM/HIGH/URGENT; assignee là soft UUID; due date TIMESTAMPTZ; FK nội bộ có cascade | Không dựa vào FK để kiểm tra assignee có quyền; không tự thêm FK sang Auth |
| `TaskRepository` | Có query thứ tự theo column/board, query task kèm column/board; chưa có API CRUD/move task trong controller | Cần query phục vụ scoped reads, phân trang và mutation |
| T-012 | Có actor adapter, synchronous membership event, rate limiter, audit service, ProblemDetail và khóa pessimistic hàng board | Dùng chung cơ chế; không inject Auth internal hoặc thêm limiter riêng cho từng route để bypass quota |
| Board GET | Trả board và column DTO, không nhúng toàn bộ task graph | Bổ sung API đọc task theo cột để tải lại thứ tự; giữ hợp đồng BoardResponse |
| Audit V3 | CHECK chỉ chấp nhận resource BOARD/COLUMN, action chưa có MOVE | Cần migration mới trước khi ghi audit TASK/MOVE; không sửa V3 đã phát hành |
| Metrics interceptor | Route patterns chưa có `/api/v1/tasks/**` | Phải mở rộng coverage cho GET/PUT/DELETE/move task |
| Membership listener Auth | Chỉ trả membership boolean, chưa trả role hoặc trạng thái active của user | Policy T-013 dựa trên membership; không tuyên bố phân quyền OWNER/ADMIN/READ_ONLY |
| BaseEntity / Task | Không có `@Version` | Khóa tuần tự hóa mutation; chưa có bảo đảm phát hiện request từ màn hình cũ |
| Bot protection | Chưa thấy verifier Turnstile trong các source Auth/Board đã kiểm tra | Là điểm phải xác định policy/tích hợp trước nghiệm thu NFR cho mutation nhạy cảm |

Đây là kết quả đọc source/schema, không phải kết quả chạy HTTP, migration mới hoặc benchmark T-013.

## 2. Kế hoạch nghiệp vụ — luồng bằng ngôn ngữ

### NV-01. Tạo task và tải danh sách

1. Thành viên mở một cột thuộc board của workspace mình được phép truy cập.
2. Nhập tiêu đề; có thể thêm mô tả, người phụ trách, hạn và độ ưu tiên. Không chọn priority thì mặc định MEDIUM; không chọn người phụ trách/hạn thì để trống.
3. Hệ thống kiểm tra dữ liệu, board còn hoạt động và người phụ trách được chọn thuộc cùng workspace.
4. Task mới được đặt cuối cột. ID và vị trí do server quyết định; người dùng không tự gán board/workspace/actor/position qua form tạo.
5. Khi tải lại, người dùng nhận đúng task của cột theo thứ tự đã lưu. Cột rỗng trả danh sách rỗng, không phải lỗi.

### NV-02. Xem và cập nhật thông tin

1. Người dùng mở task; hệ thống kiểm tra quyền theo workspace của task thật.
2. Có thể sửa tiêu đề, mô tả, người phụ trách, hạn và priority. Cột/vị trí chỉ được đổi qua thao tác move.
3. API `PUT` thay thế toàn bộ các trường nội dung cho phép: title và priority bắt buộc; bỏ hoặc gửi null cho description/assigneeId/dueDate sẽ xóa giá trị tương ứng. Không dùng semantics PATCH cho PUT.
4. Người phụ trách mới phải thuộc workspace. Cho phép hạn trong quá khứ để ghi nhận công việc quá hạn; không tự coi task là DONE khi hết hạn.
5. Dữ liệu sai ở bất kỳ trường nào khiến toàn bộ cập nhật bị từ chối. Task ID, ngày tạo, comments và tag mappings được giữ nguyên.

### NV-03. Đổi thứ tự trong cùng cột

1. Người dùng kéo task tới chỉ số cuối cùng mong muốn, tính từ 0.
2. Server lấy thứ tự mới nhất, bỏ task khỏi danh sách rồi chèn vào chỉ số đó; các task khác giữ thứ tự tương đối.
3. Vị trí phải nằm trong `0..N-1`, với N là số task hiện có của cột. Không tự clamp vị trí sai.
4. Kéo về đúng vị trí hiện tại là no-op khi thứ tự đã chuẩn; không đổi nội dung, không tạo audit thay đổi vô ích và không được hiểu thành đổi trạng thái trong T-014.
5. Sau khi tải lại, thứ tự giống dữ liệu đã commit.

### NV-04. Chuyển task sang cột khác

1. Chọn cột đích trong **cùng board** và vị trí muốn chèn.
2. Server kiểm tra cột đích, quyền, archive và giới hạn số task trước khi ghi.
3. Loại task khỏi cột nguồn, chèn vào cột đích, chuẩn hóa vị trí ở cả hai cột trong một lần lưu.
4. Cột đích rỗng chỉ nhận vị trí 0; cột đích có M task nhận vị trí `0..M`, trong đó M nghĩa là cuối danh sách.
5. Title/description/priority/assignee/dueDate/comments/tags và ID giữ nguyên. Nhóm trạng thái hiển thị được suy từ category của cột mới.
6. Không chuyển giữa hai board, kể cả người dùng thuộc cả hai. Chuyển giữa workspace càng không thuộc phạm vi. Cột ngoài board được xử lý như cột đích không tồn tại trong phạm vi được phép.
7. Nếu lưu thất bại, task và thứ tự của cả hai cột trở lại trước thao tác; không trả thành công cho một lần chuyển dở dang.

### NV-05. Xóa task

1. Người có quyền xóa task trên board đang hoạt động; thao tác là hard delete theo schema Kanban hiện có.
2. Xóa task, comments và task-tag mappings; giữ tag definition dùng chung trên board, các task khác, column, board và dữ liệu Auth.
3. Chuẩn hóa vị trí các task còn lại trong cột. Xóa task cuối cùng khiến cột rỗng nhưng không xóa cột.
4. Audit metadata còn lại để ghi nhận hành động; không sao chép nội dung task đã xóa vào audit.
5. Xóa lại một UUID đã xóa trả 404, không tạo lại dữ liệu.

### NV-06. Sai quyền, board archived và thao tác cạnh tranh

- Không đăng nhập/token không hợp lệ: 401. Task/cột không thuộc workspace được phép: 404 để không tiết lộ tài nguyên.
- Board archived vẫn đọc được nhưng chặn create/PUT/delete/move bằng 409; restore ở API T-012 trước khi thao tác.
- Assignee không tồn tại hoặc không thuộc workspace: cùng một lỗi 400, không trả hồ sơ người ngoài workspace.
- Hai mutation cùng board được xử lý tuần tự dựa trên dữ liệu mới nhất. Không hứa phát hiện màn hình cũ khi chưa có ETag/version.
- Tranh chấp khóa quá thời gian hoặc thiếu quyết định permission: lỗi hữu hạn, không ghi dữ liệu một phần. Frontend refetch khi lỗi và hoàn tác optimistic UI ở task UI tương ứng.

## 3. Kế hoạch kỹ thuật — thiết kế và các yếu tố cần suy xét

### 3.1. API và DTO đề xuất

Tất cả URI có prefix `/api/v1`, yêu cầu access JWT hợp lệ. Giữ `PUT` đúng card Trello.

| Method / URI | Request/query | Thành công |
|---|---|---|
| `POST /columns/{columnId}/tasks` | CreateTaskRequest: title, description?, priority?, assigneeId?, dueDate? | 201, `Location: /api/v1/tasks/{id}`, TaskResponse; append cuối cột |
| `GET /columns/{columnId}/tasks` | page=0, size=20; size tối đa 100 | 200, TaskPageResponse; sort cố định position,id tăng dần |
| `GET /tasks/{taskId}` | UUID | 200, TaskResponse |
| `PUT /tasks/{taskId}` | UpdateTaskRequest: title, priority, description?, assigneeId?, dueDate? | 200, TaskResponse sau thay thế các trường nội dung |
| `DELETE /tasks/{taskId}` | UUID | 204, body rỗng |
| `PATCH /tasks/{taskId}/move` | MoveTaskRequest: `{ "columnId": "<destination UUID>", "position": 0 }` | 200, TaskMoveResponse |

- Public DTO/enum đặt trong `board-api`, chỉ phụ thuộc `common`; controller/service/entity/repository đặt trong `board-impl`. Chọn tên public enum TaskPriority khác package entity và map rõ ràng.
- TaskResponse: id, columnId, boardId, workspaceId, title, description, priority, assigneeId, dueDate, position, statusCategory, createdAt, updatedAt. Không serialize entity, proxy, email, comments hoặc tag graph.
- TaskPageResponse chứa items và metadata page/size/totalElements/totalPages. Item danh sách có metadata cần dựng card; không mang description dài/comments. Dùng DTO riêng, giữ chữ ký TaskSummary/BoardApi cũ.
- TaskMoveResponse chứa task sau move, sourceColumnId, destinationColumnId. Client tải lại các cột bị ảnh hưởng qua API danh sách; không trả toàn bộ task graph trong response move.
- Ghi rõ `@PathVariable("...")` và `@RequestParam(name="...")`; không phụ thuộc compiler `-parameters` để bind request.
- Không tự bổ sung PATCH nội dung, batch move, status field ghi trực tiếp hoặc cross-board transfer trong T-013.

### 3.2. Validation, chuẩn hóa và giới hạn

| Dữ liệu | Quy tắc đề xuất |
|---|---|
| title | Bắt buộc; strip khoảng trắng đầu/cuối, loại HTML/script, kiểm tra lại có ký tự hiển thị; tối đa 255 Unicode code points |
| description | Nullable, cho phép chuỗi rỗng; tối đa 10.000 code points trước/sau sanitize; giữ văn bản Markdown thông thường, loại HTML nguy hiểm; frontend phải render Markdown bằng cơ chế an toàn ở T-017 |
| priority | LOW/MEDIUM/HIGH/URGENT dạng string chính xác; create omitted = MEDIUM, explicit null bị 400; PUT omitted/null bị 400 |
| assigneeId | UUID hoặc null; chỉ gán thành viên workspace; không biến actor thành assignee theo ngầm định |
| dueDate | ISO 8601 có offset/Z -> Instant UTC, nullable; cho phép quá khứ; bỏ/gửi null trong PUT = xóa; từ chối date-only, timezone thiếu và số epoch |
| position trong move | Integer bắt buộc >= 0; range kiểm tra bằng số task mới nhất dưới lock; từ chối chuỗi, boolean, số lẻ và overflow |
| path UUID | UUID hợp lệ, không có actor/workspace/board ID do client tự chọn trong nội dung task |
| collection | page >= 0, size 1..100; sort không do client tùy ý ghi đè |
| số task/cột | Đề xuất max 1.000, có cấu hình; create/cross-column move vượt limit trả 409; reorder trong cột đủ limit vẫn hợp lệ |
| request body | Đề xuất tối đa 64 KiB cho Task REST, chặn cả request chunked; vượt limit 413, không chỉ dựa vào Content-Length |

Jakarta `@Valid`/constraints kết hợp validator domain; validation ở service vẫn bảo vệ entry point không qua HTTP. Kiểm tra độ dài Unicode theo code points tương thích VARCHAR PostgreSQL; không mặc định `@Size` UTF-16 là cùng đơn vị. Không chấp nhận coercion JSON nguy hiểm hoặc enum dạng số. Reject field lạ/immutable như id, boardId, workspaceId, actorId, position trong create/PUT, timestamps, comments/tags. Duplicate JSON key phải có policy từ chối để tránh payload nhập nhằng; không đổi parser toàn app nếu ảnh hưởng Auth mà chưa có regression test.

PostgreSQL TIMESTAMPTZ có độ chính xác hữu hạn; chuẩn hóa dueDate về microsecond trước lưu/response và test round-trip. Không biến dueDate thành LocalDate trong REST. BoardApi cũ vẫn map ngày UTC theo hợp đồng T-012.

### 3.3. Actor, workspace và assignee

1. Lấy UUID actor qua `AuthenticatedActorProvider` / public contract `AuthenticatedActor`; không parse email/username, cast Auth internal hoặc nhận userId từ request.
2. Resolve task/column -> board -> workspace bằng dữ liệu Board sở hữu.
3. Tái sử dụng `WorkspaceMembershipPermissionService`: publish synchronous `WorkspaceMembershipCheckRequestedEvent` trong common; Auth listener quyết định trên cùng thread.
4. Chính xác một phản hồi membership hợp lệ mới cho phép tiếp tục. Không listener, nhiều phản hồi hoặc listener exception -> 503 và rollback; không fallback allow.
5. Kiểm tra assignee bằng cùng gateway với `assigneeId` và workspace của task. Actor logging/audit vẫn là người gọi, không đổi thành người phụ trách. Không gọi AuthApi trực tiếp, không query users/workspace_members từ Board.
6. Query cột đích phải scoped theo board đã cho phép: `findByIdAndBoard_Id`. Không trả thông tin cột ngoài board; 404 thống nhất cho missing/out-of-scope destination.
7. Khi PUT giữ assignee đã bị thu hồi membership, đề xuất vẫn yêu cầu hợp lệ hoặc clear/reassign; GET không tự xóa dữ liệu. Ghi policy này trong API contract.

Policy T-012 hiện cho mọi thành viên cùng quyền CRUD. Role-based write/delete hoặc yêu cầu user active cần contract Auth riêng; boolean membership hiện có không chứng minh những quyền đó. Membership được kiểm tra mới mỗi request; không cam kết atomic với thu hồi membership xảy ra giữa một request nếu chưa có cơ chế phối hợp transaction giữa các module.

### 3.4. Luồng transaction và khóa chung với T-012

```text
JWT -> request validation -> actor -> resolve resource IDs + membership
    -> rate limit / bot policy -> begin mutation transaction
    -> lock Board -> đọc lại trạng thái board/task/columns và quyền
    -> validate assignee, archive, range, capacity
    -> mutate task + positions -> flush -> audit cùng transaction
    -> commit -> trả DTO
```

Đây là luồng logic; khi dùng proxy Spring, transaction có thể bắt đầu trước các bước kiểm tra. Điểm bắt buộc là không giữ board lock trong lúc gọi dịch vụ mạng bên ngoài. TaskManagementService có public mutation `@Transactional`; reads `readOnly=true`. Nếu tách facade/transactional service, không self-invoke làm mất proxy transaction.

- Mọi create/PUT/delete/reorder/move task phải lấy **cùng `BoardRepository.findByIdForUpdate`** như create/delete column, đổi category, archive/delete board của T-012.
- Lock ordering: board trước, rồi cập nhật child. T-013 không lock task rồi chờ board. Chuyển cột trong cùng board chỉ cần một board lock; board khác thao tác độc lập.
- Resolve sơ bộ nên trả scalar projection ID, tránh nạp managed task/board cũ vào persistence context trước khi chờ lock. Sau khi có lock, đọc lại board archived, task.columnId, source/destination và siblings. Nếu đã nạp entity thì refresh rõ ràng; query lại một managed entity không tự bảo đảm dữ liệu hết stale.
- T-012 cũng phải tránh trạng thái archived cũ sau chờ lock. Nếu guard hiện có giữ entity cũ, sửa guard/refresh trong cùng ticket tích hợp và chạy regression T-012; không chỉ sửa riêng Task service.
- Task đã bị xóa trong lúc chờ: 404. Task đã move bởi request khác: lấy nguồn mới nhất; destination/range kiểm tra lại theo dữ liệu mới, không dựa vào index UI cũ.
- Với create/move vào cột và delete/category update cột: giao thức lock chung bảo đảm hoặc task được lưu trước nên column mutation thấy count mới và bị chặn, hoặc column mutation chạy trước nên Task request thấy missing/category mới. Không còn check-count rồi write không có khóa chung.
- Đề xuất lock timeout 3 giây, statement timeout 10 giây trong transaction; xác minh bằng PostgreSQL thật. Không coi JPA hint là đã có hiệu lực nếu driver/provider bỏ qua; dùng cấu hình/`SET LOCAL` trên cùng connection khi cần. Lỗi lock timeout/deadlock -> 409 `BOARD_BUSY`, transaction rollback trước khi client retry.
- Không retry mutation tự động mù: request create có thể đã commit khi response mất; chưa có idempotency key nên retry có thể tạo task khác. Move dùng chỉ số tuyệt đối cũng phải refetch nếu có cập nhật cạnh tranh.

### 3.5. Thuật toán position

Mỗi cột phải có tập vị trí `0..N-1`, mỗi task đúng một column. Đọc đầu vào bằng `position ASC, id ASC`, không lấy thứ tự bất định từ collection JPA.

**Create:** dưới board lock, kiểm tra capacity; normalize siblings nếu dữ liệu cũ lệch; tạo ở index N.

**Reorder:** lấy source list gồm task đang move; xác nhận target trong `0..N-1`; remove task theo UUID, insert tại target; gán lại index cho mọi task. No-op chỉ khi vị trí và toàn bộ list đã chuẩn; dữ liệu legacy trùng/gap vẫn cần normalize.

**Cross-column move:** source list bỏ task; target list có M task và target trong `0..M`; chuyển FK sở hữu task, insert target; normalize cả hai. Không đổi thứ tự các cột của board. Category mới suy từ destination, không cần cột `task.status` riêng.

**Delete:** xóa task rồi normalize các siblings còn lại. Persist task/positions/audit trong cùng transaction, flush trước dựng kết quả cần UUID/timestamp DB.

- Tránh tìm task bằng vị trí duy nhất; schema V2 chưa có UNIQUE(column_id,position). Khóa giao thức và test invariant mới bảo đảm thứ tự; writer SQL ngoài giao thức có thể phá invariant.
- Không thêm UNIQUE ngay mà chưa giải quyết hoán đổi positions và dữ liệu legacy. Nếu chọn constraint deferred/bulk update, có migration/test riêng; plan mặc định giữ schema position hiện có và kiểm tra invariant.
- Với tối đa 1.000 task/cột, thuật toán O(N) hoặc O(N+M) có giới hạn; cấu hình batching và đo query/latency thay vì mặc định gọi save từng task.

### 3.6. JPA, delete cascade và migration

- `TaskEntity.moveTo` hiện đồng bộ cả hai phía và có thể khởi tạo `column.tasks`. Xem SQL/query count; không vô tình tải comments/graph. Nếu tối ưu helper, phải giữ FK owning side và consistency cho collection đã load, không dùng orphanRemoval làm mất task khi move.
- Read DTO trong transaction/fetch plan rõ ràng với `open-in-view=false`; fetch column/board đủ lấy quyền/status nhưng không fetch bag comments/tags cho mỗi task.
- Child delete phải kiểm chứng cả khi parent collection đã load: cascade PERSIST/MERGE không được tái tạo task đã xóa. Có thể dùng SQL delete có tham số, flush trước và clear/refresh đúng lúc theo precedent T-012; không clear trước khi positions/audit đang chờ được flush.
- Migration kế tiếp dự kiến `V4__task_api_audit.sql` nếu version còn trống lúc triển khai: mở rộng audit resource TASK và action MOVE, giữ BOARD/COLUMN/actions cũ. Cập nhật CHECK bằng migration mới, không sửa checksum V2/V3.
- Delete task cascade comments và mappings; tag definitions phải giữ vì có thể dùng bởi task khác. Không thêm cross-module FK/JPA association cho assignee.
- Audit TASK CREATE/UPDATE/DELETE/REORDER/MOVE cùng transaction; rollback hoặc failure audit không được để mutation commit riêng. Với move ghi metadata source/destination IDs và changed fields, không sao chép description/title/token vào log/audit.
- Quyền erasure/retention audit actor và IP theo policy dữ liệu dự án; soft UUID không tự anonymize khi xóa account. Không tuyên bố T-013 tự hoàn tất toàn bộ GDPR workflow.

### 3.7. Error handling và chống lạm dụng

| HTTP | Nhóm lỗi | Hành vi |
|---|---|---|
| 400 | Validation, JSON/UUID/enum/date sai, empty/missing PUT fields, invalid position, invalid assignee | Không đổi DB; field/code rõ ràng nhưng không tiết lộ user ngoài workspace |
| 401 | Không JWT hoặc JWT sai/hết hạn/refresh token dùng như access | Security filter trả ProblemDetail |
| 404 | Missing hoặc out-of-scope task/column/destination | Cùng phản hồi an toàn; không lộ board/workspace khác |
| 409 | BOARD_ARCHIVED, TASK_LIMIT_REACHED, BOARD_BUSY, data conflict | Rollback; client refetch/retry phù hợp |
| 413 / 415 | Payload quá giới hạn / Content-Type không hỗ trợ | Không parse/lưu payload quá lớn |
| 429 | Quota actor đã hết | ProblemDetail + Retry-After; không mutation |
| 503 | Permission hoặc bot verification không sẵn sàng/không có quyết định hợp lệ | Fail closed; không ghi dữ liệu |
| 500 | Lỗi nội bộ không dự kiến | Message chung, correlation ID; không stack trace, SQL hay token |

Board advice cần xử lý TaskController cùng package và precedence với Auth advice; thêm mapping lock exceptions cụ thể trước catch-all. Test `application/problem+json` cho MVC lẫn security filter. Không catch mọi lỗi rồi chuyển thành 400 hoặc 404.

Tái sử dụng quota Board actor mặc định 60 mutation/60 giây trên một instance cho cả Board/Column/Task; đổi task UUID/route không tạo bucket mới. Quota và bot token là tài nguyên kiểm soát request, không rollback cùng DB. Load test phải ghi cấu hình quota được dùng. Redis/distributed quota chưa thuộc T-013, không hứa quota chia sẻ nhiều instance.

**Gate bot protection:** AGENTS yêu cầu Turnstile với mutation form nhạy cảm; PRODUCT_SPEC/ARCHITECTURE nêu public mutations. Khi triển khai, xác định rõ route/form Task nhạy cảm (đặc biệt thay nội dung/assignee và delete), token/header contract với frontend và verifier owner. Nếu áp dụng verifier do Auth sở hữu, đi qua typed event common; không dùng Auth internal. Không biến drag/reorder thành CAPTCHA lặp vô nghĩa mà chưa chốt policy. Route đã xác định cần challenge phải verify server-side, single-use, fail closed trước lock; có connect/read timeout, xử lý lỗi mạng hữu hạn, không log token. Không đánh dấu NFR này hoàn tất chỉ vì endpoint có JWT/rate limit. Phần mapping policy chưa chốt được giữ ở checklist G3, không tự miễn yêu cầu AGENTS.

### 3.8. Observability, public contract và bàn giao event

- Mở rộng metrics patterns cho `/api/v1/tasks/**`, giữ `/columns/**`; timer/counter gắn route template, method, status, không gắn task UUID/actor UUID làm label gây cardinality cao.
- MDC/correlation header bao phủ Task request; service actor context đúng; không log bearer token, nội dung task hoặc hồ sơ assignee. Health/Auth routes giữ hành vi cũ.
- Regression `BoardApi.findTask/findTasksByProject`: task vừa tạo/sửa/move/xóa phản ánh đúng projection hiện có; giữ `projectId=workspaceId`, status từ category và LocalDate UTC. Chưa bổ sung cross-module direct callers.
- T-013 chuẩn bị vị trí hook cùng snapshot taskId, boardId, workspaceId, source/destination column/category/name, actor, cause MANUAL. **Publisher `TaskCreatedEvent`/`TaskStatusChangedEvent` thuộc T-014**, không đánh dấu event/realtime đã triển khai trong T-013.
- Hợp đồng hiện có `TaskCreatedEvent.projectId` là workspace ID; `TaskStatusChangedEvent` chưa có board/column IDs và mô tả status là tên cột. T-014 phải đối chiếu schema/category và consumer trước thay payload; không tự truyền boardId vào projectId.
- T-014 chỉ phát thông báo nghiệp vụ khi mutation commit thành công; same-column reorder không là status change. Cross-column cùng category vẫn là đổi column, cần giữ cả category và column IDs để tránh mất thông tin. In-process event bus chưa là durable delivery/outbox; không hứa exactly-once hoặc realtime < 1 giây ở ticket API này.

### 3.9. Quyết định cần xác nhận trong review trước code

| Gate | Đề xuất của plan | Điều kiện chốt |
|---|---|---|
| G1 — API/update | PUT full content replacement; optional nullable fields omitted = clear; move tách riêng | DTO/API docs và frontend consumer thống nhất semantics |
| G2 — move/limit | Chỉ cùng board; position 0-based; max 1.000 task/cột, page max 100 | Chốt config và HTTP errors; frontend dùng final index đúng |
| G3 — bot policy | Phân loại sensitive mutations, tích hợp challenge khi policy yêu cầu | Route/token/verifier owner rõ ràng, không có ngoại lệ ngầm với AGENTS |
| G4 — quyền | Mọi member CRUD; assignee phải thuộc workspace | Không gọi membership là role permission hoặc active-user validation |
| G5 — concurrency | Board lock chung T-012 + đọc lại state, timeout hữu hạn | Test PostgreSQL race task/column/archive pass, không stale persistence context |

Đây là các điểm review thiết kế trong plan; không phải yêu cầu người dùng phê duyệt để tạo file tài liệu hiện tại.

## 4. Thứ tự triển khai và sản phẩm bàn giao

| Bước | Công việc | Bằng chứng bàn giao / trạng thái thực tế |
|---|---|---|
| P1 | Đọc card hiện hành, plan phụ thuộc, architecture và code T-011/T-012 | Hoàn tất; policy/API contract được triển khai ở các mục 3.1–3.9. |
| P2 | Tạo feature branch theo card; chuyển Trello In Progress khi bắt đầu code | Đã tạo `feature/T-013-task-crud-move-endpoints` dựa trên branch T-012 chưa merge vì dependency chưa có ở `develop`. Thử chuyển Trello sang In Progress nhưng API trả 401 `unauthorized card permission requested`; chưa cập nhật được card. |
| P3 | DTO, enum, validation/body cap, error codes | Hoàn tất; compile và request tests pass, gồm validation/null/body-size case. |
| P4 | Query/guard/lock chung và migration audit mới | Hoàn tất; migration V4 chạy trong test PostgreSQL 17; refresh state sau board lock. Race/rollback stress chưa chạy. |
| P5 | Create/list/get/PUT/delete, assignee, archive | Hoàn tất; HTTP integration kiểm tra CRUD, membership, archive, cascade/audit trên PostgreSQL. |
| P6 | Reorder/move và normalize positions | Hoàn tất cho tested path same-column và cross-column; concurrency matrix và toàn bộ vị trí đầu/giữa/cuối còn thiếu. |
| P7 | Rate limit, bot policy, metrics/MDC/advice | Hoàn tất implementation; unit tests cho Turnstile mock/fail-closed và API test cho correlation body-limit; live verifier/metrics toàn route chưa test. |
| P8 | Full regression, manual workflow và performance | `./gradlew.bat check` pass, 159 tests/0 fail/error/skip; UAT thủ công và benchmark chưa chạy. |
| P9 | Review PR, cập nhật checklist Trello, bàn giao T-014/frontend | Chưa commit/push/PR/review/merge; Trello không đổi do 401. T-014/event publisher và frontend/realtime là phần bàn giao ngoài phạm vi này. |

Chưa commit/push hoặc mở PR theo phạm vi yêu cầu hiện tại. Không mặc định trạng thái Trello đã đổi khi API từ chối quyền.

## 5. Tiêu chí hoàn thành

### 5.1. Acceptance criteria gốc

- [x] AC-01: tạo, đọc/danh sách, PUT và xóa task thành công qua HTTP trên PostgreSQL; test kiểm tra dữ liệu DB, GET sau delete trả 404, và siblings được đánh lại vị trí.
- [x] AC-02: test move/reorder đầu cột và chuyển giữa hai cột trên PostgreSQL; positions và IDs ở source/target được kiểm tra sau thao tác. Biến thể đầu/giữa/cuối và bảo toàn task có comment/tag chưa chạy hết.

### 5.2. Điều kiện nghiệm thu bổ sung

- [ ] G1–G5 đã chốt, request/response/status/default/null semantics được ghi rõ.
- [ ] Mọi route/service kiểm tra actor/workspace thật; assignee validation không bypass boundary; negative mutation không đổi DB.
- [ ] Archive chặn toàn bộ mutation Task; GET/list vẫn được đọc theo quyền.
- [ ] Server validation, sanitization, limits/coercion/mass-assignment/body cap được kiểm thử.
- [ ] Task/Column/Board dùng cùng lock protocol; timeout, refresh state, concurrency và injected rollback được kiểm chứng trên PostgreSQL.
- [ ] Delete cleanup đúng comments/mappings, giữ tag definitions và siblings; parent collection đã load không resurrect child.
- [ ] Audit TASK/MOVE được migration hỗ trợ; audit và mutation commit/rollback cùng nhau; không lộ nội dung nhạy cảm.
- [ ] ProblemDetail cho security/MVC/lock/limit/unexpected errors; quota actor, Retry-After và bot policy có bằng chứng.
- [ ] Task metrics/correlation hoạt động; response không serialize lazy graph, query và cardinality hữu hạn.
- [ ] BoardApi signatures giữ nguyên; regression Auth/T-010/T-011/T-012 pass.
- [x] `:verifyModuleBoundaries` và full `check` pass, integration test không skip vì thiếu Docker (159 tests, 0 skipped trong lượt 07/10/2026).
- [ ] Workflow thủ công và mục tiêu P95 CRUD < 200 ms có số liệu và điều kiện đo; không suy từ build pass.
- [ ] Nếu thay frontend: build/lint pass; nếu chỉ backend ghi rõ frontend checks không áp dụng theo DoD chung. **Thực tế:** chỉ thay backend, frontend checks không áp dụng.
- [ ] PR được review, squash merge develop, commit theo convention; Trello/checklist cập nhật theo bằng chứng thật.

AC gốc và regression/build gate đã có bằng chứng pass. Các checkbox bổ sung còn để mở nếu điều kiện trong checkbox bao gồm kiểm thử concurrency/rollback, đo hiệu năng, UAT hoặc observability rộng hơn chưa được thực hiện; một suite pass không tự đóng mọi case trong ma trận.

## 6. Kế hoạch testing — expected và thực tế

### 6.1. Tầng test, fixture và cách ghi kết quả

| Tầng | Suite đề xuất | Mục tiêu |
|---|---|---|
| Unit/domain | `TaskMutationRequestGuardTest` (4 tests) và `BoardRateLimiterTest` | Turnstile success/reject/fail-closed/local-disabled; quota cơ bản |
| HTTP full app | 6 task test methods trong `BoardColumnApiIntegrationTest` | MockMvc/JWT thật/Auth membership event/PostgreSQL/Flyway; CRUD, move, permission/archive, validation, pagination, body-size guard |
| Persistence/transaction | Assertions trực tiếp trên PostgreSQL trong test app | Position, cascade/comments/tags, audit, dueDate precision; chưa có injected rollback suite |
| Concurrent DB | TaskConcurrencyIntegrationTest | Nhiều connections/transaction độc lập, board lock, race và timeout |
| Operational/regression | T-012/Auth suites + TaskObservabilityTest | Advice/filter/quota/metrics/public contract và migration |
| Manual/performance | API workflow và load script riêng | Reload, latency/query/lock wait theo môi trường ghi nhận |

Fixture: workspace WA/WB; UA chỉ thuộc WA, UB chỉ thuộc WB, UAB thuộc cả hai, UX không thuộc workspace. BA và BC cùng WA, BB thuộc WB; board archived riêng. BA có C0(TODO), C1(IN_PROGRESS), C2(TODO), C3(DONE); cột rỗng và cột đủ limit riêng. Task T0..T3 có positions 0..3, khác nội dung, assignee, UTC dueDate; một task có comments, shared tag và mapping. Fixtures riêng cho từng test, không dùng dữ liệu làm việc thật.

Negative case chụp trước/sau task, siblings, comments/mappings/audit và kiểm tra không thay đổi. Assertion đọc ở transaction mới sau commit/rollback; không chỉ assert HTTP hoặc mock save. Concurrency dùng latch/barrier, timeout và invariant, không dùng sleep ngẫu nhiên hoặc giả định thread nào thắng. Rollback fault injection đặt sau một phần mutation và sau audit flush; gọi qua proxy thật, không để outer test transaction che lỗi commit.

Quy ước: `Chưa chạy` là chưa có bằng chứng; `PASS` phải có test/assertion/report; `PARTIAL` liệt kê biến thể còn thiếu; `FAIL` ghi actual khác expected và lỗi liên quan. Mỗi nhóm ghi test name, thời gian, môi trường khi thực thi. Các biến thể trong một dòng phải chạy đầy đủ mới được coi PASS.

**Lượt kiểm tra thực tế ngày 07/10/2026:** trong `backend/` chạy `./gradlew.bat check` khi Docker Desktop hoạt động; build `BUILD SUCCESSFUL`, `:verifyModuleBoundaries` pass, 23 test suites / 159 tests / 0 failures / 0 errors / 0 skipped. Integration tests chạy PostgreSQL 17 qua Testcontainers, Flyway bật và Hibernate `ddl-auto=validate`. Có 6 test method cho Task HTTP/DB trong `BoardColumnApiIntegrationTest` và 4 unit tests cho `TaskMutationRequestGuardTest`. Chưa chạy manual UAT, benchmark, test nhiều transaction cạnh tranh, fault-injection rollback hoặc live Cloudflare siteverify. Báo cáo Gradle nằm ở `backend/app/build/reports/tests/test/` và `backend/board-impl/build/reports/tests/test/`.

### A. Tạo, đọc và danh sách

| ID | Case / thiết lập | Expected | Thực tế |
|---|---|---|---|
| A01 | Create đầy đủ dữ liệu trong cột được phép | 201 + Location; UUID mới, column/board/workspace đúng, timestamps, fields lưu đúng | PARTIAL — response 201/Location, ID/column/position được kiểm tra; bộ test chưa create cùng lúc toàn bộ description/assignee/dueDate. |
| A02 | Create chỉ title; bỏ priority/optional fields | MEDIUM, description/assignee/dueDate null, append đúng | PASS — `validatesTaskPayloadDefaultsAndPagination`: tạo chỉ title, MEDIUM mặc định, position 0. |
| A03 | Tạo đầu tiên, nhiều task, title trùng | Positions liên tục; title trùng được phép, UUID khác | Chưa chạy |
| A04 | Title Unicode/emoji/khoảng trắng/HTML, description Markdown/script | Trim/sanitize đúng, Unicode và Markdown text thường giữ; không echo markup nguy hiểm | PARTIAL — kiểm tra trim/sanitize title và description Markdown/script qua create/PUT; chưa kiểm tra Unicode/emoji boundary đầy đủ. |
| A05 | GET task có comments/tags | 200 DTO đúng; không graph/proxy/PII hoặc recursion | Chưa chạy |
| A06 | List cột rỗng và cột có nhiều task | Items đúng cột, order position,id; rỗng 200 với metadata đúng | PARTIAL — list 3 task theo thứ tự và page size 2/total 3; chưa kiểm tra cột rỗng. |
| A07 | List page đầu/giữa/cuối/ngoài range; size 1/100 | Không thiếu/trùng trong dataset tĩnh; ngoài range rỗng, total đúng | PARTIAL — page đầu size 2/total 3 và reject page âm/size 101; chưa thử page giữa/cuối/ngoài range và size boundary. |
| A08 | Dữ liệu position trùng/gap, list nhiều lần | Đọc ổn định position,id; GET không tự mutate data | Chưa chạy |
| A09 | DueDate có Z/offset, past/future và microsecond | Cùng Instant sau round-trip; past hợp lệ; không đổi timezone ngoài contract | PARTIAL — PUT date có nanoseconds được đọc lại ở precision microseconds theo PostgreSQL; offset/past/future chưa phủ hết. |
| A10 | Reload sau create và kiểm tra BoardApi projection | REST/task lookup phản ánh dữ liệu commit; public UTC mapping đúng | Chưa chạy |

### B. PUT, assignee và bảo toàn nội dung

| ID | Case / thiết lập | Expected | Thực tế |
|---|---|---|---|
| U01 | PUT đầy đủ tất cả mutable fields | 200; fields đổi đúng, id/column/position/createdAt giữ | PASS — title/description/priority/assignee/dueDate cập nhật; column/position giữ. |
| U02 | PUT bỏ description/assignee/dueDate; gửi explicit null/rỗng | Optional omitted/null xóa giá trị; description rỗng giữ chuỗi rỗng | PARTIAL — explicit null xóa description/assignee/dueDate; semantics omitted và chuỗi rỗng chưa kiểm tra riêng. |
| U03 | PUT thiếu/null title hoặc priority | 400, không biến PUT thành partial update | PARTIAL — thiếu priority trên PUT bị 400; chưa phủ đủ null/thiếu title và null priority trên PUT. |
| U04 | PUT cùng dữ liệu đã chuẩn | 200 no-op; không đổi position, không audit mutation vô ích | Chưa chạy |
| U05 | PUT title/description có markup, Unicode boundaries | Dữ liệu sanitize và giới hạn đúng như create | PARTIAL — HTML/script sanitize được assert; Unicode và boundary size chưa test. |
| U06 | Assign mình/người khác trong WA, clear assignee | Thành công; actor audit vẫn là người gọi, không phải assignee | PARTIAL — assign member hợp lệ rồi clear bằng null; chưa có hai member khác nhau trong WA. |
| U07 | Assignee nonexistent/ngoài WA, kể cả UAB là actor | Cùng lỗi 400; không leak user profile; task/audit không đổi | PASS — assignee không thuộc workspace bị `INVALID_ASSIGNEE`; DB không gán assignee. |
| U08 | Assignee bị thu hồi membership, PUT giữ lại hoặc clear | Giữ assignee không hợp lệ bị 400; clear/reassign member hợp lệ được phép | Chưa chạy |
| U09 | Một field hợp lệ đi cùng field sai/permission failure | Toàn bộ PUT rollback; không cập nhật title dở dang | PARTIAL — invalid assignee request bị từ chối; test chưa assert field hợp lệ khác không đổi bằng một giá trị khác biệt. |
| U10 | PUT task có comment/tag/dueDate, reload | Comments/mappings và FK còn; ngày tạo bất biến, cập nhật timestamp đúng nếu đổi | Chưa chạy |

### C. Delete và cleanup

| ID | Case / thiết lập | Expected | Thực tế |
|---|---|---|---|
| D01 | Delete đầu/giữa/cuối trên fixture riêng | 204 body rỗng; removed UUID không còn; siblings 0..N-2 | PARTIAL — xóa task đầu trong cột hai task, API trả 204, GET 404 và task còn lại được reindex 0; chưa thử giữa/cuối và assert response body rỗng riêng. |
| D02 | Delete task duy nhất | Task bị xóa; cột/board còn, list rỗng | Chưa chạy |
| D03 | Delete task có comments/tag mappings và shared tag | Comments/mappings của task mất; tag definition và task khác còn | PASS — kiểm tra comment/mapping bị xóa, shared tag và mapping task khác vẫn còn trên PostgreSQL. |
| D04 | Delete khi column.tasks/comments đã được load | Child không bị cascade persist lại; DB đúng sau flush/clear | Chưa chạy |
| D05 | Delete missing UUID hoặc gọi delete lại | 404 ProblemDetail; không ghi audit success mới | PARTIAL — GET task đã xóa trả 404; gọi DELETE lần hai/chưa tồn tại chưa được assert. |
| D06 | Delete rồi đọc từ request mới và BoardApi | GET 404, projection empty; workspace/user/board khác còn | Chưa chạy |
| D07 | Inject lỗi sau delete/reindex/audit | Task graph, siblings, audit rollback về snapshot ban đầu | Chưa chạy |

### D. Reorder trong cùng cột

| ID | Case / thiết lập | Expected | Thực tế |
|---|---|---|---|
| R01 | [T0,T1,T2,T3], move T1 -> 0 | [T1,T0,T2,T3], positions 0..3 | PASS — reorder task từ vị trí cuối về 0; thứ tự ID trong cột được assert trên PostgreSQL. |
| R02 | [T0,T1,T2,T3], move T1 -> 3 | [T0,T2,T3,T1], positions 0..3 | Chưa chạy |
| R03 | Move T0 -> 2 và T3 -> 1, fixture riêng | Insert đúng final index, siblings giữ relative order | Chưa chạy |
| R04 | Move về vị trí hiện tại; cột chỉ một task -> 0 | No-op, không đổi nội dung/timestamp/audit khi không sửa dữ liệu | Chưa chạy |
| R05 | Reorder task có comments/tags và cột đã full | Được phép, không đổi ID/FK graph/category hay số task | Chưa chạy |
| R06 | position âm, =N, >N, int overflow | 400; không clamp hoặc mutate DB | PARTIAL — position `=N` bị `INVALID_POSITION`, DB giữ nguyên; âm/>N/overflow chưa test. |
| R07 | position thiếu/null/string/decimal/boolean | 400 từng biến thể, không coercion | Chưa chạy |
| R08 | Legacy [0,0,7] rồi reorder/no-op | Normalize 0..N-1, không mất task; đọc đầu vào position,id | Chưa chạy |
| R09 | Commit, clear context, GET ở request mới | Thứ tự/task positions đúng kết quả đã commit | PASS — sau các request move, assert thứ tự và positions từ các query DB độc lập. |
| R10 | Reorder task nonexistent/board archived | 404/409 tương ứng; không mutation | Chưa chạy |

### E. Chuyển sang cột khác

| ID | Case / thiết lập | Expected | Thực tế |
|---|---|---|---|
| M01 | Move nguồn đầu/giữa/cuối -> đích đầu/giữa/cuối | Mỗi biến thể đúng order cả hai cột và positions liên tục | PARTIAL — move task ở giữa nguồn sang vị trí cuối đích; chưa chạy ma trận đầu/giữa/cuối hai phía. |
| M02 | Cột đích rỗng -> 0; nguồn chỉ một task | Source rỗng, target có task ở 0; không xóa source column | Chưa chạy |
| M03 | Đích có M task: position M và M+1 | M append thành công; M+1 bị 400 và DB không đổi | PARTIAL — append vị trí 1 vào đích có 1 task pass; vị trí vượt N được kiểm tra trên same-column, chưa kiểm tra cross-column vượt N. |
| M04 | Move mang title/description/assignee/dueDate/comments/tags | Toàn bộ nội dung/ID/mappings giữ, chỉ column/position/status projection đổi | PARTIAL — task ID/title được giữ và status category đổi theo đích; comments/tags/assignee/date cùng move chưa assert. |
| M05 | Move khác category và khác column cùng category | Category đúng destination; same-category move vẫn đổi column; không update column category | PARTIAL — khác category TODO→IN_PROGRESS được kiểm tra; move giữa hai cột cùng category chưa test. |
| M06 | Destination missing/deleted/board khác cùng WA/board WB | Scoped 404 thống nhất, task không rời source | PARTIAL — đích ở board khác cùng workspace trả 404 và source không đổi; missing/deleted/board WB chưa test. |
| M07 | Destination UUID null/missing/sai định dạng | 400, không dùng source như default ngầm định | Chưa chạy |
| M08 | Đích N=999/1.000 theo limit 1.000 | Chèn đến limit thành công; vượt limit 409, source/target giữ nguyên | Chưa chạy |
| M09 | Move đi rồi quay lại; lặp request theo state mới | Không nhân đôi/task mất; mỗi lần áp dụng final index theo dữ liệu hiện tại | Chưa chạy |
| M10 | Reload/source+destination list sau commit | Response IDs đúng, list và DB thống nhất, board columns order giữ | PASS — API response và các truy vấn ID/order source+destination khớp sau move. |

### F. Xác thực, phân quyền và input errors

Áp dụng các case quyền cho **từng route** create/list/get/PUT/delete/move và entry point service, không chỉ một GET đại diện.

| ID | Case / thiết lập | Expected | Thực tế |
|---|---|---|---|
| S01 | Anonymous, JWT invalid/expired/wrong signature/refresh | 401, ProblemDetail + correlation, không ghi dữ liệu | Chưa chạy |
| S02 | UA/UB/UAB/UX với resource đúng/sai workspace | Member được phép; foreign/outsider resource 404; không lộ dữ liệu | PARTIAL — member đọc/list được, outsider nhận 404 và đích board khác bị 404; chưa chạy toàn bộ actor × mọi route. |
| S03 | UAB thao tác WA khi UI/header đang chọn WB | Quyền dựa trên resource thật, không dựa activeWorkspace client | Chưa chạy |
| S04 | Actor/user/workspace/board giả và immutable fields trong body | 400 theo whitelist; không mass assign actor/parent/position | Chưa chạy |
| S05 | Membership actor bị thu hồi rồi dùng lại access JWT | Request sau bị 404; token không thay thế membership check | Chưa chạy |
| S06 | Không/multiple listener, response sai hoặc listener exception | 503 fail closed, không mutation/audit success | Chưa chạy |
| S07 | Các request permission đồng thời, actor khác nhau | Không dùng nhầm decision/correlation giữa request | Chưa chạy |
| S08 | Principal dùng email username; service gọi trực tiếp | Actor UUID public contract đúng; service tự kiểm tra quyền trước write | Chưa chạy |
| S09 | Board archived: mọi Task mutation, read/list, rồi restore | Mutation 409; reads theo quyền; restore cho phép mutation lại | PARTIAL — create/PUT/delete/move trên board archived trả 409; read/list sau archive và mutation sau restore chưa test. |
| V01 | title missing/null/rỗng/spaces/script-only, POST và PUT | 400 sau sanitize; task/audit không đổi | PARTIAL — title rỗng bị từ chối, markup title/description được sanitize; chưa test đủ null/missing/script-only. |
| V02 | title 1/255/256 code points; emoji/surrogate; description 10.000/10.001 | Hợp lệ đúng boundary, quá giới hạn 400; DB/DTO cùng đơn vị | Chưa chạy |
| V03 | Mỗi priority hợp lệ; null/missing/unknown/lowercase/numeric | Create default chỉ omitted; PUT yêu cầu; mọi enum sai 400 | PARTIAL — omitted create dùng MEDIUM; explicit null create và thiếu priority PUT trả 400; chưa phủ mọi enum/sai dạng. |
| V04 | dueDate date-only/no offset/invalid/range quá DB/epoch | 400 an toàn; không lỗi 500 hoặc silently coerce | Chưa chạy |
| V05 | path UUID sai; body thiếu/sai JSON; duplicate keys/field lạ | 400 theo parser/whitelist policy; không internal details | PARTIAL — UUID path sai trả ProblemDetail 400; các biến thể JSON/unknown field khác chưa test. |
| V06 | page âm; size 0/âm/101; query non-number/sort tùy ý | 400 hoặc reject sort theo contract; không unbounded collection | PARTIAL — page âm và size 101 trả 400; size 0/âm, non-number và sort chưa test. |
| V07 | Content-Type sai, body >64 KiB có/không Content-Length | 415/413; chunked cũng được chặn, không ghi DB | PARTIAL — body 70 KiB có Content-Length trả 413 `application/problem+json`, correlation ID có và DB không đổi; chunked/Content-Type sai chưa test. |
| V08 | 400/401/404/409/413/415/429/503/500 qua full app | Status/media type/code/correlation đúng; không stack/SQL/token/nội dung nhạy cảm | Chưa chạy |

### G. Transaction, cạnh tranh và integration T-012

| ID | Case / thiết lập | Expected | Thực tế |
|---|---|---|---|
| T01 | Fault sau source removal, target insert, reindex, audit flush | Rollback mọi bảng/positions/audit; task đúng một source ban đầu | Chưa chạy |
| T02 | Hai create cùng cột tại ngưỡng capacity | UUID khác, positions liên tục; chỉ số request còn chỗ thành công | Chưa chạy |
| T03 | Hai reorder cùng cột; hai move cùng một task | Kết quả thuộc một serial order hợp lệ, task không mất/nhân đôi | Chưa chạy |
| T04 | Hai move ngược chiều giữa cùng hai cột | Không deadlock do lock ordering; source/target invariant đúng | Chưa chạy |
| T05 | Move cùng create/delete khác task trong cùng board | Serialize, siblings và capacities đúng state commit mới nhất | Chưa chạy |
| T06 | Create/move vào cột cùng delete/category update column T-012 | Người chạy sau đọc state mới: 404/409 hoặc category mới; không cascade mất task đã được nhận | Chưa chạy |
| T07 | Task mutation cùng archive/delete board | Không ghi vào board archived/deleted; loser lỗi phù hợp, không orphan/stale archived | Chưa chạy |
| T08 | Task đã đổi source hoặc bị delete trong lúc chờ lock | Refetch state; dùng source mới hoặc 404, không dùng managed entity stale | Chưa chạy |
| T09 | Lock held > timeout; controlled deadlock/statement timeout | Thất bại hữu hạn theo code hợp đồng, rollback; không SQL leak hoặc auto duplicate retry | Chưa chạy |
| T10 | Mutation board BA và BB trên hai connections | Lock độc lập; không global lock; cả hai giữ invariant | Chưa chạy |
| T11 | PG17/Flyway từ DB mới và upgrade DB có V1–V3/data/audit | Migration kế tiếp đúng, giữ rows/constraints cũ; ddl-auto=validate pass | Chưa chạy |

### H. Vận hành, public contract và regression

| ID | Case / thiết lập | Expected | Thực tế |
|---|---|---|---|
| Q01 | Rate dưới/đúng/vượt 60; actor khác; đổi task ID/route | Quota chung theo actor; vượt 429 + Retry-After; actor khác độc lập | Chưa chạy |
| Q02 | Quota refill/idle eviction với clock kiểm soát | Phục hồi đúng policy, không memory growth vô hạn theo idle actors | Chưa chạy |
| Q03 | Sensitive form theo G3: thiếu/sai/expired/replayed challenge và verifier timeout | Reject/fail closed theo policy, no mutation; valid token cho phép; không log token | PARTIAL — 4 unit tests mock siteverify success/reject/missing secret/provider error/malformed body/local-disabled. Chưa gọi Cloudflare thật, test timeout/replay đầy đủ hoặc bảo đảm bằng HTTP integration có token. |
| Q04 | CREATE/PUT/DELETE/REORDER/MOVE/no-op/failure | Audit actor/resource/workspace/action/changed fields đúng; không ghi no-op hoặc success khi rollback | PARTIAL — PostgreSQL assertions cho MOVE, PUT/audit count và DELETE; chưa assert mọi audit field, CREATE, no-op hoặc rollback. |
| Q05 | Task routes thành công/lỗi, correlation header hợp lệ/sai, MDC cleanup | Metrics/correlation đầy đủ, template labels không UUID; không rò context qua thread reuse | PARTIAL — metrics route matcher đã thêm; body-limit ProblemDetail có correlation ID được kiểm tra. Chưa test metric values/labels hoặc MDC cleanup trên toàn bộ route. |
| Q06 | BoardApi task lookup/list sau CRUD/move; dueDate null/gần ngày UTC | Signature giữ; projection/status/projectId/date đúng, task đã delete không còn | Chưa chạy |
| Q07 | GET/list với task có nhiều comments/tags, open-in-view=false | DTO không LazyInitializationException/N+1 theo graph, query count có bằng chứng | Chưa chạy |
| Q08 | Boundary và regression Auth/health/T-010/T-011/T-012 | Full check không fail/error/skip; không impl-to-impl, health/Auth behavior giữ | PASS — `./gradlew.bat check`; `:verifyModuleBoundaries` pass; 23 suites/159 tests/0 fail/0 error/0 skipped. |
| Q09 | API GET/POST/PUT/DELETE/move success contracts | 200/201/204 đúng, Location đúng, 204 body rỗng, no stub 501 | PARTIAL — POST 201 + Location, list 200, PUT 200, move 200 và DELETE 204 được assert; GET tồn tại/204 body rỗng chưa assert riêng. |
| Q10 | CRUD workload và move/reorder ở 10/100/1.000 task/cột | P95 CRUD <200 ms trong điều kiện đo; query/lock wait/response size hữu hạn, lỗi có giải thích | Chưa chạy — chưa có benchmark, không kết luận đạt ngưỡng P95. |

### 6.2. Manual UAT

1. Đăng nhập UA, tạo task đầy đủ thông tin trong C0; GET/list rồi đổi session/request và đọc lại.
2. PUT đổi mọi field, clear optional fields; chọn assignee hợp lệ/sai workspace; kiểm tra UI/API không lưu dở dang.
3. Reorder đầu/giữa/cuối/no-op; move sang cột rỗng và cột có task ở đầu/giữa/cuối; reload cả hai cột.
4. Move task có comments/tags, kiểm tra nội dung và mapping còn; delete task khác và xác nhận shared tag/board/column còn.
5. Đăng nhập UB/UX, truy cập ID đã biết; thử JWT sai, position sai và cột ngoài board; kiểm tra status/error và DB không đổi.
6. Archive board, thử mọi mutation Task, restore và thử lại. Hai client/API sessions thao tác cạnh tranh và refetch state mới.

**Expected:** đáp ứng NV-01..NV-06, API/status/order và cleanup đúng. **Thực tế:** Chưa chạy UAT thủ công. Một số workflow được cover bằng MockMvc/PostgreSQL tests (xem A–V); UI kéo thả hoàn chỉnh và đồng bộ WebSocket thuộc ticket sau, không được suy ra từ backend tests.

### 6.3. Performance và query plan

- Dùng database test riêng, PG17, dữ liệu 10/100/1.000 task mỗi cột, cùng và khác board; đủ comments/tags để phát hiện tải graph ngoài ý muốn.
- Ghi máy/CPU/RAM, Docker/profile, commit, JVM/Hikari settings, warm-up, duration, concurrency, request mix và rate-limit config. Dùng đủ actor/điều chỉnh config test có công bố; tách 429 do quota khỏi latency success, không âm thầm tắt limiter.
- Đề xuất warm-up 30 giây, đo ít nhất 120 giây, concurrency 1/10/25. Đo riêng CRUD success và move/reorder, riêng contention cùng board và workload khác board; không trộn lock timeout vào số CRUD success.
- Ghi P50/P95/P99, throughput, error rate theo status, query count, lock wait, payload size; kiểm tra index `(column_id,position)` bằng EXPLAIN khi cần. Negative/rejected load cũng không được phá invariant.
- **Expected:** core CRUD P95 < 200 ms theo NFR dự án; move/reorder có số liệu riêng và giới hạn 1.000 task/cột được chứng minh phù hợp. **Thực tế:** Chưa đo; chưa có số liệu để kết luận API đạt P95 hoặc phù hợp tải thực.

### 6.4. Lệnh kiểm tra khi triển khai

Trong `backend/`, Docker Desktop phải hoạt động để Testcontainers chạy PostgreSQL 17:

```powershell
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat :board-impl:test
.\gradlew.bat :app:test
.\gradlew.bat check
```

Không thay PostgreSQL bằng H2 cho locking/cascade/transaction assertions. Nếu thay frontend thì chạy `npm run build` và `npm run lint` trong `frontend/`; nếu backend-only ghi rõ không áp dụng theo DoD chung. Test concurrency không được skip âm thầm do thiếu Docker.

Lưu HTML/XML reports tại `backend/<module>/build/reports/tests/test/` và `build/test-results/test/`; kết quả có commit, timestamp, command, environment và số pass/fail/error/skip. Sau triển khai cập nhật từng dòng actual, không đổi tất cả sang PASS chỉ vì Gradle xanh.

### 6.5. Tổng hợp triển khai và các phần còn lại

| Hạng mục | Expected | Thực tế ngày 07/10/2026 |
|---|---|---|
| File plan | Cùng cấp task, đủ nghiệp vụ/tech/DoD/testing expected–actual | Được lập ở `docs/xuan/04_api_quan_ly_va_di_chuyen_task_plan.md` |
| Đối chiếu nguồn | Task/card/architecture/product/master plan và code nhất quán | Đã đọc trực tiếp card T-013 và source T-011/T-012; ghi rõ các policy đề xuất và gaps |
| Task API/DTO/service/migration mới | CRUD/move đúng contract | Đã triển khai; `:app:test` dùng PostgreSQL 17/Testcontainers và `check` pass. Migration V4 thêm audit TASK/MOVE. |
| Unit/HTTP/PG/concurrency/regression | Ma trận trên có assertions và report | `backend check` pass; 159 test, 0 fail/error/skip; migration được chạy từ schema Flyway hiện hành. Concurrency race, fault-injection/rollback và upgrade database cũ chưa kiểm chứng riêng. |
| Manual/performance | Workflow và số đo thực tế | Chưa chạy UAT thủ công/chưa benchmark; không kết luận đạt P95 < 200 ms. |
| Git/PR/Trello workflow T-013 | Review/merge và cập nhật card có bằng chứng | Branch đang dựa trên T-012 chưa merge; chưa commit/push/tạo PR. Yêu cầu chuyển card sang In Progress bị Trello API từ chối 401 `unauthorized card permission requested`. |
