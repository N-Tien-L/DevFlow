# T-015 — Plan triển khai backend đồng bộ Board realtime

**Task gốc:** [06_backend_dong_bo_board_realtime.md](06_backend_dong_bo_board_realtime.md).

**Trello:** [T-015 — Configure WebSocket STOMP Broadcaster for Board State Sync](https://trello.com/c/wcmdeeXc/21-t-015-configure-websocket-stomp-broadcaster-for-board-state-sync).

**Milestone:** 2 / Core Kanban realtime. **Owner:** Xuân Lê. **Module chính:** `board-api`, `board-impl`; phối hợp `app`, `auth-impl`, `common` cho transport/xác thực dùng chung. **Branch triển khai theo card:** `feature/T-015-board-stomp-broadcaster`.

**Phụ thuộc:** T-012 (Board/Column API), T-013 (Task CRUD/move), T-014 (task domain events), JWT và membership Auth. **Bàn giao:** T-018 (frontend sync), T-024 (Git tự chuyển task), T-034/T-040 (dùng chung WebSocket), T-041 (integration).

**Ngày lập:** 09/10/2026, GMT+7. **Trạng thái cập nhật:** Backend T-015 đã được triển khai trên branch `feature/T-015-board-stomp-broadcaster`; `backend/gradlew check` pass, gồm test và module-boundary verification. Đây chưa phải sign-off đóng task: ma trận 91 nhóm case bên dưới phần lớn chưa chạy; chỉ các ca ghi PASS/partial có evidence cụ thể mới được tính. Card Trello còn ở Backlog vì thao tác chuyển sang In Progress bị API từ chối `401 unauthorized card permission requested`; hai acceptance criteria chưa được tick. Các quyết định bổ sung trong plan là diễn giải kỹ thuật, chưa phải checklist mới trên Trello.

## 1. Mục tiêu, căn cứ và hiện trạng dự án

### 1.1. Mục tiêu và yêu cầu gốc

Một thành viên tạo/sửa/xóa/kéo thẻ hoặc thay đổi cột; những thành viên được phép xem cùng board nhận thông tin thay đổi sau khi dữ liệu đã commit. Người xem board khác không nhận nhầm; thay đổi thất bại không tạo thông báo thành công. Client có đủ thông tin để tải lại đúng dữ liệu và tự khôi phục sau khi mất kết nối.

**Hai acceptance criteria gốc trên Trello:**

- STOMP client kết nối và subscribe `/topic/boards/{id}` nhận được message tức thì.
- Payload message phản ánh chính xác hành động (`CARD_MOVED`, `CARD_CREATED`, v.v.).

| Nguồn đã đối chiếu | Nội dung áp dụng |
|---|---|
| Task gốc và card Trello T-015, đọc ngày lập plan | `SimpMessagingTemplate`, mutation card/column, topic theo board, owner/branch và hai AC gốc |
| [ARCHITECTURE](../ARCHITECTURE.md), mục 2–6, 9.2, 10 | Modular Monolith; Board sở hữu dữ liệu; Auth sở hữu identity/membership; transport dùng chung; event sau commit, không bền vững |
| [PRODUCT_SPEC](../PRODUCT_SPEC.md), mục 5.1, 5.2, 8 | Nhiều người xem board, Git/chat-to-board, validation, IDOR, privacy, observability, CRUD P95 < 200 ms |
| [IMPLEMENTATION_PLAN](../IMPLEMENTATION_PLAN.md), Phase 2, 3, 5 và DoD | Thứ tự triển khai Board → realtime → Git, regression, boundary và integration |
| [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) | Mục tiêu realtime < 1 giây trong điều kiện đo thống nhất; bằng chứng nghiệm thu |
| [Plan T-013](04_api_quan_ly_va_di_chuyen_task_plan.md), [plan T-014](05_phat_su_kien_task_plan.md), [T-018](09_frontend_dong_bo_board_realtime.md) | Transaction/lock, domain event chỉ bao phủ create/đổi cột, reconnect/refetch phía client |
| [Coding conventions](../../.agents/rules/coding-conventions.md), [module boundaries](../../.agents/rules/module-boundaries.md), [git workflow](../../.agents/rules/git-workflow.md) và `AGENTS.md` | Java 17, package/dependency, branch và review; ưu tiên quy tắc workspace hiện hành khi tài liệu cũ cho phép gọi public API trực tiếp |

### 1.2. Hiện trạng đọc trực tiếp từ source

Đường dẫn trong bảng tính từ root repository. Đây là kiểm tra source; không phải kết quả chạy ứng dụng hoặc xác nhận test T-015 pass. Các mô tả scaffold trong plan cũ phải được đối chiếu lại với code hiện tại.

| Thành phần | Hiện trạng thực tế | Hệ quả cho T-015 |
|---|---|---|
| `backend/app/.../config/WebSocketConfig.java` | Có native WebSocket `/ws`, simple broker `/topic`, application prefix `/app`; origin đang `*`; chưa SockJS/heartbeat/security interceptor | Tận dụng transport; bổ sung bảo vệ và cấu hình hữu hạn, không tạo endpoint thứ hai |
| `backend/auth-impl/.../SecurityConfig.java` | HTTP `/ws/**` được permit; JWT filter đọc HTTP Authorization | Permit handshake chưa xác thực STOMP CONNECT; browser cần cơ chế phù hợp |
| `JwtTokenProvider`, `UserPrincipal` | Đã có JWT parser, token type, claims; principal implements `AuthenticatedActor` | Auth tự xử lý CONNECT; Board không import `auth.internal.*`. Provider hiện chưa bắt buộc issuer trong `validateToken`, cần kiểm tra issuer/expiry/type ở đường STOMP |
| `WorkspaceMembershipPermissionService` | Hỏi Auth qua `WorkspaceMembershipCheckRequestedEvent` đồng bộ; đúng một decision, thiếu/lỗi trả unavailable | Tái sử dụng cho subscribe và kiểm tra delivery; không đổi event bus toàn app thành async |
| `TaskManagementService` | Create/replace/delete/move thật; transaction, khóa board, validation/sanitization, audit; move chỉ trong cùng board | Gắn realtime vào service ghi dữ liệu, dùng boardId đã resolve; không broadcast trong controller trước commit |
| `TaskEventPublisher` | Snapshot bất biến, `TransactionSynchronization.afterCommit()`; có eventId/correlationId/actorId; bắt lỗi bus sau commit | T-014 đã có trong code; không đăng ký listener AFTER_COMMIT cho event vốn chỉ được publish sau commit |
| `TaskCreatedEvent`, `TaskStatusChangedEvent` | Có board/workspace/column scope; chỉ create và cross-column move | Không đủ cho replace/delete/reorder/cột. Không dùng `task.status_changed` thay cho tất cả mutation |
| `DevFlowEvent` trong common | Là interface, có `eventType()`/`occurredAt()` | Event liên module mới phải `implements DevFlowEvent`, không chép ví dụ cũ `extends` |
| `BoardManagementService` | Board CRUD/archive/restore; Column CRUD/reorder; ngăn xóa cột có task và đổi category của cột có task | Giữ policy hiện hữu; broadcast đúng mutation, không tự thêm cascade xóa task qua delete-column |
| `BoardRepository`, `BoardEntity` | Có pessimistic lock; chưa board revision/sequence/`@Version` cho realtime | MVP không được giả định timestamp hoặc task position là phiên bản toàn board |
| `BoardResponse`, `TaskPageResponse`, `TaskMoveResponse` | GET board trả metadata/cột; task được phân trang theo cột, size tối đa 100; move trả source/destination | Client phải tải danh sách task phù hợp; GET board một lần không chứa toàn bộ task |
| Gradle | `app` có websocket starter; `board-impl` chưa có messaging/websocket dependency trực tiếp | Bổ sung thư viện vào module sử dụng, không phụ thuộc ngược `board-impl -> app` |
| Frontend và proxy | Chưa dependency STOMP/hook sync; Vite proxy `/ws` bật `ws: true`; Nginx đã có Upgrade/Connection | T-015 nghiệm thu bằng client thử độc lập; UI/reconnect hook là T-018; cần test qua proxy |
| Test | Có JUnit/Mockito; Board API IT ở `app` dùng PostgreSQL 17 Testcontainers/Flyway; smoke dùng H2 | Test commit/rollback/concurrency dùng PostgreSQL; MockMvc không chứng minh WebSocket network hoạt động |

### 1.3. Phạm vi và giới hạn

**Trong T-015:** hợp đồng bản tin; broadcast create/update/delete/move/reorder card, CRUD/reorder column; xác thực CONNECT, phân quyền topic, chống giả bản tin; sau commit; lỗi, quota, timeout, metrics và bộ test. Bổ sung `BOARD_UPDATED` cho metadata/archive/restore và `BOARD_DELETED` để người đang xem không giữ board đã mất là phạm vi hỗ trợ vòng đời được đề xuất trong plan.

**Ngoài T-015:** UI/hook production T-018, webhook và Git automation T-024, LLM/chat/notification delivery thật, CRUD comment/tag chưa có, topic danh sách board theo workspace, cursor replay, exactly-once, outbox, broker ngoài, đa instance. T-015 không thay role/member policy hiện hữu; quyền đọc dựa trên membership workspace, chưa có ACL riêng từng board.

## 2. Kế hoạch nghiệp vụ — mô tả luồng bằng ngôn ngữ

### NV-01. Vào xem một board

1. Thành viên đăng nhập và mở board được phép truy cập.
2. Hệ thống xác minh phiên đăng nhập, sau đó kiểm tra quyền xem chính board đó trước khi cho đăng ký nhận cập nhật.
3. Client tải dữ liệu hiện tại và bắt đầu nhận thay đổi. Các thay đổi nhận được trong lúc tải làm dữ liệu được tải lại; phần bỏ lỡ được bù bằng đối soát để màn hình hội tụ về trạng thái server.
4. Hai người cùng xem board nhận cùng tín hiệu thay đổi; người đang xem board khác không nhận tín hiệu của board này. Người không phải thành viên không được biết board có tồn tại hay không.

### NV-02. Tạo và sửa thẻ

1. Người dùng gửi thao tác bằng API hiện hữu. Hệ thống kiểm tra quyền, dữ liệu, bot protection/quota áp dụng rồi lưu task cùng audit.
2. Sau khi lưu thành công, người xem cùng board nhận thông báo tạo hoặc sửa đúng thẻ, đúng cột.
3. Client lấy dữ liệu mới đã được server chuẩn hóa; cập nhật title, priority, assignee, due date, chi tiết thẻ và các phần đang hiển thị có liên quan.
4. Người thực hiện cũng nhận thông báo. T-018 gộp kết quả API với dữ liệu theo ID, tránh hiển thị hai thẻ cho một lần tạo.
5. Sửa về đúng giá trị hiện tại không phát bản tin cập nhật giả. Tạo trong cột DONE vẫn là tạo thẻ, không phải một lần kéo thẻ.

### NV-03. Kéo thẻ và đổi thứ tự

1. Kéo sang cột khác trong cùng board: hệ thống lưu cột mới và thứ tự ở cả cột nguồn/đích trong cùng transaction.
2. Sau commit, gửi `CARD_MOVED`. Client cập nhật cả hai danh sách, kể cả các thẻ bị dồn vị trí dù không phải thẻ vừa kéo.
3. Kéo lên/xuống trong cùng cột: gửi `CARD_REORDERED`, không gửi domain event đổi trạng thái.
4. Kéo về đúng vị trí hiện tại là no-op, không gửi bản tin nếu không có sửa thứ tự dữ liệu thực tế.
5. Hai cột cùng tên/category vẫn được phân biệt bằng ID. Move qua board khác tiếp tục bị từ chối theo API hiện hữu.

### NV-04. Xóa thẻ

1. Người có quyền xóa thẻ; hệ thống xóa dữ liệu liên quan theo policy hiện hữu, sắp xếp lại thứ tự và ghi audit.
2. Sau commit, gửi `CARD_DELETED` với ID thẻ và cột trước khi xóa.
3. Client bỏ thẻ, tải lại danh sách bị ảnh hưởng và đóng/đổi trạng thái modal chi tiết đang mở. Không gọi lấy lại thẻ đã xóa để quyết định board chứa nó.

### NV-05. Thêm, sửa, xóa và sắp xếp cột

1. Thêm cột hợp lệ: người xem thấy cột mới ở vị trí đã lưu.
2. Đổi tên/category được phép: người xem tải lại cột; đổi tên có thể cần làm mới tiêu đề/trạng thái đang hiện trong task detail.
3. Reorder cột: mọi người thấy cùng thứ tự; kéo về vị trí cũ không phát thông báo thay đổi giả.
4. Xóa cột rỗng: gửi xóa cột rồi tải lại thứ tự cột còn lại.
5. Cột có task bị từ chối xóa hoặc đổi category như API hiện hữu; không phát bản tin và không tự di chuyển/xóa task để vượt policy.

### NV-06. Board archived, khôi phục hoặc bị xóa

- Sửa metadata/archive/restore thành công phát `BOARD_UPDATED`; client tải lại board, cập nhật trạng thái chỉ đọc khi archived. Board archived vẫn đọc/subscribe được nếu còn membership; mutation task/column tiếp tục bị chặn.
- Xóa board phát `BOARD_DELETED` tối thiểu tới những phiên đang có quyền, rồi dọn subscription. Client rời/đóng board; không gửi một loạt event con cho mọi task/cột bị cascade.
- Tạo board mới chưa có người subscribe không cần topic workspace mới trong T-015. Danh sách board làm mới theo API và phạm vi frontend riêng.

### NV-07. Lỗi khi thao tác hoặc khi gửi cập nhật

- Dữ liệu sai, thiếu quyền, bot/quota từ chối, audit/DB lỗi hoặc transaction rollback: không gửi thông báo thành công; dữ liệu giữ nguyên theo tính nguyên tử của transaction.
- DB đã commit nhưng gửi realtime thất bại: thao tác vẫn thành công; ghi log/metric để điều tra. Client khôi phục bằng tải lại dữ liệu, không tự gửi lại POST create chỉ vì chưa nhận bản tin.
- Mạng chậm hoặc người xem rớt mạng không được giữ khóa DB hay làm người thao tác phải chờ vô hạn.

### NV-08. Mất mạng, hết phiên hoặc bị thu hồi quyền

1. Khi mạng phục hồi, client đăng nhập/refresh theo Auth rồi kết nối, subscribe lại và tải lại board để bù phần đã bỏ lỡ.
2. Đổi board hoặc rời trang phải dọn đăng ký cũ. Một tài khoản mở nhiều tab vẫn có nhiều phiên độc lập trong giới hạn cho phép.
3. Token hết hạn: chặn dữ liệu mới, đóng phiên; không duy trì quyền vô thời hạn vì đã CONNECT trước đó.
4. Membership bị xóa: trước lần giao dữ liệu tiếp theo, kiểm tra quyền hiện tại và chặn phiên không còn quyền. Dữ liệu đã giao trước khi thu hồi không thể bị thu lại.

### NV-09. Nhiều người thao tác đồng thời và nguồn Git/AI tương lai

- Thao tác cùng board được tuần tự hóa phần ghi bởi khóa hiện hữu; sau commit, các bản tin có thể tới lệch thứ tự. Client tải trạng thái mới nhất thay vì áp lại một ảnh cũ.
- Hai người sửa cùng task giữ policy cập nhật hiện tại; realtime không bổ sung optimistic conflict hay hứa hợp nhất nội dung.
- Git/AI sau này phải đi qua mutation của Board, rồi Board phát đúng một bản tin cho thay đổi đó. Không broadcast song song từ GitCI/AI cho cùng task; T-015 chưa xác nhận webhook/chat thật đã hoạt động.

## 3. Kế hoạch kỹ thuật — contract và luồng xử lý

### 3.1. Chọn bản tin thông báo thay đổi kèm phạm vi tải lại

Chọn **invalidation**: bản tin phản ánh hành động đã commit và chỉ rõ phần dữ liệu cần đọc lại. REST/DB là nguồn dữ liệu chuẩn. Không nhét entity hoặc toàn bộ board vào MESSAGE, cũng không coi bản tin là delta đầy đủ để sửa mọi position ở client.

Lý do: create/delete/move có thể dồn vị trí hàng trăm task; API task đang phân trang; project chưa có board revision/replay. Một delta chỉ có thẻ vừa kéo dễ làm thứ tự thẻ còn lại sai. Invalidation có payload nhỏ, giảm dữ liệu nhạy cảm và chịu được bản tin trùng/đến muộn bằng cách đọc lại.

**Đánh đổi:** thêm lượt GET và chỉ bảo đảm hội tụ sau khi refetch thành công. Mục tiêu < 1 giây phải đo cả refetch, không chỉ thời gian `convertAndSend`. Nếu sau này cần delta/replay, thêm revision tăng nguyên tử theo board và snapshot tương ứng bằng thay đổi schema riêng; `occurredAt`/`updatedAt` không thay thế revision.

### 3.2. Hợp đồng wire đề xuất v1

Định nghĩa DTO bất biến `BoardMutationMessage`, `BoardMutationType`, `BoardMutationData`, `BoardRefreshTarget` trong `board-api`. Trên wire dùng `CARD_*` đúng thuật ngữ card Trello; ID phía REST vẫn là `taskId`. Contract này phục vụ client, không thay thế domain event `task.created`/`task.status_changed`.

| Trường envelope | Kiểu / quy tắc |
|---|---|
| `schemaVersion` | Integer, hiện là `1`; client version không hỗ trợ phải refetch an toàn/ghi nhận thay vì áp delta mù |
| `eventId` | UUID duy nhất cho một mutation được thông báo; giữ nguyên khi thử gửi lại cùng bản tin nếu sau này có retry |
| `type` | Enum trong bảng mapping phía dưới; phân biệt create/update/delete/move/reorder |
| `boardId` | UUID bắt buộc; phải khớp topic, lấy từ dữ liệu đã resolve phía server |
| `occurredAt` | UTC ISO-8601; thời điểm capture thay đổi, không hứa là timestamp commit hoặc thứ tự toàn cục |
| `correlationId` | UUID chuẩn hóa từ request context; automation tạo mới nếu chưa có |
| `actorId` | UUID người thao tác; nullable cho system automation; không dùng để xác thực subscriber |
| `data` | Metadata hành động và phạm vi refresh; bất biến, collection được copy |

`data` có các field cố định: `taskId`, `columnId`, `sourceColumnId`, `destinationColumnId`, `fromPosition`, `toPosition` (nullable khi không áp dụng); `affectedColumnIds`, `changedFields`, `refresh` là các list không null. ID cột phải lấy từ transaction, không tin dữ liệu routing do client gửi tùy ý. `changedFields` là tên field trong allowlist, không có giá trị trước/sau; không gửi token, email, description, audit snapshot hay danh sách thành viên.

| Type | Khi phát / dữ liệu tối thiểu | `refresh` và phạm vi |
|---|---|---|
| `CARD_CREATED` | Create thành công; `taskId`, `columnId`, `toPosition`; affected = cột chứa task | `TASK_LISTS`; nếu UI có bộ đếm/filter thì invalidate cùng scope |
| `CARD_UPDATED` | Replace có changedFields thật; `taskId`, `columnId`, danh sách field đổi | `TASK_LISTS`, `TASK_DETAIL`; affected = cột hiện tại |
| `CARD_DELETED` | Delete thành công; `taskId`, `columnId`, `fromPosition` capture trước xóa | `TASK_LISTS`, `TASK_DETAIL`; detail được xóa/đóng, không phải GET bắt buộc thành công |
| `CARD_MOVED` | Khác columnId; `taskId`, source/destination, vị trí trước/sau | `TASK_LISTS`, `TASK_DETAIL`; affected = hai cột không trùng |
| `CARD_REORDERED` | Cùng cột và thứ tự có thay đổi; source = destination, vị trí trước/sau | `TASK_LISTS`, `TASK_DETAIL`; affected = một cột |
| `COLUMN_CREATED` | Tạo cột; `columnId`, `toPosition` | `COLUMNS`; đọc lại danh sách cột |
| `COLUMN_UPDATED` | Tên/category thật sự đổi; `columnId`, changedFields | `COLUMNS`, `TASK_DETAIL`; invalidate detail đang mở thuộc cột đó |
| `COLUMN_DELETED` | Xóa cột rỗng; `columnId`, `fromPosition` | `COLUMNS`; bỏ cache task-list của cột đã xóa |
| `COLUMN_REORDERED` | Thứ tự cột thay đổi; `columnId`, vị trí trước/sau | `COLUMNS`; đọc lại toàn bộ danh sách cột có giới hạn |
| `BOARD_UPDATED` | Metadata/archive/restore có thay đổi; changedFields, không cần ID con | `BOARD`; client lấy lại archived/name/description qua API |
| `BOARD_DELETED` | Xóa board commit; chỉ boardId và metadata envelope, không liệt kê cascade | `BOARD`; xóa cache/đóng board, GET tiếp theo có thể trả 404 |

`affectedColumnIds` cho column mutation chứa cột liên quan; cho board mutation là list rỗng. `refresh` là tập không trùng từ `BOARD`, `COLUMNS`, `TASK_LISTS`, `TASK_DETAIL`. Bộ đếm/filter chưa triển khai được ghi là bàn giao T-018, không tuyên bố UI hiện có đã đồng bộ.

Ví dụ `CARD_MOVED` (UUID minh họa, không phải dữ liệu chạy test):

```json
{
  "schemaVersion": 1,
  "eventId": "55555555-5555-4555-8555-555555555555",
  "type": "CARD_MOVED",
  "boardId": "11111111-1111-4111-8111-111111111111",
  "occurredAt": "2026-10-09T03:00:00Z",
  "correlationId": "66666666-6666-4666-8666-666666666666",
  "actorId": "77777777-7777-4777-8777-777777777777",
  "data": {
    "taskId": "22222222-2222-4222-8222-222222222222",
    "columnId": null,
    "sourceColumnId": "33333333-3333-4333-8333-333333333333",
    "destinationColumnId": "44444444-4444-4444-8444-444444444444",
    "fromPosition": 2,
    "toPosition": 0,
    "affectedColumnIds": [
      "33333333-3333-4333-8333-333333333333",
      "44444444-4444-4444-8444-444444444444"
    ],
    "changedFields": ["columnId", "position"],
    "refresh": ["TASK_LISTS", "TASK_DETAIL"]
  }
}
```

### 3.3. Mutation → commit → broadcast

```text
HTTP mutation + JWT/validation/quota/Turnstile hiện hữu
  -> TaskManagementService / BoardManagementService
  -> resolve scope + kiểm tra membership + khóa Board
  -> ghi dữ liệu + normalize positions + audit
  -> capture snapshot bất biến + đăng ký callback realtime
  -> COMMIT
       -> callback T-014: domain event -> AI/Notification
       -> callback T-015: BoardRealtimeBroadcaster
            -> SimpMessagingTemplate -> /topic/boards/{boardId}
            -> kiểm tra phiên/quyền ở outbound -> WebSocket client
ROLLBACK -> không chạy các callback thành công
```

1. Tạo `BoardRealtimePublisher` trong `board-impl`, yêu cầu actual transaction và synchronization active. Không có transaction thì fail trước commit; không fallback gửi ngay.
2. Các mutation gọi publisher sau khi xác định thay đổi, chuẩn bị audit và trạng thái cuối. Copy UUID/int/enum/list thành snapshot, kể cả boardId/workspaceId trước khi delete/`entityManager.clear()`. Không giữ JPA entity, lazy collection, servlet request hoặc SecurityContext trong callback.
3. Đăng ký `TransactionSynchronization.afterCommit()` tương tự T-014. Callback chỉ gửi snapshot qua `BoardRealtimeBroadcaster`; không đọc entity đã xóa hoặc ghi DB bằng transaction vừa commit.
4. Broadcaster gọi `convertAndSend("/topic/boards/" + boardId, message)` với content type JSON; đặt timeout gửi hữu hạn. Bắt lỗi serialization/send/metric/log trong nhánh sau commit để không biến response mutation đã lưu thành lỗi giả.
5. T-015 chủ động đăng ký cho **mọi** mutation trong bảng mapping; không đồng thời nghe hai event T-014 để phát lại create/move. Nhờ vậy mỗi mutation có một publication realtime, không phụ thuộc thứ tự hoặc lỗi consumer AI/Notification.
6. T-014 vẫn phát domain event như cũ. EventId realtime độc lập eventId domain, liên kết bằng correlationId/taskId; không giả định hai event khác mục đích dùng chung một ID.
7. No-op không đăng ký; nếu normalization thực sự sửa position lỗi thì phát invalidation phù hợp dù task được yêu cầu ở vị trí cũ. Test phân biệt no-op thuần và sửa dữ liệu thứ tự.
8. Nhiều mutation trong một outer transaction: mỗi mutation hợp lệ có snapshot riêng; outer rollback không gửi bản tin nào. Không có bảo đảm callback của các transaction khác nhau chạy theo thứ tự commit.

### 3.4. CONNECT, SUBSCRIBE và chống giả bản tin

Browser gửi access JWT trong native STOMP header `Authorization: Bearer ...` của CONNECT; Auth interceptor xác minh và gắn principal vào phiên. Không đưa JWT vào URL. Cơ chế header CONNECT/interceptor và thứ tự trước authorization được mô tả trong [Spring Framework 6.2 — Token Authentication](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/authentication-token-based.html).

| Lớp | Thiết kế đề xuất |
|---|---|
| HTTP handshake | Giữ endpoint native `/ws`; permit handshake có kiểm soát để browser CONNECT được. Allowlist Origin theo môi trường; production chỉ HTTPS/WSS, từ chối Origin null/không có trừ cấu hình client ngoài browser được cấp riêng |
| STOMP CONNECT | `StompJwtAuthenticationInterceptor` thuộc Auth; chỉ một Authorization header, access token có type đúng, chữ ký/thuật toán/issuer/expiry/subject hợp lệ; reject refresh token và token thiếu type ở đường STOMP |
| Danh tính phiên | Lưu principal UUID và expiry đã xác minh, không giữ raw JWT. Contract metadata tối thiểu ở common nếu Board cần đọc expiry; Board dùng `AuthenticatedActor`, không cast `UserPrincipal` |
| Thứ tự interceptor | Authenticate trước authorization; cấu hình receive ordering/kiểm tra state để SUBSCRIBE sớm không chạy với principal rỗng hoặc vượt CONNECT |
| SUBSCRIBE | `BoardSubscriptionInterceptor` thuộc Board; chỉ chấp nhận chính xác `/topic/boards/{canonical UUID}`. Resolve workspace từ Board, hỏi membership event; board không tồn tại/ngoài quyền cùng lỗi tổng quát |
| Destination | Reject wildcard `*`/`**`, `/topic/boards`, prefix/suffix thừa, path traversal, encoded slash, UUID sai, header destination lặp; simple broker hỗ trợ pattern nên không dựa vào broker tự hạn chế scope |
| Client SEND | Cấm SEND tới `/topic/**`, `/queue/**`, `/topic/boards/{id}`, `/app/boards/**` và destination chưa có policy. Board chỉ mutation qua REST; không nhận payload giả `CARD_*` từ client |
| UNSUBSCRIBE/DISCONNECT | Theo subscriptionId/sessionId do server đã bind; cleanup idempotent, không cho sửa/xóa subscription của phiên khác |
| Topic dùng chung | `app` điều phối policy theo destination; chat/notification có allowlist và kiểm tra riêng khi triển khai. Không mở toàn bộ `/app/**` hoặc `/topic/**` chỉ để giữ scaffold hoạt động |

**Biên lỗi:** lỗi HTTP trước upgrade dùng HTTP status/ProblemDetail khi áp dụng (Origin/quota có thể do handshake handler trả 403/429). Sau upgrade dùng STOMP ERROR có mã hữu hạn rồi đóng phiên cho lỗi nghiêm trọng; không dùng HTTP 401/403 để mô tả một STOMP frame.

Mã đề xuất: `AUTH_REQUIRED`, `AUTH_INVALID`, `AUTH_EXPIRED`, `ACCESS_DENIED`, `INVALID_DESTINATION`, `RATE_LIMITED`, `PAYLOAD_TOO_LARGE`, `SERVICE_UNAVAILABLE`. Error chỉ gồm code/message/correlationId phù hợp, không trả JWT/SQL/stack trace/chi tiết membership. ERROR và thao tác close phải đi qua đường không bị outbound guard chặn đệ quy.

### 3.5. Quyền đang thay đổi và vòng đời phiên

Chỉ kiểm tra SUBSCRIBE là chưa đủ: người dùng có thể bị xóa membership hoặc token hết hạn trong khi socket còn mở. Bổ sung các hàng rào sau:

- Auth kiểm expiry trên inbound và lên lịch đóng phiên ở expiry; outbound cũng kiểm expiry ngay trước khi giao MESSAGE để task scheduler trễ không mở cửa nhận dữ liệu.
- Board quản lý mapping tin cậy `sessionId + subscriptionId -> boardId + workspaceId` đã được chấp thuận. Mỗi outbound board MESSAGE tra mapping này, kiểm tra identity/expiry và membership hiện tại; không dùng actorId của mutation làm người nhận.
- Đặt kiểm tra DB tại outbound executor ngay trước handler gửi, ví dụ `ExecutorChannelInterceptor.beforeHandle`; không đặt truy vấn blocking trong `preSend` chạy trên thread publication. Membership read dùng transaction read-only mới, timeout hữu hạn; không dùng persistence context của mutation vừa commit hoặc cache positive membership vô hạn.
- Thiếu listener, nhiều decision, Auth/DB lỗi: chặn delivery, metric và đóng phiên để client retry/refetch. Không trả dữ liệu vì lần subscribe cũ từng được phép.
- `BOARD_DELETED` dùng board/workspace snapshot tin cậy đã bind/capture trước delete và membership hiện tại; không yêu cầu find board còn tồn tại mới cho gửi tombstone. Sau tombstone dọn đăng ký; các bản tin cũ tới muộn không được hồi sinh board.
- Quyền được kiểm tại thời điểm giao; frame đã gửi trước đó hoặc nằm trong network buffer không thể thu hồi. Test thu hồi quyền phải chờ commit membership rồi mới kích hoạt mutation cần chặn, thay vì hứa atomic giữa network và DB transaction thu hồi.
- App giữ registry WebSocketSession để close/cleanup bằng transport lifecycle; Auth sở hữu validation, Board sở hữu scope. App chỉ wiring framework, không chuyển business logic Auth vào Board.
- Chi phí kiểm quyền tăng theo số người nhận; benchmark phải tính DB pool, query/giây và outbound queue. MVP ưu tiên quyền chính xác; tối ưu theo membership-change event/cache có invalidation phải là thay đổi được test riêng.

### 3.6. Đồng bộ, reconnect và giới hạn delivery

**T-015 bảo đảm tại server:** chỉ commit mới publication; đúng topic/type/scope; một publication cho một mutation; lỗi gửi hữu hạn và quan sát được. Không bảo đảm mỗi subscriber nhận exactly-once hoặc không mất bản tin khi restart/rớt mạng.

**Hợp đồng bàn giao T-018/client thử nghiệm:**

1. Sau CONNECT, subscribe rồi buffer invalidation trong lúc tải board/cột/task; thực hiện refresh hậu subscribe, không giả định GET trước subscribe đã đủ. `subscribe()` trả về hoặc CONNECTED không chứng minh broker đã đăng ký xong. V1 chưa có handshake xác nhận subscription dành cho production; cửa sổ trước khi subscribe thực sự có hiệu lực vẫn có thể bỏ lỡ event và phải được bù bởi đối soát ở bước 6. Barrier test-only không được coi là tính năng production đã có.
2. Dedup eventId bằng cache có giới hạn. Đánh dấu các `refresh` scope dirty; gộp burst khoảng 50–100 ms rồi refetch, không tăng/decrease position thủ công từ metadata.
3. Với `TASK_LISTS`, invalidate tất cả page/cache filter liên quan của affected columns; tải lại các page UI cần. Với phép so sánh toàn board trong test, đọc đủ page (`size <= 100`), không chỉ page đầu.
4. Mỗi scope có một fetch đang chạy và generation dirty. Event tới trong lúc fetch làm scope dirty lại; fetch xong phải đọc lại nếu generation tăng. Request thuộc board cũ hoặc response cũ không được ghi đè màn hình mới. Modal đang mở được invalidate theo task/cột tương ứng.
5. Reconnect dùng exponential backoff + jitter, trần đề xuất 30 giây, có JWT mới theo Auth; subscribe lại, bỏ cache đang nghi stale và refetch. Không replay bằng `occurredAt`.
6. Nếu bản tin cuối bị mất nhưng socket chưa đóng, chỉ reconnect không phát hiện được: refresh khi window focus và polling đối soát có jitter, đề xuất 30 giây khi board visible. Đây là bù best-effort, không đạt mục tiêu < 1 giây trong failure mode mất bản tin.
7. `BOARD_DELETED` là trạng thái kết thúc cho boardId đó: đóng view/cache; bỏ invalidation cũ. Khi đọc 404/permission denied thì ngừng vòng refetch/reconnect tự động vào board không còn quyền.

Không cần thêm migration trong thiết kế v1 này. PostgreSQL vẫn là nguồn chuẩn; chưa cung cấp snapshot nguyên tử cho nhiều GET/phân trang trong lúc có mutation liên tục. Khi tải bị giao thoa, dirty/refetch giúp hội tụ sau khi thao tác lắng xuống; nếu cần snapshot nhất quán tại một revision thì phải bổ sung API/schema có version.

### 3.7. Transport, giới hạn tài nguyên và vận hành

Giữ một backend instance với simple broker. Heartbeat cần scheduler; pattern subscription là khả năng sẵn có của broker, nên ACL phải chặn trước khi đăng ký. Tham khảo [Spring Framework 6.2 — Simple Broker](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/handle-simple-broker.html).

Các giá trị dưới là **cấu hình khởi điểm đề xuất**, cần validate cấu hình và benchmark; không phải giới hạn đang có trong code:

| Yếu tố | Cấu hình / hành vi dự kiến |
|---|---|
| Heartbeat | Hai chiều khoảng 10 giây; scheduler riêng có lifecycle; client ngừng heartbeat được phát hiện/dọn sau timeout thương lượng + tolerance |
| Phiên chưa CONNECT | `timeToFirstMessage` khoảng 5 giây; đồng thời giới hạn handshake/IP trước khi biết actor để không giữ vô hạn socket anonymous |
| Connect quota | Khởi điểm 20 handshake/phút/IP; 5 socket đã xác thực/user; 200 socket toàn instance. Đếm atomic cả pending/authenticated; IP lấy qua proxy tin cậy |
| Subscribe quota | Tối đa 10 board subscription/session, 60 lần SUBSCRIBE/phút/user; duplicate subscriptionId bị reject, không tạo map rác |
| Inbound STOMP | Message limit 16 KiB; tổng header/native token bị giới hạn trong mức đó; Board không cần SEND body. Test cả frame fragment, không chỉ một WebSocket frame lớn |
| Outbound | Message JSON Board tối đa 4 KiB; send buffer/session 256 KiB; sendTimeLimit 5 giây; đóng slow client thay vì giữ backlog vô hạn |
| Channel executors | Core/max và queue capacity hữu hạn, khởi điểm core 4/max 8/queue 500 mỗi channel; tune theo CPU/DB pool, không dùng queue vô hạn |
| Publication timeout | `SimpMessagingTemplate` send timeout khởi điểm 100 ms; channel rejection phải xử lý rõ, timeout không được coi là cam kết client đã nhận |
| Ordering | Cân nhắc preserve receive order cho state CONNECT/SUBSCRIBE; invalidation không cần cam kết FIFO toàn board. Không coi preserve publish order là thứ tự commit |
| Slow/disconnected client | Cleanup subscription/registry/quota/timer idempotent khi disconnect, timeout, expiry, denied, transport error; shutdown giải phóng scheduler/executor |
| Proxy | Kiểm Vite và Nginx hiện hữu; production WSS, Upgrade, timeout proxy dài hơn heartbeat; không ghi Authorization native header vào access/debug log |
| Readiness | DB và broker đang running/accepting message; không-ready khi broker dừng. Không dùng số subscriber = 0 làm unhealthy; liveness không phụ thuộc lỗi một client |

Spring phân biệt channel queue, send buffer/time và message size; simple broker không fan-out giữa nhiều instance. Xem [Performance](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/configuration-performance.html). Ordering của channel cũng không mặc nhiên giữ thứ tự do executor; xem [Order of Messages](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/ordered-messages.html). Không tự thêm RabbitMQ/Redis trong T-015; trước khi scale backend phải chốt broker relay hoặc cơ chế fan-out tương ứng.

Không thêm retry vô hạn/CircuitBreaker cho đường in-process chỉ để đủ checklist. External Turnstile tiếp tục dùng timeout của guard hiện hữu; LLM/email/Git chưa nằm trong đường này. Send thất bại dựa vào client resync và observability; nếu cần giao bền vững thì thiết kế outbox và idempotency ở task riêng.

### 3.8. Log, metrics, audit và privacy

- Log JSON chỉ metadata cần thiết: eventId/type/boardId/correlationId, userId của phiên và outcome; không log raw frame, JWT, title/description/email, exception message có thể chứa payload.
- Capture correlation/actor trước khi đổi thread; thiết lập rồi restore/clear MDC trong callback/executor. SessionId không làm metric tag, log chỉ khi cần điều tra với policy retention hiện hữu.
- Counter đề xuất: `devflow.board.realtime.publications` (type/outcome), `devflow.board.realtime.deliveries` (outcome), `devflow.websocket.auth` (outcome), `devflow.board.realtime.subscriptions` (outcome), `devflow.websocket.rejections` (reason hữu hạn).
- Timer: publication duration, authorization duration; gauge: active sessions/subscriptions, channel queue depth. Không dùng UUID/userId/eventId/destination động làm tag. Publication success nghĩa là accepted bởi messaging path, không có nghĩa mọi client đã nhận.
- Server không suy ra end-to-end latency từ `occurredAt`; harness/performance collector đo commit → receipt → refetch/render. Đếm riêng dropped/denied/queue-full/timeout và so sánh với số mutation commit.
- Giữ một audit nghiệp vụ hiện hữu cho mutation; không ghi audit CRUD lần hai chỉ vì broadcast. Kết nối/denied có security log phù hợp, không lưu payload board vào notification store.

## 4. Các bước triển khai và bàn giao

| Bước | Công việc nghiệp vụ/kỹ thuật | Đầu ra và điều kiện chuyển bước |
|---|---|---|
| P1. Chốt baseline | Đọc lại card, source T-012–T-014, policy Auth, giới hạn môi trường; trao đổi contract T-018 với Tiến/Xuân khi triển khai | Mapping 11 type, invalidation/refetch, quyền/expiry, phạm vi Board lifecycle rõ; ghi commit baseline |
| P2. Contract | DTO/enum v1 trong `board-api`, validate invariants/immutable lists, ví dụ JSON và error codes | Contract tests serialize/map/size pass; không sửa nghĩa domain event T-014 |
| P3. Transport/Auth | Wiring `/ws`, origin, CONNECT JWT, metadata expiry, registry/quota/heartbeat/error handler | Client thật CONNECT đúng/sai hoạt động; không còn anonymous subscribe hoặc SEND vào broker |
| P4. Topic ACL | Board subscribe guard, fresh outbound membership/expiry, lifecycle cleanup và tombstone board-delete | Test khác board/workspace, wildcard, expiry/revoke, unavailable fail closed pass |
| P5. Task realtime | Thêm publisher/broadcaster; create/replace/delete/move/reorder tích hợp sau commit, no-op | Mapping task và test commit/rollback/duplicate publication pass; T-014 regression không đổi |
| P6. Column/Board | CRUD/reorder column; metadata/archive/restore/delete board; capture trước xóa | Policy non-empty/archived giữ đúng; tombstone và cascade chỉ 1 bản tin board; no-op không gửi |
| P7. Integration/vận hành | Full app random port + PostgreSQL; 2–3 STOMP client, proxy, disconnect, queue/slow client, log/metric | Các ca server và performance có evidence; không kết luận WebSocket pass chỉ bằng Mockito |
| P8. Bàn giao/nghiệm thu | Client thử và contract cho T-018; checklist kết quả thực tế; review/PR theo branch card | Đủ gate mục 5, ghi giới hạn best-effort/single instance; Trello chỉ tick theo evidence |

### 4.1. Bản đồ file dự kiến thay đổi khi triển khai

Tên file mới là đề xuất, chưa tồn tại/chưa được tạo trong lượt lập plan.

| Nơi đặt | Thay đổi |
|---|---|
| `backend/board-api/.../api/` | `BoardMutationMessage`, `BoardMutationData`, `BoardMutationType`, `BoardRefreshTarget` |
| `backend/board-impl/.../internal/` | `BoardRealtimePublisher`, `BoardRealtimeBroadcaster`, `BoardSubscriptionInterceptor`, `BoardOutboundAuthorizationInterceptor`; tích hợp vào hai management service |
| `backend/auth-impl/.../internal/security/` | CONNECT JWT interceptor, expiry handling; tận dụng provider trong chính Auth |
| `backend/common/.../security/` | Chỉ metadata identity/expiry dùng chung nếu cần; không thêm dependency vào domain module |
| `backend/app/.../config/WebSocketConfig.java` và config hỗ trợ | Wiring inbound/outbound interceptor từ module qua framework; allowlist, scheduler, quotas, executors, session registry/error handler, readiness |
| `backend/{board-impl,auth-impl}/build.gradle.kts` | Dependency `spring-messaging`/`spring-websocket` trực tiếp khi dùng; version qua BOM; không impl→impl |
| `backend/app/src/main/resources/application.yml` | Config hữu hạn theo môi trường; production không fallback Origin `*` hoặc bypass Auth |
| Test ở `common`, `board-api`/`board-impl`, `auth-impl`, `app` | Unit và PostgreSQL/network integration theo ma trận; harness client không cần frontend production |
| `docs/xuan/` và docs contract khi triển khai | Cập nhật thực tế/evidence và hướng dẫn subscribe/refetch/error cho T-018 |

App được wiring tất cả module theo vai trò bootstrap. Business coordination Board ↔ Auth tiếp tục qua typed membership event; Board không gọi AuthApi hoặc import JwtTokenProvider. Nếu phát sinh contract event liên module khác, đặt trong common và cập nhật event catalog; tín hiệu nội bộ chỉ phục vụ broadcaster cùng Board không buộc mở rộng common.

## 5. Tiêu chí hoàn thành

| ID | Tiêu chí nghiệm thu | Bằng chứng bắt buộc / case liên quan |
|---|---|---|
| AC-01 | Client STOMP thật CONNECT/subscribe đúng topic và nhận ngay sau commit | RT-01–RT-04; network test trên random port, không chỉ mock template |
| AC-02 | Payload chính xác cho đủ card/column action; board lifecycle bổ sung có contract rõ | CT-01–CT-06, FN-01–FN-18; JSON + DB/REST đối chiếu |
| AC-03 | Một mutation tạo một publication realtime; no-op/rollback/denied tạo 0 | TX-01–TX-08, FN-05/FN-09/FN-14; T-014 không phát lại realtime |
| AC-04 | Không rò board/workspace, không wildcard/fake SEND, phiên hết hạn/revoke không nhận mới | AU-01–AU-10, ACL-01–ACL-12; network negative tests |
| AC-05 | Broadcast không đổi kết quả DB đã commit; failure/slow client có giới hạn, quan sát được | TX-06–TX-08, RS-01–RS-10, OP-01–OP-05 |
| AC-06 | Hai client cùng board hội tụ dữ liệu, reconnect/refetch bù thay đổi; contract T-018 đủ dùng | CN-01–CN-06, RS-01–RS-04, UAT-01–UAT-05; phân biệt harness và UI thật |
| AC-07 | Có số đo realtime < 1 giây và CRUD P95 < 200 ms theo workload thống nhất | PF-01–PF-04; báo cáo percentile, max, error/loss, refetch fan-out và điều kiện đo |
| AC-08 | Boundary/security/regression và tài liệu bàn giao đạt gate | OP-06–OP-08; backend check/boundary pass; frontend build/lint nếu frontend thay đổi |

**Gate đóng T-015:** hai AC gốc và mọi tiêu chí server P0/P1 áp dụng đều có evidence; không có FAIL/BLOCKED chưa xử lý. Các test UI thuộc T-018 có thể ghi `Chưa chạy — phụ thuộc T-018`, nhưng server và client thử độc lập phải hoàn tất trước khi đóng T-015. Không đánh dấu backend done nếu còn thiếu kiểm quyền/rollback hoặc chỉ có happy path.

Nếu đổi thiết kế, cập nhật contract/case/DoD cùng nhau. Case N/A phải có lý do và mapping thay thế; không dùng N/A để bỏ kiểm tra quyền, rollback, expiry, cleanup hay publication trùng. Review cần xác nhận giới hạn best-effort, single instance và cách đối soát khi mất bản tin; bền vững delivery chưa nằm trong cam kết T-015.

## 6. Kế hoạch testing — expected và thực tế

### 6.1. Cách tổ chức, fixture và ghi kết quả

**Mức ưu tiên:** P0 = chặn release (sai dữ liệu/quyền/transaction), P1 = chức năng và chất lượng bắt buộc, P2 = mở rộng điều kiện/soak. **Lớp:** UT = unit/contract; IT = PostgreSQL/application; WS = WebSocket/STOMP thật; PERF = benchmark; UAT = thủ công/harness nhiều client.

Fixture cố định theo từng test, UUID sinh riêng: W1/W2; U1/U2 là thành viên W1; U3 thuộc W2; U4 đăng nhập nhưng không có membership. B1/B2 thuộc W1, B3 thuộc W2, BA archived, BX không tồn tại. B1 có C1/C2/C3; thêm hai cột trùng tên/category; task ở đầu/giữa/cuối, một cột rỗng, một cột đủ capacity. Client S1/S2 subscribe B1; S3 subscribe B2; S4 outsider; hai tab cùng U1 để test quota/lifecycle.

- API mutation vẫn dùng access JWT, guard/quota/Turnstile tương ứng. Test xác thực/quyền dùng Auth/membership listener thật; chỉ test lỗi Auth mới mock/fault-inject dependency có chủ đích.
- Transaction test dùng PostgreSQL 17 Testcontainers + Flyway, `ddl-auto=validate`; không dùng H2 để kết luận rollback/lock giống production. WS test dùng `@SpringBootTest(webEnvironment = RANDOM_PORT)` và `WebSocketStompClient`/native STOMP; MockMvc chỉ kiểm REST.
- Không bọc toàn bộ test vào transaction mặc định rollback rồi chờ afterCommit. Chủ động `TransactionTemplate`, latch/barrier và commit/rollback; kiểm DB bằng transaction/connection mới.
- Chỉ mutation sau khi broker thực sự đăng ký subscription. Harness dùng barrier test-only sau xử lý subscribe hoặc probe có correlation; không chỉ đợi CONNECTED, không giả định simple broker có RECEIPT/ACK/replay như full broker.
- Collector gắn caseId/correlationId/eventId; clear sau fixture setup. Assert cả type/scope/JSON, số publication, từng client nhận/không nhận và DB/REST. Publication count và delivery count khác nhau khi nhiều người xem.
- Positive wait có deadline (ví dụ 3 giây trong IT, không dùng làm SLO); negative assertion có cửa sổ quan sát (ví dụ 500 ms sau barrier), kết hợp verify không đăng ký/gửi để tránh kết luận từ im lặng đơn thuần.
- Fake clock/scheduler cho expiry, latch cho cạnh tranh; không `Thread.sleep` tùy tiện. Cleanup client, subscriptions, clock, executor, meter state và fixture sau mỗi test.
- Mỗi hàng gom các biến thể được nêu thành parameterized cases. `Thực tế` ghi PASS/FAIL/BLOCKED + giá trị quan sát + thời gian/report; không đổi thành PASS chỉ vì code đã viết hoặc test tương tự T-014 từng pass.

**Lịch sử:** khi lập plan, toàn bộ case T-015 bên dưới là `Chưa chạy`. Sau triển khai, các test thực sự chạy được cập nhật theo evidence; ma trận còn lại vẫn `Chưa chạy` và không được suy ra pass từ code review.

### 6.2. Bộ contract và mapping

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| CT-01 / P1 | UT | Serialize/deserialize đủ 11 type ở mục 3.2 | JSON v1 đúng field/enum/UUID/UTC/null/empty-list; không serialize entity/lazy graph hoặc field nhạy cảm | PASS một payload CARD_CREATED deserialize qua WebSocket JSON trong IT; đủ 11 type và các biên JSON chưa chạy |
| CT-02 / P0 | UT | Mapping CARD_CREATED/MOVED/REORDERED và source/destination/vị trí | Phân biệt đúng action; boardId khớp topic; affected columns chính xác, không trùng | Chưa chạy |
| CT-03 / P0 | UT/IT | Capture delete task/column/board rồi clear persistence context | Tombstone còn đúng ID/scope trước delete; không query entity đã mất, không phát list cascade | Chưa chạy |
| CT-04 / P1 | UT | Sửa list/input object sau khi đăng ký callback; hai mutation khác nhau | Snapshot không đổi; eventId khác nhau; correlation giữ đúng request; mutation no-op không có snapshot publication | Chưa chạy |
| CT-05 / P1 | UT | Payload thiếu ID bắt buộc, refresh/type sai tổ hợp, list null/trùng, position âm, field ngoài allowlist | Validator/factory nội bộ reject invariant sai trước đăng ký; không tạo bản tin wire không hợp lệ | Chưa chạy |
| CT-06 / P1 | UT/WS | Payload hợp lệ lớn nhất và client nhận type/schemaVersion chưa biết | Board JSON <= 4 KiB theo cấu hình; client thử bỏ cách apply chưa biết, refetch an toàn, không crash/nhân đôi task | Chưa chạy |

### 6.3. Bộ nghiệp vụ card, column và board

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| FN-01 / P1 | IT/WS | Tạo task cột rỗng/có task/cột DONE; có và không optional fields | 201; 1 CARD_CREATED đúng task/cột/cuối list; 1 domain created, không domain status; S1/S2 nhận, S3 không | PASS một biến thể: task đơn giản trả 201 và subscriber cùng board nhận CARD_CREATED với boardId/columnId đúng; optional fields, nhiều client và phân loại domain-event chưa assert trong test realtime này |
| FN-02 / P1 | IT/WS | Replace từng title/description/priority/assignee/dueDate, nhiều field; xóa optional field bằng null | 200; 1 CARD_UPDATED, changedFields chính xác, refetch list/detail phản ánh dữ liệu chuẩn hóa | Chưa chạy |
| FN-03 / P0 | IT/WS | HTML/Unicode/CRLF trong dữ liệu task/cột hợp lệ | REST lưu bản sanitized theo API; wire chỉ IDs/field names, không có nội dung hoặc log injection | Chưa chạy |
| FN-04 / P0 | IT/WS | Delete task đầu/giữa/cuối; có comment/tag mapping | 204; 1 CARD_DELETED; cascade và positions đúng; GET task 404; modal client thử đóng, task không hồi sinh | Chưa chạy |
| FN-05 / P1 | IT/WS | Replace đúng dữ liệu hiện tại; GET/list task/board | 200; 0 publication, 0 audit mutation thêm do realtime | Chưa chạy |
| FN-06 / P0 | IT/WS | Move sang cột rỗng/có task ở đầu/giữa/cuối, vào DONE và reopen | 200; 1 CARD_MOVED; source/target đúng; task xuất hiện đúng 1 lần, positions hai cột khớp REST; domain status đúng | Chưa chạy |
| FN-07 / P1 | IT/WS | Move giữa hai cột khác ID nhưng trùng tên/category | CARD_MOVED và domain status vẫn phát một lần; không phân loại dựa tên/category | Chưa chạy |
| FN-08 / P0 | IT/WS | Reorder trong cột từ đầu xuống cuối, giữa lên đầu, cuối lên giữa | 1 CARD_REORDERED, affected một cột; không domain status; toàn bộ thứ tự sau refetch đúng | Chưa chạy |
| FN-09 / P1 | IT/WS | Move/reorder về vị trí cũ; riêng fixture position lỗi được normalize | No-op thuần: 0 publication; normalization thật: invalidation một lần cho scope được sửa, không bịa status change | Chưa chạy |
| FN-10 / P1 | IT/WS | Tạo cột; đổi tên/category cột rỗng; rename cột có task | 1 COLUMN_CREATED/UPDATED đúng trường; refetch columns/detail đúng; không tạo task domain event giả | Chưa chạy |
| FN-11 / P0 | IT/WS | Xóa cột rỗng ở đầu/giữa/cuối | 204; 1 COLUMN_DELETED; thứ tự cột còn lại đúng; cache cột đã mất được bỏ | Chưa chạy |
| FN-12 / P0 | IT/WS | Xóa cột có task hoặc đổi category cột có task | 409 COLUMN_NOT_EMPTY; DB giữ nguyên; 0 publication, không cascade task trái policy | Chưa chạy |
| FN-13 / P1 | IT/WS | Reorder cột qua các vị trí biên; cột trùng tên | 1 COLUMN_REORDERED; client lấy lại đúng thứ tự theo ID, không mất/nhân đôi cột | Chưa chạy |
| FN-14 / P1 | IT/WS | Patch column/board đúng giá trị cũ; reorder column về vị trí cũ | 0 publication nếu dữ liệu không đổi; normalize thật được kiểm riêng | Chưa chạy |
| FN-15 / P1 | IT/WS | Sửa board name/description; archive rồi restore | Mỗi thay đổi 1 BOARD_UPDATED đúng changedFields; GET board archived đúng; UI thử đổi chế độ đọc | Chưa chạy |
| FN-16 / P0 | IT/WS | Subscribe board archived và thử mutation task/column | Đọc/subscribe được với member; mutation 409 BOARD_ARCHIVED, 0 mutation MESSAGE | Chưa chạy |
| FN-17 / P0 | IT/WS | Xóa board đang có subscriber và task/cột/comment | 1 BOARD_DELETED sau commit, không event cascade con; client hợp lệ nhận tombstone, sau đó subscription được dọn | Chưa chạy |
| FN-18 / P1 | IT | Tạo board mới; gọi API read-only; automation giả lập qua service Board khi có đường phù hợp | Create-board không cần topic workspace; read 0 publication; helper automation đi cùng callback, không broadcast từ module khác; Git E2E chưa được tính pass | Chưa chạy |

### 6.4. Bộ transaction, rollback và regression T-014

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| TX-01 / P0 | UT/IT/WS | Đăng ký mutation, giữ transaction mở bằng latch trước commit | Chưa có MESSAGE/publication; commit xong mới gửi; connection mới đọc được trạng thái đã commit | PASS một happy path network sau commit; chưa kiểm cửa sổ trước commit/latch và DB read từ connection riêng |
| TX-02 / P0 | IT/WS | Parameterize rollback cho cả 11 type, kể cả tombstone | 0 realtime publication/delivery; DB/audit/positions rollback nguyên tử | PASS unit cho publisher rollback với BOARD_DELETED; chưa parameterize đủ 11 type hoặc assert DB/audit rollback |
| TX-03 / P0 | IT/WS | Lỗi flush/constraint, audit save và commit sau khi đăng ký callback | Không gửi thông báo thành công; client nhận lỗi theo API; DB không thay đổi dở dang | Chưa chạy |
| TX-04 / P0 | UT | Gọi publisher ngoài transaction hoặc synchronization inactive | Fail trước gửi, không fallback immediate broadcast | PASS unit: thiếu transaction bị reject, không gọi template |
| TX-05 / P0 | IT/WS | Hai create/move trong một outer transaction; lần lượt commit và rollback | Commit: đúng 2 snapshot/publication riêng; rollback: 0; không dùng entity trạng thái cuối cho cả hai | Chưa chạy |
| TX-06 / P0 | IT/WS | Template/serializer/channel ném lỗi sau commit | HTTP mutation vẫn 201/200/204 tương ứng, DB đã lưu; counter failure/log metadata đúng; không tự tạo lại task | Chưa chạy |
| TX-07 / P0 | IT | AI/Notification/bus T-014 lỗi; riêng callback realtime lỗi | Domain failure không làm mất đăng ký realtime; realtime failure không ngăn domain callback; không có realtime publication trùng | Chưa chạy |
| TX-08 / P1 | UT/IT | MeterRegistry/log appender lỗi; full regression domain events | Lỗi instrumentation không đổi response sau commit; domain create/status semantics, membership sync và audit count giữ đúng | Chưa chạy |

### 6.5. Bộ xác thực và bảo mật topic

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| AU-01 / P0 | WS | Browser-like handshake, CONNECT bằng access token hợp lệ | CONNECTED; principal UUID/expiry đúng, token không nằm URL/log; không yêu cầu custom HTTP header browser không gửi được | PASS một ca network: CONNECT access JWT, subscribe member, nhận CARD_CREATED sau REST commit; test `BoardColumnApiIntegrationTest` |
| AU-02 / P0 | WS | Thiếu/blank/sai prefix Authorization; header Authorization lặp | ERROR AUTH_REQUIRED/AUTH_INVALID rồi close; không subscribe/delivery; không giữ slot/socket rác | PASS một biến thể unit: duplicate Authorization bị `AUTH_REQUIRED`; thiếu/blank/sai prefix chưa chạy |
| AU-03 / P0 | WS | Sai chữ ký, malformed JWT/subject, thuật toán không cho phép, issuer sai | AUTH_INVALID; đóng phiên; không leak parser exception hoặc nội dung JWT | Chưa chạy |
| AU-04 / P0 | WS | Refresh token, token thiếu type, token expired; boundary expiry bằng clock | Bị từ chối; type access bắt buộc; expired trả AUTH_EXPIRED, không nhận dữ liệu | PASS unit cho refresh và expired; type thiếu/boundary clock/network chưa chạy |
| AU-05 / P0 | WS | CONNECT hợp lệ rồi chờ expiry, subscriber không gửi inbound tiếp | Outbound mới bị chặn ngay tại expiry; timer close/cleanup hữu hạn; socket idle không tiếp tục nhận | Chưa chạy |
| AU-06 / P1 | WS | Refresh qua REST rồi reconnect token mới; CONNECT lặp trên cùng socket | Phiên mới hợp lệ hoạt động; CONNECT lặp bị reject; không thay actor của phiên cũ hoặc nhân slot quota | Chưa chạy |
| AU-07 / P0 | WS | Origin hợp lệ dev/prod và Origin lạ/null/thiếu | Chỉ allowlist/cấu hình client riêng được phép; origin denied từ handshake; production không wildcard | Chưa chạy |
| AU-08 / P0 | WS | Frame SUBSCRIBE/SEND trước CONNECT, hoặc giả principal/userId/native actor header | Không truy cập dữ liệu; dùng identity server đã bind, không tin metadata client | Chưa chạy |
| AU-09 / P1 | WS/PERF | Vượt handshake/IP, socket/user, socket toàn instance; nhiều CONNECT song song | Quota atomic, lỗi hữu hạn, không vượt cap; cleanup trả slot đúng, không chặn vĩnh viễn sau disconnect | Chưa chạy |
| AU-10 / P0 | IT/WS | Log/debug/error khi JWT sai chứa token mẫu và PII | Không plaintext token/email/raw frame/stack trace ra client; error code/correlation đủ điều tra | Chưa chạy |
| ACL-01 / P0 | WS | U1/U2 subscribe B1; S3 chỉ subscribe B2; mutate B1 | Chỉ S1/S2 nhận B1; S3 không nhận B1 dù cùng W1; publish/delivery count khác nhau đúng | Chưa chạy |
| ACL-02 / P0 | WS | U3/U4 subscribe B1; U1 subscribe B3; BX không tồn tại | ACCESS_DENIED tổng quát, không registration/delivery, không tiết lộ board tồn tại qua error detail | PASS unit cho outsider bị từ chối; các membership/unknown-board biến thể khác chưa chạy |
| ACL-03 / P0 | WS | `/topic/boards`, `*`, `**`, prefix/suffix, traversal, encoded path, UUID sai | INVALID_DESTINATION/ACCESS_DENIED, không wildcard subscription đi vào broker | PASS unit cho traversal và UUID không hợp lệ; các wildcard/encoded path còn lại chưa chạy |
| ACL-04 / P0 | WS | SEND payload CARD_CREATED/MOVED vào topic/queue, `/app/boards/**`, path chưa đăng ký | Deny; không delivery giả và không DB mutation; không bypass qua application prefix | Chưa chạy |
| ACL-05 / P0 | IT/WS | Xóa membership U2 commit, sau đó U1 mutate B1 | S2 không nhận MESSAGE mới, bị close/dọn; S1 vẫn nhận; không dùng membership cached cũ | Chưa chạy |
| ACL-06 / P0 | IT/WS | Membership listener thiếu/nhiều decision/lỗi/DB timeout tại subscribe và outbound | Fail closed SERVICE_UNAVAILABLE; không delivery; timeout hữu hạn; response HTTP đã commit không bị đổi | Chưa chạy |
| ACL-07 / P1 | WS | Một user nhiều tab và nhiều board; UNSUBSCRIBE một subscription | Chỉ mapping cần xóa biến mất; tab/board khác tiếp tục đúng quyền; không lẫn sessionId/subscriptionId | Chưa chạy |
| ACL-08 / P0 | WS | Duplicate subscriptionId, subscriptionId/UNSUBSCRIBE giả thuộc phiên khác, destination header lặp | Reject theo policy, không ghi đè quyền hoặc dọn đăng ký người khác | Chưa chạy |
| ACL-09 / P1 | WS | Vượt 10 subscription/session hoặc subscribe-rate quota, mở/đóng liên tiếp | RATE_LIMITED, không tăng registry vượt cap; quota dọn đúng, không leak map | Chưa chạy |
| ACL-10 / P0 | IT/WS | Board bị xóa; U2 đã revoke; tombstone tới hai phiên | U1 còn quyền nhận BOARD_DELETED dù row board mất; U2 không nhận; dọn topic không cần query entity đã xóa | Chưa chạy |
| ACL-11 / P0 | IT/WS | REST mutation thiếu JWT/ngoài workspace/ID không tồn tại; target column ngoài board | 401/404 theo API; 0 mutation publication; client không override được board/workspace/actor routing | Chưa chạy |
| ACL-12 / P0 | IT/WS | Validation/unknown field/oversize/position/capacity/quota/Turnstile thiếu-sai-timeout | 400/413/409/429/403/503 theo guard hiện hữu; không mutation MESSAGE; giữ nguyên dữ liệu và không rò secret | Chưa chạy |

### 6.6. Bộ WebSocket network, concurrency và phục hồi

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| RT-01 / P1 | WS | Full app random port: CONNECT, subscribe effective, REST create/move | MESSAGE thật đúng JSON/type/topic sau commit, không chỉ verify `convertAndSend` | PASS một biến thể: random-port CONNECT/subscribe và REST create nhận CARD_CREATED JSON sau commit; move chưa chạy |
| RT-02 / P1 | WS | 0, 1 và nhiều subscriber cùng board; actor cũng subscribe | Mutation vẫn thành công khi không ai nghe; mỗi subscription hợp lệ nhận một delivery trong run không fault; actor không bị loại | Chưa chạy |
| RT-03 / P1 | WS | Connect qua Vite và Docker Nginx hiện hữu | Upgrade thành công, Origin đúng, MESSAGE đi qua proxy; heartbeat giữ phiên qua idle timeout cấu hình | Chưa chạy |
| RT-04 / P1 | WS | JSON content type/UTF-8, session close bình thường và transport error | Client parse đúng; close/error dọn registry, subscriptions/timers và quota | PASS parse JSON application/json qua client converter; cleanup close/error chưa assert |
| CN-01 / P0 | IT/WS | Hai actor move cùng task C1 → C2 với latch; delete/archive race move | Kết quả theo lock hợp lệ; không duplicate task/event; no-op sau lock không bịa transition; failure không publication | Chưa chạy |
| CN-02 / P0 | IT/WS | Hai create tranh một slot còn lại; giữ lock tới timeout | Một 201/1 CARD_CREATED, một 409/0; lock timeout lỗi Board hiện hữu; positions/capacity đúng | Chưa chạy |
| CN-03 / P0 | IT/WS | Delay callback đầu để mutation sau tới trước; rapid create/update/move/delete | Wire snapshot/scope đúng; client invalidation refetch hội tụ tới REST sau quiescence, không hồi sinh task đã xóa | Chưa chạy |
| CN-04 / P1 | WS/client | Server commit lúc client initial load/subscribe; event tới giữa fetch | Buffer/dirty generation gây refresh hậu subscribe/fetch; trạng thái cuối đúng, không bỏ sót cửa sổ race | Chưa chạy |
| CN-05 / P0 | WS/client | Delay response GET cũ, đổi board, thêm event khi fetch đang chạy | Response cũ không overwrite view mới; dirty scope được refetch đến trạng thái mới nhất | Chưa chạy |
| CN-06 / P1 | WS/client | Task > 100/cột, delete/move qua ranh giới page; filter/chi tiết đang mở | Invalidate mọi page liên quan, refetch page cần và detail; full-board collector đọc đủ trang, không kết luận từ page đầu | Chưa chạy |
| RS-01 / P1 | WS/client | Ngắt mạng S2, S1 mutate, reconnect/resubscribe bằng token hợp lệ | Không replay được tự động; full resync bù dữ liệu thiếu, S1/S2 cuối cùng khớp REST | Chưa chạy |
| RS-02 / P1 | WS/client | Tắt/mở backend trong lúc có người xem | Dữ liệu commit còn trong DB; kết nối mới/refetch hội tụ; không hứa event in-memory sống qua restart | Chưa chạy |
| RS-03 / P1 | WS/client | Lặp cùng eventId; gửi hai event khác ID cùng scope; burst 100 mutation | Dedup có giới hạn, coalesce dirty scopes; không nhân đôi task/cột hoặc tạo vòng refetch vô hạn | Chưa chạy |
| RS-04 / P1 | WS/client | Drop bản tin cuối nhưng socket còn sống; window focus/poll đối soát | Dữ liệu phục hồi ở kỳ refetch đề xuất <= 30 giây + thời gian GET; ghi rõ không đạt realtime < 1 giây khi fault | Chưa chạy |
| RS-05 / P1 | WS | Client không đọc/đọc rất chậm, outbound buffer đầy | Send/buffer limit đóng slow session, resource được thu hồi; client khỏe vẫn nhận, mutation không chờ vô hạn | Chưa chạy |
| RS-06 / P1 | IT/WS | Fill inbound/outbound queue, broker dừng, channel reject/timeout | Failure/drop counter tăng, readiness phù hợp; không unbounded queue hoặc false success delivery; DB commit giữ đúng | Chưa chạy |
| RS-07 / P0 | WS | STOMP > limit, header/token lớn, frame fragmented, body SEND không được phép | Reject/close trước xử lý nghiệp vụ; không bypass size bằng fragment, không tăng heap vô hạn hoặc log payload | Chưa chạy |
| RS-08 / P1 | WS | Socket handshake không CONNECT; heartbeat dừng; disconnect event lặp | Hết timeout đóng và dọn, cleanup idempotent; active counts không âm/không leak | Chưa chạy |
| RS-09 / P1 | WS | Board-delete tombstone rồi event cũ tới muộn; reconnect board đã mất/ngoài quyền | Không hồi sinh view; refetch 404/denied dừng resubscribe board; không loop lỗi | Chưa chạy |
| RS-10 / P2 | WS/PERF | Churn 1.000 connect/subscribe/disconnect, shutdown/restart context | Registry/count/timer về baseline; không thread/connection leak, graceful shutdown trong timeout cấu hình | Chưa chạy |

### 6.7. Bộ observability, performance và nghiệm thu

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| OP-01 / P1 | IT | Đọc counters/timers trước/sau commit, rollback, denied, send failure | Delta đúng; rollback không publication; publication/delivery/denied không lẫn; tag values hữu hạn | Chưa chạy |
| OP-02 / P1 | IT/WS | Hai actor/correlation khác nhau, callback chạy thread pool | MDC/event/log nối đúng request, restore/clear context; không nhiễm user/request trước | Chưa chạy |
| OP-03 / P0 | IT | Log và ERROR với dữ liệu nhạy cảm giả, exception chứa SQL/token | Không lộ nội dung/stack/secret; metadata đủ điều tra; audit nghiệp vụ không nhân đôi | Chưa chạy |
| OP-04 / P1 | IT/WS | Broker/DB down; 0 subscriber; một client lỗi | Readiness phản ánh subsystem, liveness ổn định; 0 subscriber không unhealthy; client lỗi không ảnh hưởng toàn app | Chưa chạy |
| OP-05 / P1 | IT | Invalid config origin/capacity/timeout/heartbeat; prod thiếu allowlist | Fail startup rõ ràng hoặc reject cấu hình thiếu, không tự mở `*`/disable Auth; không crash vì vòng phụ thuộc scheduler | Chưa chạy |
| OP-06 / P0 | Build/review | Boundary task, rà import/package/Gradle và event bus config | 0 impl→impl; common/api dependency đúng; không đổi membership control event sang async; app chỉ wiring | PASS: `backend/gradlew check` gồm `:verifyModuleBoundaries`; code cross-module dùng public API/common và typed membership event |
| OP-07 / P1 | Build/IT | Full `check`, Auth/Board API/persistence/Flyway/domain event regression | 0 compile/fail/error; required PostgreSQL/WS suite thật sự executed, skipped không được tính pass | PASS: `backend/gradlew check` BUILD SUCCESSFUL; auth, board, app tests đều chạy, bao gồm Testcontainers migration/schema và WS integration; 0 failed/skipped trong các suite báo cáo |
| OP-08 / P1 | Build/review | Contract docs, frontend build/lint nếu có thay đổi UI/harness nằm frontend | Command/reports đúng scope; không đánh dấu UI T-018 pass khi mới dùng Java/client thử | PASS theo scope backend: plan và contract evidence được cập nhật; không đổi frontend nên frontend build/lint không chạy |
| PF-01 / P1 | PERF | Baseline/candidate: 20 active clients, 5 board, 10 mutation/giây trong 3 phút sau warm-up 30 giây | CRUD P95 < 200 ms; commit→receipt và commit→refetch đo riêng; mục tiêu P95 end-to-end < 1 giây, báo cả max; 0 loss/duplicate ngoài fault | Chưa chạy |
| PF-02 / P1 | PERF | Fan-out 50 subscriber/board, burst create/move/reorder; giữ outbound quyền thật | Đo auth DB QPS/pool wait, GET fan-out, queue/CPU/heap; vẫn đạt profile nghiệm thu đã chốt hoặc điều chỉnh capacity trước release | Chưa chạy |
| PF-03 / P1 | PERF | Slow clients + outsider/revoke song song với workload bình thường | Không rò dữ liệu; healthy clients đạt mục tiêu profile, error/drop phân loại rõ; không tính 409 có chủ đích là lỗi kỹ thuật | Chưa chạy |
| PF-04 / P2 | PERF | Soak 30 phút, churn/heartbeat, nhiều task và refetch phân trang | Resource ổn định, không leak; báo latency/max/loss/cleanup và capacity thực tế, không suy SLA production từ local | Chưa chạy |
| UAT-01 / P1 | UAT | Hai STOMP client độc lập cùng board, một client board khác; CRUD/move/reorder đủ mapping | Cùng board nhận đúng; khác board không nhận; snapshot/refetch khớp DB và payload đủ cho T-018 | Chưa chạy |
| UAT-02 / P1 | UAT | 2 browser với UI thật khi T-016/T-018 sẵn sàng: kéo/sửa/xóa, mở modal | Không F5, không duplicate, detail/count/filter áp dụng đúng theo scope; ghi riêng evidence UI và backend | Chưa chạy — phụ thuộc T-018 |
| UAT-03 / P1 | UAT | Browser disconnect/reconnect/expiry/đổi board/thu hồi quyền | Hết phiên được xử lý, resync đúng, không nhận board cũ/ngoài quyền; không reconnect storm | Chưa chạy — UI phụ thuộc T-018; harness phải chạy trong T-015 |
| UAT-04 / P1 | UAT | Archive/restore/delete board khi người khác đang xem | Trạng thái đọc/đóng board đúng; tombstone không mất do query row đã xóa; không tạo dữ liệu giả | Chưa chạy |
| UAT-05 / P1 | Review | Đối chiếu card AC, contract với T-018/T-024, giới hạn mất bản tin/multi-instance | Hai AC có evidence; Git/webhook/UI chưa có không được nhận pass; backlog phần ngoài scope rõ | Chưa chạy |

### 6.8. Cách đo hiệu năng và lưu bằng chứng

- Chốt phần cứng, JVM/JDK17, PostgreSQL17, CPU/RAM, network/proxy và config baseline/candidate giống nhau. Tạo đủ task/column/capacity để workload không vô tình thành toàn 409 hoặc no-op; move phải đổi cột/vị trí thực.
- Có thể override quota/capacity hoặc dùng Turnstile test fixture để đo riêng CRUD, nhưng phải ghi rõ override. ACL/membership vẫn chạy thật; không kết luận profile đã mock authorization là throughput production. Không gọi LLM/email thật trong đường sync.
- Gắn mốc commit bằng instrumentation test/performance phía server, receipt và refetch-complete ở collector cùng clock hoặc clock đã đồng bộ; tránh lấy HTTP response làm thời điểm commit vì MESSAGE có thể tới trước response. Ghi riêng handler publication latency và thời gian đến UI khi có browser.
- Báo P50/P95/P99/max, throughput, số mutation commit/publication/delivery dự kiến/thực nhận, denied/dropped, GET/giây, DB pool wait, queue depth, heap/CPU. Gốc “< 1 giây” cần report tỉ lệ dưới 1 giây và outlier; cách dùng P95 trong plan là đề xuất thước đo, không xóa bỏ outlier khỏi evidence.
- PF-01 là profile acceptance khởi điểm, PF-02 kiểm fan-out và quyết định capacity. Nếu không đạt, chỉnh batching/giới hạn/DB path rồi đo lại; không nâng ngưỡng để hợp thức hóa run lỗi. Fault-injection chạy riêng, không trộn vào báo cáo healthy-path.
- Chỉ đổi trạng thái `Thực tế` khi có evidence theo case. FAIL ghi observed khác expected, issue, cách tái hiện; BLOCKED ghi dependency/môi trường thiếu. N/A ghi lý do và tiêu chí thay thế. Test aggregate pass không tự biến mọi hàng thành pass.

### 6.9. Lệnh kiểm tra khi triển khai

Chạy từ `backend/` trên Windows, cần JDK17 và Docker Desktop/Testcontainers. Các test class mới được tạo theo bước P2–P7; lệnh dưới là kế hoạch, chưa được thực thi trong lượt tạo tài liệu.

```powershell
.\gradlew.bat :board-impl:test :auth-impl:test :app:test
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat check
```

Nếu thêm contract unit test vào module khác thì thêm `:<module>:test` tương ứng; root `check` là regression gate. Dùng focused tests khi phát triển, đọc executed/up-to-date/skipped trong report; chỉ rerun khi cần evidence mới hoặc có thay đổi. Chỉ khi sửa frontend, chạy thêm từ `frontend/`:

```powershell
npm run build
npm run lint
```

| Evidence | Cần lưu |
|---|---|
| Automated | Commit, config, giờ chạy GMT+7, command/exit code, JUnit XML và HTML dưới `backend/<module>/build/test-results/test/` và `build/reports/tests/test/` |
| Contract/API/WS | CaseId, request đã bỏ JWT/Turnstile token, expected/observed JSON, eventId/correlation, subscription effective barrier và timeline commit→receipt |
| Transaction/concurrency | DB assertion qua connection mới, latch/barrier, số commit/rollback/publication/delivery, positions/capacity |
| Security/lifecycle | Error code đã sanitize, số session/subscription trước/sau revoke/expiry/disconnect; không đính raw access token |
| Metrics/performance | Counter delta, histogram/percentiles, workload/overrides, DB QPS/pool, queue/resource, tỉ lệ nhận và refetch hoàn tất |
| Manual/review | 2 client/2 browser evidence theo scope, PR/reviewer, mapping AC→case; checklist Trello chỉ cập nhật theo kết quả thật |

## 7. Tổng hợp thực tế sau triển khai (09/10/2026)

| Hạng mục | Thực tế 09/10/2026 |
|---|---|
| Đọc tài liệu và source | Đã đối chiếu task T-015/T-018, các plan liên quan, architecture/product/master plan, Board/Auth/transport/proxy/test setup |
| Card Trello T-015 | Đã đọc trực tiếp; card vẫn Backlog, hai AC chưa tick. Chuyển sang In Progress nhận HTTP 401 `unauthorized card permission requested`; chưa cập nhật card |
| Implementation backend | Đã thêm contract versioned, JWT STOMP CONNECT, board membership authorization cho subscribe/outbound, registry/quota/cleanup, publisher after-commit, event cho board/column/task mutation, cấu hình origin/heartbeat/queue/limits và metrics/log |
| WebSocket end-to-end | PASS: integration test random-port dùng native STOMP, access JWT, member subscription và nhận CARD_CREATED sau REST commit; assertions kiểm tra type/boardId/columnId |
| Test thực thi trong `backend/gradlew check` | PASS: auth-impl, board-impl, app; 3 test publisher, 3 test board STOMP authorization, 2 test STOMP authentication; BoardColumnApiIntegrationTest 17, migration 6, schema 55; `:verifyModuleBoundaries` qua check |
| Ma trận test | 91 case group tại mục 6.2–6.7; phần lớn vẫn chưa chạy. Chỉ các dòng có actual PASS/partial evidence được tính; performance, multi-client negative, reconnect, expiry network và UAT chưa chạy |
| Runtime test/benchmark/UAT | Chưa chạy benchmark/UAT và chưa xác nhận mục tiêu P95 < 1 giây hoặc CRUD P95 < 200 ms |
| Trạng thái bàn giao | Code và plan đã cập nhật; T-015 chưa đủ evidence để đóng theo gate mục 5. Plan là backlog testing và hướng dẫn nghiệm thu tiếp theo |
