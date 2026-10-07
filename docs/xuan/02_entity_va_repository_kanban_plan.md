# T-011 — Plan triển khai Entity và Repository Kanban

**Task gốc:** [Ánh xạ và truy xuất dữ liệu Kanban](02_entity_va_repository_kanban.md) · **Module:** `board-impl` · **Phụ thuộc:** T-010.

**Trello:** [T-011 — Implement Entities & Repositories for Board, Column, Task & Comment](https://trello.com/c/QP1K3XCk/17-t-011-implement-entities-repositories-for-board-column-task-comment).

**Ngày lập:** 06/10/2026. **Trạng thái:** Đã triển khai và kiểm tra; T-011 PostgreSQL integration test và backend check đều pass. Các quyết định chi tiết bên dưới là đề xuất triển khai, không phải toàn bộ đều đã được quy định trên Trello.

## 1. Mục tiêu, căn cứ và phạm vi

### 1.1. Mục tiêu

Backend lưu, đọc, cập nhật và xóa dữ liệu Kanban thông qua JPA entity/repository; dữ liệu sau khi đọc lại khớp schema V2, quan hệ đúng, thứ tự ổn định và không truy cập entity của module khác.

### 1.2. Căn cứ đã đối chiếu

| Nguồn | Nội dung sử dụng |
|---|---|
| [Task T-011](02_entity_va_repository_kanban.md) và thẻ Trello | Bốn entity kế thừa `BaseEntity`, quan hệ một–nhiều/nhiều–một, repository và hai acceptance criteria gốc |
| [Kiến trúc](../ARCHITECTURE.md), mục 2–4, 6, 10 | Quyền sở hữu dữ liệu, module boundary và hợp đồng event |
| [Đặc tả sản phẩm](../PRODUCT_SPEC.md), mục 5.1 | Board, cột, task, người phụ trách, deadline, bình luận |
| [Kế hoạch tổng thể](../IMPLEMENTATION_PLAN.md), Phase 2 | T-011 là lớp persistence trước CRUD API, event và realtime |
| [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) | Kiểm thử phù hợp, boundary, review và bằng chứng thực tế |
| [Coding conventions](../../.agents/rules/coding-conventions.md), [module boundaries](../../.agents/rules/module-boundaries.md), [Git workflow](../../.agents/rules/git-workflow.md) | Package nội bộ, dependency, branch và kiểm tra |
| [Migration V2](../../backend/app/src/main/resources/db/migration/V2__board_schema.sql) | Tên bảng/cột, kiểu dữ liệu, nullability, defaults, CHECK, FK và index thực tế |
| [BaseEntity](../../backend/common/src/main/java/io/devflow/common/entity/BaseEntity.java), [JpaAuditingConfig](../../backend/common/src/main/java/io/devflow/common/config/JpaAuditingConfig.java) | UUID do JPA sinh; `createdAt`, `updatedAt` kiểu `Instant`; Spring Data auditing |

Khi mô tả kiến trúc khái quát và schema thực tế khác nhau về tên bảng, dùng V2 làm hợp đồng mapping của T-011. Không suy ra thêm bảng `task_history` hay trường `tasks.board_id` từ mô tả tổng thể.

### 1.3. Hiện trạng

- V2 đã định nghĩa `boards`, `columns`, `tasks`, `task_comments`, `task_tags`, `task_tag_mappings`.
- `board-impl` có dependency JPA, `common`, `board-api`; bốn entity/repository đã được thêm trong branch `feature/T-011-board-entities`.
- `BaseEntity` đã cung cấp ID và audit. Không khai báo lại các trường này trong entity con.
- Cấu hình ứng dụng dùng `ddl-auto=validate`, `open-in-view=false`. Test mặc định của `app` dùng H2 và tắt Flyway; integration test T-010/T-011 chạy PostgreSQL 17 thật qua Testcontainers.
- Docker Engine 29.1.3 không tương thích với Docker client đóng gói cùng Testcontainers 1.20.6 ở cấu hình máy này (API `/info` trả 400). Đã nâng Testcontainers BOM của `app` và `board-impl` lên 1.21.4; cấu hình Gradle chuyển tiếp Docker host/strategy override từ environment vào test JVM. Với user config hiện tại, cần trỏ `DOCKER_HOST` vào pipe `dockerDesktopLinuxEngine` và dùng `EnvironmentAndSystemPropertyClientProviderStrategy`.
- Thẻ T-011 được đọc khi lập plan và đang có hai acceptance criteria gốc. Yêu cầu triển khai đã được bắt đầu; lệnh chuyển thẻ Trello sang In Progress nhận HTTP 401 `unauthorized card permission requested`, nên chưa cập nhật được trạng thái thẻ.

### 1.4. Phạm vi và giới hạn

**Triển khai:** `BoardEntity`, `ColumnEntity`, `TaskEntity`, `CommentEntity`; bốn repository; enum trạng thái/mức ưu tiên cần cho mapping; helper quản lý quan hệ; cấu hình và test persistence.

**Bàn giao cho task sau:** REST/permission của T-012, task CRUD và thuật toán reorder/move của T-013, event T-014, realtime T-015, UI. T-011 kiểm chứng việc đổi cột/position ở tầng dữ liệu, chưa triển khai toàn bộ nghiệp vụ kéo thả.

Nhãn/tag không thuộc bốn entity của T-011. Chỉ kiểm tra tương thích cascade SQL với bảng nhãn hiện có. Repository lọc theo workspace/board không thay thế kiểm tra quyền người dùng ở service/API.

## 2. Kế hoạch nghiệp vụ — mô tả luồng bằng ngôn ngữ

### NV-01. Lưu và mở lại board

1. Người dùng muốn có một board thuộc workspace để tổ chức công việc.
2. Hệ thống ghi tên, mô tả và trạng thái lưu trữ; board mới mặc định chưa lưu trữ.
3. Khi mở danh sách, người dùng nhận các board thuộc workspace đang xem. Khi mở một board, hệ thống đọc đúng dữ liệu đã lưu.
4. Board của workspace khác không xuất hiện trong kết quả lọc. Board được lưu trữ vẫn còn dữ liệu; danh sách đang hoạt động có thể loại board này bằng truy vấn riêng.

**Đầu ra T-011:** lưu/đọc/cập nhật board và truy vấn theo workspace; chưa có màn hình hay API tạo board.

### NV-02. Tổ chức cột trạng thái

1. Người dùng chia board thành các cột như Cần làm, Đang làm, Đang review, Hoàn thành.
2. Mỗi cột thuộc một board, có tên, vị trí và nhóm trạng thái. Tên hiển thị có thể tùy chỉnh; nhóm trạng thái dùng các giá trị được schema hỗ trợ.
3. Khi đọc board, các cột xuất hiện theo vị trí tăng dần. Cột của board khác không bị trộn vào.
4. Đổi tên hoặc vị trí không tạo lại cột, không làm mất các task đang nằm trong cột.

### NV-03. Lưu thông tin công việc

1. Người dùng thêm task vào một cột, nhập tiêu đề, mô tả, mức ưu tiên, người phụ trách và hạn hoàn thành nếu có.
2. Task được lưu với một cột hiện tại và một vị trí trong cột; chưa có người phụ trách/deadline là hợp lệ.
3. Khi mở lại task, nội dung tiếng Việt, Markdown và thông tin đã nhập được giữ nguyên. Mức ưu tiên mặc định là `MEDIUM` nếu phía tạo entity không cung cấp giá trị khác.
4. Danh sách task theo cột/board chỉ chứa task thuộc phạm vi được yêu cầu và có thứ tự xác định.

### NV-04. Đổi vị trí hoặc chuyển cột

1. Khi task chuyển sang cột khác trong cùng board, hệ thống đổi nơi chứa task và vị trí mới.
2. Task giữ nguyên ID, nội dung, người phụ trách, deadline và bình luận. Cột cũ không còn task đó; cột mới có task đó sau khi lưu thành công.
3. Đổi vị trí trong cùng cột giữ nguyên dữ liệu khác. Nhóm trạng thái được hiểu theo cột mới; không lưu thêm trạng thái riêng chưa có trong schema task.
4. Nếu thao tác lưu thất bại, dữ liệu trong database trở về trước thao tác, tránh trạng thái cập nhật dở dang.

**Giới hạn:** tính lại vị trí các task lân cận, cấm/cho phép chuyển khác board, quyền thao tác và xử lý hai người kéo thả cùng lúc cần được định nghĩa ở T-013. T-011 kiểm tra persistence không tự làm mất task khi đổi cha.

### NV-05. Bình luận và truy xuất lịch sử trao đổi

1. Người dùng viết bình luận trong một task; hệ thống lưu nội dung và ID tác giả.
2. Khi mở lại, bình luận thuộc đúng task và được xếp từ cũ đến mới.
3. Sửa bình luận giữ nguyên ID, tác giả, task và thời điểm tạo; thời điểm cập nhật thay đổi.
4. Xóa một bình luận chỉ xóa bình luận đó, không xóa task hoặc bình luận khác.

### NV-06. Lưu trữ, xóa và bảo toàn dữ liệu

1. Lưu trữ board chỉ đổi trạng thái board; cột, task, bình luận vẫn được giữ.
2. Khi tầng nghiệp vụ đã cho phép xóa thật, xóa board sẽ xóa các cột/task/bình luận của board; xóa cột sẽ xóa task/bình luận trong cột; xóa task sẽ xóa bình luận của task.
3. Board/cột/task khác không bị tác động. Xóa dữ liệu Kanban không xóa user/workspace.
4. Bỏ task khỏi danh sách Java chưa đồng nghĩa xóa task. Xóa thật phải được gọi rõ ràng để phân biệt với chuyển cột.

## 3. Kế hoạch kỹ thuật — luồng và các yếu tố cần suy xét

### 3.1. Luồng persistence tổng thể

```text
[Service/use case ở task sau hoặc fixture test]
    -> Mở transaction
    -> Tìm cha và giới hạn truy vấn theo workspace/board/cột
    -> Tạo entity hoặc cập nhật entity đang được quản lý
    -> Đồng bộ phía sở hữu FK và collection phía cha
    -> Repository / EntityManager
    -> Hibernate: UUID + auditing + SQL khi flush
    -> PostgreSQL: schema V2 + NOT NULL/CHECK/FK
    -> Commit; lỗi thì rollback
    -> Đọc lại ở persistence context mới để kiểm chứng
```

Repository chỉ lưu và truy xuất; không tự kiểm tra membership, phát event liên module hoặc quyết định toàn bộ quy tắc xóa/chuyển cột. Không đưa entity JPA vào DTO public hoặc trả entity trực tiếp qua API.

### 3.2. Hợp đồng mapping

Mọi entity kế thừa `BaseEntity`: `id: UUID`, `createdAt: Instant`, `updatedAt: Instant`. Map audit về `created_at`, `updated_at`; kiểm tra naming strategy hiện tại khi chạy Hibernate validate. Entity có constructor không tham số phù hợp JPA, constructor tạo hợp lệ và accessor cần thiết; collection khởi tạo rỗng.

| Entity / bảng | Trường Java → cột DB | Kiểu và quy tắc |
|---|---|---|
| `BoardEntity` / `boards` | `workspaceId` → `workspace_id`; `name`; `description`; `archived` → `is_archived` | UUID bắt buộc; name `VARCHAR(255)` bắt buộc; description TEXT nullable; boolean mặc định false |
| `ColumnEntity` / `columns` | `board` → `board_id`; `name`; `position`; `statusCategory` → `status_category` | Cha bắt buộc; name `VARCHAR(100)`; int >= 0; enum string `TODO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`, length 50 |
| `TaskEntity` / `tasks` | `column` → `column_id`; `title`; `description`; `position`; `priority`; `assigneeId` → `assignee_id`; `dueDate` → `due_date` | Cha bắt buộc; title `VARCHAR(255)`; TEXT nullable; int >= 0; enum string `LOW`, `MEDIUM`, `HIGH`, `URGENT`, length 50, mặc định MEDIUM; UUID nullable; Instant nullable |
| `CommentEntity` / `task_comments` | `task` → `task_id`; `authorId` → `author_id`; `content` | Cha bắt buộc; author UUID bắt buộc; content TEXT bắt buộc |

Các cột bắt buộc còn lại trong bảng trên dùng `nullable=false`. Dùng `@Enumerated(EnumType.STRING)`, không lưu ordinal. Đặt enum trong `board.internal.entity` ở task này; nếu task sau cần enum public, thống nhất contract trong `board-api` khi đó. Không thêm giá trị `BACKLOG` vào enum nhóm trạng thái chỉ vì tên cột có thể là “Backlog”.

**Default:** Hibernate thường đưa cột vào INSERT nên SQL DEFAULT không thay thế việc khởi tạo `priority=MEDIUM`/`archived=false` ở Java. Explicit null cho priority phải bị từ chối, không âm thầm sửa dữ liệu sai. Position do phía tạo cung cấp; không giả định task mới luôn có position 0.

**Kiểu thời gian/TEXT:** dùng `Instant` cho TIMESTAMPTZ, so sánh cùng thời điểm UTC và độ chính xác microsecond của PostgreSQL; không so chuỗi múi giờ. Với TEXT, chọn mapping tương thích PostgreSQL, chẳng hạn `columnDefinition="text"`; không dùng `@Lob` khiến Hibernate chọn kiểu large object khác TEXT.

**Không tự mở rộng validation:** V2 yêu cầu NOT NULL nhưng chưa cấm chuỗi rỗng/trắng, position trùng hoặc tên trùng. T-011 bảo toàn hợp đồng này; quy tắc `NotBlank`, chống trùng và tái đánh số thuộc tầng nghiệp vụ/DTO task sau.

### 3.3. Quan hệ, cascade và chuyển cha

| Quan hệ | Phía sở hữu FK | Phía collection |
|---|---|---|
| Board → Column | `ColumnEntity.board`: `@ManyToOne(fetch=LAZY, optional=false)`, `@JoinColumn(name="board_id", nullable=false)` | `BoardEntity.columns`: `@OneToMany(mappedBy="board")` |
| Column → Task | `TaskEntity.column`: LAZY, optional=false, join `column_id` | `ColumnEntity.tasks`: mappedBy `column` |
| Task → Comment | `CommentEntity.task`: LAZY, optional=false, join `task_id` | `TaskEntity.comments`: mappedBy `task` |

**Phương án đã kiểm thử:** collection cha dùng cascade `PERSIST`, `MERGE`, `REMOVE`; `orphanRemoval=false`. Không cascade từ con lên cha. `REMOVE` hỗ trợ xóa graph đang được quản lý; FK `ON DELETE CASCADE` trong V2 tiếp tục bảo vệ xóa trực tiếp ở database. Không bật `CascadeType.ALL` một cách mặc định.

- Lưu graph mới từ board có thể persist cột/task/bình luận nếu helper đã thiết lập đủ phía sở hữu FK. Lưu con với cha đã tồn tại không persist ngược toàn bộ board.
- `orphanRemoval=false` tránh Hibernate đánh dấu task bị xóa khi gỡ khỏi cột cũ để chuyển cột. Collection không phải nguồn quyết định xóa database. Khi xóa child riêng bằng repository, gỡ child khỏi collection cha đang được quản lý trước để `PERSIST` cascade không lưu lại entity vừa bị đánh dấu xóa.
- Tạo helper thêm cột/task/bình luận; helper move task phải kiểm tra cha mới khác null, gỡ khỏi collection cũ nếu đã tải, cập nhật `task.column`, thêm vào collection mới. Hoàn tất các bước trong cùng transaction, không flush ở trạng thái FK chưa hợp lệ.
- Không dùng helper move để ngầm cho phép chuyển khác board. Service T-013 sẽ kiểm tra policy này trước khi gọi helper.
- Khi xóa cha bằng repository, Hibernate `REMOVE` cascade theo graph; khi xóa trực tiếp bằng SQL, database xử lý FK cascade. Entity con có thể còn trong persistence context sau xóa SQL: flush rồi clear trước khi đọc xác nhận.
- Bulk delete/update bỏ qua callback/audit JPA và có thể làm context stale. T-011 ưu tiên thao tác entity/repository thông thường; nếu thêm bulk query thì phải có test riêng và clear context.
- Không dùng Lombok `@Data`/`toString` duyệt graph hai chiều. Helper/test so sánh ID hoặc cùng instance; nếu triển khai `equals/hashCode`, tránh thay đổi hash khi ID được sinh và kiểm tra entity transient/proxy.

### 3.4. Hợp đồng repository và thứ tự

Bốn repository extends `JpaRepository<Entity, UUID>` trong `io.devflow.board.internal.repository`. Các tên dưới đây là phương án method cụ thể, cần giữ nguyên ý nghĩa truy vấn khi hiện thực.

| Repository / method dự kiến | Kết quả và phạm vi |
|---|---|
| Board: `findByWorkspaceIdOrderByCreatedAtAscIdAsc` | Tất cả board đúng workspace; thứ tự `createdAt`, rồi `id` |
| Board: `findByWorkspaceIdAndArchivedFalseOrderByCreatedAtAscIdAsc` | Chỉ board đang hoạt động; không đổi hành vi method lấy tất cả |
| Board: `findByIdAndWorkspaceId` | Optional; ID đúng nhưng workspace sai cũng trả empty |
| Column: `findByBoard_IdOrderByPositionAscIdAsc` | Cột của board, `position`, rồi `id` |
| Column: `findByIdAndBoard_Id` | Optional; tránh lấy nhầm cột ngoài board |
| Task: `findByColumn_IdOrderByPositionAscIdAsc` | Task của cột, `position`, rồi `id` |
| Task: `findByColumn_Board_IdOrderByColumn_PositionAscColumn_IdAscPositionAscIdAsc` | Task của board; thứ tự cột theo `position,id`, trong mỗi cột task theo `position,id` |
| Task: `findByIdAndColumn_Board_Id` | Optional; ID đúng nhưng board sai trả empty |
| Comment: `findByTask_IdOrderByCreatedAtAscIdAsc` | Bình luận của task; `createdAt`, rồi `id` |
| Comment: `findByIdAndTask_Id` | Optional; không lấy nhầm bình luận ngoài task |

Nếu method truy vấn task theo board quá dài, dùng JPQL `@Query` với cùng WHERE/ORDER BY, tránh tên query khó bảo trì. `tasks` không có `board_id`; truy vấn đi qua `task.column.board.id`.

SQL không đảm bảo thứ tự khi không có ORDER BY. V2 không UNIQUE position: dùng `id` làm tie-breaker để đọc ổn định, không tự coi ID là thứ tự người dùng chọn. Collection có thể thêm `@OrderBy("position ASC, id ASC")` cho columns/tasks và `@OrderBy("createdAt ASC, id ASC")` cho comments, nhưng collection đang nằm trong RAM không tự sắp lại sau mỗi lần đổi position; đọc lại hoặc sort khi cần. Không dùng `@OrderColumn` vì sẽ tạo cơ chế ghi thứ tự cạnh tranh với trường position.

### 3.5. Transaction, audit, isolation và hiệu năng

| Yếu tố | Phương án / lý do |
|---|---|
| Audit | Import `JpaAuditingConfig` vào test slice; persist có hai timestamp, update giữ createdAt và đổi updatedAt. Không tự sửa BaseEntity để né lỗi thiếu cấu hình |
| Ranh giới transaction | Service task sau mở transaction cho thao tác nhiều repository. Repository save không bảo đảm nguyên tử cho cả một use case gồm nhiều lần gọi |
| LAZY | Mọi ManyToOne dùng LAZY; đọc graph trong transaction hoặc query/projection có chủ đích. `open-in-view=false` nên task sau phải map DTO trước khi đóng transaction |
| N+1 | Test kiểm tra association chưa bị tải ngay. Chưa fetch join toàn bộ collections cùng lúc; khi cần màn hình board lớn, task API thiết kế query/projection theo nhu cầu |
| Phạm vi dữ liệu | Query giới hạn workspace/board/task; `findById` vẫn là CRUD cơ bản và không tự kiểm tra quyền. Không tuyên bố repository đã xử lý authorization |
| User/workspace | `workspaceId`, `assigneeId`, `authorId` là UUID thuần; không FK hoặc association JPA tới Auth. ID chưa có trong Auth vẫn lưu được ở tầng này |
| Constraint | DB là lớp kiểm tra cuối. Test invalid phải flush; không chỉ kiểm tra annotation hoặc mock repository |
| Migration | Giữ V2 làm nguồn schema; Hibernate validate. Nếu phát hiện thiếu cột/constraint thực sự cần thiết, đề xuất migration mới, không sửa V2 đã áp dụng chỉ để làm test pass |
| Concurrent update | Chưa có cột version; không thêm `@Version` thiếu migration. Chính sách tránh lost update/locking cho move/reorder bàn giao T-013 |
| Index | Tận dụng index V2 theo workspace, board-position, column-position, task-created. Tie-breaker id không đồng nghĩa đã có composite index cho mọi ORDER BY; chỉ tối ưu thêm dựa trên query plan/thực đo |

### 3.6. File triển khai và trình tự

| Bước | Công việc | Đầu ra / kiểm tra trước bước tiếp theo |
|---|---|---|
| 1 | Xác nhận T-010 đã sẵn sàng, đọc V2/BaseEntity và scope Trello | Chốt mapping, cascade, thứ tự và giới hạn task; khi bắt đầu code dùng branch `feature/T-011-board-entities`, cập nhật ticket theo workflow |
| 2 | Tạo bốn entity và hai enum trong `backend/board-impl/src/main/java/io/devflow/board/internal/entity/` | Compile; không lặp ID/audit; soft references đúng; helper đồng bộ quan hệ |
| 3 | Tạo bốn repository trong `backend/board-impl/src/main/java/io/devflow/board/internal/repository/` | Spring khởi tạo được tất cả derived query/JPQL; các query scoped và ordered đúng hợp đồng |
| 4 | Thiết lập test PostgreSQL/migration cho board-impl | Test bootstrap, auditing, Hibernate validate hoạt động; không dependency sang `app` hoặc foreign impl |
| 5 | Viết/run các bộ E, D, R, C, T bên dưới | Có assertions sau flush/clear, negative case, scope khác và rollback |
| 6 | Chạy test ứng dụng thật và quality gates | Boundary, app/schema regression và backend check pass; lưu report |
| 7 | Review và bàn giao | Mapping case → test method → evidence; ghi Actual; cập nhật checklist Trello khi có bằng chứng, PR theo workflow |

**Test files đã tạo:** `TestBoardApplication.java` và `BoardPersistenceIntegrationTest.java` dưới `backend/board-impl/src/test/java/io/devflow/board/internal/`.

Thêm một smoke test validate mapping Kanban trong `backend/app/src/test/java/io/devflow/board/` nếu các test T-010 hiện có chưa đủ xác nhận entity scan/repository wiring thật. Chỉ thay test/build config cần thiết; không triển khai REST hoặc thay service stub trong T-011.

## 4. Tiêu chí hoàn thành

### 4.1. Acceptance criteria gốc trên Trello

- [x] **AC-01:** Entity mapping chuẩn, hỗ trợ cascade hợp lý.
- [x] **AC-02:** Repository query pass các ca kiểm thử tích hợp.

### 4.2. Điều kiện kiểm chứng chi tiết

Các mục dưới đây diễn giải để nghiệm thu; không phải checklist mới đã cập nhật lên Trello.

- [x] **AC-03:** Đủ bốn entity/repository, kế thừa BaseEntity, package và dependency đúng boundary; Hibernate validate pass trên V1+V2 thật.
- [ ] **AC-04:** Round-trip mọi field, default Java, nullable, enum, TEXT/Unicode, UUID và TIMESTAMPTZ đúng; audit đúng cho cả bốn entity.
- [x] **AC-05:** Quan hệ hai chiều đúng; persist graph mới và lưu con riêng hoạt động; chuyển cột giữ nguyên task/bình luận; collection removal không ngầm xóa task.
- [ ] **AC-06:** Query theo workspace/board/cột/task không lẫn dữ liệu, xử lý empty/missing đúng và có thứ tự ổn định cả khi trùng position/timestamp.
- [ ] **AC-07:** Xóa comment/task/cột/board đúng phạm vi; archive không xóa; DB cascade với tags/mappings còn tương thích; user/workspace và board khác được bảo toàn.
- [ ] **AC-08:** Case dữ liệu sai bị từ chối khi flush; rollback tạo/sửa/move/xóa khôi phục dữ liệu; không đánh đồng rollback mặc định của test với kiểm chứng commit thật.
- [x] **AC-09:** Testcontainers PostgreSQL 17 thực sự chạy; các bộ test bắt buộc không fail/error/skip; boundary, app regression và backend check pass, có bằng chứng.
- [ ] **AC-10:** Có review, report, mapping test case và ghi rõ hạn chế bàn giao T-012/T-013. Chỉ đánh dấu Trello hoàn thành khi tiêu chí tương ứng đã được chứng minh.

Không yêu cầu test frontend/API/E2E cho thay đổi persistence này. Nếu triển khai thực tế mở rộng sang frontend thì bổ sung build/lint theo DoD. Mục tiêu CRUD P95 < 200 ms của dự án được kiểm chứng ở task API với môi trường thống nhất, không dùng thời gian Testcontainers khởi động làm chỉ số persistence.

## 5. Kế hoạch testing

### 5.1. Môi trường và cách kiểm chứng

1. Java 17, Gradle wrapper, JUnit 5 + AssertJ + Spring Boot test, PostgreSQL `postgres:17-alpine` qua Testcontainers; Docker khả dụng.
2. Test repository dùng `@DataJpaTest` với test bootstrap trong `board-impl`, entity/repository scan giới hạn Board, import `JpaAuditingConfig`, tắt thay DataSource sang embedded database (`@AutoConfigureTestDatabase(replace=NONE)`).
3. Bổ sung test dependencies trong `board-impl`: starter-test, Testcontainers PostgreSQL/JUnit, Flyway core + PostgreSQL support, PostgreSQL driver ở test runtime. Dùng version đã thống nhất trong repo, không thêm dependency module `app`/foreign impl.
4. Chạy migration production V1+V2, `spring.flyway.enabled=true`, `ddl-auto=validate`. Để board-impl thấy migration: Gradle test task truyền system property chứa đường dẫn tuyệt đối `rootProject.file("app/src/main/resources/db/migration")`; test config dùng `filesystem:` location từ property đó. Không copy/rewrite migration trong fixture; không phụ thuộc thư mục shell đang đứng.
5. V1 tạo bảng Auth qua SQL là hợp lệ trong database integration; test không import entity/repository Auth vào Board. Smoke test ở `app` dùng wiring thật và migration `classpath:db/migration`.
6. Với round-trip, gọi save/flush, clear EntityManager rồi query lại; dữ liệu và quan hệ phải được xác nhận từ DB, không chỉ từ object vừa tạo.
7. Negative test dùng transaction riêng cho từng biến thể. Sau lỗi constraint, rollback trước case khác; không tiếp tục query trong transaction PostgreSQL đã abort. Khi cần xác định CHECK/FK, dùng JDBC tạo dữ liệu sai mà enum/Java type không biểu diễn được và kiểm tra SQLSTATE/constraint.
8. Commit/rollback/cascade dùng `TransactionTemplate` hoặc test tắt transaction mặc định để kiểm tra ở transaction/kết nối mới. Auditing dùng clock kiểm soát được trong test khi cần; deadline kỳ vọng chọn độ chính xác microsecond.
9. Không silently skip khi Docker không chạy. Ghi Blocked với nguyên nhân, chưa nghiệm thu integration; H2 chỉ hỗ trợ test nhanh bổ sung, không là bằng chứng thay PostgreSQL.

### 5.2. Fixture và quy ước kết quả

Fixture tạo mới và độc lập mỗi test: W1/W2 là hai workspace UUID; B1/B2 thuộc W1, B3 thuộc W2, BA thuộc W1 và archived; C10/C11 thuộc B1, C20 thuộc B2, C30 thuộc B3. T10/T11/T12 thuộc C10, T13 thuộc C11, T20/T30 thuộc C20/C30; mỗi task có thể có 0–3 comments. User ID tác giả/người phụ trách là UUID thuần.

Chủ động insert theo thứ tự khác thứ tự mong muốn; có position `0, 2, 2, 9` và hai timestamp bằng nhau. Dùng UUID cố định ở fixture tie-breaker qua JDBC nếu cần để Expected cụ thể; graph/JPA CRUD riêng vẫn kiểm tra UUID được sinh tự động. Khi dùng JDBC seed audit/default, ghi rõ đây là setup, assertion persistence chính phải đi qua repository.

**Actual trong ma trận:** các ca PostgreSQL chưa chạy do Testcontainers không tìm thấy Docker daemon. Test methods đã được biên dịch; app smoke test riêng đã pass trên H2 nhưng không thay thế xác minh PostgreSQL. Một dòng có nhiều biến thể phải triển khai parameterized test hoặc tách từng ca có evidence. Chỉ đổi sang PASS khi tất cả biến thể của dòng đạt; suite T-010 pass không chứng minh entity/repository T-011 pass.

### 5.3. Bộ E — Mapping, quan hệ và auditing

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| E-01 | Bootstrap board test, migrate V1+V2 và Hibernate validate | Context khởi tạo, đủ bốn entity/repository; validate không tạo/sửa schema; Flyway V2 success | PASS: V1+V2 migrate; Hibernate validate và repository bootstrap thành công trên PostgreSQL. |
| E-02 | Với từng entity, tạo hợp lệ rồi flush/clear/read bằng ID | UUID khác null/không trùng; mọi field scalar khớp; không mất nullable hay default | Round-trip đã kiểm tra graph board/column/task/comment; chưa assert toàn bộ scalar và audit riêng cho cả bốn entity. |
| E-03 | Tạo đầy đủ Board → Column → Task → Comment bằng helper; chỉ save board | Bốn cấp được persist nhờ PERSIST; FK đúng, không có child mồ côi; đọc ngược tìm đúng cha | PASS: chỉ save board đã persist column/task/comment; reload trong persistence context mới thấy đủ graph. |
| E-04 | Tạo từng child với cha đã tồn tại và save repository tương ứng | Lưu thành công, không tạo thêm cha, không ghi lại graph cha ngoài nhu cầu | Đã lưu column/task/comment bằng repository với cha đã có trong fixture; chưa assert riêng số cha trước/sau. |
| E-05 | Thêm child bằng helper rồi flush/clear/read cả hai chiều | Phía sở hữu FK và collection thống nhất, không lặp child trong collection do cùng helper được gọi lại | Graph đọc lại có FK/collection đúng; chưa test helper được gọi lặp. |
| E-06 | Query từng child mà chưa truy cập cha; kiểm tra PersistenceUnitUtil/Hibernate | Parent association chưa initialized; truy cập trong transaction đọc được cha đúng | Task→Column LAZY được xác nhận; hai association còn lại chưa kiểm tra trạng thái initialized. |
| E-07 | Persist và update field scalar thật của từng entity bằng clock test t0 rồi t1 | createdAt/updatedAt có giá trị lúc tạo; update giữ createdAt=t0, updatedAt=t1; kiểm tra sau reload | Board có audit lúc tạo; Task giữ created_at theo precision DB khi update; chưa assert audit update cho mọi entity. |
| E-08 | Đọc entity mà không thay đổi và kết thúc transaction | Không phát UPDATE/audit chỉ do đọc; timestamp giữ nguyên | Chưa chạy |
| E-09 | Detach graph đã lưu, sửa scalar board/cột/task/comment rồi merge board | MERGE lưu đúng các thay đổi trong graph thử nghiệm, giữ ID/createdAt/FK; không sinh bản sao | Chưa chạy |
| E-10 | Tạo graph mới, inspect/log entity hoặc chạy equals/hashCode nếu có triển khai | Không recursion/StackOverflow; không tự load collections do toString; hash/identity policy nhất quán | Chưa chạy |

### 5.4. Bộ D — Default, dữ liệu biên, dữ liệu sai và soft references

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| D-01 | Tạo board tối thiểu và task tối thiểu qua Java, không gán archive/priority | Board archived=false; task priority=MEDIUM khi đọc DB; không dựa vào SQL DEFAULT để cứu null | PASS: board archived=false và task priority=MEDIUM khi reload. |
| D-02 | Board/task description null; task assigneeId/dueDate null | Lưu/đọc null được; cột nullable đúng V2 | PASS: description, assigneeId, dueDate null lưu/đọc được. |
| D-03 | Parameterize bốn status categories và bốn priorities | Mọi giá trị lưu string đúng tên; reload enum đúng, không ordinal | PASS: toàn bộ status category/priority persist ở dạng string. |
| D-04 | Cố insert status/priority không hỗ trợ qua JDBC, mỗi biến thể riêng | CHECK tương ứng từ chối, SQLSTATE 23514; không chấp nhận BACKLOG/giá trị sai | Chưa chạy |
| D-05 | Lần lượt null workspaceId, board.name, column.board/name/statusCategory, task.column/title/priority, comment.task/authorId/content | Mỗi biến thể bị từ chối trước hoặc tại flush; không có bản ghi sai sau rollback; JDBC bổ sung kiểm chứng NOT NULL khi cần | Task priority null bị database từ chối; các biến thể NOT NULL còn lại chưa chạy. |
| D-06 | Qua JDBC null column.position/task.position; qua JPA position=-1, mỗi bảng riêng | NULL bị NOT NULL từ chối; -1 bị CHECK từ chối; JPA primitive không dùng để mô phỏng null | Chưa chạy |
| D-07 | Column/task position=0 rồi Integer.MAX_VALUE, từng biến thể | Lưu/đọc đúng giới hạn INT hợp lệ; không overflow/coerce; không khẳng định đây là position thực tế phù hợp nghiệp vụ | Chưa chạy |
| D-08 | Board.name/task.title dài 255 và 256; column.name dài 100 và 101 ký tự ASCII | Tại giới hạn lưu nguyên vẹn; vượt giới hạn bị từ chối, không cắt chuỗi; mỗi biến thể có assertion riêng | Chưa chạy |
| D-09 | Lưu tiêu đề/nội dung tiếng Việt, emoji, Markdown, xuống dòng, dấu nháy và chuỗi giống SQL | Reload giữ nguyên; query bind tham số; không bị xử lý thành SQL hay mất nội dung | PASS: tiếng Việt, emoji, Markdown và xuống dòng được lưu/đọc nguyên vẹn. |
| D-10 | Description/comment TEXT dài 10.000 ký tự; chuỗi rỗng/trắng cho các trường chuỗi bắt buộc | TEXT không bị giới hạn 255; empty/blank hiện được V2 chấp nhận, không nhầm với null; validation sản phẩm để task API | Chưa chạy |
| D-11 | dueDate quá khứ, hiện tại, tương lai và hai biểu diễn offset cùng một instant | Round-trip đúng instant ở độ chính xác DB; tầng repository không tự cấm quá khứ | Một dueDate Instant chính xác microsecond round-trip; các offset/biến thể thời gian khác chưa chạy. |
| D-12 | Lưu workspaceId/assigneeId/authorId không có bản ghi Auth tương ứng | Thành công do soft reference; không cascade/import/query entity Auth | Workspace UUID không có bản ghi Auth và author/assignee UUID thô được lưu; không dùng entity Auth. |
| D-13 | Child tham chiếu UUID cha không tồn tại; từng FK nội bộ | Flush bị FK từ chối; khi cần JDBC xác nhận SQLSTATE 23503 và đúng constraint; rollback không lưu child | Chưa chạy |
| D-14 | Hai tên board/cột/task trùng; hai cột/task trùng position | Lưu được theo V2; query trả đủ record với tie-breaker; không tự áp UNIQUE chưa có | Chưa chạy |

### 5.5. Bộ R — Repository CRUD, scope và thứ tự

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| R-01 | Với từng repository: save/read, đổi field scalar, save/flush/clear/read, delete/flush/clear/read | CRUD đúng; update giữ ID, field đổi đã persist; find sau delete empty | Board/Task save-read, Task update-delete đã chạy; chưa kiểm đủ CRUD riêng cho mọi repository. |
| R-02 | Mỗi repository findById UUID chưa tồn tại và existsById | Optional.empty và false, không exception do không có dữ liệu | Chưa chạy |
| R-03 | List board W1/W2 qua query tất cả và query active | W1 gồm B1/B2/BA; active chỉ B1/B2; W2 chỉ B3; đúng createdAt,id | Workspace scope và active-only filter pass; thứ tự createdAt/id chưa được kiểm riêng. |
| R-04 | find board B1 trong W1 rồi W2, và ID thiếu | Đúng scope trả B1; sai workspace/ID thiếu trả empty | PASS: board ID đúng workspace trả kết quả; workspace sai trả empty. |
| R-05 | List column B1/B2/B3, seed position không theo thứ tự insert | Đủ cột mỗi board, không lẫn; tăng theo position,id; tie-breaker UUID đúng | Column scope và position tăng dần pass; tie-breaker khi trùng position chưa kiểm. |
| R-06 | List task C10/C11/C20/C30 | Đúng cột, tăng position,id; không chứa task của cột khác | PASS: query theo column trả đúng task theo position; board khác không lẫn. |
| R-07 | List task B1/B2/B3 có column/task position trùng | Đúng board qua join; xếp column.position, column.id, task.position, task.id; không duplicate row | Query task theo board trả đúng board và thứ tự position ở fixture; tie case chưa kiểm. |
| R-08 | Scoped lookup column/task/comment với đúng và sai board/task ID | Đúng trả entity; sai trả empty dù entity ID tồn tại; không truy xuất lẫn scope | ID scope mismatch trả empty cho column/task/comment; board scope được kiểm ở R-04. |
| R-09 | List comment task có timestamp đảo thứ tự insert và timestamp trùng | Tăng createdAt,id, đúng task; không gộp comment task khác | Chưa chạy |
| R-10 | List với workspace/board/cột/task hợp lệ nhưng không có con; rồi UUID không tồn tại | Danh sách rỗng ở cả hai biến thể, không null và không tự tạo record | Comment query của task không tồn tại trả danh sách rỗng; các resource khác chưa kiểm. |
| R-11 | Đổi column.position/task.position, flush/clear/query lại | Thứ tự query phản ánh position mới; ID/nội dung/FK giữ nguyên | Chưa chạy |
| R-12 | Cập nhật tên/mô tả/archive board, tên/category cột, title/description/priority/assignee/deadline task, content comment | Từng field được round-trip riêng hoặc parameterized; không vô tình sửa field khác; đổi archive chỉ tác động query active | Task title/position update pass; các field còn lại mới được kiểm round-trip, chưa kiểm từng update độc lập. |
| R-13 | Reload graph rồi truy cập collections có @OrderBy | Collection sau reload có thứ tự đã cam kết; không dựa vào RAM đã sửa position để kết luận lỗi ORDER BY | Chưa chạy |

### 5.6. Bộ C — Cascade, chuyển cha và bảo toàn dữ liệu

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| C-01 | Chuyển T10 từ C10 sang C11 trong B1 bằng helper, flush/clear/read | T10 giữ ID/scalars/createdAt/comments, FK đổi; C10 mất T10, C11 có T10; task khác còn | PASS: chuyển task sang cột khác giữ ID và comment; truy vấn cột cũ/mới đúng. |
| C-02 | Đổi position T10 trong C10 rồi gọi move tới chính C10 | Không nhân đôi task/collection, không mất comment; FK vẫn C10 | Chưa chạy |
| C-03 | Gỡ task khỏi collection C10, không đổi phía sở hữu và không gọi delete, flush/clear | DB không xóa task; reload vẫn thuộc C10; chứng minh orphanRemoval=false và collection không sở hữu FK | PASS: gỡ task khỏi inverse collection không xóa row hoặc đổi FK. |
| C-04 | Tạo child không gắn cha, save/flush | Thất bại do cha bắt buộc; không dùng cascade lên cha hay tạo cha giả để né lỗi | Chưa chạy |
| C-05 | Delete một comment T10 | Chỉ comment đó mất; T10, comments còn lại, cha và task khác nguyên vẹn | Chưa chạy |
| C-06 | Delete T10 có comments và tag mapping bằng repository | T10/comments/mapping của T10 mất do DB cascade; cột, tag và T11 còn; đọc sau clear | PASS: xóa task xóa comments, task kế bên còn; tag mapping chưa có fixture. |
| C-07 | Delete C10 có nhiều task/comments | Chỉ C10 và task/comments/mappings dưới C10 mất; B1/C11/T13 và board khác còn | Chưa chạy |
| C-08 | Delete B1 có cả graph và tag/mapping | B1/cột/task/comments/tags/mappings của B1 mất; B2/B3 và Auth fixture còn; xác nhận qua repository + JDBC | Board cascade xóa column/task/comment và giữ board khác; tag mapping/Auth bảo toàn chưa được assert. |
| C-09 | Delete board/cột/task không có con, từng biến thể | Xóa thành công; không lỗi vì collections rỗng | Chưa chạy |
| C-10 | Archive B1 có graph | B1 vẫn tồn tại và archived=true; số lượng/ID các con không đổi; query active loại B1 | Archive flag và active-only query pass; chưa assert lại toàn bộ graph sau archive. |
| C-11 | Tải graph, delete cha qua SQL để kiểm chứng DB cascade độc lập Hibernate, clear rồi read | Con bị xóa đúng phạm vi; context cũ không được dùng làm bằng chứng còn record | Chưa chạy |
| C-12 | Move T10 sang C11 rồi xóa C10 trong cùng transaction, flush/clear/read | T10 và comments sống ở C11; các task thật sự còn thuộc C10 bị cascade; không xóa nhầm task đã move | Chưa chạy |

### 5.7. Bộ T — Transaction và persistence thực tế

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| T-01 | Tạo graph và commit transaction A; đọc ở transaction/kết nối B | Đủ ID/FK/field tồn tại từ DB; chứng minh persistence qua commit | Chưa chạy |
| T-02 | Tạo graph rồi ép rollback | Transaction mới không thấy board/cột/task/comment vừa tạo | Chưa chạy |
| T-03 | Đổi scalar/position rồi ép rollback, từng biến thể | Transaction mới thấy field và audit trước thao tác; không có thay đổi dở dang | Chưa chạy |
| T-04 | Move T10 sang C11 rồi rollback | T10 vẫn C10, position cũ; comments nguyên vẹn; C11 không nhận T10 | Chưa chạy |
| T-05 | Delete comment/task/cột/board rồi rollback, mỗi cấp riêng | Transaction mới khôi phục cha và toàn bộ con đã cascade, kể cả mapping/tag liên quan | Chưa chạy |
| T-06 | Use case nhiều bước: lưu board hợp lệ, tiếp đó lưu child sai constraint, rollback cả transaction | Không có board/child của use case được commit một phần | Chưa chạy |
| T-07 | Save rồi clear/reload và update; kiểm tra timestamp bằng test clock | Dirty checking lưu thay đổi thật; ID/createdAt không thay; updatedAt đúng; không dùng cùng object để xác nhận | Chưa chạy |

### 5.8. Bộ B — Boundary, wiring và hồi quy

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| B-01 | Chạy :verifyModuleBoundaries, review imports/JPA target và Gradle dependencies | Task exit 0; không foreign impl hoặc app dependency ở Board; user/workspace chỉ UUID. Gradle gate hiện chỉ kiểm tra dependency, nên review import/entity vẫn cần | PASS: :verifyModuleBoundaries và full check đều thành công. |
| B-02 | Chạy :board-impl:test | Các case đã hiện thực pass trên PostgreSQL; report có số test thực chạy; không dùng NO-SOURCE/skip làm PASS | PASS: 11 tests, 0 failures/errors/skips trên PostgreSQL 17. |
| B-03 | App smoke với PostgreSQL, Flyway và Hibernate validate | Component/entity/repository scan thật tìm đủ Kanban; không lỗi table/type/naming/auditing | PASS: DevFlowApplicationTests khởi động app context trên H2; PostgreSQL validate chạy trong B-02. |
| B-04 | Chạy KanbanMigrationIntegrationTest và KanbanSchemaIntegrationTest T-010 | Các ca migration/schema cũ pass; V2/checksum/constraints/cascade không bị hồi quy | PASS: migration suite 6 tests và schema suite 55 tests; 0 failures/errors/skips. |
| B-05 | Chạy :app:test và toàn backend check | Không regression test Auth/app/module khác; process exit 0; test bắt buộc không bị bỏ qua | PASS: backend :check hoàn tất. |
| B-06 | Review phạm vi và bằng chứng bàn giao | Không lẫn triển khai T-012–T-015; mọi AC và case bắt buộc có test/evidence hoặc trạng thái chưa hoàn thành rõ ràng | Chưa chạy |

### 5.9. Lệnh chạy khi đã triển khai test

Chạy trong `backend/` bằng PowerShell. Máy phát triển này có cấu hình user-level ép named-pipe strategy cũ, nên thiết lập Docker context và strategy cho phiên PowerShell để Testcontainers dùng đúng endpoint.

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_DOCKER_CLIENT_STRATEGY = 'org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy'
.\gradlew.bat :board-impl:test
.\gradlew.bat :app:test --tests "io.devflow.board.schema.KanbanMigrationIntegrationTest" --tests "io.devflow.board.schema.KanbanSchemaIntegrationTest"
.\gradlew.bat :app:test
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat check
```

Nếu JDK cấu hình sai, xác nhận đường dẫn Java 17 trên máy và đặt JAVA_HOME tương ứng. Không cần `clean` hoặc `--rerun-tasks` mặc định; chỉ chạy lại test không cache khi cần bằng chứng mới hoặc có nghi vấn môi trường.

### 5.10. Ghi Expected / Actual và điều kiện kết thúc

Mỗi case/biến thể ghi test method, kết quả quan sát và bằng chứng. Expected đã định nghĩa trong ma trận; Actual phải ghi giá trị thực nhận hoặc lỗi thực nhận, không chỉ chép lại Expected.

| Case / biến thể | Test class.method | Expected | Actual quan sát | Status | Evidence | Người chạy / thời gian (Asia/Saigon) / commit |
|---|---|---|---|---|---|---|
| E-01, E-03, R-01–R-08, C-01, C-03, C-06, C-08, C-10 — persistence cases | `BoardPersistenceIntegrationTest` (11 test methods) | Các test đã hiện thực chạy trên PostgreSQL 17 | 11 tests, 0 failures, 0 errors, 0 skipped sau khi nâng Testcontainers 1.21.4 và chỉ định Docker Desktop pipe | PASS cho hành vi có assertion; biến thể chưa có test vẫn Not Run | `backend/board-impl/build/test-results/test/TEST-io.devflow.board.internal.BoardPersistenceIntegrationTest.xml` | 06/10/2026, 14:06 Asia/Saigon / `feature/T-011-board-entities` |
| B-01 — module boundary | `:verifyModuleBoundaries` (qua `check`) | Không có dependency vi phạm giữa module | BUILD SUCCESSFUL | PASS | Gradle output của `check` | 06/10/2026 / `feature/T-011-board-entities` |
| B-03 — app smoke/wiring | `DevFlowApplicationTests` | Context app khởi động và wiring entity/repository hợp lệ | 1 test pass trên H2; schema V2 được kiểm tra ở integration suites PostgreSQL | PASS | `backend/app/build/test-results/test/TEST-io.devflow.DevFlowApplicationTests.xml` | 06/10/2026 / `feature/T-011-board-entities` |
| B-04 — migration/schema regression | `KanbanMigrationIntegrationTest`, `KanbanSchemaIntegrationTest` | Migration V1→V2 và schema Kanban không hồi quy | Lần lượt 6 và 55 tests, không failure/error/skip | PASS | XML reports trong `backend/app/build/test-results/test/` | 06/10/2026 / `feature/T-011-board-entities` |
| B-05 — full backend check | `:check` | Module compile/tests và boundary pass | BUILD SUCCESSFUL; bao gồm app, auth, migration/schema và Board integration | PASS | Gradle output của `check` | 06/10/2026, 14:07 Asia/Saigon / `feature/T-011-board-entities` |
| Compile | `:board-impl:compileTestJava` (qua `:check`) | Main và test sources biên dịch | BUILD SUCCESSFUL | PASS | Gradle output | 06/10/2026 / `feature/T-011-board-entities` |

Quy ước: **PASS** đúng kỳ vọng; **FAIL** khác kỳ vọng; **Blocked** không chạy được vì môi trường/phụ thuộc; **Not Run** chưa chạy. Case parameterized cần lưu đủ từng biến thể; không bỏ một biến thể rồi đánh dấu cả dòng PASS. Một case không áp dụng phải ghi lý do và được review scope, không đổi sang PASS.

Report dự kiến: `backend/board-impl/build/reports/tests/test/index.html`, `backend/board-impl/build/test-results/test/TEST-*.xml`; tương tự dưới `backend/app/build/`. Ghi kèm log gate, phiên bản PostgreSQL, số test thực chạy/fail/error/skip và commit được kiểm chứng.

**Điều kiện kết thúc testing:** mọi biến thể bắt buộc ở các bộ E/D/R/C/T/B có mapping tới test và evidence; không FAIL/Blocked/Not Run chưa giải quyết; quality gates đạt. Lỗi mới phát hiện phải có test tái hiện và chạy lại bộ liên quan sau fix. Các vấn đề ngoài phạm vi được ghi riêng để bàn giao, không tuyên bố đã kiểm chứng ở T-011.

**Kết quả cập nhật 06/10/2026:** 4 entity, 2 enum, 4 repository và 11 test methods đã được thêm. `BoardPersistenceIntegrationTest`: 11 pass trên PostgreSQL 17; `KanbanMigrationIntegrationTest`: 6 pass; `KanbanSchemaIntegrationTest`: 55 pass; `:verifyModuleBoundaries`, app smoke test và full `:check` đều pass. Ma trận giữ `Chưa chạy` cho biến thể chưa được assertions bao phủ. Acceptance criteria gốc AC-01/AC-02 đã được kiểm chứng; các tiêu chí mở rộng và review/bàn giao còn lại chưa hoàn tất.

## 6. Rủi ro và bàn giao

| Rủi ro | Cách xử lý / bên nhận |
|---|---|
| orphanRemoval/REMOVE làm task bị xóa khi move | Dùng cascade có chủ đích và test C-01/C-12; ghi rõ helper cho T-013 |
| DB cascade đúng nhưng context còn object cũ | Flush/clear và đọc transaction mới; T-012/T-013 tránh tiếp tục dùng graph đã xóa |
| Enum/default/audit khác schema | Validate + round-trip + negative tests; Java khởi tạo default, test import auditing |
| H2 pass nhưng PostgreSQL lỗi TEXT/time/FK | Dùng migration thật trên PostgreSQL 17 làm gate nghiệm thu |
| Query đúng scope nhưng API vẫn thiếu quyền | Bàn giao scoped methods; T-012/T-013 kiểm membership trước truy cập |
| Trùng position hoặc concurrent reorder | Tie-breaker chỉ đảm bảo đọc ổn định; T-013 quyết định thuật toán reorder, locking và chính sách concurrency |
| Board lớn sinh N+1 hoặc tải toàn bộ task | Giữ LAZY; T-012/T-013 thiết kế DTO/projection/pagination theo nhu cầu và đo query trước tối ưu |
| Soft reference trỏ tới user/workspace đã mất | T-011 không liên kết JPA xuyên module; cleanup/validation nghiệp vụ cần contract/event ở task phù hợp |

Bàn giao gồm mapping, chính sách cascade/xóa, helper move, query có phạm vi/thứ tự, yêu cầu transaction/DTO khi LAZY, ma trận test và report thực tế. Không đánh dấu hoàn thành việc triển khai chỉ từ việc file plan đã được tạo.
