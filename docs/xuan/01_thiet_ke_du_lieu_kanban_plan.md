# T-010 — Plan triển khai dữ liệu Kanban

**Task gốc:** [01_thiet_ke_du_lieu_kanban.md](01_thiet_ke_du_lieu_kanban.md) · **Milestone:** 1 · **Phạm vi:** `:app`, dữ liệu thuộc `board` · **Người phụ trách:** Xuân.

**Ngày lập:** 06/10/2026 · **Trạng thái:** Đã triển khai migration và bộ test PostgreSQL; quality gate lần gần nhất PASS. Ma trận dưới đây vẫn là catalog thiết kế theo case; kết quả chạy gộp được ghi tại mục 5.10, không hàm ý mọi biến thể trong kế hoạch đều đã được tự động hóa.

**Trello:** [T-010 — Setup Flyway Migration for Board Schema](https://trello.com/c/SeDVPyX8/16-t-010-setup-flyway-migration-for-board-schema-v2boardschemasql). Đã đọc thẻ ngày 06/10/2026: In Progress, hai tiêu chí gốc chưa được đánh dấu hoàn thành. Việc lập plan không thay đổi thẻ/checklist.

## 1. Mục tiêu, phạm vi và căn cứ

Tạo cấu trúc lưu trữ bền vững cho board, cột, task, bình luận và nhãn; giữ đúng quan hệ cha/con, thứ tự hiển thị và ranh giới dữ liệu với Auth. Kết quả của T-010 là migration và bằng chứng kiểm thử ở tầng database. Các luồng người dùng dưới đây mô tả dữ liệu cần hỗ trợ; API/UI để thực hiện luồng sẽ được triển khai ở task tiếp theo.

### 1.1. Tài liệu tham chiếu

- [Task và tiêu chí gốc](01_thiet_ke_du_lieu_kanban.md).
- [Thiết kế database, mục 3.2 và 6](../DATABASE_DESIGN.md): từ điển dữ liệu và lộ trình V2.
- [Kiến trúc, mục 2, 4, 6 và 10](../ARCHITECTURE.md): module, event, quyền sở hữu bảng và ranh giới.
- [Đặc tả sản phẩm, mục 5.1 và 6.1](../PRODUCT_SPEC.md): Kanban, thông tin task và mức ưu tiên.
- [Kế hoạch tổng thể, Phase 2 và DoD](../IMPLEMENTATION_PLAN.md).
- [DoD chung của Xuân](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung).

### 1.2. Hiện trạng đã đối chiếu trong repository

| Thành phần | Hiện trạng | Ý nghĩa đối với T-010 |
|---|---|---|
| `backend/app/src/main/resources/db/migration/` | Có `V1__init_schema.sql`, chưa có V2 | Cần thêm version V2 sau V1; kiểm tra lại tên/version ngay trước khi code |
| `backend/app/build.gradle.kts` | Có Flyway core, PostgreSQL adapter và driver | Tận dụng cấu hình hiện có; chỉ bổ sung dependency phục vụ test khi cần |
| `backend/app/src/main/resources/application.yml` | Flyway bật; `ddl-auto: validate`; `baseline-on-migrate: true` | Hibernate không được dùng để tự tạo schema thay migration; phải kiểm tra lịch sử Flyway |
| `backend/app/src/test/resources/application.yml` | H2, Flyway tắt, `ddl-auto: create-drop` | Smoke test hiện có không chứng minh SQL V2 chạy được trên PostgreSQL |
| `backend/common/.../BaseEntity.java` | UUID, `createdAt`, `updatedAt`, JPA auditing | Bảng có audit phải tương thích mapping ở T-011; SQL update không tự kích hoạt JPA auditing |
| Module `board` | Controller/service vẫn là scaffold, chưa có entity Kanban | Test T-010 dùng JDBC/SQL; không chờ T-011 hoặc giả định CRUD API đã hoạt động |

### 1.3. Phạm vi triển khai

Migration dự kiến: `backend/app/src/main/resources/db/migration/V2__board_schema.sql`.

Bao gồm sáu bảng: `boards`, `columns`, `tasks`, `task_comments`, `task_tags`, `task_tag_mappings`; PK, FK nội bộ, default, nullability, check constraint đã nêu trong plan và index theo thiết kế.

Task gốc/Trello liệt kê năm bảng chính; `DATABASE_DESIGN.md` mục 3.2 và 6 còn có bảng nối `task_tag_mappings`. Plan đưa bảng nối vào V2 để quan hệ nhiều-nhiều task–tag dùng được, ghi rõ đây là phần bổ sung theo thiết kế database đã có.

Không triển khai entity/repository (T-011), REST/phân quyền (T-012/T-013), event nghiệp vụ (T-014), WebSocket hoặc kéo thả frontend (T-015–T-018). Không thêm bảng Git/CI, activity history, task code `T-XXX` hoặc trigger nghiệp vụ vào V2; những phần đó cần hợp đồng và task riêng.

## 2. Kế hoạch nghiệp vụ — luồng theo ngôn ngữ người dùng

### NV-01. Tạo không gian làm việc trên board

Người dùng chọn workspace mà mình được phép sử dụng, tạo một board với tên và mô tả. Board thuộc đúng workspace đó. Người dùng thêm các cột thể hiện quy trình làm việc và đặt thứ tự từ trái sang phải. Khi mở lại board, tên, mô tả và thứ tự cột vẫn giữ nguyên.

Dữ liệu cần hỗ trợ: board lưu ID workspace, cột lưu board cha và vị trí. Việc kiểm tra người dùng có quyền vào workspace là trách nhiệm API/service ở T-012, không thể suy ra chỉ từ ID được lưu trong database.

### NV-02. Tạo task và ghi thông tin công việc

Người dùng thêm task vào một cột, nhập tiêu đề; có thể để trống mô tả, người phụ trách và hạn hoàn thành. Nếu chưa chọn độ ưu tiên, task nhận mức MEDIUM. Khi nhập mô tả tiếng Việt hoặc Markdown, nội dung được lưu nguyên văn. Sau khi mở lại task, những thông tin đã lưu vẫn được đọc đúng.

Task phải thuộc một cột tồn tại. Người phụ trách được biểu diễn bằng ID; API sau này kiểm tra người đó tồn tại và là thành viên workspace. Thời điểm hạn được lưu thống nhất để cùng một hạn không đổi ý nghĩa khi người xem ở múi giờ khác.

### NV-03. Sắp xếp cột và di chuyển task

Người dùng đổi thứ tự cột hoặc thẻ. Task có thể đổi vị trí trong cùng cột hoặc sang cột khác của cùng board. Sau khi tải lại, board hiển thị theo thứ tự đã lưu, task vẫn giữ nội dung, người phụ trách, hạn và nhãn.

T-010 chứng minh database lưu và đọc được `position`, cập nhật `column_id` và rollback thay đổi. Thuật toán kéo thả, kiểm soát thao tác đồng thời và ngăn chuyển sang board không phù hợp thuộc T-013. Vị trí được tính từ 0; dữ liệu cuối một thao tác hợp lệ phải có thứ tự rõ ràng.

### NV-04. Bình luận và gắn nhãn

Người dùng viết bình luận trên một task; bình luận ghi người viết và thời điểm tạo/cập nhật. Khi đọc lại, bình luận được sắp theo thời điểm tạo. Một task có thể mang nhiều nhãn; một nhãn có thể được dùng cho nhiều task trên cùng board. Gắn lại cùng một nhãn không được tạo thêm liên kết trùng.

Nhãn thuộc board và có tên/màu. Người viết bình luận được lưu bằng ID. T-010 chỉ tạo cấu trúc và kiểm tra lưu dữ liệu; giao diện bình luận/nhãn và kiểm tra cùng board được bàn giao cho các task nghiệp vụ.

### NV-05. Lưu trữ và xóa dữ liệu

Khi lưu trữ board, hệ thống đặt cờ lưu trữ và giữ toàn bộ cột, task, bình luận, nhãn để có thể phục hồi hiển thị sau này. Lưu trữ không phải xóa.

Khi thực hiện xóa vật lý đã được cho phép ở tầng nghiệp vụ: xóa task thì bình luận và liên kết nhãn của task bị xóa; nhãn dùng chung vẫn tồn tại. Xóa nhãn chỉ xóa nhãn và các liên kết của nhãn, không xóa task. Xóa cột thì task trong cột cùng dữ liệu con bị xóa. Xóa board thì toàn bộ dữ liệu Kanban thuộc board bị xóa; board khác và user/workspace được giữ nguyên.

### NV-06. Khởi động và nâng cấp hệ thống

Lần chạy đầu, hệ thống tạo Auth từ V1 rồi tạo Kanban từ V2. Nếu database đã có V1 và dữ liệu người dùng/workspace, nâng cấp chỉ thêm phần Kanban, không làm mất dữ liệu trước đó. Khởi động lại không tạo lại bảng và không thay đổi dữ liệu đã lưu. Nếu migration lỗi, hệ thống báo lỗi rõ ràng và không sử dụng schema đang dang dở.

## 3. Kế hoạch kỹ thuật — thiết kế và các yếu tố cần suy xét

### 3.1. Luồng kỹ thuật tổng thể

```text
Khởi động app / chạy bộ test PostgreSQL
  -> lấy connection tới database đích
  -> Flyway validate lịch sử/checksum
  -> chạy V1 nếu database mới
  -> chạy V2 nếu chưa được áp dụng
  -> ghi version V2 thành công vào flyway_schema_history
  -> Hibernate validate các entity hiện có
  -> app sẵn sàng; test JDBC kiểm tra schema và dữ liệu
```

Trong T-010, chưa có entity Board nên Hibernate chỉ validate những entity đã tồn tại. JDBC và catalog PostgreSQL là bằng chứng chính cho sáu bảng mới; việc validate mapping Board đầy đủ được làm ở T-011.

### 3.2. Hợp đồng dữ liệu cho migration

Quy ước chung: mọi `id` độc lập là `UUID PRIMARY KEY DEFAULT gen_random_uuid()`; audit dùng `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`. Bảng nối có PK ghép, không thêm ID riêng. Độ dài/type dưới đây theo thiết kế hiện có.

| Bảng | Cột bắt buộc và default | Cột tùy chọn | Khóa/quan hệ |
|---|---|---|---|
| `boards` | `id`; `workspace_id UUID`; `name VARCHAR(255)`; `is_archived BOOLEAN DEFAULT FALSE`; audit `created_at`, `updated_at` | `description TEXT` | `workspace_id` là soft reference, không FK sang Auth |
| `columns` | `id`; `board_id UUID`; `name VARCHAR(100)`; `position INT`; `status_category VARCHAR(50)`; audit | Không | `board_id -> boards.id`, CASCADE |
| `tasks` | `id`; `column_id UUID`; `title VARCHAR(255)`; `position INT`; `priority VARCHAR(50) DEFAULT 'MEDIUM'`; audit | `description TEXT`; `assignee_id UUID`; `due_date TIMESTAMPTZ` | `column_id -> columns.id`, CASCADE; `assignee_id` không FK sang Auth |
| `task_comments` | `id`; `task_id UUID`; `author_id UUID`; `content TEXT`; audit | Không | `task_id -> tasks.id`, CASCADE; `author_id` không FK sang Auth |
| `task_tags` | `id`; `board_id UUID`; `name VARCHAR(50)`; `color VARCHAR(20)` | Không | `board_id -> boards.id`, CASCADE; không có audit theo từ điển dữ liệu hiện tại |
| `task_tag_mappings` | `task_id UUID`; `tag_id UUID` | Không | PK `(task_id, tag_id)`; cả hai FK nội bộ CASCADE; không audit |

Các cột ở nhóm bắt buộc đều `NOT NULL`; default chỉ được áp dụng khi bỏ cột khỏi INSERT hoặc dùng `DEFAULT`, không thay thế giá trị `NULL` được truyền tường minh.

### 3.3. Quyết định thiết kế và phân chia trách nhiệm

| Yếu tố | Phương án trong plan | Lý do và giới hạn |
|---|---|---|
| Giá trị trạng thái | Đề xuất CHECK `status_category IN ('TODO','IN_PROGRESS','IN_REVIEW','DONE')` | Theo miền giá trị của database design; cột tên Backlog có thể thuộc TODO, không thêm category BACKLOG tự phát |
| Độ ưu tiên | Đề xuất CHECK `priority IN ('LOW','MEDIUM','HIGH','URGENT')` | Chặn dữ liệu ngoài miền ngay cả khi ghi bằng SQL |
| Vị trí | Đề xuất CHECK `position >= 0` cho cột và task | Không ép liên tục hoặc UNIQUE ở V2; đổi vị trí có thể cần giá trị trùng tạm thời trong transaction |
| Vị trí trùng/khoảng trống | Schema cho phép; truy vấn dùng `ORDER BY position, id` | Thứ tự liền mạch cuối thao tác và xử lý concurrent reorder thuộc T-013; index thường không đảm bảo uniqueness |
| Tên/tiêu đề/nội dung rỗng | V2 theo NOT NULL và giới hạn độ dài; không tự thêm CHECK trim | SQL vẫn có thể lưu chuỗi rỗng; `NotBlank`, trim và thông báo lỗi thuộc validation server ở task API |
| Nhãn cùng board | Hai FK đảm bảo task/tag tồn tại, chưa đảm bảo chung board | Tầng nghiệp vụ phải kiểm tra board của task và tag trước khi gắn nhãn; test ghi trực tiếp khác board ghi nhận giới hạn, không coi schema hiện tại đã ngăn được |
| Tham chiếu Auth | Chỉ lưu `workspace_id`, `assignee_id`, `author_id` dạng UUID | Không SQL JOIN hoặc FK sang Auth; không import entity/repository `auth.internal` |
| Người dùng/workspace bị xóa | Database Board không tự xóa theo Auth | Hành vi dọn soft reference cần hợp đồng sự kiện riêng; không thêm event hay cascade xuyên module trong T-010 |
| Audit | Default khi INSERT; JPA auditing khi entity được triển khai | `updated_at` không tự tăng khi UPDATE bằng SQL; test JDBC cập nhật cột này tường minh, không thêm trigger chỉ để vượt test |
| Hạn và múi giờ | `TIMESTAMPTZ`, so sánh thời điểm bằng Instant/UTC | Test dùng hai biểu diễn cùng thời điểm với offset khác nhau; không đặt CHECK cấm hạn quá khứ |
| Xóa/lưu trữ | CASCADE nội bộ; archive là UPDATE cờ | Không gắn quyền xóa vào DB; API phải quyết định khi nào cho phép xóa cột còn task |
| Tên `columns` | Giữ tên theo database design | Dùng tên/identifier nhất quán với JPA ở T-011; không đổi sang tên khác chỉ ở SQL |

Ba CHECK nêu trên là phương án tăng bảo vệ dữ liệu trong plan, chưa phải ràng buộc đã có trong code hoặc checklist mới trên Trello. Trước khi viết V2, đối chiếu enum/hợp đồng của T-011/T-013; nếu điều chỉnh phương án, cập nhật expected tương ứng trước khi chạy test.

Giữ nguyên việc tên board/cột/nhãn có thể trùng vì thiết kế chưa yêu cầu unique tên. Không thêm `board_id` dư thừa vào `tasks`, trigger kiểm tra cùng board, optimistic locking hoặc bộ đếm ticket trong task này.

### 3.4. Index và truy vấn cần hỗ trợ

| Index theo thiết kế | Cột/thứ tự | Truy vấn được hỗ trợ |
|---|---|---|
| `idx_boards_workspace` | `boards(workspace_id)` | Liệt kê board theo workspace |
| `idx_columns_board_position` | `columns(board_id, position)` | Đọc cột theo board, sắp vị trí |
| `idx_tasks_column_position` | `tasks(column_id, position)` | Đọc task theo cột, sắp vị trí |
| `idx_tasks_assignee` | `tasks(assignee_id)` | Lọc task theo người phụ trách |
| `idx_tasks_due_date` | `tasks(due_date)` | Lọc task theo khoảng hạn |
| `idx_task_comments_task_created` | `task_comments(task_id, created_at ASC)` | Đọc timeline bình luận; thêm `id` làm tie-breaker trong ORDER BY |
| `idx_task_tags_board` | `task_tags(board_id)` | Liệt kê nhãn của board |
| `idx_tag_mappings_tag` | `task_tag_mappings(tag_id)` | Tìm task dùng nhãn và hỗ trợ xóa nhãn |

PK bảng nối hỗ trợ chiều truy vấn bắt đầu bằng `task_id`; không thêm index đơn trùng mục đích. Kiểm tra catalog đúng định nghĩa và dùng EXPLAIN với dữ liệu đủ lớn; không bắt buộc index scan ở dataset nhỏ và không cam kết độ trễ API chỉ dựa vào index.

### 3.5. Migration, nâng cấp và xử lý lỗi

1. Kiểm tra V1 có lịch sử thành công và không có version V2 trùng với thay đổi của người khác. Không chỉnh V1 đã áp dụng.
2. Tạo bảng theo thứ tự cha trước con: board -> column -> task -> comment; tag sau board; mapping sau task và tag. Đặt tên FK/CHECK rõ ràng để log lỗi xác định được ràng buộc.
3. Dùng SQL DDL thông thường trong transaction Flyway. Không dùng `CREATE INDEX CONCURRENTLY` trong V2; không dùng `IF NOT EXISTS` cho bảng để che schema lệch. Không seed board/task thật trong migration.
4. Chạy trên database mới và database chỉ có V1 đã seed Auth. Kiểm tra version, checksum, success và dữ liệu Auth giữ nguyên.
5. Với database không rỗng nhưng không có `flyway_schema_history`, không mặc định xem đó là V1 hợp lệ. `baseline-on-migrate` chỉ thiết lập mốc, không kiểm chứng cấu trúc bảng. Test negative dùng cấu hình không tự baseline; khi triển khai phải đối chiếu schema và baseline version trước khi tiếp tục.
6. Nếu V2 chưa từng áp dụng ngoài database test tạm, có thể sửa file rồi tạo database test mới để chạy lại. Nếu V2 đã áp dụng ở môi trường chia sẻ, tạo migration tiến tới version tiếp theo; không sửa checksum V2 hoặc chạy repair để giấu khác biệt.
7. Migration lỗi: dừng khởi động; kiểm tra transaction/catalog trên PostgreSQL. Test lỗi dùng bản sao migration cố tình hỏng trong fixture test, không sửa V2 thật. Không tự động drop schema/database chung để thử lại.
8. V2 chưa có down migration. Phương án phục hồi khi rollout là migration sửa tiếp hoặc khôi phục bản sao lưu trong môi trường đã xác định; việc kiểm thử rollback DDL lỗi không đồng nghĩa đã có rollback release.

### 3.6. File triển khai T-010

| File | Thay đổi dự kiến |
|---|---|
| `backend/app/src/main/resources/db/migration/V2__board_schema.sql` | Tạo sáu bảng, constraint và tám index đã liệt kê |
| `backend/app/src/test/java/io/devflow/board/schema/KanbanMigrationIntegrationTest.java` | Flyway fresh/upgrade/re-run/checksum/failure trên PostgreSQL |
| `backend/app/src/test/java/io/devflow/board/schema/KanbanSchemaIntegrationTest.java` | JDBC kiểm tra dictionary, dữ liệu, constraint, FK, cascade, index |
| `backend/app/src/test/resources/docker-java.properties` | Cố định Docker API ở 1.44 để Testcontainers 1.20.6 tương thích Docker Engine hiện tại |
| `backend/app/build.gradle.kts` | Testcontainers BOM 1.20.6, JUnit Jupiter và PostgreSQL module cho integration test |

`KanbanSchemaIntegrationTest` khởi động Spring Boot trên PostgreSQL 17 qua Testcontainers, bật Flyway và Hibernate validate; không cần class startup riêng. Fixture migration lỗi/duplicate được tạo trong thư mục tạm khi test, không làm bẩn production resources. Không đưa entity Board hoặc logic nghiệp vụ vào `app`, không thay profile H2 chung chỉ để chạy bộ test Kanban.

### 3.7. Thứ tự triển khai và đầu ra kiểm chứng

| Bước | Việc thực hiện | Đầu ra / điều kiện chuyển bước |
|---|---|---|
| 1 | Đối chiếu T-010, database design, migration và enum dự kiến | Đã chốt sáu bảng, CHECK và giới hạn bàn giao |
| 2 | Viết V2 và rà từng cột/constraint/index | Đã tạo V2; không FK xuyên module, không chỉnh V1 |
| 3 | Dựng harness PostgreSQL test tách biệt, fixture V1 và Kanban | Đã chạy JDBC + Flyway và app context trên PostgreSQL 17 Testcontainers |
| 4 | Kiểm thử fresh/upgrade, metadata, DML và cascade | Đã có integration test; xem kết quả và phần chưa bao phủ tại mục 5.10 |
| 5 | Kiểm thử rollback transaction, checksum và startup app | Các nhánh có trong suite đã pass; persistence được kiểm tra qua kết nối mới |
| 6 | Chạy quality gate, cập nhật evidence, review/bàn giao | `check` và boundary verification đã pass; review/handoff vẫn là bước bàn giao |

Plan này mô tả triển khai cục bộ; chưa tạo commit/PR và chưa thay đổi trạng thái/checklist Trello.

## 4. Tiêu chí hoàn thành

### 4.1. Tiêu chí gốc trên Trello

- [ ] **AC-01:** Flyway migrate V2 thành công trên PostgreSQL.
- [ ] **AC-02:** Các ràng buộc quan hệ và cascade delete hoạt động đúng thiết kế.

### 4.2. Điều kiện kiểm chứng cho T-010

- [ ] **AC-03:** Có đủ sáu bảng và dictionary đúng type, chiều dài, NOT NULL, default, PK, FK, CHECK đã chốt; có tám index theo mục 3.4.
- [ ] **AC-04:** Database mới chạy V1 -> V2; database đã có V1 nâng cấp được; version V2 chỉ ghi thành công một lần và Auth không thay đổi dữ liệu.
- [ ] **AC-05:** Lưu/đọc/cập nhật task, thứ tự, thông tin tùy chọn, Markdown/Unicode và thời điểm đúng; commit tồn tại qua kết nối mới, rollback không để lại thay đổi.
- [ ] **AC-06:** Toàn bộ nhánh cascade trong mục 5.6 được kiểm chứng; archive không xóa con; board khác và Auth không bị tác động bởi xóa Board.
- [ ] **AC-07:** Không có FK/JPA relation xuyên module; các giới hạn soft reference, nhãn khác board, position trùng và audit SQL được ghi rõ cho task tiếp theo.
- [ ] **AC-08:** Bộ test PostgreSQL chạy thật, có expected/actual/status/evidence cho tất cả case bắt buộc; không bỏ qua case vì Docker không sẵn sàng rồi báo thành công.
- [ ] **AC-09:** App khởi động với Flyway bật và Hibernate validate; `gradlew :verifyModuleBoundaries` và `gradlew check` thành công; không phá test Auth hiện có.
- [ ] **AC-10:** Có review migration, report test và bàn giao cho T-011/T-013; checklist Trello chỉ được đánh dấu dựa trên bằng chứng thực tế khi triển khai.

AC-03–AC-10 là điều kiện diễn giải trong plan, chưa được thêm lên Trello. Frontend build/lint chỉ áp dụng nếu có thay đổi frontend; T-010 theo phạm vi này không có thay đổi frontend. Không dùng việc UI scaffold hiện tại hiển thị được để nghiệm thu dữ liệu Kanban.

## 5. Kế hoạch testing

### 5.1. Môi trường, cách chạy và dữ liệu chuẩn

- **Môi trường nghiệm thu:** PostgreSQL 17 tạm dành riêng cho test, Java 17, JUnit 5, Flyway và JDBC. Dự kiến dùng Testcontainers theo hướng kiểm thử PostgreSQL của dự án; pin phiên bản image cụ thể khi triển khai và ghi vào report.
- **Tách test:** Test migration/schema chạy Flyway trực tiếp, không cần cả Spring context hoặc LLM. Test startup app cấu hình PostgreSQL riêng, bật Flyway, `ddl-auto=validate`, tắt các model AI không sử dụng như cấu hình hiện có. Giữ smoke test H2 hiện tại làm regression, không coi H2 là thay thế PostgreSQL.
- **Vòng đời:** Các test fresh/upgrade/failure dùng database hoặc schema test độc lập. Mỗi test DML tạo fixture riêng và rollback/dọn dữ liệu trong phạm vi test. Đối với case cần commit/reconnect, dùng database riêng để tránh state chung. Negative SQL dùng transaction riêng hoặc savepoint rồi rollback vì transaction PostgreSQL bị lỗi cần phục hồi trước assertion tiếp theo.
- **Fixture Auth:** Hai user U1/U2, hai workspace W1/W2 và membership hợp lệ theo V1; hash/email/slug giả, không dùng thông tin thật. Snapshot ID và giá trị Auth trước khi nâng cấp.
- **Fixture Kanban:** B1 ở W1, B2 ở W2; B1 có C1(TODO,0), C2(IN_PROGRESS,1), C3(DONE,2); B2 có C4(TODO,0). C1 có T1(position 0), T2(position 1); C4 có T3. T1 có hai comment, G1/G2 là tag B1, G3 là tag B2; gắn G1 vào T1 và T2, G3 vào T3. Tất cả ID fixture cố định và khác nhau.
- **Thời gian:** Dùng thời điểm cố định, ví dụ `2026-10-06T03:00:00Z` và `2026-10-06T10:00:00+07:00` để kiểm tra cùng Instant. Không dùng sleep để so audit; khi cần default CURRENT_TIMESTAMP so với mốc trong cùng transaction.
- **Assertion:** Kiểm tra SQLSTATE/constraint, metadata, số dòng, ID và giá trị trước/sau. Không chỉ assert “không có exception”; lỗi constraint phải không làm thay đổi dữ liệu khác.

**Cột Thực tế (Actual):** Các bảng dưới đây là thiết kế test chi tiết ban đầu. `Chưa chạy` nghĩa là chưa ghi kết quả/evidence gắn riêng cho ID hoặc biến thể đó; một phần hành vi đã được kiểm tra gộp trong suite. Kết quả chạy gộp hiện có được tổng hợp ở mục 5.10. Không coi toàn bộ danh mục bên dưới đã được xác nhận chỉ từ việc quality gate pass; các case/biến thể chưa có mapping và evidence riêng vẫn cần chạy hoặc ghi rõ chưa bao phủ trước khi nghiệm thu đầy đủ.

### 5.2. Bộ M — Migration và khởi động

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| M-01 | Database PostgreSQL rỗng; Flyway migrate production locations | V1 và V2 thành công theo thứ tự; đủ bảng Auth và sáu bảng Kanban; V2 success=true | Chưa chạy |
| M-02 | Migrate chỉ tới V1; seed Auth; bỏ target rồi migrate | Chỉ V2 mới được áp dụng; toàn bộ ID/giá trị/count Auth không đổi | Chưa chạy |
| M-03 | Database đã V2; seed Kanban; gọi migrate lần hai | Không chạy lại V1/V2; count/version/checksum và dữ liệu không đổi | Chưa chạy |
| M-04 | Khởi động app bằng PostgreSQL test với Flyway bật, Hibernate validate | Context khởi động thành công; history có V2; không dùng H2 hoặc create-drop | Chưa chạy |
| M-05 | Sau M-04 commit fixture; đóng rồi mở app/context mới trên cùng DB | V2 không chạy lại; đọc đủ giá trị đã commit; không mất dữ liệu | Chưa chạy |
| M-06 | DB đã V2; thay checksum trong bản sao resource test rồi validate | Flyway báo mismatch V2; không tự repair hoặc đổi dữ liệu | Chưa chạy |
| M-07 | DB V1; migration V2 trong fixture có DDL tạo bảng rồi SQL cố tình lỗi | Migrate thất bại; rollback DDL V2, không có V2 success=true; Auth/V1 nguyên vẹn | Chưa chạy |
| M-08 | DB V1 đã có bảng Kanban trùng tên không do Flyway quản lý; migrate | Báo lỗi/schema conflict; không âm thầm bỏ qua bảng bằng IF NOT EXISTS | Chưa chạy |
| M-09 | DB không rỗng, thiếu history; tắt auto-baseline ở harness; migrate | Báo cần xác minh/baseline; không coi schema chưa kiểm chứng là V1 hợp lệ | Chưa chạy |
| M-10 | Fixture resource chứa hai migration cùng version V2 | Phát hiện version trùng; không chạy SQL hoặc đổi DB | Chưa chạy |

### 5.3. Bộ S — Dictionary và metadata schema

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| S-01 | Sau V2, đọc catalog bảng/cột | Đủ sáu bảng, đúng tên, không tạo bảng ngoài phạm vi | Chưa chạy |
| S-02 | Parameterized từng bảng/cột theo mục 3.2 | UUID/INT/BOOLEAN/TEXT/VARCHAR đúng type và độ dài; audit/due_date là TIMESTAMPTZ | Chưa chạy |
| S-03 | Parameterized toàn bộ cột bắt buộc/tùy chọn; đọc nullability/default | NOT NULL/nullable/default khớp hợp đồng, gồm MEDIUM và FALSE | Chưa chạy |
| S-04 | Kiểm tra PK cho năm bảng chính và bảng nối | PK UUID đúng cột; mapping có PK ghép đúng thứ tự `(task_id, tag_id)` | Chưa chạy |
| S-05 | Kiểm tra toàn bộ FK của sáu bảng | Đúng sáu cạnh nội bộ và ON DELETE CASCADE; không thiếu cạnh hoặc trỏ nhầm bảng | Chưa chạy |
| S-06 | Kiểm tra FK tại workspace_id, assignee_id, author_id | Không có FK từ Board sang users/workspaces hoặc bảng module khác | Chưa chạy |
| S-07 | Kiểm tra CHECK đã chốt | Category/priority đúng miền; position cột và task không âm | Chưa chạy |
| S-08 | Đọc pg_indexes và constraint index | Đủ tám index thường mục 3.4, đúng cột/thứ tự; PK index tồn tại | Chưa chạy |
| S-09 | Kiểm tra task_tags/mapping và vị trí | Không audit/ID riêng ngoài dictionary; position không bị đặt unique tự phát | Chưa chạy |
| S-10 | Snapshot schema + Auth trước V2, so sau V2 | Không thay type, constraint, index hoặc dữ liệu bảng Auth do V2 | Chưa chạy |

### 5.4. Bộ D — Lưu dữ liệu, default, dữ liệu biên và dữ liệu sai

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| D-01 | INSERT tối thiểu hợp lệ từng bảng chính, bỏ id/audit/default; mapping đủ hai ID | Insert thành công, id UUID và audit được tạo; archive FALSE, priority MEDIUM | Chưa chạy |
| D-02 | INSERT task bỏ description/assignee/due_date rồi đọc lại | Ba trường nhận NULL; không mất title/column/position | Chưa chạy |
| D-03 | Lưu full task, mô tả/comment tiếng Việt, Markdown, emoji, xuống dòng | Đọc lại nội dung và toàn bộ trường đúng nguyên văn, không truncate/sai encoding | Chưa chạy |
| D-04 | Parameterized NULL tường minh ở từng cột bắt buộc, kể cả default và PK mapping | Bị từ chối SQLSTATE 23502; không thêm dòng | Chưa chạy |
| D-05 | Parameterized mọi VARCHAR: độ dài N-1, N, N+1 bằng chuỗi ký tự thông thường | N-1/N lưu được; N+1 bị từ chối 22001; áp dụng cả category/priority với kiểm tra độ dài phù hợp miền giá trị | Chưa chạy |
| D-06 | Parameterized 4 giá trị priority và 4 status_category hợp lệ | Mọi giá trị hợp lệ lưu/đọc đúng | Chưa chạy |
| D-07 | Priority/category là chuỗi ngắn sai miền hoặc khác hoa/thường | Bị từ chối CHECK, SQLSTATE 23514 | Chưa chạy |
| D-08 | Position cột/task lần lượt 0, 1, số nguyên lớn hợp lệ, -1 và vượt INT | Không âm trong miền INT lưu được; -1 lỗi 23514; vượt INT lỗi 22003 | Chưa chạy |
| D-09 | Hai INSERT riêng cùng PK, lần lượt năm bảng chính và PK ghép mapping | Bản ghi thứ hai lỗi 23505; bản ghi đầu không đổi | Chưa chạy |
| D-10 | Parameterized truyền chuỗi không phải UUID cho từng trường UUID | Bị từ chối 22P02; không âm thầm chuyển kiểu hay thêm dòng | Chưa chạy |
| D-11 | INSERT title/name/content là rỗng hoặc chỉ whitespace khi cột VARCHAR/TEXT cho phép | DB chấp nhận theo V2 không có CHECK trim; ghi nhận yêu cầu NotBlank ở API sau này | Chưa chạy |
| D-12 | Lưu due_date bằng hai biểu diễn cùng Instant; đổi timezone session để đọc | Cùng thời điểm UTC; hiển thị offset có thể khác, ý nghĩa thời gian không đổi | Chưa chạy |
| D-13 | Due_date NULL, quá khứ và tương lai | Cả ba lưu đúng; V2 không áp đặt deadline phải trong tương lai | Chưa chạy |
| D-14 | Parameterized TEXT NULL ở trường tùy chọn và nội dung dài, ví dụ 64 KiB | NULL được giữ; nội dung dài được lưu nguyên văn; comment content NULL thuộc D-04 và bị chặn | Chưa chạy |
| D-15 | UPDATE title/priority/description/assignee/due_date; ghi updated_at tường minh | ID/created_at giữ nguyên; trường sửa và updated_at đúng; không kỳ vọng trigger audit tự chạy | Chưa chạy |

D-05 với `priority`/`status_category`: không có giá trị miền hợp lệ dài N-1/N; kiểm thử metadata độ dài ở S-02 và test N+1 để kiểm tra 22001. Các case lưu thành công sát độ dài áp dụng cho name/title/color; tránh kết luận lỗi độ dài là lỗi CHECK miền giá trị.

### 5.5. Bộ R — Quan hệ, soft reference và phạm vi board

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| R-01 | INSERT column trỏ board không tồn tại | Lỗi FK 23503; không có column mồ côi | Chưa chạy |
| R-02 | INSERT task trỏ column không tồn tại | Lỗi FK 23503; không có task mồ côi | Chưa chạy |
| R-03 | INSERT comment trỏ task không tồn tại | Lỗi FK 23503; không có comment mồ côi | Chưa chạy |
| R-04 | INSERT tag trỏ board không tồn tại | Lỗi FK 23503; không có tag mồ côi | Chưa chạy |
| R-05 | Mapping task tồn tại/tag không tồn tại và ngược lại; cả hai không tồn tại | Mỗi biến thể lỗi FK 23503; không tạo mapping | Chưa chạy |
| R-06 | UPDATE từng FK nội bộ sang ID cha không tồn tại | Mỗi biến thể lỗi 23503; quan hệ ban đầu giữ nguyên | Chưa chạy |
| R-07 | INSERT workspace_id/assignee_id/author_id là UUID hợp lệ chưa có ở Auth | DB chấp nhận theo soft reference; không được hiểu là đã qua validation quyền/tồn tại | Chưa chạy |
| R-08 | Task T1 có G1/G2; G1 dùng cho T1/T2; thử lặp mapping T1/G1 | Quan hệ nhiều-nhiều đọc đủ; liên kết lặp bị từ chối 23505 | Chưa chạy |
| R-09 | Ghi SQL trực tiếp gắn T1(board B1) với G3(board B2) | Schema hiện tại chấp nhận do chỉ hai FK; ghi rõ giới hạn để T-013/service nhãn chặn ở tầng nghiệp vụ | Chưa chạy |

R-09 là test ghi nhận giới hạn schema, không nghiệm thu nghiệp vụ gắn nhãn khác board. Nếu nhóm chốt bổ sung cơ chế DB ngăn khác board trong thiết kế riêng, cập nhật phương án và expected trước triển khai.

### 5.6. Bộ C — Cascade, archive và bảo toàn dữ liệu khác

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| C-01 | Xóa T1 có comment và G1/G2 | T1/comment/mapping của T1 mất; G1/G2 và T2/mapping T2-G1 còn | Chưa chạy |
| C-02 | Xóa C1 có T1/T2, comment và mapping | C1/T1/T2 cùng comment/mapping con mất; tag B1, C2/C3 và B2 còn | Chưa chạy |
| C-03 | Xóa G1 được T1/T2 cùng sử dụng | G1 và mapping tới G1 mất; T1/T2/comment, G2 và mapping G2 còn | Chưa chạy |
| C-04 | Xóa B1 có nhiều cột/task/comment/tag/mapping | Tất cả dòng Kanban thuộc B1 và mapping liên quan mất; B2/C4/T3/G3 và dữ liệu Auth còn nguyên | Chưa chạy |
| C-05 | Xóa board/cột/task/tag không có dữ liệu con | Mỗi biến thể xóa thành công, không lỗi cascade | Chưa chạy |
| C-06 | Xóa riêng comment và xóa riêng mapping | Không xóa task, tag, column hoặc board cha | Chưa chạy |
| C-07 | UPDATE B1.is_archived TRUE rồi FALSE | Mọi dữ liệu con giữ nguyên cả hai lần; chỉ cờ thay đổi | Chưa chạy |
| C-08 | Bỏ membership, xóa W1 trong fixture Auth đúng FK nội bộ | Board B1 không bị DB cascade; chứng minh soft reference và ghi nhận cần policy/event dọn dữ liệu sau này | Chưa chạy |
| C-09 | Xóa user fixture không còn bị Auth.owner_id RESTRICT, từng vai trò assignee/author | Task/comment không tự xóa; ID soft reference còn; phải chuẩn bị fixture để không nhầm lỗi FK nội bộ Auth với FK Board | Chưa chạy |

### 5.7. Bộ P — Thứ tự, transaction, kết nối và truy vấn

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| P-01 | Insert cột/task không theo thứ tự position; SELECT ORDER BY position,id | Danh sách trả đúng thứ tự vị trí, tie-breaker xác định | Chưa chạy |
| P-02 | Trong transaction đổi vị trí T1/T2 và thứ tự C1/C2 bằng SQL; commit/reconnect | Đọc lại đúng thứ tự mới; ID/nội dung không đổi; giá trị trùng tạm thời không bị UNIQUE chặn | Chưa chạy |
| P-03 | Chuyển T1 từ C1 sang C2, cập nhật position; commit | T1 xuất hiện đúng một lần ở C2, không còn C1; comment và mapping đi theo ID task | Chưa chạy |
| P-04 | Cố tình rollback transaction tạo dữ liệu, reorder và xóa cascade, từng biến thể | Count/ID/giá trị và dữ liệu con trở về trạng thái trước transaction | Chưa chạy |
| P-05 | Commit task/comment/tag/mapping; đóng connection, mở connection khác | Dữ liệu đọc lại đủ và đúng; chứng minh persistence ở DB, chưa phải E2E reload UI | Chưa chạy |
| P-06 | Position trùng hoặc có khoảng trống; hai comment cùng created_at | DB chấp nhận; ORDER BY position,id hoặc created_at,id cho kết quả xác định; ghi giới hạn normalize ở T-013 | Chưa chạy |
| P-07 | Seed dataset đủ lớn, ANALYZE; EXPLAIN truy vấn theo tám index mục 3.4 | Ghi query/plan/row count/thời gian; cấu trúc index đúng; không fail chỉ vì optimizer chọn seq scan hợp lý | Chưa chạy |

### 5.8. Bộ B — Boundary, hồi quy và bằng chứng

| ID | Tiền điều kiện / thao tác | Kỳ vọng (Expected) | Thực tế (Actual) |
|---|---|---|---|
| B-01 | Rà SQL V2 và package/import/dependency được thêm | Không FK/SQL JOIN xuyên module hoặc dùng auth.internal; thay đổi đúng phạm vi | Chưa chạy |
| B-02 | Chạy `gradlew :verifyModuleBoundaries` trong backend | Exit 0, không dependency impl -> impl; kiểm tra thủ công B-01 vẫn cần vì task build không kiểm hết SQL/JPA | Chưa chạy |
| B-03 | Chạy riêng test migration/schema/startup trên PostgreSQL | Tất cả case bắt buộc chạy thật và pass; report xác nhận PostgreSQL, không silently skip | Chưa chạy |
| B-04 | Chạy `gradlew check` gồm regression Auth và smoke test | Exit 0; không regression; xác nhận B-03 chạy cùng gate hoặc cung cấp report chạy riêng nếu cấu hình task riêng | Chưa chạy |
| B-05 | Review file migration, report và ma trận expected/actual | AC-01–AC-10 có bằng chứng; case fail/blocked không bị đánh dấu xong | Chưa chạy |
| B-06 | Bàn giao schema cho T-011/T-013 | Có thông tin soft refs, enum, audit, thứ tự, nhãn cùng board và cascade; không tuyên bố API/UI đã được triển khai | Chưa chạy |

### 5.9. Lệnh chạy bộ test đã triển khai

Chạy từ thư mục `backend/`, Docker engine phải sẵn sàng cho PostgreSQL Testcontainers.

```powershell
.\gradlew.bat :app:test --tests "io.devflow.board.schema.KanbanMigrationIntegrationTest"
.\gradlew.bat :app:test --tests "io.devflow.board.schema.KanbanSchemaIntegrationTest"
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat check
```

Nếu test mặc định phát hiện class theo JUnit, giữ bộ PostgreSQL trong `:app:test` để `check` không bỏ sót. Nếu sau này tách task `integrationTest`, phải nối task đó vào quality gate và cập nhật lệnh/report ở plan. Docker/Java/dependency thiếu là BLOCKED, không phải PASS; ghi nguyên nhân và chạy lại khi môi trường sẵn sàng.

### 5.10. Ghi kết quả thực tế và điều kiện kết thúc test

Mỗi case/biến thể giữ ID ổn định. Khi chạy bổ sung, cập nhật Actual bằng quan sát thực tế và liên kết evidence:

| Case / biến thể | Expected | Actual quan sát được | Status | Evidence | Người chạy / thời gian / commit |
|---|---|---|---|---|---|
| Ví dụ: D-04/tasks.title=NULL | SQLSTATE 23502, không thêm task | Chưa chạy | NOT RUN | Chưa có | Chưa ghi nhận |

#### Kết quả thực thi hiện tại

| Hạng mục | Expected | Actual | Status / evidence |
|---|---|---|---|
| Migration + schema integration trên PostgreSQL | Flyway và schema Kanban hợp lệ; constraints, defaults, tham chiếu, cascade, persistence/rollback theo các test đã viết hoạt động đúng | `KanbanMigrationIntegrationTest`: 6 tests, 0 failures/errors; `KanbanSchemaIntegrationTest`: 55 tests, 0 failures/errors. PostgreSQL 17 chạy qua Testcontainers | PASS — `backend/app/build/test-results/test/TEST-io.devflow.board.schema.KanbanMigrationIntegrationTest.xml` và `...KanbanSchemaIntegrationTest.xml` |
| App/Auth regression | Spring app khởi động với Flyway và Hibernate validate; test Auth hiện hữu không regression | `DevFlowApplicationTests`: 1 test, 0 failures/errors; Auth suite chạy trong quality gate | PASS — `backend/app/build/test-results/test/TEST-io.devflow.DevFlowApplicationTests.xml` |
| Quality gate và module boundary | `gradlew check` exit 0; boundary verification thành công | `BUILD SUCCESSFUL`, 31 actionable tasks (3 executed, 28 up-to-date) | PASS — chạy `backend\\gradlew.bat check`, 06/10/2026; build output tại phiên triển khai |
| Toàn bộ catalog case mục 5.2–5.8 | Mọi ID và biến thể đều có Actual/status/evidence riêng | Có nhiều case được kiểm tra gộp/parameterized nhưng chưa lập mapping từng ID; một số case dự kiến (ví dụ EXPLAIN dataset lớn, đủ mọi nhánh xóa Auth, giới hạn content cực dài) chưa có test/evidence chuyên biệt | PARTIAL — tiếp tục hoàn thiện mapping và các case chưa bao phủ trước nghiệm thu AC-08 |

Bằng chứng gồm JUnit XML/HTML (`backend/app/build/test-results/test/`, `backend/app/build/reports/tests/test/`), log migrate/validate đã bỏ thông tin nhạy cảm, truy vấn catalog/count trước-sau và report review. Khi dùng nhiều lệnh test có thể ghi đè report, lưu evidence từng lượt trong thư mục báo cáo riêng được thống nhất.

Điều kiện kết thúc: toàn bộ case bắt buộc và biến thể hoàn thành; không có FAIL/BLOCKED/NOT RUN chưa xử lý; AC-01–AC-10 được xác nhận. Truy vấn EXPLAIN là bằng chứng xem xét index, không là gate P95 API. Các test giới hạn (D-11, R-07, R-09, C-08/C-09, P-06) chỉ đạt khi actual đúng hợp đồng và giới hạn được bàn giao rõ ràng.

## 6. Rủi ro và nội dung bàn giao

| Rủi ro / điểm cần theo dõi | Cách xử lý trong kế hoạch |
|---|---|
| Task gốc thiếu bảng nối nhãn | Dùng dictionary sáu bảng làm căn cứ, review phạm vi trước khi code V2 |
| H2 pass nhưng SQL PostgreSQL lỗi | Có harness PostgreSQL/Flyway riêng và test app startup, giữ regression H2 |
| Auto-baseline che DB chưa đúng V1 | Kiểm tra history/schema; test negative M-09; không baseline database lạ tự động khi nghiệm thu |
| Migration đã chạy bị sửa lại | Test checksum; sửa bằng migration mới ở môi trường đã áp dụng |
| UUID soft reference không bảo đảm tồn tại/quyền | Bàn giao validation và lifecycle event cho Auth/Board; không thêm FK xuyên module |
| Nhãn khác board / chuyển task khác board | Ghi giới hạn hai FK; cần service guard ở T-013 hoặc task nhãn tương ứng |
| Concurrent reorder tạo position trùng | T-010 chỉ hợp đồng lưu trữ; bàn giao transaction/locking/normalize cho T-013 |
| Hard delete cột gây mất task | Test cascade đầy đủ; T-012/T-013 phải định nghĩa quyền và điều kiện xóa |
| updated_at không tự tăng khi JDBC UPDATE | Test ghi audit tường minh; T-011 xác nhận JPA auditing bằng test entity |
| Task code dùng cho Git chưa có trong dictionary | Không thêm code tự phát; chốt phạm vi uniqueness/cách cấp mã trước task Git linking |
| Metadata/index đúng nhưng truy vấn thực tế chậm | Lưu EXPLAIN dataset lớn; đo P95 API khi T-012/T-013 có endpoint |

Hồ sơ bàn giao T-010 gồm migration được review, hợp đồng sáu bảng, ma trận test đã điền kết quả thực tế, report quality gate và danh sách giới hạn trên. Sau đó T-011 ánh xạ entity/repository theo schema, T-012/T-013 triển khai quyền và nghiệp vụ, T-014 mới phát sự kiện thay đổi task.
