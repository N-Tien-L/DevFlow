# T-012 — Plan triển khai API quản lý Board và Column

**Task gốc:** [API quản lý bảng và cột](03_api_quan_ly_board_va_column.md).

**Trello:** [T-012 — Implement REST Endpoints for Board and Column CRUD](https://trello.com/c/T7HtO3t8/18-t-012-implement-rest-endpoints-for-board-and-column-crud).

**Module chính:** `board-api`, `board-impl`. **Phụ thuộc:** persistence T-011; identity và kiểm tra quyền workspace của Auth.

**Ngày lập:** 06/10/2026. **Cập nhật triển khai:** 07/10/2026. **Trạng thái:** Đã triển khai trên branch tính năng; lượt backend `check` cuối lúc 14:51 GMT+7 pass với 149 test, không fail/error/skip. Review/PR và kiểm thử thủ công, hiệu năng, cạnh tranh chưa thực hiện.

## 1. Mục tiêu, căn cứ và phạm vi

### 1.1. Mục tiêu

Người dùng đã đăng nhập có thể quản lý bảng và các cột trạng thái trong workspace mình được phép truy cập. Thứ tự cột được lưu bền vững, thao tác sai quyền không thay đổi dữ liệu, lỗi giữa chừng không để database cập nhật dở dang.

Yêu cầu ban đầu là tạo plan; yêu cầu tiếp theo là triển khai plan sau khi Docker Desktop được bật. Phần dưới đây ghi cả quyết định triển khai thực tế lẫn các hạng mục kiểm thử chưa chạy, không xem nội dung kế hoạch là kết quả đã xác minh.

### 1.2. Căn cứ và cách phân biệt yêu cầu

| Nguồn | Nội dung áp dụng |
|---|---|
| [Task T-012](03_api_quan_ly_board_va_column.md) | CRUD board/column, kiểm tra membership, ngăn truy cập workspace khác, validation và duy trì public contract |
| Thẻ Trello T-012, đã đọc ngày lập plan | Bốn endpoint nêu trong phần kỹ thuật; hai acceptance criteria gốc; branch `feature/T-012-board-column-endpoints` |
| [Architecture](../ARCHITECTURE.md), mục 2–6 và 10 | Quyền sở hữu bảng, module boundary, public API và event contract |
| [Product spec](../PRODUCT_SPEC.md), mục 5.1 và 8 | Kanban; validation, IDOR, lỗi ProblemDetail, giới hạn collection, rate limit, audit và mục tiêu hiệu năng |
| [Implementation plan](../IMPLEMENTATION_PLAN.md), Phase 2 và DoD | URI tham chiếu, phụ thuộc T-011, kiểm tra backend và bàn giao cho task/realtime |
| [DoD của Xuân](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) | Bằng chứng nghiệm thu và kiểm thử theo phần thay đổi |
| [Coding conventions](../../.agents/rules/coding-conventions.md), [module boundaries](../../.agents/rules/module-boundaries.md) | Package, DTO, HTTP status và dependency |
| [V2 schema](../../backend/app/src/main/resources/db/migration/V2__board_schema.sql), entity/repository T-011 | Constraint, cascade SQL, trường dữ liệu và truy vấn thực tế |
| [AuthApi](../../backend/auth-api/src/main/java/io/devflow/auth/api/AuthApi.java), cấu hình JWT hiện tại | Membership đang có; thiếu contract public để Board lấy UUID actor từ principal |

**Hai tiêu chí gốc trên Trello:**

- Tạo board mới và lấy danh sách board theo workspace chính xác.
- Đổi thứ tự hiển thị của các cột trạng thái thành công.

CRUD đầy đủ được tài liệu task yêu cầu thêm để nghiệm thu rõ ràng. Chính sách xóa, archive, giới hạn payload, phân trang, HTTP status và phương án cạnh tranh dưới đây là **đề xuất của plan**, chưa phải checklist mới đã được cập nhật lên Trello.

### 1.3. Hiện trạng đã kiểm tra trong code

- T-011 có `BoardEntity`, `ColumnEntity`, `TaskEntity`, `CommentEntity` và repository. Board giữ `workspaceId` dạng UUID; không có quan hệ JPA đến entity Auth.
- `BoardController` hiện là scaffold. `GET /api/v1/boards/{boardId}` trả `501`; các route card cũng là stub. Đây là kết quả đọc code, chưa phải kết quả gọi HTTP trong lượt lập plan.
- `BoardService` implements `BoardApi`; `findTasksByProject` và `findTask` đang ném `UnsupportedOperationException`.
- `AuthApi.isWorkspaceMember(userId, workspaceId)` đã có implementation thật. Contract chỉ trả membership boolean, chưa trả quyền theo vai trò.
- JWT filter tạo `auth.internal.security.UserPrincipal`. `getUsername()` trả email; `getId()` mới trả UUID. Board không được import hoặc cast sang class nội bộ đó, cũng không được parse email thành UUID.
- `board-impl` hiện chưa có dependency security/validation chuyên dụng hoặc permission adapter cho T-012.
- V2 cho phép tên trùng, position trùng; chỉ kiểm tra position không âm. Không có `@Version`, trường version hay unique constraint `(board_id, position)`.
- Xóa board/cột ở database có cascade đến task, bình luận và dữ liệu nhãn liên quan. Không được triển khai `DELETE column` bằng một lời gọi repository mà bỏ qua nghiệp vụ bảo toàn task.
- `open-in-view=false`, `ddl-auto=validate`; phải map response trong transaction và dùng Flyway cho mọi thay đổi schema.
- Advice toàn cục hiện nằm trong `auth-impl`, có handler chung `Exception -> 500`. Cần kiểm tra độ ưu tiên và phạm vi advice khi thêm lỗi Board để `400/404/409` không bị handler chung bắt thành `500`.

### 1.4. Phạm vi

**Trong T-012:** board CRUD; column CRUD; reorder một cột trong board; DTO, validation, permission, phân trang danh sách board, lỗi HTTP, transaction và kiểm thử. Bổ sung điểm tích hợp Auth/common tối thiểu khi cần, phối hợp chủ module.

**Bàn giao task khác:** task CRUD/move T-013; event vòng đời task T-014; realtime T-015; giao diện T-016 trở đi. Không thêm quản lý thành viên, invite, WIP limit, tag CRUD, task drag-and-drop hoặc gọi AI trong task này.

## 2. Kế hoạch nghiệp vụ — mô tả luồng bằng ngôn ngữ

### NV-01. Tạo board và mở danh sách workspace

1. Người dùng đăng nhập, chọn workspace và nhập tên board, mô tả tùy chọn.
2. Hệ thống xác định người đang thao tác từ phiên xác thực, kiểm tra họ có quyền trong workspace đó và kiểm tra dữ liệu nhập.
3. Nếu hợp lệ, tạo một board đang hoạt động; trả thông tin và địa chỉ của board vừa tạo. Không tự tạo cột mẫu vì task chưa quy định mẫu mặc định.
4. Khi tải danh sách, chỉ hiển thị board trong workspace được chọn, theo thứ tự ổn định và theo trang. Workspace hợp lệ chưa có board trả danh sách rỗng.
5. Tên tiếng Việt được giữ đúng; tên có khoảng trắng đầu/cuối được chuẩn hóa. Tên trùng vẫn được phép theo schema hiện tại.

### NV-02. Xem, sửa và lưu trữ board

1. Người dùng mở board; hệ thống kiểm tra quyền theo workspace thật của board, rồi trả thông tin board và các cột đã sắp xếp.
2. Người dùng sửa tên/mô tả. ID, workspace và thời điểm tạo không thay đổi.
3. Người dùng có thể lưu trữ hoặc khôi phục board bằng trường archive của thao tác cập nhật. Lưu trữ giữ nguyên cột, task, bình luận; không phải xóa dữ liệu.
4. Board lưu trữ vẫn đọc được khi có quyền; mặc định không xuất hiện trong danh sách board hoạt động. Muốn xem phải yêu cầu `includeArchived=true`.
5. Trong board đang lưu trữ, từ chối thêm/sửa/xóa/reorder cột; thao tác board chỉ cho khôi phục bằng PATCH chỉ chứa `archived=false`, hoặc xóa. Muốn đổi tên/mô tả phải khôi phục trước; không gộp sửa nội dung vào request khôi phục.

### NV-03. Thêm, xem và sửa cột

1. Người dùng mở board đang hoạt động, nhập tên cột và nhóm trạng thái.
2. Hệ thống thêm cột vào cuối danh sách, tự cấp vị trí; client không tự gửi vị trí khi tạo.
3. Người dùng xem một cột hoặc danh sách cột; thứ tự đọc lại giống thứ tự đã lưu.
4. Đổi tên cột giữ nguyên ID, vị trí và các task. Nhóm trạng thái hỗ trợ `TODO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`; tên hiển thị có thể khác tên nhóm.
5. Đề xuất chỉ cho sửa nhóm trạng thái khi cột chưa có task. Cột có task trả xung đột, tránh đổi trạng thái của hàng loạt task mà chưa có luồng event T-014.

### NV-04. Đổi thứ tự cột

1. Người dùng kéo một cột đến vị trí mới trong cùng board.
2. Hệ thống kiểm tra quyền và vị trí đích, chuyển cột đó, rồi đánh lại thứ tự tất cả cột bị ảnh hưởng.
3. Các vị trí sau thao tác liên tục từ `0` đến `N-1`; không mất cột, không tạo cột mới và không đổi ID/nhóm trạng thái/task.
4. Khi tải lại hoặc mở một phiên khác, thứ tự đã lưu được giữ nguyên.
5. Khi hai người thao tác đồng thời, xử lý lần lượt trong transaction theo từng board; người thứ hai nhận kết quả dựa trên dữ liệu đã commit của người thứ nhất. Không cam kết phát hiện một màn hình cũ nếu API chưa có version/ETag.

### NV-05. Xóa cột và xóa board

1. Xóa cột rỗng chỉ xóa cột đó và đánh lại thứ tự các cột còn lại.
2. Xóa cột có task bị từ chối; người dùng phải chuyển/xóa task qua T-013 trước. Không tự chuyển task sang cột bất kỳ.
3. Xóa board là xóa thật toàn bộ graph Kanban thuộc board: cột, task, bình luận, tag và mapping liên quan. Tài liệu API phải mô tả rõ tác động để frontend có thể thiết kế xác nhận phù hợp.
4. Board/workspace khác và dữ liệu user/workspace không bị xóa. Board đã xóa không thể đọc lại; lần xóa tiếp theo trả `404`.
5. Nếu có lỗi trong quá trình xóa hoặc đánh lại thứ tự, toàn bộ thao tác rollback.

### NV-06. Từ chối sai quyền và dữ liệu sai

1. Không có phiên đăng nhập hợp lệ thì từ chối trước khi thực hiện nghiệp vụ.
2. Người không thuộc workspace không được đọc hoặc thay đổi dữ liệu dù biết chính xác UUID.
3. Tên rỗng, ID sai định dạng, vị trí sai hoặc payload không đúng nhận lỗi có cấu trúc; database không đổi.
4. Membership bị thu hồi có hiệu lực ở lần yêu cầu tiếp theo; token hợp lệ không đồng nghĩa còn quyền workspace.
5. Lỗi không tiết lộ tên board, workspace khác, SQL, stack trace hoặc token.

## 3. Kế hoạch kỹ thuật — luồng và các yếu tố cần suy xét

### 3.1. Luồng xử lý chung

```text
HTTP request + access token
  -> Security filter xác thực
  -> Lấy UUID actor qua contract public của security context
  -> Controller: parse path/body/query và validate hình dạng
  -> Service: tìm tài nguyên -> suy ra workspace thật -> PermissionGateway
  -> Nếu mutation: mở transaction + khóa board khi thay đổi cấu trúc
  -> Kiểm tra archive, dữ liệu và quy tắc nghiệp vụ
  -> Repository / Hibernate / PostgreSQL
  -> Map DTO trong transaction -> commit
  -> Response HTTP; ngoại lệ -> rollback + ProblemDetail
```

Authorization đặt ở service, không chỉ ở controller: gọi service bằng adapter khác vẫn phải kiểm tra quyền. Với mutation có khóa, đọc lại board dưới khóa và kiểm tra archive/quyền trước khi ghi; không dùng dữ liệu từ một entity đã đọc ở transaction khác.

### 3.2. Điểm tích hợp Auth và quyết định đã áp dụng

**G1 — Quyền liên module:** `ARCHITECTURE.md` và rule file cho phép truy vấn đồng bộ qua public `AuthApi`; tài liệu task cũng nêu phụ thuộc này. Tuy nhiên, hướng dẫn AGENTS do người dùng cung cấp trong phiên yêu cầu mọi giao tiếp liên module qua Spring Event Bus. Plan không mặc nhiên lấy nội dung tài liệu để cho phép ngoại lệ với hướng dẫn đó.

- **G1 — Hoàn tất:** Board gọi `WorkspaceMembershipCheckRequestedEvent`, một event đồng bộ kế thừa `DevFlowEvent`, có correlation ID, actor/workspace và quyết định. Listener thuộc Auth tự đọc repository membership; Board không phụ thuộc `AuthApi` hoặc bảng Auth. Không có hoặc có nhiều hơn một phản hồi thì fail closed bằng `503`.
- Permission được kiểm tra ở service trước mọi mutation; mutation có khóa đọc lại board và kiểm tra membership lần nữa. Không cache quyền.
- Lỗi khi phát/đọc event trả `503` và transaction không ghi dữ liệu. Integration test PostgreSQL đi qua listener Auth thật cho luồng thành viên và không thành viên; trường hợp thiếu listener/chạy đồng thời nhiều phản hồi vẫn chưa có test riêng.

**G2 — Hoàn tất:** `UserPrincipal` triển khai `common.security.AuthenticatedActor`; Board đọc UUID từ `SecurityContext`, không cast sang `auth.internal`, parse token lại hoặc nhận actor ID từ request. API integration test dùng JWT thật xác nhận actor được dùng để kiểm tra membership.

**G3 — Hoàn tất theo phạm vi T-012:** mọi thành viên workspace có cùng quyền Board/Column; role OWNER/ADMIN/MEMBER chưa phân quyền khác nhau vì nguồn quyền hiện tại chỉ xác nhận membership. Workspace-level list/create từ người không phải thành viên trả `403`; resource ID thuộc workspace khác trả `404` để che giấu sự tồn tại. Quyền chỉ đọc hoặc quyền xóa riêng cần một thay đổi contract riêng.

### 3.3. Endpoint và hợp đồng response dự kiến

Giữ bốn URI cốt lõi của Trello/master plan, bổ sung các URI CRUD cần nghiệm thu:

| Method / URI | Request hoặc query | Kết quả thành công |
|---|---|---|
| `POST /api/v1/boards` | `workspaceId`, `name`, `description?` | `201`, `Location: /api/v1/boards/{id}`, `BoardResponse`; không có cột mặc định |
| `GET /api/v1/workspaces/{workspaceId}/boards` | `page=0`, `size=20`, `includeArchived=false` | `200`, trang `BoardSummaryResponse`; sort cố định `createdAt,id` tăng dần |
| `GET /api/v1/boards/{boardId}` | UUID board | `200`, board + `columns` theo `position,id`; không nhúng toàn bộ task/comments |
| `PATCH /api/v1/boards/{boardId}` | Một hoặc nhiều trường `name`, `description`, `archived` | `200`, board sau cập nhật |
| `DELETE /api/v1/boards/{boardId}` | UUID board | `204`, body rỗng; xóa thật graph Kanban |
| `POST /api/v1/boards/{boardId}/columns` | `name`, `statusCategory` | `201`, `Location: /api/v1/columns/{id}`, cột cuối danh sách |
| `GET /api/v1/boards/{boardId}/columns` | UUID board | `200`, danh sách cột thứ tự ổn định, có giới hạn số cột trên board |
| `GET /api/v1/columns/{columnId}` | UUID column | `200`, `ColumnResponse` sau kiểm tra board/workspace của nó |
| `PATCH /api/v1/columns/{columnId}` | `name?`, `statusCategory?` | `200`; đổi category chỉ khi cột rỗng |
| `DELETE /api/v1/columns/{columnId}` | UUID column | `204` nếu rỗng; reindex các cột còn lại |
| `PATCH /api/v1/columns/{columnId}/reorder` | `{ "position": 2 }` | `200`, danh sách cột của board sau reorder |

`position` là chỉ số **0-based**. Reorder cột có `N` phần tử chỉ chấp nhận `0 <= position < N`; không clamp giá trị sai. Endpoint này không chuyển cột sang board khác, không nhận `boardId` đích.

### 3.4. DTO, validation và giới hạn

- Public records ở `board-api`: create/update request, board summary/detail, column response, page response; enum public nếu hợp đồng HTTP cần. Không đưa JPA entity/proxy, Spring Security principal hoặc repository ra public contract.
- `board-api` chỉ phụ thuộc project `:common`; không phụ thuộc `auth-api` hay bất kỳ `*-impl`. Validation starter ở `board-impl`. Nếu gắn Bean Validation annotations lên DTO public, khai báo dependency thư viện Jakarta validation-api tối thiểu; không đưa starter/controller vào API module.
- Board name trim, bắt buộc có ký tự sau trim, tối đa 255 ký tự; column name tương tự, tối đa 100. Kiểm tra cả trước và sau chuẩn hóa; test giá trị Unicode theo độ dài được PostgreSQL hỗ trợ.
- Description tùy chọn, plain text, đề xuất tối đa 10.000 ký tự. Reject markup HTML trong tên/mô tả theo contract đầu vào; giữ đúng dấu tiếng Việt, ký tự `&` và xuống dòng hợp lệ. Không tự giải mã/thay đổi nội dung nhiều lần.
- `statusCategory` bắt buộc khi tạo, nhận đúng bốn giá trị schema. Map giữa enum public/nội bộ rõ ràng; đổi tên hiển thị không đổi category.
- PATCH phân biệt **trường vắng mặt** với **trường null**: vắng mặt giữ nguyên; `description:null` xóa mô tả; `name:null`, `archived:null`, `statusCategory:null` bị từ chối. Payload rỗng và field ngoài whitelist trả `400`.
- `workspaceId` immutable khi sửa board; `boardId` immutable khi sửa cột; `position` chỉ đổi qua reorder. Reject field `id`, `workspaceId`, `boardId`, `createdAt`, `updatedAt`, `tasks`, `columns` ở PATCH tương ứng, tránh mass assignment.
- Pagination: page không âm, size trong `1..100`; vượt giới hạn trả `400`; trang ngoài dữ liệu trả `200` với items rỗng. Không dùng `Page`/entity làm wire contract.
- Đề xuất tối đa 100 cột/board, kiểm tra dưới khóa; cột thứ 101 trả `409`. Đây là giới hạn sản phẩm dự kiến, cần chốt trước triển khai. Không silent truncate board đã có hơn 100 cột từ dữ liệu cũ; có báo cáo/luồng xử lý dữ liệu trước rollout.
- Đặt giới hạn body theo cấu hình API, đề xuất 64 KiB; quá giới hạn trả `413`. Chốt cấu hình hiệu lực trong app/gateway để MockMvc và HTTP thật không đưa ra kết quả khác nhau.

### 3.5. Kiểm tra quyền và chống truy cập sai workspace

| Tình huống | Hành vi dự kiến |
|---|---|
| Thiếu/sai/hết hạn access token hoặc dùng refresh token | `401`; không chạy mutation |
| Tạo/list board theo workspace mà actor không thuộc | `403`, cùng phản hồi cho workspace không tồn tại vì contract membership hiện chỉ trả boolean |
| Board/column không tồn tại | `404` |
| Actor không có quyền đối với board/column đã tồn tại | `404` để không xác nhận sự tồn tại của ID ngoài phạm vi |
| Cơ chế permission không trả kết quả hoặc gặp lỗi | `503` dạng ProblemDetail; fail closed, không ghi DB |

Không dựa vào `workspaceId` do client gửi để kiểm tra quyền cho endpoint có board/column ID. Với column: load column -> board -> `board.workspaceId` thật. Đối với danh sách board, query bắt buộc có workspace filter; không lấy mọi board rồi lọc Java. Nếu thêm URI nested nhận cả parent và child ID, phải kiểm tra cả quan hệ thuộc cha, không chỉ kiểm tra membership.

Authorization cache không thuộc mặc định của plan. Nếu thêm cache sau này, phải có TTL/eviction khi membership đổi và test thu hồi quyền. Trong MVP, quyết định membership lấy mới cho từng yêu cầu; trường hợp membership bị thu hồi ngay giữa một request được ghi thành giới hạn nhất quán cần chốt với Auth, không cam kết atomic với hai module khi chưa có contract tương ứng.

### 3.6. Transaction, khóa và thuật toán thứ tự

**Đề xuất:** khóa pessimistic write trên hàng board để tuần tự hóa mọi mutation của board/cột đang tồn tại: đổi tên/mô tả, thêm/xóa/reorder cột, đổi category, archive và xóa board. Một phương thức repository mới dùng `PESSIMISTIC_WRITE`; mọi entry point cập nhật cùng board dùng cùng quy tắc, kể cả đổi tên cột để không vượt kiểm tra archive khi có race. Tạo board mới chưa có hàng để khóa. GET không lấy write lock.

1. Load board/column đủ để xác định tài nguyên, kiểm tra quyền ban đầu.
2. Trong transaction, lấy write lock board; refresh/load lại trạng thái board và child, kiểm tra quyền/archive/quan hệ trước ghi. Khóa theo cùng thứ tự **board trước, column sau** để giảm deadlock.
3. Đọc các cột theo `position,id`. Với create/delete/reorder, chuẩn hóa toàn bộ danh sách thành `0..N-1`, kể cả dữ liệu cũ có gap/trùng position.
4. Reorder: tìm index của column ID, remove khỏi list, insert vào target index, cập nhật position từng cột trong cùng transaction. Di chuyển đến cùng vị trí và dữ liệu đã chuẩn là no-op, không đổi audit vô ích.
5. Create: thêm cột sau phần tử cuối; delete cột rỗng: gỡ khỏi collection cha đang managed rồi xóa child rõ ràng. `orphanRemoval=false` nên remove collection không tự xóa DB.
6. Map response trong transaction, flush/commit; lỗi constraint hoặc bất kỳ lỗi cập nhật nào rollback toàn bộ. Test đọc lại bằng persistence context mới.

Không thêm unique constraint position vào migration V2 đã phát hành. Nếu sau này cần unique `(board_id, position)`, thêm migration mới và thuật toán hai pha/constraint deferred tránh va chạm position tạm thời.

**Race với task T-013:** kiểm tra `taskCount=0` rồi xóa cột không đủ nếu task có thể được thêm đồng thời. T-012/T-013 phải dùng cùng giao thức khóa board cho tạo/chuyển task vào cột và xóa/đổi category. Chưa có giao thức chung thì không nghiệm thu bảo đảm “không mất task do race”. Không sửa thuật toán task move trong task này.

**Giới hạn cạnh tranh:** write lock tránh position trùng/lost update cấu trúc; không cung cấp ETag/version để phát hiện ý định reorder từ màn hình cũ. Đề xuất semantics là áp dụng target index trên danh sách mới nhất theo thứ tự transaction. Nếu cần optimistic conflict `409`, đó là thay đổi contract/schema cần plan riêng.

Đặt lock timeout hữu hạn; lock timeout/deadlock trả `409` với mã `BOARD_BUSY`, khuyên tải lại và thử lại; không lộ SQL hoặc tự retry mutation không có chính sách idempotency. Hai board khác nhau được phép thay đổi độc lập.

### 3.7. Xóa, lazy loading, audit và hiệu năng

- Delete board đã qua permission: xóa graph theo cascade hiện có; verify tags/mappings ở PostgreSQL thật. Không khai báo lại FK/JPA đến users/workspaces.
- Delete column: query count task có index, kiểm tra dưới giao thức khóa đã chốt; có task trả `409 COLUMN_NOT_EMPTY`. Không chuyển task ngầm hoặc dùng bulk delete vượt kiểm tra nghiệp vụ.
- DTO map trong read transaction; detail board chỉ lấy board + cột. List board lấy summary không duyệt `columns/tasks`; tránh N+1 và vòng lặp JSON hai chiều. Có thể dùng projection/entity graph/query riêng sau khi đo, không load toàn bộ task graph để kiểm tra taskCount.
- `createdAt` giữ nguyên; `updatedAt` phản ánh entity thực sự đổi. Reorder không sửa nội dung/task/status. Không phát `task.status_changed` vì thao tác reorder không thay đổi trạng thái task.
- Thay đổi category ở cột có task bị chặn như mục NV-03; nếu mở rộng sau này phải đồng bộ trạng thái/event T-014 và cache đọc.
- Audit/log mutation gồm actor UUID, action, resource UUID, workspace UUID, correlation ID, kết quả và thời gian; không log bearer token/body nhạy cảm. Gắn audit hook theo cơ chế dự án, nếu chưa có audit storage phải ghi rõ phụ thuộc, không coi application log là bằng chứng đã lưu audit bền vững.
- Rate limit mutation theo actor + nhóm route Board bằng component sở hữu chung/Board; không inject limiter từ `auth.internal`. Chốt capacity/refill và clock test; trả `429` + `Retry-After`. Đọc health giữ hành vi hiện có. Single-instance dùng in-memory; Redis không thuộc T-012.
- Theo dõi request duration, tỷ lệ lỗi, query count và lock wait. Mục tiêu CRUD P95 < 200 ms phải có workload/môi trường đo, không suy ra từ một request thủ công.

### 3.8. Lỗi HTTP và xử lý exception

Response lỗi là `application/problem+json`, có `type`, `title`, `status`, `detail`, `instance`; thêm `code`, `errors` theo field và correlation ID theo quy chuẩn dự án. Không có stack trace/tên class nội bộ/SQL.

| HTTP | Nhóm lỗi |
|---|---|
| `400` | UUID sai, JSON sai/thiếu, field ngoài whitelist, enum/position/name/pagination sai |
| `401` | Chưa xác thực bằng access token hợp lệ |
| `403` | Không thuộc workspace được yêu cầu trực tiếp |
| `404` | Resource không tồn tại hoặc ngoài quyền truy cập resource ID |
| `409` | Board archived, cột có task, thay category cột có task, vượt limit cột, lock conflict |
| `413`, `415` | Body quá lớn; Content-Type không được hỗ trợ |
| `429` | Vượt rate limit mutation |
| `503` | Không đánh giá được quyền; dependency permission chưa sẵn sàng |
| `500` | Lỗi không dự kiến, thông báo an toàn; rollback và log correlation |

Board advice xử lý exception sở hữu bởi Board, không import exception từ `auth.internal`. Kiểm tra thứ tự advice với handler chung hiện có bằng full-app integration test. Lỗi security filter phải do security entry point/handler trả đúng ProblemDetail; MVC advice không xử lý được mọi lỗi trước controller. Không wrap mọi exception thành `400` hoặc `404`.

### 3.9. Duy trì BoardApi

Giữ nguyên signature `findTasksByProject(UUID)` và `findTask(UUID)` cùng `TaskSummary`; không đưa entity ra API hoặc đổi `LocalDate` sang `Instant` mà không version contract.

Hai method đã được hoàn thiện bằng truy vấn read-only trên repository T-011. Quyết định áp dụng là `projectId = workspaceId`; query đi qua `task.column.board.workspaceId`, không dùng board ID.

Status được map từ category cột; `dueDate: Instant -> LocalDate` theo UTC; null giữ null. Contract không mang actor nên không được dùng thay cho API HTTP có kiểm tra quyền. Không thay đổi signature `BoardApi`.

## 4. Thứ tự triển khai và sản phẩm bàn giao

| Bước | Công việc | Đầu ra / điều kiện chuyển bước |
|---|---|---|
| P1 | Chốt G1/G2/G3, nghĩa projectId, archive/delete policy, giới hạn và rate limit | Hoàn tất; mọi thành viên có cùng quyền Board; 100 columns/board, page size tối đa 100, rate limit 60 mutation/actor/60 giây trong mỗi app instance |
| P2 | Tạo branch theo thẻ, đối chiếu dependency T-011 | Hoàn tất trên `feature/T-012-board-column-endpoints`, dựa trên `origin/feature/T-011-board-entities` vì T-011 chưa có trong `develop` tại thời điểm bắt đầu |
| P3 | Public DTO/enum/page response; chuẩn hóa và validate request; actor adapter/permission gateway | Hoàn tất; không có dependency impl-to-impl; `:verifyModuleBoundaries` pass |
| P4 | Board create/list/detail/update/archive/delete | Hoàn tất; membership theo workspace, phân trang, archive, cascade và ProblemDetail |
| P5 | Column create/list/detail/update/delete; khóa board và kiểm tra task | Hoàn tất; position liên tục; không xóa cột có task; mutation bị chặn với board archived |
| P6 | Reorder transaction | Hoàn tất implementation và test move cột cuối lên đầu; matrix concurrency/rollback toàn diện chưa chạy |
| P7 | Filter/advice, audit/rate limit, BoardApi | Hoàn tất implementation; audit cùng transaction; audit/rate-limit/unit và full-app regression pass |
| P8 | Chạy suites và cập nhật kết quả | Backend `check` pass với Docker/PostgreSQL thật ngày 07/10/2026; chưa đo performance hoặc chạy manual UAT |
| P9 | Review PR, cập nhật Trello, bàn giao T-013/T-015/frontend | Còn lại: chưa tạo PR/review; thao tác chuyển card Trello sang In Progress trả HTTP 401 `unauthorized card permission requested`; chưa xác nhận được trạng thái card |

## 5. Tiêu chí hoàn thành

### 5.1. Acceptance criteria gốc

- [x] **AC-01:** Tạo board và list đúng workspace; integration test HTTP tạo board, phân trang và đọc dữ liệu trên PostgreSQL.
- [x] **AC-02:** Reorder cột đúng; kiểm tra response thứ tự và vị trí còn liên tục sau request kế tiếp.

### 5.2. Tiêu chí nghiệm thu bổ sung

- [x] G1/G2/G3 và policy quyền đã chốt; endpoint dùng actor thật và fail closed khi permission event không có đúng một phản hồi.
- [x] Board/column CRUD trả status, DTO và Location; không còn route Board/Column stub `501`.
- [x] Mọi read/write kiểm tra membership theo workspace thật; test xác nhận truy cập chéo resource bị che bằng `404`, workspace list không quyền trả `403`.
- [x] Validation server, presence-aware PATCH, bảo vệ field bất biến, kích thước trường/page và giới hạn tạo cột đã triển khai. HTTP body cap toàn cục chưa được bổ sung.
- [x] Create/delete/reorder chuẩn hóa positions; integration test xác nhận reorder và reindex qua thao tác tiếp theo. No-op và mọi đầu/cuối chưa có case riêng.
- [ ] Khóa pessimistic trên board được triển khai; test concurrency/rollback chưa chạy. T-013 phải dùng cùng lock contract khi tạo/move task để loại race task-count với delete/category update.
- [x] Delete cột có task trả `409`; test delete board xác nhận cascade task/comment/tag mapping và giữ workspace/user. Giữ board khác và failure rollback cần case riêng.
- [x] Archive giữ board, chặn column mutation; restore và sửa board sau restore đã chạy qua API test.
- [x] MVC validation/permission errors và security 401 trả `application/problem+json`; test 401/400 không có stack/SQL trong response. Các nhóm `429/503/500` chưa được exercise end-to-end.
- [x] Không có impl-to-impl import; `board-api` chỉ phụ thuộc `common`; `:verifyModuleBoundaries` pass.
- [x] Giữ nguyên chữ ký `BoardApi`; quyết định `projectId=workspaceId`, UTC date mapping và category mapping đã ghi trong mục 3.9.
- [ ] Audit cùng transaction và rate limit per-actor/per-instance đã triển khai, unit/integration test pass. Chưa có kết quả đo latency/query/lock wait; rate limit dùng bộ nhớ JVM, chưa chia sẻ quota giữa nhiều instance; chưa tích hợp bot challenge.
- [x] Bộ test chạy được và full `check` pass; matrix chưa chạy vẫn giữ trạng thái riêng ở bảng bên dưới.
- [ ] Plan ghi API/status/giới hạn và tác động delete; chưa tạo PR và chưa review.

## 6. Kế hoạch testing

### 6.1. Tầng kiểm thử và fixture

| Tầng | Bộ test / kế hoạch | Nội dung |
|---|---|---|
| Unit | `BoardRateLimiterTest` ở board-impl | Quota tách theo actor, `429`/`Retry-After`, reject cấu hình sai |
| Full-app HTTP integration | `BoardColumnApiIntegrationTest` ở app | MockMvc + JWT thật + Auth membership event + PostgreSQL 17/Flyway V1–V3; Board/Column API, quyền, validation, audit, archive/cascade |
| PostgreSQL regression | `BoardPersistenceIntegrationTest`, `KanbanSchemaIntegrationTest`, `KanbanMigrationIntegrationTest` | Persistence T-011, mapping, constraints, migration và cascade trên PostgreSQL 17 |
| Concurrency | Test nhiều transaction/kết nối riêng ở PostgreSQL | Hai request đồng thời, lock wait, race create/delete/reorder và task |
| Manual / performance | Kịch bản API và load workload có ghi thông số | Reload, workflow người dùng, latency P95, logs/audit |

Fixture chuẩn: workspace `WA`, `WB`; `UA` chỉ thuộc WA, `UB` chỉ thuộc WB, `UAB` thuộc cả hai, `UX` không thuộc workspace nào. Token thiếu/sai/hết hạn/refresh được chuẩn bị riêng. `BA` thuộc WA, `BB` thuộc WB; board rỗng và board archived. BA có cột `C0..C3` vị trí `0..3`, thêm cột chứa task/bình luận/tag để kiểm thử bảo toàn và cascade.

Mỗi test có dữ liệu riêng và assertion database sau thao tác; negative case so sánh snapshot trước/sau. PostgreSQL test flush/clear hoặc đọc ở transaction mới; không chỉ assert response hoặc mock `save()`. Test concurrency dùng latch/barrier và timeout hữu hạn, không phụ thuộc sleep ngẫu nhiên; assertion tập trung invariant và kết quả cuối, không giả định thread nào thắng. Test rollback gọi service qua Spring proxy/transaction thật, không để outer test transaction che lỗi commit.

`board-impl` test mock permission gateway, không thêm dependency/import Auth impl. Test tích hợp nhiều module đặt ở `app` — bootstrap được phép wire module; có thể tạo fixture qua public REST hoặc fixture thuộc app, không đưa access repository Auth vào production Board.

### 6.2. Quy ước expected và thực tế

Mỗi dòng bên dưới có một expected có thể assert và một cột **Thực tế**. Các case chưa chạy vẫn ghi rõ như vậy; `PASS`/`PARTIAL` chỉ áp dụng cho thao tác đã được assert trong report `backend/app/build/reports/tests/test/index.html` hoặc `backend/board-impl/build/reports/tests/test/index.html` của lượt ngày 07/10/2026. Case tham số hóa chỉ được xem là pass nếu toàn bộ biến thể được chạy.

### A. Tạo, liệt kê và quản lý board

| ID | Thiết lập / thao tác | Expected | Thực tế |
|---|---|---|---|
| B01 | UA tạo board WA với tên/mô tả hợp lệ | 201 + Location; UUID mới, archived=false, chưa có cột; DB đúng WA | PASS — `BoardColumnApiIntegrationTest.createsAndListsBoardsWithinTheAuthorizedWorkspace`: response 201/Location, UUID và columns rỗng; list đọc lại từ PostgreSQL. |
| B02 | Tên có dấu, khoảng trắng, markup; description chứa script/markup | Trim tên; lưu text đã sanitize; response giữ Unicode và không chứa tag/script | PASS — cùng test: tên `Kế hoạch Q4`, description `Roadmap`; HTML bị làm sạch thành text. |
| B03 | Bỏ description hoặc gửi null lúc create | 201; description=null | Chưa chạy |
| B04 | Tạo hai board trùng tên trong cùng WA | Đều 201, ID khác; không tự áp unique rule | Chưa chạy |
| B05 | List WA khi có BA và BB; UAB thuộc cả hai | Chỉ board WA, không trộn WB; chọn WB chỉ có board WB | PARTIAL — test chặn outsider list WA bằng 403 và che board/column bằng 404; chưa tạo hai board ở hai workspace rồi so sánh cả hai danh sách. |
| B06 | Workspace actor được phép nhưng chưa có board | 200; items rỗng, metadata trang đúng | Chưa chạy |
| B07 | Nhiều board và createdAt trùng; gọi nhiều lần | Order createdAt,id ổn định; không thiếu/trùng items trong dataset tĩnh | Chưa chạy |
| B08 | page đầu/giữa/cuối/ngoài dữ liệu; size 1 và 100 | 200, số item và metadata đúng; trang ngoài dữ liệu rỗng | PARTIAL — size=1 trả một trong hai board và totalElements=2; page=-1/size=101 bị 400. Trang giữa/cuối/rỗng và size=100 chưa chạy. |
| B09 | List mặc định và includeArchived=true | Mặc định chỉ active; true gồm archived của đúng workspace | PASS — test create/list: mặc định totalElements=1 sau archive; `includeArchived=true` trả 2. |
| B10 | GET BA có cột và nhiều task/comments | 200; cột theo position,id; không serialize graph task/comments/proxy | Chưa chạy |
| B11 | PATCH từng trường name/description; omit trường khác | 200; chỉ trường có mặt đổi; ID/workspace/createdAt không đổi | PARTIAL — test lifecycle PATCH name+description sau restore và xác nhận sanitized response; từng field/presence/null riêng và timestamp chưa so sánh. |
| B12 | PATCH description=null, description="", description bị omit | Lần lượt xóa, giữ chuỗi rỗng, giữ giá trị cũ theo contract | Chưa chạy |
| B13 | Archive BA rồi GET/list và thử mọi column mutation | Dữ liệu giữ nguyên; GET được; list đúng filter; column mutation 409 | PARTIAL — archive response, list filter và POST column bị 409 đã assert; GET archived và các PATCH/DELETE/reorder khác chưa chạy. |
| B14 | Restore archived board; cập nhật name khi đang archived | Restore 200; column mutation hoạt động lại; đổi name khi chưa restore 409 | PARTIAL — restore 200 và PATCH name khi archived bị 409; chưa tạo cột sau restore. |
| B15 | DELETE board có cột/task/comment/tag/mapping; BA và BB độc lập | 204 body rỗng; xóa graph BA, giữ BB/users/workspaces | PARTIAL — PostgreSQL test xác nhận comment/tag mapping bị cascade và workspace/user còn; chưa xác nhận board thứ hai độc lập trong cùng fixture. |
| B16 | GET/PATCH/DELETE UUID board không tồn tại; xóa lại board đã xóa | 404 ProblemDetail; DB không đổi | Chưa chạy |

### B. CRUD column và bảo toàn task

| ID | Thiết lập / thao tác | Expected | Thực tế |
|---|---|---|---|
| C01 | Thêm cột đầu tiên vào board rỗng | 201 + Location; position=0; boardId/category đúng | PARTIAL — test tạo cột đầu tiên trả 201 và sau đó kiểm tra được ID/order/position qua các request tiếp theo; chưa assert Location/category ngay ở POST đầu tiên. |
| C02 | Thêm cột vào BA có N cột | 201; cột mới cuối danh sách; positions 0..N | PARTIAL — test tạo liên tiếp A/B/C và GET trả A/B/C; chưa assert mọi position ban đầu trong cùng một truy vấn. |
| C03 | Tạo cột với từng category hợp lệ; tên trùng/tên khác category | 201; lưu đúng enum; không ngầm suy category từ tên | PARTIAL — TODO/IN_PROGRESS/DONE được tạo và IN_REVIEW được PATCH; chưa chạy mọi category qua POST hoặc trùng tên. |
| C04 | GET column và list column board | 200; parent đúng; order ổn định; không có column của BB | PARTIAL — list/get trả đúng các cột board; truy cập foreign column trả 404 ở permission test. |
| C05 | PATCH tên cột đang có task | 200; ID/position/category/task/comment/audit task không đổi | Chưa chạy |
| C06 | PATCH category cột rỗng; omit hoặc gửi category không đổi khi sửa tên cột có task | Category khác chỉ đổi ở cột rỗng; omitted/giá trị không đổi giữ status task, sửa tên vẫn hợp lệ | PARTIAL — đổi category ở cột rỗng pass; PATCH name-only cho cột có task chưa được assert thành công. |
| C07 | PATCH sang category khác ở cột có task | 409; category/task/status không đổi; không phát task event sai | PASS — nhận 409; truy vấn PostgreSQL xác nhận name, category và task còn nguyên. |
| C08 | DELETE cột rỗng ở đầu/giữa/cuối | 204; cột bị xóa; còn lại positions 0..N-2 | PARTIAL — xóa cột rỗng ở giữa sau reorder; còn lại được reindex liên tục. Đầu/cuối/duy nhất chưa chạy. |
| C09 | DELETE cột rỗng duy nhất | 204; board còn; columns=[] | Chưa chạy |
| C10 | DELETE cột có task và bình luận/tag | 409 COLUMN_NOT_EMPTY; toàn bộ graph giữ nguyên | PARTIAL — cột có task trả 409; comment/tag mapping trên chính cột đó chưa được thêm vào fixture. |
| C11 | GET/PATCH/DELETE column không tồn tại hoặc parent board đã xóa | 404; không tái tạo column/board | Chưa chạy |
| C12 | Tạo đến 100 cột, rồi thêm cột thứ 101 | Đạt limit còn thành công; lần vượt limit 409, không ghi thêm | Chưa chạy |

### C. Reorder và tính bền vững thứ tự

| ID | Thiết lập / thao tác | Expected | Thực tế |
|---|---|---|---|
| R01 | [C0,C1,C2,C3], move C1 -> 0 | [C1,C0,C2,C3], positions 0..3 | PARTIAL — fixture [A,B,C], move C -> 0 đã pass và response có positions 0..2; fixture bốn cột đúng như case chưa chạy. |
| R02 | [C0,C1,C2,C3], move C1 -> 3 | [C0,C2,C3,C1], positions 0..3 | Chưa chạy |
| R03 | Move C0 -> 2 và C3 -> 1 trên fixture riêng | Thứ tự insert đúng; phần còn lại giữ relative order | Chưa chạy |
| R04 | Move cột đến vị trí hiện tại | 200, no-op; ID/order/audit không đổi vô ích | Chưa chạy |
| R05 | Board chỉ một cột; reorder -> 0 | 200, vẫn position=0 | Chưa chạy |
| R06 | Move column chứa task/comments | Chỉ vị trí cột đổi; nội dung, ID, category, task parent và số lượng không đổi | Chưa chạy |
| R07 | Commit rồi clear context/GET lại/đổi phiên HTTP | Thứ tự trả về giống kết quả đã commit | PARTIAL — sau reorder, request xóa cột kế tiếp reload dữ liệu và truy vấn DB xác nhận reindex; GET list order riêng sau commit chưa được assert. |
| R08 | Position âm, =N, >N, quá int range | 400; không clamp; không thay đổi DB | PARTIAL — `position=N` bị 400 và vị trí cột được truy vấn giữ nguyên; các giá trị âm và int boundary chưa chạy. |
| R09 | Position null/thiếu/chuỗi/thập phân; JSON sai | 400 cho từng biến thể; DB không đổi | Chưa chạy |
| R10 | Reorder UUID cột không tồn tại hoặc board archived | Lần lượt 404/409; không ghi position | Chưa chạy |
| R11 | Dữ liệu cũ position [0,0,7], thực hiện reorder/create/delete | Đọc đầu vào theo position,id; mutation normalize 0..N-1, không mất cột | Chưa chạy |
| R12 | Cố gửi boardId đích hoặc danh sách ID cột ngoài board vào reorder | 400 theo whitelist; không chuyển column sang board khác | Chưa chạy |

### D. Xác thực, phân quyền và chống IDOR

Áp dụng ma trận **mọi endpoint x mọi actor**: UA/UB/UAB/UX, anonymous, invalid/expired/refresh token. Mỗi route phải có assertion không rò dữ liệu và negative mutation không đổi database.

| ID | Thiết lập / thao tác | Expected | Thực tế |
|---|---|---|---|
| S01 | Không token; token sai/chữ ký sai/hết hạn; dùng refresh token | 401 cho từng route/biến thể; không có mutation | PARTIAL — anonymous GET trả 401 ProblemDetail + correlation ID; token sai/hết hạn/refresh và các mutation chưa chạy trong suite T-012. |
| S02 | UA/UB/UAB gọi đúng workspace mình thuộc | Được phép theo policy membership đã chốt; response scoped đúng | PARTIAL — actor là member được tạo/list/CRUD; chưa kiểm thử UAB với hai workspace. |
| S03 | UX hoặc UB create/list theo WA | 403; không tiết lộ danh sách/tên board WA | PARTIAL — non-member list WA trả 403; create WA và các token biến thể chưa chạy. |
| S04 | UB biết UUID BA/C0, gọi GET/PATCH/DELETE/reorder | 404 thống nhất; không đọc/ghi dữ liệu WA | PARTIAL — GET board/column và PATCH board foreign ID trả 404; DELETE/reorder/PATCH column foreign ID chưa chạy. |
| S05 | UAB thao tác BA dù UI đang chọn WB | Quyền dựa vào BA.workspaceId thật; không phụ thuộc activeWorkspace phía client | Chưa chạy |
| S06 | Gửi actorId/userId/workspaceId giả ở body/header/query | Không thay actor xác thực hoặc quyền; reject field trái contract | Chưa chạy |
| S07 | Thu hồi membership sau request thành công, tái dùng access token | Request sau bị từ chối theo 403/404 policy; token không bypass membership | Chưa chạy |
| S08 | Workspace UUID không tồn tại cho create/list | 403 giống non-member, không tạo orphan board | Chưa chạy |
| S09 | Gọi service trực tiếp với actor không đủ quyền | Bị từ chối trước save/delete; controller không phải lớp quyền duy nhất | Chưa chạy |
| S10 | Permission thiếu listener/response, exception hoặc unavailable | 503; fail closed; DB không đổi; log có correlation ID | Chưa chạy |
| S11 | Nếu dùng event bridge: response thiếu/sai correlation hoặc trùng response; chạy song song actor khác | Từ chối kết quả không hợp lệ; không dùng nhầm quyền; trạng thái request được dọn sạch | Chưa chạy |
| S12 | Principal thật dùng email làm username; lấy actor UUID qua contract public | Lấy đúng UUID; không parse username; không import/cast auth.internal | PASS — JWT integration request dùng principal Auth thật; Board kiểm tra membership đúng actor UUID qua `AuthenticatedActor`. |
| S13 | Health check và auth routes sau khi thêm Board security/advice | Health/auth regression vẫn đúng; không mở public board route | PARTIAL — full Auth regression suite pass và Board anonymous request trả 401; health endpoint chưa gọi trong test T-012. |

### E. Validation và hợp đồng HTTP

| ID | Thiết lập / thao tác | Expected | Thực tế |
|---|---|---|---|
| V01 | Board name thiếu/null/rỗng/toàn khoảng trắng; column name tương tự | 400, errors đúng field; không tạo/cập nhật row | PARTIAL — board name toàn khoảng trắng trả 400 ProblemDetail/field error; biến thể column và null/missing chưa chạy. |
| V02 | Board name 1/255/256; column name 1/100/101; Unicode | Boundary hợp lệ qua; vượt max 400; DB và DTO không lệch độ dài | Chưa chạy |
| V03 | Description null, rỗng, 10.000/10.001 ký tự | Null/rỗng/max hợp lệ; vượt giới hạn 400 | Chưa chạy |
| V04 | Markup HTML/script trong name/description; plain text có & | Sanitize tag/script thành text; không echo markup nguy hiểm | PASS — create board xác nhận tên/mô tả trả về text sạch, Unicode còn nguyên. |
| V05 | workspaceId thiếu/null; mọi path UUID sai định dạng | 400 ProblemDetail; không thành 500 | PARTIAL — path UUID sai định dạng trả 400 `INVALID_REQUEST`; workspaceId missing/null chưa chạy. |
| V06 | Category thiếu/null/không hỗ trợ/lowercase/numeric | 400 từng biến thể; enum string đúng hợp đồng | Chưa chạy |
| V07 | PATCH rỗng; name/category/archived explicit null | 400; không cập nhật dở dang | Chưa chạy |
| V08 | Mass assignment id/workspaceId/boardId/position/audit/graph | 400; immutable fields và relations giữ nguyên | PARTIAL — `workspaceId` trong PATCH bị từ chối bằng 400 `UNKNOWN_FIELDS`; các field/route khác chưa chạy. |
| V09 | page âm; size 0/âm/101; giá trị không phải số | 400; không query toàn bộ hoặc silent truncate | PARTIAL — `page=-1` và `size=101` trả 400 đúng mã lỗi; 0/âm/non-number khác chưa chạy. |
| V10 | Body thiếu/sai JSON; sai Content-Type; body vượt limit | Lần lượt 400/415/413; DB không đổi | Chưa chạy |
| V11 | GET/POST/PATCH/DELETE thành công | Status/body/Location đúng bảng API; 204 không có body | PARTIAL — Board create/list/PATCH/delete và Column CRUD/reorder trả status kỳ vọng; Board detail success, mọi Location và 204 body chưa được assert đầy đủ. |
| V12 | Mọi nhóm lỗi 400/401/403/404/409/429/503/500 | ProblemDetail + status đúng; không stack trace/SQL/token; MVC và filter đều được kiểm tra | PARTIAL — test xác nhận 400/401/403/404/409 là `application/problem+json`; endpoint `429/503/500` và nội dung không lộ dữ liệu ở mọi handler chưa exercise end-to-end. |
| V13 | Board exception gặp Auth advice catch-all trong full app | Lỗi nghiệp vụ vẫn đúng status, không bị chuyển thành 500 | PASS — full-app integration test giữ đúng các status Board 400/403/404/409; không bị advice Auth chuyển thành 500. |

### F. Transaction, cascade và cạnh tranh

| ID | Thiết lập / thao tác | Expected | Thực tế |
|---|---|---|---|
| T01 | Gây exception sau khi đổi một phần positions, trước commit | Toàn bộ order rollback; audit không commit dở dang | Chưa chạy |
| T02 | Lỗi giữa create/delete board/column | Không có row/graph/position cập nhật nửa chừng | Chưa chạy |
| T03 | Hai request thêm cột đồng thời vào một board | Hai UUID khác nhau; positions liên tục/không trùng; không vượt limit | Chưa chạy |
| T04 | Hai reorder đồng thời, hai connection riêng | Serialize theo board; kết quả hợp lệ theo thứ tự transaction; không mất cột | Chưa chạy |
| T05 | Reorder chạy đồng thời với create/delete column | Một kết quả serial hợp lệ; positions liên tục; resource bị xóa xử lý 404 khi phù hợp | Chưa chạy |
| T06 | Archive/delete board chạy đồng thời với column mutation | Không ghi vào board đã archived/deleted; loser nhận lỗi đúng; không orphan | Chưa chạy |
| T07 | Hai request thay cấu trúc ở BA và BB | Khóa độc lập, không dùng global lock; cả hai giữ invariant | Chưa chạy |
| T08 | Task được tạo/move vào cột cùng lúc delete/category update | Theo khóa chung T-013: task tồn tại thì chặn delete/category; không cascade mất task do race | Chưa chạy |
| T09 | Giữ board lock đến quá timeout; mô phỏng deadlock có kiểm soát | 409 BOARD_BUSY trong timeout hữu hạn; rollback; không lộ SQL | Chưa chạy |
| T10 | Delete một child khi parent collection đã load | Child thật sự bị xóa, không bị cascade persist lại; siblings giữ nguyên | Chưa chạy |
| T11 | Run PostgreSQL/Flyway với ddl-auto=validate, open-in-view=false | Mapping/schema khớp; DTO response không LazyInitializationException | PASS — app/persistence/schema suites chạy trên PostgreSQL 17, Flyway V1–V3 và `ddl-auto=validate`; full `check` pass. |

### G. Public contract, module boundary và vận hành

| ID | Thiết lập / thao tác | Expected | Thực tế |
|---|---|---|---|
| Q01 | Compile consumer public DTO/BoardApi hiện có | Không phá signature; không yêu cầu class internal/entity/proxy | PASS — full Gradle `check` biên dịch app và tất cả consumer module; `BoardApi` signature giữ nguyên. |
| Q02 | Nếu hoàn thiện lookup: findTask có/không có task; findTasksByProject | Optional/list đúng, projectId đúng quyết định; không trộn workspace | Chưa chạy |
| Q03 | Lookup task có/không dueDate, gần ranh giới ngày UTC | Map Instant -> LocalDate UTC đúng; null giữ null; status từ category | Chưa chạy |
| Q04 | Boundary task và tìm imports internal nước ngoài | Không impl-to-impl; actor/permission/audit/rate limit không bypass boundary | PASS — `:verifyModuleBoundaries` pass trong build `check`. |
| Q05 | Rate limit dưới/đúng/trên ngưỡng; chờ refill với clock kiểm soát | Hợp lệ trước ngưỡng; vượt 429 + Retry-After; hồi phục đúng; không ghi request bị chặn | PARTIAL — `BoardRateLimiterTest` xác nhận quota theo actor, 429 exception và Retry-After dương; refill và HTTP header chưa chạy. |
| Q06 | Hai actor/workspace khi rate limiting; UUID resource thay đổi liên tục | Quota cô lập theo policy; không bypass bằng đổi path UUID; không ghi lẫn dữ liệu | Chưa chạy |
| Q07 | Create/update/delete/reorder thành công và lỗi | Log/audit đúng actor/resource/correlation; không log token; chỉ ghi mutation đã commit vào audit thành công | PARTIAL — test audit actor/resource/action/correlation cho create và delete; không lưu bearer token; đủ mọi mutation/failure rollback chưa chạy. |
| Q08 | List board và GET detail với nhiều task/comment phía dưới | Không N+1 theo số task; response không tải/serialize graph ngoài DTO | Chưa chạy |
| Q09 | Backend check và regression Auth/T-010/T-011 | Tất cả pass, không skipped do thiếu Docker; report có timestamp/commit | PASS — `backend/gradlew.bat check`, Docker Desktop + PostgreSQL 17; 07/10/2026, 14:51 GMT+7; 149 tests, 0 failed/error/skipped trong XML reports. Không có commit mới. |
| Q10 | Load workload CRUD đã mô tả ở mục 6.4 | P95 < 200 ms trong điều kiện đo, error rate không vượt ngưỡng đã chốt; ghi số liệu thật | Chưa chạy |

### 6.3. Kiểm thử workflow thủ công

1. Đăng nhập UA; tạo board WA; GET danh sách rồi GET detail: đúng dữ liệu, columns rỗng.
2. Thêm bốn cột; đổi tên; reorder đầu/giữa/cuối; tải lại: thứ tự không đổi, ID giữ nguyên.
3. Với fixture có task, thử xóa cột/đổi category: nhận 409; task và bình luận vẫn đọc được.
4. Xóa cột rỗng; tải lại: thứ tự liên tục. Archive board; xác nhận list/GET/mutation đúng; restore rồi thao tác tiếp.
5. Đăng nhập UB, dùng UUID board/column WA để đọc/sửa/xóa: bị chặn. UAB truy cập hai workspace nhận đúng tập dữ liệu từng bên.
6. Xóa board bằng UA; kiểm tra graph Kanban đã xóa và workspace/user/BB còn. Không chạy delete trên dữ liệu thật của nhóm; dùng fixture testing riêng.

**Expected:** toàn bộ workflow đáp ứng các case tương ứng. **Thực tế:** Chưa chạy manual UAT; các fixture và request tự động dùng test database riêng, không có thao tác xóa dữ liệu người dùng.

### 6.4. Đo hiệu năng và query

- PostgreSQL 17; dataset dự kiến 2 workspace, 100 board/workspace, 10 cột/board và 100 task/board; dùng fixture lớn để phát hiện lazy graph/N+1, không trả task trong DTO T-012.
- Warm-up 30 giây; đo 60 giây, 10 concurrent users, mix 70% GET list/detail và 30% mutation trên board riêng. Đo riêng contention nhiều actor cùng một board, không trộn vào baseline CRUD độc lập.
- Ghi máy/CPU/RAM, profile, connection pool, commit, số request và tỷ lệ lỗi; P50/P95/P99, query count, lock wait và response size. Khống chế rate limit theo cấu hình test có ghi rõ; không tắt âm thầm rồi báo kết quả production.
- **Expected:** CRUD P95 < 200 ms theo mục tiêu sản phẩm; không có lỗi dữ liệu/5xx ngoài failure injection; số query không tăng theo toàn bộ task graph. Nếu không đạt, xác định bottleneck rồi cập nhật bằng chứng sau sửa.
- **Thực tế:** Chưa chạy; chưa có số liệu latency, throughput, query count hoặc lock wait của API T-012.

### 6.5. Lệnh kiểm tra dự kiến và ghi nhận kết quả

Chạy trong `backend/`. Các suite đã được tạo và chạy:

```powershell
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat :board-impl:test
.\gradlew.bat :app:test
.\gradlew.bat check
```

Integration test sử dụng PostgreSQL 17 qua Testcontainers với migration thật. Docker phải hoạt động; không thay PostgreSQL bằng H2 cho transaction/locking/cascade assertions. Không gọi test skipped là PASS.

Trên máy đã kiểm tra T-011, Testcontainers 1.21.4 và Docker Desktop cần environment override dưới đây do user config Docker client. Chỉ dùng khi môi trường hiện tại vẫn cần, không hard-code đường pipe Windows vào code dùng chung/CI Linux:

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_DOCKER_CLIENT_STRATEGY = 'org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy'
```

Frontend build/lint áp dụng nếu triển khai có thay đổi frontend; plan T-012 hiện chỉ backend, không tuyên bố đã chạy frontend checks. Bằng chứng test lưu tại `backend/<module>/build/reports/tests/test/index.html` và `build/test-results/test/`; kèm commit, thời gian, môi trường vì file build có thể bị ghi đè ở lần chạy sau.

| Đợt kiểm tra | Expected | Thực tế / evidence |
|---|---|---|
| Review file plan | Đủ nghiệp vụ/tech/DoD/test expected–thực tế, link nội bộ hợp lệ | Cập nhật 07/10/2026; case đã chạy đánh `PASS/PARTIAL`, case còn lại giữ `Chưa chạy`; file nằm cùng cấp với task gốc. |
| Chốt integration/policy | G1/G2/G3, projectId, archive/delete, bounds/rate limit | Hoàn tất và ghi tại mục 3.2–3.9; lock contract của T-013 còn là điều kiện phối hợp. |
| Unit + HTTP | Permission/JWT/validation/status/CRUD | `BoardRateLimiterTest` và `BoardColumnApiIntegrationTest` pass trong full `check`; từng coverage gap ghi tại matrix. |
| PostgreSQL + concurrency | Mapping/migration/rollback/lock/cascade | PostgreSQL 17/Flyway suites pass; concurrency, injected rollback và lock timeout chưa chạy. |
| Full-app + security/regression | Permission/filter/advice/Auth và T-010/T-011 | Pass; full check, 149 tests, 0 failure/error/skip theo reports ngày 07/10/2026. |
| Backend check / boundary | Exit code 0, boundary pass, không skip integration | `backend/gradlew.bat check` exit code 0 lúc 14:51 GMT+7; `:verifyModuleBoundaries` pass; PostgreSQL 17 Testcontainers chạy bằng Docker Desktop. |
| Manual + performance | Workflow và workload đo được | Chưa chạy; chưa có latency/throughput evidence. |

Các test unit/full-app/backend check đã pass nhưng không thay thế các case `Chưa chạy`, concurrency, manual UAT hoặc performance. Trello chưa đổi được do phản hồi permission 401; PR/review/push chưa thực hiện trong lượt này.
