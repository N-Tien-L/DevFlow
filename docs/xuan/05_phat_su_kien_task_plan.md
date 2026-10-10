# T-014 — Plan triển khai phát sự kiện khi task thay đổi

**Task gốc:** [05_phat_su_kien_task.md](05_phat_su_kien_task.md).

**Trello:** [T-014 — Implement Event Publisher for task.created & task.status_changed](https://trello.com/c/fYuN9nAn/20-t-014-implement-event-publisher-for-taskcreated-taskstatuschanged).

**Milestone:** 1 / Sprint 2. **Owner:** Xuân Lê. **Module chính:** `board-impl`, `common`. **Branch triển khai theo card:** `feature/T-014-task-events-publisher`.

**Phụ thuộc:** T-013 và hợp đồng event thống nhất với Tiến. **Bàn giao:** T-015 (broadcast realtime), T-024 (chuyển cột từ Git), AI/Notification và T-041 (tích hợp liên module).

**Ngày lập:** 08/10/2026 (GMT+7). **Cập nhật:** Đã triển khai T-014 trên branch `feature/T-014-task-events-publisher`, dựa trên source T-013 tại `ea5f785`; code chưa commit. Card được đọc trực tiếp khi bắt đầu ở Backlog; thao tác chuyển card sang In Progress nhận HTTP 401 nên trạng thái Trello chưa đổi. Bảng test ghi kết quả hiện có; các ca còn lại vẫn để `Chưa chạy`.

## 1. Mục tiêu, căn cứ và hiện trạng

### 1.1. Mục tiêu và nguồn yêu cầu

Khi task được tạo hoặc chuyển sang cột khác và dữ liệu đã commit, Board phát đúng typed event để AI/Notification nhận biết thay đổi. Thao tác thất bại không tạo thông báo về một thay đổi chưa tồn tại; reorder không bị hiểu nhầm thành chuyển trạng thái.

| Nguồn đã đọc | Nội dung áp dụng |
|---|---|
| [Task gốc](05_phat_su_kien_task.md), card Trello T-014 | Phát hai loại event, đầy đủ payload, skeleton listener nhận và ghi log |
| [ARCHITECTURE](../ARCHITECTURE.md), mục 2–4, 6, 9.2, 10 | Modular Monolith, quyền sở hữu dữ liệu, event catalog, NFR và module boundary |
| [PRODUCT_SPEC](../PRODUCT_SPEC.md), mục 5.1, 5.2, 8 | Kanban, tự chuyển task theo Git, thông báo, bảo mật, riêng tư và CRUD P95 < 200 ms |
| [IMPLEMENTATION_PLAN](../IMPLEMENTATION_PLAN.md), Phase 2/3/5 | Thứ tự T-013 → T-014 → realtime; T-024 dùng cause GIT; kiểm thử liên module |
| [Plan T-013](04_api_quan_ly_va_di_chuyen_task_plan.md), [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) | Transaction/lock, quyền workspace, audit, testing và nghiệm thu |
| `.agents/rules/coding-conventions.md`, `module-boundaries.md`, `git-workflow.md` | Naming, phân lớp, workflow branch/review; giao tiếp qua event theo chỉ dẫn workspace hiện hành |

**Hai acceptance criteria gốc trên Trello:**

- Bắn đúng typed event với đầy đủ payload lên Spring Event Bus.
- Skeleton listener nhận được event và ghi log kiểm chứng.

Các quyết định về payload bổ sung, thời điểm publish, metrics, isolation và test trong tài liệu này là **đề xuất triển khai/diễn giải nghiệm thu**, không phải checklist mới đã ghi lên Trello. Card gọi nơi tích hợp là `BoardService`; source hiện tại đặt mutation trong `TaskManagementService`, vì vậy tích hợp vào service thực sự ghi task.

### 1.2. Hiện trạng đọc từ code

Đường dẫn dưới đây tính từ root repository; đây là kết quả đọc source, không phải bằng chứng test đã pass.

| Thành phần | Hiện trạng | Hệ quả cho T-014 |
|---|---|---|
| `backend/common/.../event/DevFlowEvent.java` | Interface có `eventType()` và `occurredAt()` | Record event **implements** interface; không áp dụng ví dụ cũ `extends DevFlowEvent`/constructor `source` |
| `TaskCreatedEvent` trong common | Record: `taskId`, `projectId`, `description`, `occurredAt`; có overload tự lấy `Instant.now()` | Tái sử dụng typed event, giữ `projectId` nghĩa là workspace; còn thiếu board/column routing |
| `TaskStatusChangedEvent` trong common | Record: `taskId`, `oldStatus`, `newStatus`, `cause`, `occurredAt`; cause MANUAL/GIT | Status được Javadoc mô tả là tên cột; thiếu scope và ID cột để phân biệt cột trùng tên |
| `EventTypes` trong common | Đã có `task.created`, `task.status_changed` | Không tạo event type trùng hoặc đổi wire name |
| `TaskManagementService` trong board-impl | Create/move có `@Transactional`, kiểm tra quyền, khóa board, flush và audit; chưa phát hai task event | Thêm publication vào mutation hiện hữu, không tạo CRUD song song |
| `BoardService` trong board-impl | Read-only implementation của `BoardApi` | Không đặt publisher trong truy vấn đọc hoặc chuyển CRUD sang đây chỉ để giống card |
| `TaskEntity`, `ColumnEntity` | Task gắn column; trạng thái suy từ column; category TODO/IN_PROGRESS/IN_REVIEW/DONE | Không có field status riêng hoặc endpoint complete; chuyển vào cột DONE là hoàn thành |
| `AiEventListeners`, `NotificationEventListeners` | Đã có skeleton `@EventListener` cho hai task event và log; chưa tạo thông báo/AI thật | Tận dụng, kiểm chứng wiring; cần chỉnh log/isolation khi mở rộng contract |
| `GitActivityEventListener` | Chỉ log/TODO, chưa chuyển task | Chừa hợp đồng GIT cho T-024, không tuyên bố Git automation đã hoạt động |
| `WorkspaceMembershipPermissionService` | Publish event kiểm tra quyền đồng bộ; cần chính xác một quyết định, thiếu/lỗi trả 503 | Không đổi event bus toàn app sang async hoặc nuốt lỗi toàn cục |
| `TaskController`, `TaskMutationRequestGuard` | JWT actor, validation, quota, Turnstile trước mutation; request-size guard đã có | Giữ nguyên hàng rào T-013; request bị chặn phải có 0 task event |
| Test hiện hữu | `app` có MockMvc + PostgreSQL 17 Testcontainers/Flyway; smoke test dùng H2 | Test commit/rollback/lock phải dùng PostgreSQL, không dùng H2 để kết luận tương đương |

### 1.3. Phạm vi và giới hạn

**Trong T-014:** chốt/mở rộng contract common có kiểm soát, publisher sau commit cho create và cross-column move, skeleton listener/log/metrics, bảo toàn T-013 và bộ test. Việc chỉnh hai task handler sẵn có ở AI/Notification là phối hợp integration với Tiến, không thêm phụ thuộc `impl -> impl`.

**Ngoài T-014:** WebSocket/STOMP thực tế, UI, notification delivery, gọi LLM, Git webhook/auto-transition, event cho update/delete/assignment/reorder, retry bền vững/outbox/broker. T-015 vẫn phải bao phủ sửa/xóa/reorder và thay đổi column bằng cơ chế riêng; chỉ hai event T-014 không đủ làm toàn bộ realtime board.

## 2. Kế hoạch nghiệp vụ — luồng bằng ngôn ngữ

### NV-01. Tạo task

1. Thành viên tạo task trong một cột thuộc board đang hoạt động của workspace được phép truy cập.
2. Hệ thống kiểm tra dữ liệu, quyền, người phụ trách và giới hạn số task, rồi lưu task ở cuối cột cùng audit.
3. Chỉ sau khi lưu toàn bộ thành công, hệ thống báo một lần rằng task mới đã được tạo, kèm định danh task, board/workspace và mô tả đã chuẩn hóa.
4. AI và Notification nhận cùng thông tin; trong T-014 chỉ ghi log kiểm chứng. Người dùng nhận response tạo task như T-013, không phải đợi AI phân tích hoặc gửi email.
5. Tạo trực tiếp vào cột DONE vẫn chỉ là một lần tạo task, không phát thêm sự kiện chuyển trạng thái từ một trạng thái chưa tồn tại.

### NV-02. Chuyển task sang cột khác

1. Thành viên chuyển task tới một cột khác trong cùng board, ở vị trí hợp lệ.
2. Hệ thống ghi nhớ cột/trạng thái trước thao tác, kiểm tra cột đích, rồi lưu cột mới và thứ tự ở cả hai cột trong một transaction.
3. Sau khi lưu thành công, phát một sự kiện đổi trạng thái với cột cũ, cột mới và nguyên nhân thao tác thủ công.
4. Chuyển sang DONE là hoàn thành; mở lại từ DONE về TODO/IN_PROGRESS cũng là một thay đổi và phải phát event tương ứng.
5. Hai cột khác nhau có cùng tên hoặc cùng category vẫn là một lần chuyển cột và phát event. ID cột là căn cứ, không so sánh tên/category để bỏ qua.

### NV-03. Đổi thứ tự và thao tác không làm thay đổi trạng thái

- Kéo lên/xuống trong cùng cột: lưu thứ tự theo T-013, không phát `TaskStatusChangedEvent`.
- Kéo về đúng cột/vị trí hiện tại: không phát hai event T-014.
- Chỉ sửa title/description/priority/assignee/dueDate, đọc task hoặc xóa task: không phát hai event này. Đổi người phụ trách không bị gán nhầm thành `task.created`.
- Đổi tên/category hoặc reorder column không thuộc publisher task của T-014. Việc category đổi có thể thay đổi trạng thái suy ra của nhiều task là vấn đề bàn giao cho task quản lý column; không tự phát hàng loạt event trong phạm vi này.

### NV-04. Thao tác bị từ chối hoặc lưu thất bại

1. Request sai dữ liệu, thiếu quyền, board archived, cột đích ngoài board, quota/bot protection thất bại hoặc tranh chấp khóa bị từ chối theo API hiện hữu.
2. Nếu transaction rollback do task, audit hoặc commit lỗi, trạng thái và thứ tự vẫn như trước thao tác.
3. AI/Notification không nhận event thành công của thao tác đó. Client nhận lỗi hữu hạn theo hợp đồng API, không nhận nội dung exception/database.

### NV-05. Listener gặp lỗi sau khi task đã lưu

1. Task đã commit tiếp tục là kết quả hợp lệ dù skeleton consumer gặp lỗi xử lý.
2. Ghi nhận thất bại bằng metadata/log/metric; không biến một task đã tạo thành response lỗi khiến client tạo lại vì tưởng chưa lưu.
3. Mỗi task consumer tự cô lập lỗi xử lý để consumer còn lại vẫn được gọi. T-014 chưa tự động retry delivery và chưa bảo đảm event sống qua restart.

### NV-06. Hai người thao tác gần nhau và request lặp

- Hai mutation cùng board dùng cơ chế khóa T-013, đọc trạng thái mới nhất dưới lock. Mỗi thay đổi đã commit tạo một event có snapshot riêng.
- Nếu hai người cùng yêu cầu chuyển task A → B, người sau thấy task đã ở B thì chỉ reorder/no-op trong B, không phát một lần đổi cột thứ hai.
- Gửi lại POST create là một lần tạo mới nếu API chưa có idempotency key; có thể tạo hai task và hai event. Không hứa chống lặp request bằng cách ngăn event.
- Hai lần chuyển khác nhau A → B rồi B → C phải có hai event; consumer không được suy ra thứ tự toàn cục chỉ từ thời điểm nhận.

### NV-07. Bàn giao tự động hóa Git

T-024 nhận Git event, kiểm tra policy rồi yêu cầu Board tự chuyển task qua luồng mutation nội bộ. Board là nơi phát `TaskStatusChangedEvent` sau khi chính Board đã commit, với cause GIT. GitCI không phát lại một event task thành công cho cùng lần chuyển; T-014 chuẩn bị hợp đồng/cause, chưa triển khai webhook hay tự chuyển cột.

## 3. Kế hoạch kỹ thuật — thiết kế và các yếu tố cần suy xét

### 3.1. Hợp đồng event đề xuất

Giữ hai record trong `io.devflow.common.event`, giữ interface `DevFlowEvent` và wire name hiện hữu. Không truyền entity, repository, principal, lazy proxy hoặc request vào event. Payload là snapshot bất biến gồm UUID, String, enum và Instant.

| Trường | `TaskCreatedEvent` | `TaskStatusChangedEvent` | Quy tắc |
|---|---|---|---|
| `taskId`, `boardId` | Có | Có | UUID bắt buộc, lấy từ task/board đã resolve; bổ sung boardId |
| Scope workspace | Giữ `projectId` | Bổ sung `workspaceId` | `projectId == board.workspaceId`, không phải boardId; không đổi nghĩa accessor cũ |
| Cột | Bổ sung `columnId` | Bổ sung `sourceColumnId`, `destinationColumnId` | UUID lấy từ dữ liệu Board; status event yêu cầu hai ID khác nhau |
| Nội dung | Giữ `description` | Không mang nội dung task | Nullable/rỗng được phép; lấy bản đã sanitize và giới hạn theo T-013 |
| Trạng thái hiển thị | Không cần | Giữ `oldStatus`, `newStatus` | Snapshot tên cột cũ/mới, đúng nghĩa contract đang có; không tự đổi thành category |
| Category | Không cần | Bổ sung `oldStatusCategory`, `newStatusCategory` | String thuộc TODO/IN_PROGRESS/IN_REVIEW/DONE; không import enum của board-api/impl vào common |
| `cause` | Không cần | Giữ MANUAL/GIT | REST move luôn MANUAL; client không tự truyền GIT |
| Metadata | Bổ sung `eventId`, `correlationId`, `actorId`; giữ `occurredAt` | Tương tự | eventId mới mỗi thay đổi, correlation dùng context đã chuẩn hóa; actor của REST từ AuthenticatedActor |

`occurredAt` là thời điểm tạo snapshot để chờ publish trong transaction; không phải bằng chứng commit hoặc version tăng dần. Dùng `Clock` có thể inject vào publisher để test thời gian; Instant UTC. Nếu giữ constructor tiện ích lấy `Instant.now()` hiện hữu, publisher production vẫn sử dụng clock đã inject.

**Tương thích và phối hợp:**

1. Liệt kê mọi constructor/accessor đang dùng bằng tìm kiếm source trước sửa; hiện AI/Notification chỉ đọc accessor, không nên đổi tên chúng.
2. Mở rộng canonical record constructor và factory đầy đủ cho Board. Nếu giữ overload cũ để tương thích source, đánh dấu deprecated và ghi rõ các trường routing mới có thể null; không dùng overload này trong publisher production.
3. Producer kiểm tra đầy đủ payload trước đăng ký callback; consumer cũ vẫn đọc được các trường cũ. Legacy event không đủ routing không được broadcast hoặc gây side effect cần workspace; ghi metric/skip rõ ràng, không query chéo module để đoán scope.
4. Không thể vừa bắt buộc mọi trường mới trong canonical constructor vừa để overload cũ truyền null mà không có thiết kế tương thích riêng. Chọn validation đầy đủ tại factory/publisher production, bổ sung contract test cho cả hai đường.
5. Cập nhật event Javadoc và catalog mục 4 của Architecture khi triển khai; ghi Board là nơi xác nhận task mutation kể cả nguyên nhân Git. Không thay đổi signature mọi loại event khác trong common chỉ để thêm metadata cho T-014.

### 3.2. Thời điểm publish và transaction

**Chọn cho T-014:** helper nội bộ Board `TaskEventPublisher` đăng ký callback `TransactionSynchronization.afterCommit`, rồi mới gọi `ApplicationEventPublisher.publishEvent(snapshot)`. Giữ các task consumer sẵn có là `@EventListener` để chúng nhận event đã commit.

```text
HTTP -> JWT / validation / quota / Turnstile
     -> TaskManagementService @Transactional
        -> membership event đồng bộ -> khóa board -> kiểm tra domain
        -> snapshot cột cũ -> mutate task/order -> flush -> audit flush
        -> tạo snapshot event bất biến -> đăng ký afterCommit
     -> COMMIT thành công -> publish typed task event -> skeleton consumers
     -> response 201 (create) / 200 (move)

Lỗi trước COMMIT / rollback -> không publish task event
```

- `saveAndFlush` chỉ flush SQL, chưa bảo đảm commit. Không publish ngay sau flush hoặc trong controller.
- Helper chỉ chấp nhận transaction đang hoạt động **và** synchronization active; nếu thiếu thì fail trước ghi thành công, không fallback publish ngay.
- Mỗi create đăng ký đúng một callback; move chỉ đăng ký khi `sourceColumnId != destinationColumnId`, không dùng biến `changed` của reorder làm điều kiện event.
- Chụp source ID/tên/category **trước** `task.moveTo(destination)`; snapshot đích và scope từ dữ liệu được khóa. Lấy metadata một lần và giữ xuyên callback.
- Đăng ký sau khi task/order và audit đã flush; outer transaction rollback hoặc commit thất bại vẫn không publish. Callback thuộc transaction ngoài cùng nếu service tham gia transaction có sẵn.
- Không giữ tham chiếu entity trong lambda rồi đọc lại sau commit. Hai mutation liên tiếp không được làm payload của event đầu bị đổi theo task mới nhất.
- Không thêm `@TransactionalEventListener(AFTER_COMMIT)` vào consumer của event được phát từ callback này: dễ gắn vào transaction đã hoàn tất và bỏ lỡ event. Chỉ dùng một cơ chế kiểm soát commit.
- Nếu future listener cần ghi database, mở transaction mới qua bean/proxy `REQUIRES_NEW`; không dựa vào tài nguyên còn gắn với transaction đã commit. Skeleton T-014 chỉ log/metrics.
- Callback bắt lỗi dispatch có thể phục hồi, ghi metric/log tối thiểu và không ném lại thành lỗi HTTP sau commit; không dùng catch toàn cục để che lỗi nghiệp vụ trước commit.

### 3.3. Listener isolation và event bus dùng chung

Giữ event multicaster mặc định, không cài global async executor/error handler để chữa lỗi task event. Membership request phải xử lý ngay trên thread gọi, chính xác một quyết định và fail closed như T-013.

Hai task handler AI/Notification cần bọc riêng phần xử lý bằng guard/catch exception, ghi lỗi theo eventId và trả về để bus tiếp tục gọi consumer khác. Callback Board bắt exception thoát ra là lớp bảo vệ cuối, **không tự bảo đảm fan-out đầy đủ** nếu một listener mới chưa có guard làm dispatch dừng; trường hợp đó phải ghi thất bại, test và sửa listener.

T-014 không thêm công việc mạng vào callback. Khi AI/Notification thật được triển khai, tách công việc nặng sang worker có queue hữu hạn, timeout, retry/circuit breaker cho network và quản lý MDC; không dùng CallerRunsPolicy để đẩy LLM/email trở lại request thread. Chính sách từ chối queue và delivery bền vững phải được thiết kế ở task consumer tương ứng.

### 3.4. Bảo mật, riêng tư và lỗi

- Tái sử dụng toàn bộ actor/membership/board lock/archive/assignee validation, quota và Turnstile của T-013. EventId/cause/actor/scope do server tạo; không thêm field cho client giả mạo.
- Publisher không phải một public endpoint mới. Internal entry point cho Git sau này vẫn phải có kiểm tra policy/scope riêng; không dùng nó để bypass REST guard.
- Log không dump `event.toString()` vì record chứa description/tên cột có thể mang PII hoặc ký tự xuống dòng. Chỉ log loại event, UUID định danh, cause, category và exception class đã giới hạn; không description/title/tên cột/email/token/IP/payload exception message.
- Hạn chế metric tags ở `eventType`, `consumer`, `outcome`; UUID/correlation/actor không làm label Prometheus gây cardinality cao.
- Payload description chỉ phục vụ consumer có nhu cầu; không thêm title/comment/secret vào status event. EventId/actorId cũng là dữ liệu truy vết cần kiểm soát quyền truy cập và retention log theo policy dự án.
- Lỗi trước commit giữ RFC 7807/ProblemDetail từ T-013. Lỗi xử lý listener sau commit chỉ là lỗi delivery, không báo rằng task mutation thất bại.

### 3.5. Observability đề xuất

| Điểm đo | Nội dung | Ý nghĩa |
|---|---|---|
| `devflow.task.events.dispatch` Counter | eventType, outcome=success/failure | Một lần gọi dispatch sau commit; success không đồng nghĩa thông báo/AI thật đã hoàn tất |
| `devflow.task.events.consume` Counter | eventType, consumer=ai/notification, outcome=success/failure/skipped | Mỗi handler tự xác nhận xử lý/skip/lỗi |
| `devflow.task.events.dispatch.duration` Timer | eventType, outcome | Thời gian synchronous fan-out của skeleton, ảnh hưởng trực tiếp độ trễ HTTP |
| Log dispatch/consumer | eventId, taskId, boardId, workspace/projectId, cause, correlationId, actorId khi có | Nối được request → mutation → consumer mà không ghi nội dung task |

Rollback không tăng dispatch/consume success. Nếu publisher không có HTTP MDC (test/internal), tạo correlation UUID một lần và dùng cùng ID trong mọi log của event. Nếu callback/worker đổi MDC, luôn khôi phục context cũ trong `finally`; không để thông tin người trước nhiễm sang request sau. Không thêm health check giả cho event bus in-memory; dùng metrics lỗi delivery và health hiện hữu.

### 3.6. Concurrency, delivery guarantee và rủi ro

| Yếu tố | Quyết định / giới hạn |
|---|---|
| Khóa board | Giữ giao thức pessimistic lock T-013; capture trạng thái cũ sau khi lấy lock, không đọc snapshot từ client |
| Thứ tự dispatch | Lock serialize dữ liệu nhưng callback của hai thread có thể đến consumer khác thứ tự commit. Không hứa FIFO toàn board hoặc dùng occurredAt làm version |
| Event count | Một event cho mỗi mutation thuộc phạm vi đã commit; consumer fan-out không được tính là nhiều event nghiệp vụ |
| Request lặp | POST chưa idempotent; move tới cột hiện tại không phát status event. Không gọi cơ chế này là exactly-once |
| Crash sau commit trước callback | Event có thể mất vì chỉ ở RAM; eventId không biến delivery thành bền vững |
| Listener lỗi | Guard từng consumer + metric; không retry trong T-014, không rollback task đã commit |
| Multi-instance | Spring Event Bus chỉ trong process hiện tại; chưa có delivery tới instance khác |
| Schema | Không cần migration chỉ để phát snapshot; không sửa migration cũ hoặc tạo bảng event ngầm trong task này |

Giới hạn in-memory phải ghi trong bàn giao/nghiệm thu. Nếu notification/AI/realtime về sau yêu cầu không mất event qua crash, cần task riêng về transactional outbox, dispatcher/retry và consumer idempotency trước khi bật side effect quan trọng. Không xem skeleton T-014 là bảo đảm delivery production cho các tính năng đó. T-015 phải có refetch/resync khi mất message và không áp dụng event tới muộn như phiên bản dữ liệu mới nhất khi chưa có version contract.

### 3.7. Các file dự kiến thay đổi

Các đường dẫn `...` dưới đây là package đã nêu ở mục 1.2; tên helper/test mới là đề xuất, chưa tồn tại.

| Nơi thay đổi | Việc thực hiện |
|---|---|
| `backend/common/src/main/java/io/devflow/common/event/TaskCreatedEvent.java` | Thêm routing/metadata, factory/overload compatibility và Javadoc |
| `backend/common/src/main/java/io/devflow/common/event/TaskStatusChangedEvent.java` | Thêm scope, cột, category/metadata; giữ cause và accessor cũ |
| `backend/board-impl/src/main/java/io/devflow/board/internal/TaskEventPublisher.java` (mới) | Validate snapshot, clock, afterCommit dispatch, guard/metrics |
| `backend/board-impl/src/main/java/io/devflow/board/internal/TaskManagementService.java` | Inject helper; create/cross-column move đăng ký event tại đúng chỗ |
| `AiEventListeners.java`, `NotificationEventListeners.java` trong module tương ứng | Chỉnh riêng hai task handler: guarded consume, log metadata và metrics |
| Test trong `common`, `board-impl`, `app` | Contract, transaction, consumer, API và concurrency theo mục 6 |
| `common/build.gradle.kts` nếu cần test serialization | Thêm dependency **test-only** cần thiết; common không phụ thuộc module nghiệp vụ |
| `docs/ARCHITECTURE.md`, plan này khi triển khai | Đồng bộ catalog chính xác và ghi kết quả/bằng chứng test |

## 4. Thứ tự triển khai và đầu ra

| Bước | Công việc | Đầu ra / điều kiện qua bước |
|---|---|---|
| TH-01 | Đối chiếu T-013, mọi constructor/listener; chốt scope/status/compatibility với Tiến; tạo branch T-014 từ develop đã có dependency | Contract đã thống nhất, không bắt đầu từ develop còn thiếu Task CRUD |
| TH-02 | Mở rộng hai record, validation factory và contract tests | Giữ accessor/wire name cũ; payload đầy đủ; không import board/internal vào common |
| TH-03 | Thêm publisher afterCommit/Clock/metrics; nối create và cross-column move | Không phát trước commit/rollback; đúng số lượng, snapshot và cause |
| TH-04 | Chỉnh skeleton AI/Notification, guard lỗi/log/MDC | Cả hai nhận cùng eventId sau commit, không log dữ liệu nhạy cảm |
| TH-05 | Viết/chạy test transaction, API/PostgreSQL, negative/regression, concurrency; đo hiệu năng | Bằng chứng DB + payload + consumer + HTTP, không chỉ mock verify publish |
| TH-06 | Boundary/full check, rà import, đồng bộ catalog/plan và chuẩn bị review | Kết quả test thực tế rõ ràng; ghi các test chưa chạy/rủi ro; PR/review theo workflow |

Chỉ chuyển card sang In Progress khi thực sự bắt đầu triển khai; việc lập tài liệu này không đánh dấu T-014 hoàn thành hoặc tự tick acceptance criteria. Khi phát triển, cập nhật Trello theo tiến độ đã có bằng chứng, không copy danh sách test thành kết quả pass.

## 5. Tiêu chí hoàn thành

### 5.1. Chức năng và hợp đồng

- [ ] Create commit thành công phát đúng một `TaskCreatedEvent`, mọi scope/ID/description/metadata đúng dữ liệu đã lưu.
- [ ] Cross-column move commit thành công phát đúng một `TaskStatusChangedEvent`, snapshot old/new trước/sau mutation chính xác, cause MANUAL.
- [ ] Chuyển vào/ra DONE, cột trùng tên/cùng category đều đúng; create vào DONE không phát status event giả.
- [ ] Reorder/no-op, GET, PUT nội dung/assignee, DELETE và thao tác column không phát nhầm hai task event.
- [ ] AI và Notification skeleton thực sự nhận event và ghi log kiểm chứng; không chỉ khai báo method chưa được Spring scan.
- [ ] Contract common giữ wire name và ý nghĩa accessor cũ; có routing board/workspace, test compatibility và catalog đồng bộ.

### 5.2. Dữ liệu, an toàn và chất lượng

- [ ] Task/audit/order lỗi hoặc rollback ở outer transaction/commit không dispatch task event; dữ liệu không cập nhật dở dang.
- [ ] Listener lỗi không đổi response của mutation đã commit; guard một consumer không chặn consumer còn lại trong bộ handler đã triển khai.
- [ ] Membership event vẫn đồng bộ/fail closed; JWT, quota, Turnstile, validation, IDOR/archive policy T-013 không bị bỏ qua.
- [ ] Snapshot bất biến; không entity/lazy proxy, không log PII/nội dung task/token và không UUID trong metric tags.
- [ ] Counter/timer đúng outcome; correlation/actor xuyên luồng, MDC không nhiễm sang request khác.
- [ ] Chạy backend `check`, `:verifyModuleBoundaries`; rà source imports vì task boundary hiện hữu kiểm tra dependency Gradle, không tự chứng minh mọi import đúng.
- [ ] Các test P0/P1 ở mục 6 đều pass với bằng chứng; test concurrency trên PostgreSQL và benchmark có kết quả. Không xem thiếu Docker hoặc skip test là pass.
- [ ] CRUD P95 < 200 ms theo workload mục 6.5; ghi môi trường, baseline và ảnh hưởng fan-out, không suy từ thời gian một unit test.
- [ ] Ghi rõ delivery chỉ trong process, rủi ro mất event/reordering, phần GIT/realtime/consumer thật là bàn giao; không tuyên bố exactly-once.
- [ ] Review theo workflow, checklist Trello cập nhật dựa trên kết quả thật. Chỉ cần frontend build/lint nếu triển khai làm thay đổi frontend; T-014 dự kiến chỉ backend/docs.

### 5.3. Đối chiếu acceptance criteria với kiểm thử

| Acceptance criterion gốc | Bộ kiểm chứng chính |
|---|---|
| Typed event đầy đủ payload lên Spring Event Bus | CT-01–CT-09; NV-01–NV-08; TX-01–TX-07; SEC-01–SEC-08; CON-01–CON-04 |
| Skeleton listener nhận event và ghi log kiểm chứng | TX-01, TX-08–TX-10; OBS-01–OBS-03; SEC-09; UAT-01–UAT-02 |

Các ca còn lại bảo vệ regression, concurrency, hiệu năng và chất lượng bàn giao; hai dòng trên không thay thế toàn bộ DoD.

## 6. Kế hoạch testing — Expected và Thực tế

### 6.1. Quy ước và môi trường

- **P0:** chặn nghiệm thu vì sai nghiệp vụ, rò dữ liệu hoặc báo thay đổi chưa commit. **P1:** bắt buộc trước hoàn thành để bảo đảm resilience, regression và vận hành.
- **UT:** JUnit 5/Mockito/AssertJ, test contract/helper/service quyết định đăng ký. **IT:** Spring context và transaction thật, PostgreSQL 17 Testcontainers, Flyway bật/DDL validate. **API:** MockMvc trong full app có Auth thật và collector test-only. **UAT/PERF:** thao tác thủ công/đo tải riêng.
- Mọi ô **Thực tế = Chưa chạy** tại ngày lập plan. Sau khi chạy ghi `PASS/FAIL/BLOCKED`, dữ liệu quan sát, commit/environment, đường dẫn report hoặc log đã che dữ liệu nhạy cảm; không thay bằng Expected.
- Fixture: workspace W1 có actor U1/U2 và assignee U3; W2 có outsider U4. Board B1/B2 thuộc W1, B3 thuộc W2; cột A=TODO, B=IN_PROGRESS, C=DONE; D cùng tên/category với A nhưng khác ID; cột rỗng và cột đủ capacity; thêm board archived. Task có mô tả null, rỗng, Unicode/XSS, assignee nullable và dueDate.
- Tạo fixture bằng SQL/setup repository rồi **clear collector/metric baseline** để không đếm event setup. Collector giữ payload theo eventId/taskId, độc lập với log; ghi thời điểm nhận và lỗi consumer có chủ đích.
- Với positive case, kiểm tra đồng thời HTTP, DB sau transaction bằng connection/transaction mới, số event, payload và lượt consume của cả AI/Notification. Với negative case, kiểm tra DB/order/audit không đổi và 0 task event; bỏ qua membership request hợp lệ xuất hiện trên bus.
- Không bọc toàn bộ test positive bằng transaction mặc định rollback. Dùng `TransactionTemplate`/`TestTransaction` để commit/rollback tường minh; dùng latch/barrier và timeout hữu hạn cho concurrency, không dựa vào sleep ngẫu nhiên.

### 6.2. Bộ contract và publisher

| ID / mức | Lớp | Case / cách thực hiện | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| CT-01 / P0 | UT/IT | Tạo snapshot đầy đủ của cả hai event | implements DevFlowEvent; eventType chính xác; mọi accessor trả đúng input đã chuẩn hóa | PASS — snapshot create được kiểm bằng unit test; snapshot move được kiểm qua API integration |
| CT-02 / P0 | UT/IT | projectId ở create và workspaceId ở move khác boardId | Scope luôn bằng W1; boardId bằng B1; không nhầm hai định danh | PASS — create và move integration phân biệt board/workspace |
| CT-03 / P0 | UT | Thiếu lần lượt taskId/boardId/scope/cột/eventId/correlationId/occurredAt; MANUAL thiếu actor; status thiếu cause | Factory/publisher production từ chối trước đăng ký, không publish; assertion parameterized cho từng field | Chưa chạy |
| CT-04 / P0 | UT | Status event source=destination; category lạ; status name null | Payload nghiệp vụ không hợp lệ bị từ chối; hai cột cùng category nhưng khác ID vẫn hợp lệ | PASS một phần — unit test từ chối source=destination và category không hợp lệ; chưa có ca status name null trong payload legacy |
| CT-05 / P1 | UT | Description null/rỗng/Unicode và timestamp từ fixed Clock | Không tự bịa mô tả; bảo toàn Unicode đã sanitize, occurredAt đúng Instant cố định | Chưa chạy |
| CT-06 / P1 | UT | Overload/accessor cũ và overload đầy đủ | Source compatibility đúng policy; legacy thiếu routing không chạy side effect cần scope, ghi skipped | PASS — constructor cũ giữ accessor/type; chưa có test consumer skip legacy |
| CT-07 / P1 | UT/IT | Snapshot rồi thay entity/cột; đọc payload sau commit | Payload event cũ không đổi, không giữ TaskEntity/ColumnEntity/proxy; serialize snapshot không LazyInitializationException | Chưa chạy |
| CT-08 / P1 | UT | Hai mutation cùng correlation và fixed clock | Hai eventId khác nhau; correlation có thể giống; không dùng time làm khóa chống lặp | Chưa chạy |
| CT-09 / P1 | UT/IT | Tạo snapshot/factory cause GIT với scope đầy đủ rồi đăng ký helper trong transaction test | Giữ cause GIT tới collector sau commit, rollback không publish; actor có thể null khi automation không có người gọi, không gán actor giả; chưa kiểm webhook/auto-move T-024 | PASS một phần — publisher unit test giữ cause GIT và actor null; webhook/auto-move T-024 chưa kiểm |

### 6.3. Bộ nghiệp vụ và API regression

| ID / mức | Lớp | Case / cách thực hiện | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| NV-01 / P0 | API/IT | POST task tối thiểu vào cột rỗng | 201 + Location; task/audit commit; đúng 1 created, 0 status; đúng task/board/workspace/column | PASS — PostgreSQL API test; xác minh task đã lưu và cả hai consumer nhận cùng eventId/payload |
| NV-02 / P0 | API/IT | POST đủ description/assignee/priority/dueDate | Response/DB/payload cùng taskId; description là bản lưu đã sanitize, actor=U1 không phải assignee U3 | Chưa chạy |
| NV-03 / P1 | API/IT | Parameterized description null/rỗng, tiếng Việt/emoji, HTML/script | Request hợp lệ 201, 1 created; payload khớp bản DB sau sanitize, log không chứa nội dung | Chưa chạy |
| NV-04 / P0 | API/IT | Create trực tiếp trong C=DONE | 1 created, 0 status; không có oldStatus giả hoặc notification duplicate từ hai loại event | Chưa chạy |
| NV-05 / P0 | API/IT | PATCH move A → B; chạy vị trí đầu/giữa/cuối | 200; thứ tự hai cột đúng; 1 status, 0 created; source A/destination B, old/new names/categories đúng, MANUAL | PASS một biến thể — A → DONE vị trí 0 trên PostgreSQL; chưa kiểm các vị trí đầu/giữa/cuối tổng quát |
| NV-06 / P0 | API/IT | Move vào cột rỗng | position=0, đúng 1 status; task fields/comment/tag mappings giữ nguyên | Chưa chạy |
| NV-07 / P0 | API/IT | Move A → C rồi C → B ở hai request | Hai status event tương ứng TODO → DONE và DONE → IN_PROGRESS; ID/time/snapshot riêng | Chưa chạy |
| NV-08 / P0 | API/IT | Move A → D, hai cột cùng tên/category | Đúng 1 status dù oldStatus=newStatus; IDs khác nhau là căn cứ thay đổi | Chưa chạy |
| NV-09 / P0 | API/IT | Reorder đầu/giữa/cuối trong A | DB position đúng, audit REORDER theo T-013; 0 created/status | PASS một biến thể — task đơn cùng cột/vị trí giữ nguyên; chưa kiểm thứ tự nhiều task |
| NV-10 / P0 | API/IT | No-op same column/same position; cột chỉ có một task | 200, 0 task event; không tạo audit thay đổi vô ích theo T-013 | PASS — no-op cùng vị trí cho 200/0 event; vị trí vượt range trả 400/0 event |
| NV-11 / P0 | API/IT | PUT từng field nội dung; gán/đổi/xóa assignee; PUT không đổi | Giữ hợp đồng PUT/audit T-013; 0 created/status ở tất cả biến thể | Chưa chạy |
| NV-12 / P0 | API/IT | GET detail/list; DELETE task | 200/204 theo route; 0 hai task event; DELETE cleanup/normalize giữ đúng | Chưa chạy |
| NV-13 / P1 | API/IT | Rename/category/reorder column, archive/restore board | API T-012 không phát hai task event ngoài phạm vi; không tạo fan-out giả cho task nằm trong cột | Chưa chạy |
| NV-14 / P1 | API/IT | POST lặp cùng payload rồi move lặp tới cột hiện tại | Hai POST: hai task/hai created do chưa idempotency; move lặp không thêm status event khi đã ở đích | Chưa chạy |

### 6.4. Bộ transaction, listener và lỗi

| ID / mức | Lớp | Case / cách thực hiện | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| TX-01 / P0 | IT | Giữ transaction create/move mở, kiểm collector trước commit rồi commit | Trước commit 0 task event; sau commit đúng 1 loại tương ứng; cả AI/Notification nhận cùng eventId | PASS một phần — API integration xác minh cả consumer nhận sau khi service transaction commit; chưa quan sát collector bên trong outer transaction trước commit |
| TX-02 / P0 | IT PostgreSQL | Gọi service trong outer transaction rồi rollback/setRollbackOnly | Không event/consume success, task/order/audit trở về trước thao tác | PASS — outer TransactionTemplate rollback task và audit; cả hai listener không nhận created event |
| TX-03 / P0 | IT | Inject lỗi task save/flush | Mutation lỗi; 0 event; task không xuất hiện hoặc move không đổi cả hai cột | Chưa chạy |
| TX-04 / P0 | IT | Inject lỗi audit flush sau task đã flush | Toàn bộ rollback, 0 event; không coi saveAndFlush task là đủ điều kiện thành công | Chưa chạy |
| TX-05 / P0 | IT | Sau đăng ký callback, gây PostgreSQL deferred constraint failure tại commit bằng fixture test-only | Commit lỗi thật, không chạy afterCommit; DB/audit/order rollback, 0 task event | Chưa chạy |
| TX-06 / P0 | UT/IT | Gọi helper không có transaction hoặc synchronization active | Fail closed, 0 publish; không chạy fallback; không để service self-invocation bỏ qua proxy | PASS — unit test helper không có transaction ném lỗi và không publish; self-invocation chưa áp dụng |
| TX-07 / P0 | IT | Consumer test đọc DB qua transaction/connection mới sau nhận event | Nhìn thấy dữ liệu commit; không phụ thuộc persistence context của producer; payload vẫn là snapshot kể cả DB sau đó thay đổi | Chưa chạy |
| TX-08 / P0 | API/IT | Consumer AI guarded ném exception; đảo thứ tự consumer và lặp với Notification lỗi | HTTP vẫn 201/200 và DB đã commit; consumer còn lại nhận; consume failure/success đúng, không dispatch lại | Chưa chạy |
| TX-09 / P1 | UT/IT | Exception thoát khỏi một listener test chưa guard | Callback không biến thành lỗi mutation sau commit; dispatch failure được ghi; test xác nhận không tự hứa consumer sau vẫn nhận | PASS — publisher unit test ném lỗi từ bus, callback sau commit bắt lỗi và không ném ngược; chưa kiểm fan-out sau consumer không guard |
| TX-10 / P1 | IT | Không có task listener đăng ký trong context publisher tối giản | Commit và publish không crash; không có consume success giả; không làm điều kiện cho phép mutation | Chưa chạy — đã kiểm context app có cả hai listener, chưa có context không listener |
| TX-11 / P0 | API/IT | Membership thiếu listener/nhiều quyết định/listener lỗi | 503 fail closed như T-013, 0 task mutation/event; domain guard không nuốt lỗi permission | Chưa chạy |
| TX-12 / P1 | IT | Hai create/move trong một outer transaction: commit và rollback | Commit: mỗi thay đổi đúng 1 event, snapshot riêng; rollback: 0 event dù có nhiều callback đăng ký | Chưa chạy |

### 6.5. Bộ bảo mật, concurrency, vận hành và nghiệm thu

| ID / mức | Lớp | Case / cách thực hiện | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| SEC-01 / P0 | API | Create/move thiếu JWT, token sai/hết hạn | 401 theo Auth, không thay dữ liệu, 0 task event | Chưa chạy |
| SEC-02 / P0 | API | U4 thao tác task/cột W1; UUID không tồn tại | 404 không lộ tài nguyên; 0 mutation/event | Chưa chạy |
| SEC-03 / P0 | API | Move sang B2 cùng workspace hoặc B3 workspace khác | 404 do ngoài board; không event chứa dữ liệu cột đích ngoài scope | Chưa chạy |
| SEC-04 / P0 | API | Board archived; assignee không thuộc workspace | 409 archived / 400 invalid assignee như T-013; 0 task event | Chưa chạy |
| SEC-05 / P0 | API | Title thiếu/blank/quá dài; UUID/JSON/enum sai; move position âm/out-of-range; body/field lạ, duplicate key | 400; payload vượt size limit 413; parameterized theo guard T-013; 0 task event | Chưa chạy |
| SEC-06 / P0 | API | Vượt quota; Turnstile thiếu/sai/quá dài; verifier timeout/misconfigured | 429 quota; thiếu/quá dài token 400 BOT_CHALLENGE_REQUIRED, token bị từ chối 403 BOT_CHALLENGE_REJECTED, verifier lỗi 503 BOT_PROTECTION_UNAVAILABLE; 0 task event, không log token/secret | Chưa chạy |
| SEC-07 / P0 | API | Create/move vào cột đủ capacity; reorder cột đủ capacity | Create/cross-column move 409 và 0 event; reorder hợp lệ 200, vẫn 0 status event | Chưa chạy |
| SEC-08 / P0 | API | Client cố gửi actorId/workspaceId/boardId/cause=GIT/eventId/occurredAt | Bị reject field không thuộc request; không override metadata; không phát GIT từ REST | Chưa chạy |
| SEC-09 / P1 | API/IT | Description/tên cột chứa email, token mẫu, CRLF, HTML; ép listener exception có message nhạy cảm | Payload description giữ bản sanitized cần thiết; log/ProblemDetail không rò nội dung, secret hoặc exception message; không log injection | Chưa chạy |
| CON-01 / P0 | IT PostgreSQL | Hai actor đồng thời move cùng task A → B với barrier | Chỉ một cross-column transition/event; request sau reorder/no-op theo dữ liệu mới nhất, không task duplicate/mất thứ tự | Chưa chạy |
| CON-02 / P0 | IT PostgreSQL | Hai request A → B và tiếp theo B → C được phối hợp bằng latch | Hai commit/two status snapshots nối đúng source/đích; không dùng thứ tự đến listener để khẳng định trạng thái hiện tại | Chưa chạy |
| CON-03 / P0 | IT PostgreSQL | Hai create cùng cột còn một chỗ trống | Một create 201/1 event; một 409/0 event; capacity/position không bị vượt | Chưa chạy |
| CON-04 / P0 | IT PostgreSQL | Giữ board lock tới timeout; race move với delete/archive/column mutation | Lỗi lock 409 BOARD_BUSY hoặc kết quả hợp lệ theo thứ tự lock; event chỉ cho mutation đã commit, không snapshot stale | Chưa chạy |
| CON-05 / P1 | IT | Kéo dài callback transaction đầu để event sau có thể đến trước | Snapshot/event count vẫn đúng; ghi nhận không có bảo đảm FIFO, không flaky assertion về thứ tự callback | Chưa chạy |
| OBS-01 / P1 | IT | Đọc counter/timer trước/sau create/move/rollback/reorder | Đúng delta dispatch/consume; rollback/reorder không tăng; duration có sample, metric tags hữu hạn | Chưa chạy |
| OBS-02 / P1 | API/IT | Correlation header hợp lệ, sai hoặc không có; actor khác nhau ở request kế tiếp | ID chuẩn hóa nối được response/log/event; actor đúng U1/U2, MDC khôi phục, không nhiễm user trước | Chưa chạy |
| OBS-03 / P1 | IT | Full application startup với cả AI/Notification | Hai task handlers thực sự là bean, nhận cả hai loại event; không gọi trực tiếp method để giả wiring | PASS cho T-014 — full app integration spies xác minh AI/Notification nhận create và move; chưa kiểm case thiếu handler |
| OBS-04 / P0 | Build/review | Boundary task + rà Gradle/import/source | Zero impl→impl; common không phụ thuộc domain; không consumer query Board repository hoặc đổi membership async | PASS — `check` chạy `verifyModuleBoundaries`; source các phần thay đổi đã rà |
| OBS-05 / P1 | Build | Backend full check, regression T-012/T-013/Auth và migration | Không compile/test failure, không test skipped do thiếu Docker; report ghi số test thực tế, không dùng số từ plan cũ | PASS — `gradlew check`: 171 test, 0 failure/error/skip; có Testcontainers PostgreSQL |
| PERF-01 / P1 | PERF | Baseline T-013 và bản T-014 cùng môi trường; 20 client, warm-up 30s, đo 3 phút, 50% create/50% move | Ghi P50/P95/P99, throughput/error, dispatch duration; CRUD P95 < 200 ms, 0 lỗi ngoài lỗi nghiệp vụ có chủ đích; không nhận event thiếu/lặp trong run | Chưa chạy |
| PERF-02 / P1 | PERF | 10.000 mutation hợp lệ + request rollback xen kẽ trong môi trường test | Số event bằng số commit thuộc phạm vi; collector không lẫn fixture; không tăng memory/connection bất thường sau cleanup | Chưa chạy |
| UAT-01 / P1 | UAT | Dùng API client tạo task rồi move qua A/B/C, đối chiếu DB/log hai consumer | Response/data đúng; mỗi mutation đúng eventId/payload; log đủ đối chiếu acceptance criteria Trello | Chưa chạy |
| UAT-02 / P1 | UAT | Reorder, no-op, bad request, outsider, archived board | Không log task event thành công giả; lỗi và dữ liệu giữ đúng theo API | Chưa chạy |
| UAT-03 / P1 | Review | Kiểm bàn giao GIT, realtime, giới hạn crash/outbox | Cause GIT có contract/factory test, chưa ghi Git pipeline pass; T-015 hiểu hai event chưa bao phủ update/delete/reorder | Chưa chạy |

**Chi tiết thực thi PERF:** dùng Docker/PostgreSQL 17 và cấu hình ứng dụng giống nhau cho baseline và candidate; tắt gọi AI/email thật (skeleton), mô phỏng/fixture Turnstile thay vì gọi Cloudflare để đo riêng CRUD. Chọn capacity test đủ lớn hoặc trải create trên nhiều cột để không biến 409 capacity thành lỗi kỹ thuật; chuẩn bị task/cột di chuyển để mỗi request move thực sự đổi cột. Ghi cấu hình capacity, quota và bot protection đã override, CPU/RAM/JVM/network. Không áp số P95 trong môi trường test đã override thành kết quả end-to-end production có Turnstile mạng thật.

Test GIT của T-014 chỉ kiểm cause/factory và helper nội bộ dưới transaction trong UT/IT. End-to-end webhook → Git event → Board move → task.status_changed cause GIT là acceptance integration của T-024/T-041, không tự đánh dấu pass ở đây.

### 6.6. Lệnh chạy và bằng chứng cần lưu khi triển khai

Chạy từ thư mục `backend/` trên Windows; cần JDK 17, Docker Desktop/Testcontainers khả dụng. Tên test mới ở mục 3.7 sẽ được chốt khi viết code; các lệnh dưới không khẳng định chúng đã tồn tại hoặc đã chạy.

```powershell
.\gradlew.bat :common:test :board-impl:test :app:test
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat check
```

Sau lần chạy focused, full `check` là gate regression; đọc XML/HTML để xác định executed/up-to-date/skipped. Chỉ dùng `--rerun-tasks` khi cần bằng chứng một lượt thực thi mới, không lặp test không có lý do. Frontend chỉ chạy `npm run build`/`npm run lint` khi có thay đổi frontend.

| Bằng chứng | Vị trí / cách ghi |
|---|---|
| Unit/integration test reports | `backend/<module>/build/reports/tests/test/index.html`, XML ở `build/test-results/test/` |
| API/DB/event snapshot | Fixture IDs không chứa PII, response đã che token, SQL assertion và collector output; ghi kèm case ID |
| Listener/log/metrics | eventId/correlation liên kết được hai consumers; metrics delta, lỗi đã che nội dung nhạy cảm |
| Concurrency | Barrier/latch scenario, số request/commit/event, order invariants và timeout thực tế |
| Performance | Script/workload, cấu hình overrides, baseline/candidate, phân vị latency, error rate và điều kiện đo |
| Bàn giao/review | Commit/PR và các giới hạn đã xác nhận; mapping acceptance criteria → case IDs |

### 6.7. Tổng hợp thực tế cập nhật 08/10/2026

| Hạng mục | Thực tế ngày 08/10/2026 |
|---|---|
| Đọc task/card/architecture/product/master plan/source | Đã thực hiện; card có đúng hai acceptance criteria ở mục 1.1 |
| Contract/publisher/listener bổ sung T-014 | Đã triển khai trên branch T-014; chưa commit; task event chỉ phát sau commit |
| Test contract/publisher/API PostgreSQL liên quan T-014 | 5 common tests, 6 publisher tests, 16 Board API integration tests: pass, 0 failure/error/skip |
| Backend `check` và module boundaries | Pass; tổng XML 174 test trên `common`, `board-impl`, `app`, `auth-impl`, 0 failure/error/skip |
| Bộ CT/NV/TX/SEC/CON/OBS/PERF/UAT | 59 case đã thiết kế; các case không được đánh dấu PASS/PASS một phần vẫn chưa chạy |
| Benchmark và kiểm thử thủ công riêng | Chưa chạy; không suy P95 từ thời gian Gradle test |
| Tình trạng ticket/Trello | Code chưa commit; Trello vẫn Backlog do API trả 401; chưa đánh dấu hoàn thành hoặc tick acceptance criteria |

**Điều kiện nghiệm thu:** không có P0/P1 FAIL hoặc BLOCKED chưa giải quyết; mọi kết quả có evidence. Nếu một case không còn áp dụng sau thay đổi thiết kế, ghi N/A kèm lý do và ca thay thế/ảnh hưởng tiêu chí hoàn thành; không dùng N/A để bỏ rollback, quyền, listener isolation hoặc concurrency.
