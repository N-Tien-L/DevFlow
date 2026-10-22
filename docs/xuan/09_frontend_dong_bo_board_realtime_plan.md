# T-018 — Kế hoạch triển khai frontend đồng bộ board realtime

**Milestone 2 · Phạm vi chính:** `frontend` · **Người phụ trách:** Xuân Lê (@xuanle2).
**Ngày lập/đối chiếu:** 15/10/2026, múi giờ Asia/Saigon.
**Trạng thái:** ĐANG TRIỂN KHAI — đã thêm luồng frontend và build/lint file đổi thành công; bộ test chức năng/E2E chưa chạy nên chưa nghiệm thu DoD.

[Task gốc](09_frontend_dong_bo_board_realtime.md) · [Thẻ Trello T-018](https://trello.com/c/f2hncr87/24-t-018-implement-frontend-stomp-client-hook-for-multi-client-real-time-board-sync) · [Tổng quan nhiệm vụ](00_nhiemvu.md) · [Backend realtime T-015](06_backend_dong_bo_board_realtime_plan.md) · [Kanban T-016](07_giao_dien_kanban_keo_tha_plan.md) · [Task detail T-017](08_cua_so_chi_tiet_task_plan.md).

## 1. Mục tiêu, căn cứ và phạm vi

### 1.1. Kết quả người dùng cần nhận được

Hai thành viên cùng mở một board: khi A kéo, sửa, tạo hoặc xóa task/cột thành công, B tự thấy dữ liệu đã lưu mà không F5. Khi mất kết nối rồi phục hồi, B tự kết nối lại và tải bù dữ liệu. Đổi board, hết phiên hoặc mất quyền không để dữ liệu của ngữ cảnh cũ đi vào màn hình hiện tại.

Đã đọc thẻ Trello trực tiếp ngày 15/10/2026: card ID `6aaabc25c6bb012a27bbd886`, thuộc Backlog tại thời điểm đọc; branch tham chiếu `feature/T-018-stomp-board-sync`. Hai acceptance criteria gốc là:

- Mở 2 trình duyệt cùng xem 1 board: kéo card ở tab 1 thì tab 2 tự nhảy vị trí.
- Xử lý tự động reconnect khi mất kết nối mạng.

Các tiêu chí bổ sung bên dưới là điều kiện kiểm chứng kỹ thuật/DoD được diễn giải cho plan, chưa được cập nhật thành checklist Trello. Khi lập plan chỉ tạo tài liệu; cập nhật triển khai và quyền Trello được ghi ở mục 8.

### 1.2. Tài liệu và mã nguồn đã đối chiếu

- [ARCHITECTURE.md](../ARCHITECTURE.md): module map, event contract, REST/WebSocket, giới hạn delivery, bảo mật và module boundaries.
- [PRODUCT_SPEC.md](../PRODUCT_SPEC.md): Kanban/realtime MVP và mục 8 về chất lượng SaaS.
- [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md): Phase 2/Sprint 2, T-015 → T-018; [DoD của Xuân](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung): realtime < 1 giây trong điều kiện kiểm thử thống nhất.
- Quy tắc trong `AGENTS.md`, `.agents/rules/` và skill `frontend-dev`, `trello-management`.

| Vị trí hiện có | Quan sát từ source ngày lập plan | Việc T-018 cần xử lý |
|---|---|---|
| `frontend/src/pages/BoardPage.tsx` | REST load board/cột/task, `generationRef`, local filters, DnD preview, optimistic move và reconcile; chưa subscribe realtime | Bổ sung lifecycle kết nối và điều phối refresh; tránh gọi `loadBoard` vốn xóa board/reset state cho mọi event |
| `frontend/src/board/mutations.ts` | Move/reorder theo ID; kéo task yêu cầu cột đã tải đủ | Giữ invariant thứ tự, không áp `toPosition` từ event như delta đầy đủ |
| `frontend/src/api/boardApi.ts` | GET board, columns, tasks size 100; GET/PUT detail, comments phân trang | Truyền AbortSignal cho các GET cần hủy; gom request trùng với reconcile hiện có |
| `frontend/src/auth/AuthProvider.tsx` | Có `request`, refresh promise dùng chung, retry GET một lần; không replay mutation; token giữ trong Session/sessionStorage | Chưa có API cấp access token cho STOMP hoặc lifecycle phiên; cần bổ sung qua AuthProvider |
| `frontend/src/components/board/TaskDetailModal.tsx` | Draft nội dung và comment riêng, PUT If-Match bằng task.version, xử lý 412; chưa nhận invalidation ngoài | Thêm đầu vào refresh có kiểm soát; bảo toàn draft khi event tới; đồng bộ comments đúng ID |
| `frontend/package.json` | React 19.1, TypeScript 5.8; có build/lint | Chưa có `@stomp/stompjs`, unit/component runner hoặc E2E runner/scripts |
| `backend/board-api/.../BoardMutationMessage.java` và các enum/data cùng package | Contract schemaVersion 1, 11 mutation types, 4 refresh targets | Viết types và runtime validation tương ứng, không tự phát minh payload chứa toàn bộ task |
| `BoardRealtimePublisher`, `BoardRealtimeBroadcaster` | Đăng ký publish trong transaction, broadcast sau commit tới `/topic/boards/{id}`; send timeout; metrics/log | REST là nguồn chuẩn; publication failure sau commit không được khiến client tự gửi lại mutation |
| `WebSocketConfig`, `StompAuthenticationInterceptor` | Native WebSocket `/ws`, simple broker `/topic`, heartbeat 10s/10s; JWT trong STOMP CONNECT header `Authorization: Bearer ...` | Dùng native WebSocket; không thêm SockJS, không đặt JWT trên URL |
| `BoardStompAuthorizationInterceptor`, `BoardOutboundAuthorizationInterceptor` | Kiểm quyền subscribe và trước delivery; giới hạn 10 subscriptions/session, 25/user, 200 toàn server | Một subscription board đang xem; xử lý bị từ chối; không suy ra quyền từ actorId |
| `BoardOutboundAuthorizationInterceptor` | Token hết hạn/membership bị thu hồi có thể chỉ drop MESSAGE, không chủ động đóng socket hoặc gửi ERROR | Không chờ socket đóng mới refresh token/kiểm quyền; có lịch kiểm tra và resync dự phòng |
| `TaskCommentService` | Comment mới phát `CARD_UPDATED`, `changedFields=[comments]`, `refresh=[TASK_DETAIL]` | Refresh danh sách comments của task đang mở, không reset form hoặc gọi lại mọi task-list |
| `frontend/vite.config.ts`, `frontend/nginx.conf` | Đã proxy `/ws` và Upgrade ở Nginx | Kiểm chứng cả Vite và Docker/Nginx, HTTPS/WSS và timeout thực tế |

**Lưu ý về tài liệu cũ:** plan T-017 có phần khảo sát trước triển khai và phần kết quả bổ sung sau đó. Source hiện tại đã có comments, Markdown và task.version/If-Match; T-018 dùng hiện trạng này, không coi chúng là API còn thiếu.

### 1.3. Phạm vi và phụ thuộc

**Trong phạm vi:** `useBoardSync`, STOMP transport, auth/token integration, validation và gom invalidation, refetch/reconcile board, lifecycle khi đổi trang, reconnect/resync, DnD/modal integration, trạng thái kết nối và các bộ test tương ứng. Xử lý đủ mutation type backend đang phát; thao tác create/delete chưa có UI có thể được tạo bằng API thật trong E2E.

**Ngoài phạm vi:** xây CRUD UI mới, chat/notification realtime, presence/avatar người đang xem, cursor người khác, lưu mutation offline, tự merge nội dung, transactional outbox, board revision/replay, thay broker hoặc triển khai nhiều backend instance. Git/AI chỉ được tích hợp qua mutation Board hiện có khi các ticket đó hoàn tất.

| Gate | Điều kiện trước khi nghiệm thu | Chủ sở hữu |
|---|---|---|
| G1 — Contract/backend | `/ws` và topic thật hoạt động, JWT/permissions đúng, các mutation phát invalidation sau commit | Board/Auth, Xuân phối hợp Tiến |
| G2 — Auth lifecycle | Cấp token hợp lệ/refresh single-flight; response refresh cũ không tái tạo phiên sau logout/đổi tài khoản | Frontend Auth, phối hợp Tiến |
| G3 — Reconciliation | Một coordinator cho local mutation và remote event; bảo vệ request cũ, pagination, drag và draft | Frontend Board, Xuân |
| G4 — Test environment | Hai tài khoản cùng workspace + một outsider, PostgreSQL/backend thật, Vite/Nginx và cấu hình Turnstile xác định | Xuân |
| G5 — Bằng chứng | Hai AC gốc có E2E/demo thật; có kết quả reconnect, security, race và latency; build/lint/check qua | Xuân + reviewer |

## 2. Kế hoạch nghiệp vụ — mô tả luồng bằng ngôn ngữ người dùng

### NV-01. Vào board và bắt đầu theo dõi

1. Người dùng đăng nhập, chọn workspace/board được cấp quyền. Trang tải dữ liệu hiện tại và thông báo đang kết nối đồng bộ.
2. Sau khi có kết nối, trang đọc lại dữ liệu để bù thay đổi xảy ra trong lúc mở board. Không cần người dùng bấm F5.
3. Trang cho biết dữ liệu đang được cập nhật hay kết nối đang gián đoạn. Kết nối socket thành công nhưng dữ liệu tải lỗi không được hiển thị là đã đồng bộ hoàn toàn.
4. Board rỗng vẫn hoạt động; task/cột mới xuất hiện sau khi người khác tạo và lưu thành công.

### NV-02. Người khác tạo, sửa, di chuyển hoặc xóa task

1. A thực hiện thao tác; sau khi máy chủ lưu thành công, B tự đọc dữ liệu mới và thấy task ở cột/vị trí đúng.
2. Đổi title/priority/assignee/due date cập nhật card. Nếu B đang lọc, áp lại bộ lọc hiện tại: task có thể xuất hiện hoặc biến mất theo điều kiện lọc, không xóa nhầm dữ liệu thật.
3. Move giữa hai cột cập nhật cả cột nguồn và cột đích, số lượng và thứ tự các task còn lại. Reorder trong một cột không làm task bị nhân đôi.
4. Delete bỏ task khỏi danh sách. Nếu task đang mở, modal báo không còn tồn tại/không truy cập được, ngừng lưu/gửi comment; không tự tạo lại từ bản nháp.
5. Chính A cũng nhận thông báo; cùng một task vẫn chỉ hiện một lần dù API response và realtime tới theo thứ tự khác nhau.

### NV-03. Người khác sửa cột hoặc board

1. Thêm/xóa/reorder cột cập nhật bố cục, không mất task ở các cột không đổi.
2. Đổi tên/category cột cập nhật nhãn liên quan. Không dựa vào tên cột để xác định task thuộc đâu.
3. Board archived chuyển sang chỉ đọc, kể cả khi người dùng đang chuẩn bị kéo/lưu; vẫn xem và theo dõi được nếu còn quyền. Restore cho phép thao tác sau khi đọc lại trạng thái chuẩn.
4. Board bị xóa: rời trạng thái board đang hoạt động, xóa dữ liệu cache tương ứng, cập nhật danh sách board và thông báo an toàn. Không tự động retry mutation vào board đã mất.

### NV-04. Nhận thay đổi khi đang kéo hoặc đang lưu

1. Khi đang kéo, trang không giật bố cục giữa tay người dùng. Nếu có thay đổi ảnh hưởng cấu trúc board, hủy lần kéo theo cách rõ ràng, tải dữ liệu mới và mời kéo lại.
2. Nếu thay đổi tới lúc một thao tác đang gửi lên server, giữ trạng thái “đang lưu/đang đối soát”, đợi kết quả rồi đọc trạng thái chuẩn.
3. Không khôi phục toàn bộ snapshot cũ lên thay đổi mới của đồng đội. Nếu chưa rõ server đã lưu hay chưa, trang thông báo chưa xác nhận, khóa thao tác xung đột và đối soát bằng GET.
4. Người dùng không phải thực hiện lại thao tác đã xác nhận lưu chỉ vì thông báo realtime tới chậm hoặc thất lạc.

### NV-05. Nhận thay đổi khi cửa sổ chi tiết đang mở

1. Form chưa sửa: thông tin task được làm mới khi người khác cập nhật.
2. Form có nội dung chưa lưu: giữ nguyên bản nháp và báo có phiên bản mới. Người dùng chủ động tải bản mới hoặc xử lý xung đột theo cơ chế T-017; không âm thầm ghi đè draft.
3. Comment mới xuất hiện trong danh sách sau khi tải lại; nội dung comment đang gõ và vị trí/focus soạn thảo còn nguyên.
4. Khi đang lưu hoặc gửi comment, thông báo được ghi nhận và xử lý sau khi request kết thúc. Không gửi lại PUT/POST để “đồng bộ”.

### NV-06. Mất mạng, mất kết nối rồi phục hồi

1. Khi phát hiện mất kết nối, trang báo dữ liệu có thể cũ; giữ dữ liệu đang xem và bản nháp trong bộ nhớ. Không nhận thao tác kéo/lưu mới khi đang offline hoặc chưa đối soát được.
2. Khi mạng trở lại, tự thử kết nối với khoảng chờ tăng dần. Không yêu cầu người dùng refresh trang hoặc tạo hàng loạt kết nối cùng lúc.
3. Kết nối lại xong phải tải lại board/cột/task và detail đang mở, vì trong thời gian mất mạng có thể có nhiều thay đổi đã bỏ lỡ.
4. Chỉ mở lại thao tác sau khi phần dữ liệu cần thao tác đã được đối soát. Nếu vẫn lỗi, hiện nút thử đồng bộ và giữ trạng thái chưa cập nhật.

### NV-07. Hết phiên, mất quyền và đổi ngữ cảnh

1. Access token sắp hết hạn được làm mới qua cơ chế Auth chung; kết nối mới dùng token mới. Refresh thất bại thì yêu cầu đăng nhập lại.
2. Bị gỡ khỏi workspace hoặc không còn đọc được board: dừng theo dõi, khóa thao tác và xóa dữ liệu thuộc board khỏi màn hình/bộ nhớ feature. Thông báo không phân biệt board không tồn tại với board không được xem.
3. Chuyển board/workspace chỉ nhận thay đổi của board mới. Đăng xuất/rời trang dọn socket, timers, requests; phản hồi tới muộn không được phục hồi màn hình cũ.
4. Mỗi tab có phiên kết nối riêng trong giới hạn server; không dùng việc cùng tài khoản để bỏ qua cập nhật từ tab còn lại.

## 3. Kế hoạch kỹ thuật — hợp đồng và luồng xử lý

### 3.1. Quyết định nền tảng

Chọn **STOMP để nhận invalidation, REST để lấy dữ liệu chuẩn**. Không áp task/cột trực tiếp từ event vì payload chỉ có ID/phạm vi thay đổi, thiếu dữ liệu card và các task bị dồn position. Giữ state React hiện có, tách coordinator để tránh biến BoardPage thành nơi xử lý transport, retry và race cùng lúc.

Đã thêm `@stomp/stompjs` `^7.4.0` vào dependency và lockfile. `Client` dùng URL `/ws` cùng origin, token lấy mới trước CONNECT, heartbeat 10s, connection timeout 10s, reconnect exponential với cap 30s và jitter ban đầu 0,8–1,2s. Chi tiết API đã đối chiếu theo [tài liệu Client chính thức](https://stomp-js.github.io/api-docs/latest/classes/Client.html); chưa đo latency hoặc reconnect trên backend thật.

Luồng tổng thể:

```text
A thao tác -> REST mutation -> transaction commit -> Board publish invalidation
                                                        |
                                                        v
B: /ws -> CONNECT JWT -> SUBSCRIBE /topic/boards/{boardId}
                         -> validate + dedupe + gom dirty scope
                         -> coordinator GET REST
                         -> kiểm generation/request scope -> apply state -> render

Reconnect / focus / lịch đối soát -> cùng coordinator -> bù dữ liệu bỏ lỡ
Local mutation thành công/lỗi chưa rõ -> cùng coordinator -> đọc dữ liệu chuẩn
```

Không thay module boundary: nếu phải sửa backend phụ thuộc, chỉ sửa module sở hữu; trao đổi liên module bằng typed `DevFlowEvent`, không import foreign `internal`, không gọi repository Auth từ Board.

### 3.2. Transport, lifecycle và trạng thái

- URL suy ra từ origin trang: `http:` → `ws:`, `https:` → `wss:`, path `/ws`; không hardcode host backend hoặc đưa token vào query string. Native WebSocket khớp backend hiện tại, không thêm `/ws/info` hay SockJS fallback.
- Hook chỉ bật khi auth `ready`, có user và boardId hợp lệ theo UUID. GET board kiểm quyền; khi CONNECT thành công đăng ký đúng một topic của board hiện tại và đăng ký lại sau mỗi reconnect.
- Trạng thái kết nối có `idle`, `connecting`, `connected`, `reconnecting`, `offline`, `error`; trạng thái dữ liệu được phản ánh riêng bằng loading, sync-blocked và partial-column. `connected` chưa có nghĩa snapshot đã được đối soát.
- Heartbeat vào/ra 10.000 ms và STOMP connection timeout 10.000 ms đã cấu hình. Chưa kiểm chứng khi browser treo nền; heartbeat không dùng để đo latency cập nhật card.
- Dùng một cơ chế reconnect exponential của STOMP Client, delay ban đầu ngẫu nhiên 0,8–1,2s và cap 30s. Các lần tiếp theo do thư viện tăng theo cấp số nhân, không có jitter riêng từng lần; chưa reset backoff theo phiên ổn định.
- Khi `navigator.onLine=false`, UI chuyển read-only, giữ dữ liệu/draft và deactivate Client để dừng retry. Khi online, Client được activate lại; nếu socket cũ còn hoạt động thì full reconcile; sau reconnect vẫn có lượt bù sau 500ms.
- Cleanup đánh dấu hook inactive, abort token acquisition, deactivate client cưỡng bức, clear timer và gỡ listeners. STOMP Client tự tạo lại subscription sau reconnect; chưa có unsubscribe API riêng. StrictMode/lifecycle cần chạy bộ test CN trước nghiệm thu.
- StrictMode mount→cleanup→mount phải an toàn; callback stable/ref đọc dữ liệu mới, filter gõ mỗi phím không reconnect. Không dùng singleton giữ token của user trước; ChatPanel hiện chưa có socket nên không tự xây connection manager chung ngoài phạm vi.

### 3.3. Auth và phân loại lỗi

1. Mở rộng AuthContext bằng API như `getValidAccessToken({ minValidityMs, signal })` và `sessionGeneration`/thông báo phiên thay đổi. Hook không đọc/parse sessionStorage riêng hoặc giữ refresh token.
2. `beforeConnect` lấy access token còn hạn; nếu cần, dùng đúng refresh promise của AuthProvider. Refresh do GET401 và STOMP cùng lúc chỉ tạo một HTTP refresh cho cùng thế hệ phiên.
3. Lập lịch renew trước expiry 60s; helper dùng `exp` chỉ làm tín hiệu lịch, server vẫn quyết định token hợp lệ. Token mới được dùng khi re-CONNECT và subscribe/resync. Chưa có đo kiểm expiry/rotation với backend thật.
4. Mọi refresh/GET callback kiểm tra `sessionGeneration`; logout hoặc đổi tài khoản làm response refresh cũ vô hiệu. Refresh bị từ chối 4xx (trừ 429) kết thúc session; lỗi mạng/5xx giữ session hiện tại để thử lại, không tự dựng phiên hợp lệ. Chưa chạy concurrency/identity tests.
5. STOMP ERROR có thể không chứa mã từ interceptor theo cấu trúc ổn định. Không phân loại chỉ bằng chuỗi stack trace; dùng mã allowlist nếu server thực sự cung cấp, kết hợp GET board có phân quyền để xác định hướng xử lý.
6. GET401: Auth thử refresh theo policy một lần, thất bại thì stop/clear/login. GET403/404: stop/clear board và thông báo chung. GET429: giữ dirty, chờ Retry-After/`retryAfterSeconds`; 5xx/network: backoff hữu hạn mỗi đợt, hiện stale, cho thử lại. Không tự replay mutation và không retry subscribe liên tục khi bị quota/quyền từ chối.
7. Backend hiện có thể drop delivery khi mất quyền/token hết hạn mà socket vẫn mở. Ngoài renew token, đối soát quyền qua GET board khi focus/reconnect và định kỳ khi tab visible (mục 3.6). Dữ liệu đã giao trước khi thu hồi không thể thu lại; không hứa frontend biết mất quyền ngay lập tức nếu chưa có tín hiệu server.

### 3.4. Runtime contract và routing invalidation

Tạo types theo Java records trong `board-api`; JSON.parse phải nhận `unknown` và validate trước khi dùng. Envelope v1 gồm `schemaVersion`, `eventId`, `type`, `boardId`, `occurredAt`, `correlationId`, `actorId` (có thể null), `data`. `data` gồm các ID nullable, positions nullable, `affectedColumnIds`, `changedFields`, `refresh`.

- UUID/topic/boardId phải hợp lệ và khớp active board; event sai board hoặc callback lifecycle cũ bị bỏ qua, không GET board khác theo payload.
- Validate enum, array, type/nullable và yêu cầu tối thiểu theo mutation; position là integer không âm khi có. Giới hạn ứng dụng đề xuất: body 64 KiB UTF-8, arrays tối đa 100 phần tử; đo kích thước trước parse. Frame vượt giới hạn không tạo vòng refetch hoặc crash.
- Chỉ xử lý refresh targets allowlist `BOARD`, `COLUMNS`, `TASK_LISTS`, `TASK_DETAIL`. Type/schema mới chưa hỗ trợ nhưng envelope đúng board: đánh dấu cần full reconcile có throttle, báo trạng thái tương thích nếu lặp lại; không áp delta. JSON hỏng/quá lớn: bỏ payload, diagnostic an toàn và lên lịch reconcile có giới hạn cho board hiện tại.
- LRU/TTL eventId trong bộ nhớ: tối đa 1.000 IDs/5 phút cho active board; xóa khi đổi context. Trùng event không tạo công việc mới; refetch đã lỗi phải còn dirty để retry độc lập, không phụ thuộc event được phát lại.
- `occurredAt`, eventId và correlationId không phải sequence/revision; không loại event vì timestamp nhỏ hơn event trước. Không bỏ event theo `actorId === currentUserId`, vì tab khác cùng user cũng cần đồng bộ.

| Mutation hiện tại | Phần cần đọc lại | Chi tiết áp dụng |
|---|---|---|
| `CARD_CREATED` | Affected task lists | Replace snapshot danh sách, dedupe ID; không append task chỉ từ event |
| `CARD_UPDATED` | Theo refresh targets; thông thường task lists + detail | Nếu chỉ `changedFields=[comments]`, `refresh=[TASK_DETAIL]`: cập nhật comments task đang mở, tránh reload cả board |
| `CARD_DELETED` | Cột cũ + detail tương ứng | Vô hiệu hóa task/detail ngay; đọc lại cột để sửa totals/positions; detail GET404 là trạng thái kết thúc |
| `CARD_MOVED` | Cột nguồn + đích, detail nếu đang mở | Gộp affected IDs, không sửa riêng task rồi để các vị trí còn lại sai |
| `CARD_REORDERED` | Cột bị ảnh hưởng, detail theo contract | Reconcile cả cột; dedupe danh sách affected |
| `COLUMN_CREATED` | Columns rồi task-list của cột mới | Cột rỗng và task được tạo ngay sau đó đều được xử lý |
| `COLUMN_UPDATED` | Columns, detail liên quan | Giữ tasks theo column ID; cập nhật nhãn/category qua dữ liệu chuẩn |
| `COLUMN_DELETED` | Columns | Bỏ cache/request của cột mất, không resurrect từ GET muộn |
| `COLUMN_REORDERED` | Columns | Merge metadata theo ID, thứ tự theo position server |
| `BOARD_UPDATED` | Board metadata, columns nếu cấu trúc thay đổi; board list của workspace đang xem | Áp archived/restore và canEdit; giữ filter/draft khi còn quyền |
| `BOARD_DELETED` | Terminal board + board list | Dừng mutation, clear cache/subscription; không chờ GET404 mới khóa thao tác |

Nếu metadata thiếu ID cần thiết để refresh hẹp, chuyển full reconcile board hiện tại. Cột lạ phải resolve qua danh sách cột được đọc dưới board đang xem trước khi GET task-list, không tin ID tùy ý trong payload. Board list ở workspace khác không nằm trong subscription này và không được hứa cập nhật.

### 3.5. Coordinator, race và phân trang

**Một đầu mối cập nhật state:** local mutation reconciliation, remote invalidation, reconnect, retry/focus/periodic đi qua cùng coordinator. Đầu vào là tập dirty scopes: board metadata, columns, set column IDs, selected detail/comments; không lưu hàng nghìn event nguyên bản.

1. Event đánh dấu dirty ngay. Gom trong cửa sổ 50–100 ms, max-wait 250 ms; dùng timer theo cửa sổ để stream liên tục không trì hoãn vô hạn. Tối đa một refresh batch đang apply cho active board; các GET cột trong batch giới hạn concurrency 4.
2. Mỗi batch chụp lifecycle generation, session generation, request sequence và dirty counter theo scope. Event tới trong lúc GET phải giữ dirty counter mới; response cũ không được xóa dirty mới hoặc ghi đè state từ request mới hơn.
3. Refresh board metadata/column list trước khi resolve task lists. Tải task pages từ page 0 vào vùng staging; chỉ publish snapshot sau khi kiểm response còn đúng context và scope. Một batch thay đổi cột nguồn/đích nên publish cùng lần để tránh task thoáng hiện ở hai cột.
4. Giữ logic `size=100`, giới hạn tối đa 50 trang trong một lượt tải. Reconcile đọc từ page 0 nhưng chỉ nạp số trang đã hiển thị (ít nhất 1); không tải các trang người dùng chưa mở trong mỗi lần periodic sync. Replace từ đầu sau create/delete/move vì offset page có thể dồn; không chỉ append trang tiếp theo. Dedupe task theo ID, sort position rồi ID; metadata `loadedPages/totalElements/totalPages/complete` phải khớp snapshot vừa đọc.
5. Dữ liệu có thể đổi giữa các GET page; REST hiện không có snapshot/revision cho toàn board. Nếu có invalidation trong lượt đọc, chạy lại phạm vi dirty sau đó; nếu pagination bất nhất hoặc task trùng xuyên cột, đánh dấu partial/stale, đối soát lại có backoff. Không coi dedupe là bằng chứng không thiếu task.
6. Mỗi lượt vượt cap 50 trang: hiển thị chưa tải đủ và giữ hành động tải thêm; không khẳng định full sync, không cho DnD cột chưa complete. Khi invalidation thay đổi offset, reset pagination của cột; không dùng cursor trang cũ. Không tăng cap vô hạn để che vấn đề dung lượng.
7. GET abort/timeout/400/404/429/5xx có trạng thái rõ. Cột bị xóa trong khi GET trả404: đọc columns để xác định cấu trúc mới; board GET404 thì kết thúc context. Giữ dirty khi lỗi và dữ liệu cũ có nhãn stale, không thay bằng danh sách rỗng giả.
8. Background refresh giữ board hiện tại, filter, scroll và modal; tránh dùng `loadBoard` reset `setBoard(null)` cho mọi event. Một nhánh initial load và một nhánh reconcile chia sẻ fetch/coordinator, không có hai nơi cùng replace state.
9. Sau batch thành công, flush scopes còn dirty; giới hạn tốc độ batch khi burst liên tục tối đa 4 lần bắt đầu/giây/board và báo đang cập nhật nếu chưa hội tụ. Scope terminal/quyền luôn ưu tiên hơn debounce và mutation lock.

### 3.6. Kết nối đầu tiên, reconnect và mất event im lặng

Không có replay log hay board revision. Bản tin có thể trùng/đến muộn/thất lạc, publication sau commit có thể lỗi trong khi socket vẫn khỏe. Vì vậy chỉ “subscribe lại” là chưa đủ.

1. Có thể tải REST ban đầu để hiện dữ liệu sớm; sau CONNECTED gửi SUBSCRIBE, bật thu invalidation rồi full reconcile board/cột/task đã xem và selected detail/comments. Event tới trong quá trình đọc đánh dấu dirty để có lượt bù.
2. Simple broker của Spring không hỗ trợ STOMP receipt/ack; không chờ `watchForReceipt` để xác nhận subscription sẵn sàng. Đây là giới hạn nêu trong [tài liệu Spring Framework 6.2](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/stomp/handle-broker-relay.html), cần kiểm chứng race trên backend thật.
3. Gửi SUBSCRIBE không chứng minh broker đã xử lý trước GET. Đề xuất thêm một lượt đối soát ổn định sau 500 ms khi kết nối đầu/reconnect; lượt này cùng coordinator, không tạo request song song vô kiểm soát. Đây là biện pháp giảm cửa sổ race, không phải bảo đảm snapshot atomic.
4. Khi tab visible, định kỳ 30s + jitter 0–5s đọc lại board/columns/task lists cần xem để bù silent loss và kiểm quyền; gộp với batch đang chạy. Tab hidden tạm dừng lịch nặng, đánh dấu stale; khi visible/focus/BFCache pageshow thì full reconcile và renew token nếu cần. Không dùng periodic polling để tuyên bố AC realtime đã đạt.
5. Mục tiêu hội tụ sau silent loss: trong một chu kỳ đối soát cộng thời gian GET, khi mạng/backend khỏe và không có thao tác liên tục. Trong điều kiện tab visible, mất quyền được phát hiện ở lần GET kiểm quyền tiếp theo, không hứa thu hồi dữ liệu tức thời.
6. Reconnect phải resubscribe đúng board và full reconcile ngay cả khi không có event mới. Chỉ đánh dấu dữ liệu current khi scopes cần thiết thành công; phần không tải đủ giữ partial và khóa thao tác tương ứng.
7. Nếu yêu cầu tương lai đòi không bỏ lỡ event hoặc snapshot đồng nhất tuyệt đối, phải có ticket backend riêng cho subscribe-ready handshake/revision/snapshot/outbox. T-018 không tự thêm broker hay hứa exactly-once/FIFO.

### 3.7. Phối hợp optimistic mutation và kéo thả

- Thêm mutation coordinator/lock đồng bộ bằng ref, không chỉ state render, để chặn double submit cùng tick. Local move/reorder/save và remote refetch cùng chia sẻ scope/generation.
- Khi drag đang diễn ra, nhận structural invalidation (task-list/columns/board) thì đánh dấu drag invalid, thông báo và kết thúc/hủy preview theo API DnD. Drop handler phải kiểm cờ invalid trước khi gửi move; flush dirty rồi người dùng kéo lại. Comment-only event không cần hủy drag.
- Nếu mutation đã gửi: giữ remote scopes dirty, không replace/rollback toàn board giữa request. Khi settle, hợp nhất scopes từ mutation và remote event, đọc canonical state rồi unlock. Không kéo lại bằng vị trí tính từ snapshot đã biết stale.
- Mutation lỗi chắc chắn: bỏ preview cục bộ của thao tác, reconcile; chỉ phục hồi snapshot nếu không có cập nhật mới hơn và snapshot còn đúng generation. Mutation đã commit/timeout/5xx chưa rõ: không rollback thành “chưa lưu”, không tự gửi lại POST/PUT/PATCH; GET đối soát.
- Event đến trước HTTP response, sau response hoặc do tab khác cùng actor đều xử lý. Local API response có thể patch nội dung hợp lệ trước, nhưng không được ghi đè snapshot mới hơn sau remote reconcile; có request ordering chung.
- Board archived/deleted, task deleted, mất quyền có hiệu lực khóa ngay; không đợi mutation lock kết thúc để tiếp tục cho phép thao tác.

### 3.8. Tích hợp modal và comments

- Thêm prop/signal như `detailRefreshKey`/`commentsRefreshKey`, lý do invalidation và terminal state; không remount modal bằng key eventId, không dùng DOM custom event toàn cục.
- Form clean: đọc detail mới, kiểm taskId/boardId/workspaceId và task.version trước apply. Form dirty: lấy bản mới vào `remoteTask`, giữ draft và base version để If-Match tiếp tục phát hiện conflict; chỉ đổi base khi người dùng chấp nhận hướng xử lý.
- Dirty là trạng thái tại thời điểm response về, không chỉ lúc bắt đầu GET: nếu đang tải mà người dùng gõ, không overwrite. Khi saving/sending comment thì dồn refresh đến settle; request detail cũ không được kéo version lùi sau save thành công.
- Comment-only invalidation cập nhật comments, giữ draft task/comment, member search và focus. Tải lại các trang comments đã xem từ page 0, dedupe ID và giữ thứ tự server; không bỏ trang cũ âm thầm hoặc tự cuộn người đang đọc lên đầu.
- Task moved/reordered cập nhật vị trí/status trên board và detail cần thiết; task deleted vô hiệu hóa save/comment ngay. Board archived chuyển canEdit false và giải thích draft chưa được lưu; board deleted/mất quyền clear dữ liệu feature theo policy mục 2.
- Lỗi refresh detail/comments không xóa nội dung đã tải hoặc draft; chỉ báo stale/error và cho retry đọc. Không lấy event như xác nhận PUT/POST đã thành công.

### 3.9. Bảo mật, khả năng phục hồi và quan sát

- Client chỉ CONNECT/SUBSCRIBE/UNSUBSCRIBE/DISCONNECT; mutation vẫn qua REST có validation/quyền/Turnstile/quota hiện hữu. Không SEND để thay đổi task hoặc phát bản tin vào `/topic/boards/*`.
- Kiểm quyền phía server là bắt buộc; runtime validation chỉ bảo vệ UI. Test IDOR trên REST lẫn STOMP và outbound sau revoke, không chỉ kiểm “ẩn nút”.
- Tắt STOMP debug/raw-frame logging trong production; không log CONNECT headers, JWT, refresh token, description/comment hoặc toàn event/request body. Không lưu event/draft/cache board vào persistent storage mới. Session storage hiện hữu của Auth là baseline, không mở rộng phạm vi token.
- Dùng diagnostic có cấu trúc tối thiểu: transition kết nối, reason allowlist, số retry, thời lượng refetch, số invalidation gộp/trùng/hỏng, kết quả reconcile. Chưa có telemetry frontend thì giữ interface/diagnostic dev và evidence test; không tự thêm endpoint thu thập dữ liệu.
- Đối chiếu metrics backend hiện có: `devflow.board.realtime.publications`, `.publication.duration`, `.deliveries`, `devflow.websocket.board.subscriptions`, `.rejections`. Không dùng UUID/user/board ID làm metric tag cardinality cao, không xem publication duration là end-to-end latency.
- UI có status text + màu, `aria-live="polite"`, nút thử đồng bộ dùng được bằng bàn phím, không toast mỗi event. Mạng lỗi không lặp thông báo vô hạn hoặc phá focus/modal.
- Kiểm dependency/lockfile và bundle sau thêm STOMP; cấu hình WSS/allowed origins chính xác, không nới origin `*`. Proxy idle timeout phải lớn hơn heartbeat hợp lý và được test qua Nginx.

## 4. Thứ tự triển khai và file dự kiến

| Bước | Kết quả triển khai hiện tại | Còn lại trước nghiệm thu |
|---|---|---|
| 1 | Đối chiếu contract v1 từ Java và giữ nguyên REST/WebSocket hiện có | Smoke kiểm quyền, CONNECT/SUBSCRIBE và proxy trên backend thật; ghi fixture/evidence |
| 2 | AuthContext cấp token hợp lệ, dùng refresh single-flight và chặn response phiên cũ | Chạy case expiry/logout/đổi user/GET401 đồng thời |
| 3 | Parser v1, allowlist, dedupe tối đa 1.000 event/5 phút và dirty scopes đã có | Thêm/chạy unit tests cho đủ 11 type, malformed, duplicate và routing |
| 4 | `useBoardSync` quản lý native WS, CONNECT token, subscribe, heartbeat, reconnect và cleanup | Fake transport tests, reconnect thật, expiry và quota/error |
| 5 | `boardSyncCoordinator.ts` tải snapshot có staging, concurrency 4, tối đa 50 trang/lượt; reconcile nạp lại các trang đã hiển thị và sequence/generation bảo vệ response cũ | Chạy race/pagination/partial/error scenarios |
| 6 | Board, DnD và modal/comments đã tích hợp; có status read-only, tombstone xóa task và giữ draft | Component tests, accessibility/manual và multi-tab E2E |
| 7 | Chưa chạy E2E hai browser, offline/restart/token/quyền, Nginx, latency hoặc soak | Cần môi trường G1/G4; cập nhật từng Actual cùng evidence |
| 8 | Build và lint các file thay đổi đã qua; ghi nhận limitations ở mục 8 | Full test report, review/PR và cập nhật Trello; chưa chuyển Done |

| File/nhóm file | Thực hiện hiện tại |
|---|---|
| `frontend/src/hooks/useBoardSync.ts` | Native STOMP lifecycle, token CONNECT/renew, subscribe một board, status và retry |
| `frontend/src/realtime/boardMutation.ts` | Types và runtime parser v1, UUID/enums/nullable/array/body-size checks |
| `frontend/src/board/boardSyncCoordinator.ts` | Dirty scopes, dedupe work helpers, giới hạn page/concurrency và fetch/staging snapshot |
| `frontend/src/auth/AuthProvider.tsx` | Token API, session generation, stale refresh guard và giữ session khi refresh gặp lỗi tạm thời |
| `frontend/src/api/boardApi.ts` | AbortSignal cho GET board/task/comment |
| `frontend/src/pages/BoardPage.tsx` | Hook/coordinator, reconcile, reconnect/focus/periodic sync, drag invalidation và status UI |
| `frontend/src/components/board/TaskDetailModal.tsx` | Refresh detail/comments có sequence guard; giữ draft và khóa terminal/read-only state |
| `frontend/package.json`, lockfile | Đã thêm `@stomp/stompjs` `^7.4.0`; chưa thêm test runner |
| Unit/component/E2E tests | Chưa thêm/chưa chạy; mọi Actual trong ma trận vẫn `CHƯA CHẠY` |

Chưa sửa backend/DB. Reconnect hiện dùng backoff của STOMP Client, có jitter ban đầu nhưng không có jitter mỗi lần và chưa tạm dừng hoàn toàn offline; hai điểm này được ghi rõ để cân nhắc khi có kết quả RC/soak.

## 5. Tiêu chí hoàn thành

### 5.1. Acceptance criteria chức năng

- [ ] **AC01 — Gốc Trello:** hai tài khoản ở hai browser/context độc lập cùng board; A kéo card, B tự đổi đúng cột/vị trí, không F5, không task trùng. Lặp move giữa cột và reorder trong cột.
- [ ] **AC02 — Gốc Trello:** ngắt mạng B, A tiếp tục sửa; mạng B trở lại thì B tự reconnect/resubscribe và khớp REST mới nhất, không cần thao tác tay.
- [ ] **AC03:** xử lý đủ 11 mutation types, filter/totals/pagination đúng; comments dùng TASK_DETAIL đúng scope; state hội tụ sau event trùng/đến muộn.
- [ ] **AC04:** reconnect/initial/focus/silent-loss resync có kiểm thử; socket connected nhưng fetch lỗi không báo fully synced.
- [ ] **AC05:** event trong drag/save không gửi vị trí stale, rollback cũ hoặc replay mutation; archived/deleted/mất quyền khóa thao tác phù hợp.
- [ ] **AC06:** modal clean được làm mới; dirty/saving giữ draft và base version; comments mới không xóa nội dung đang gõ.
- [ ] **AC07:** board/workspace change, unmount, StrictMode, logout/login khác user không còn connection/subscription/request/timer rò rỉ hoặc response cũ apply.
- [ ] **AC08:** expiry/refresh rotation, refresh đồng thời, quota, quyền và generic STOMP ERROR có trạng thái kết thúc/retry đúng; không lộ token/PII.

### 5.2. Chất lượng và bằng chứng nghiệm thu

- [ ] **Q01:** `npm run build`, `npm run lint`, suites unit/component/E2E đã thêm đều qua; backend `check` và `:verifyModuleBoundaries` qua tại commit nghiệm thu.
- [ ] **Q02:** không lỗi task trùng, task mất do phân trang, totals/positions sai hoặc stale overwrite trong stress/race suite; partial phải được hiển thị rõ.
- [ ] **Q03:** đạt mục tiêu cập nhật < 1 giây theo protocol đo mục 6.3; công bố P50/P95/max và mọi mẫu vượt ngưỡng. Không dùng thời gian broadcast backend làm bằng chứng UI.
- [ ] **Q04:** reconnect/silent loss hội tụ đúng budget và điều kiện đã nêu; không socket/timer leak sau 30 lần chuyển board hoặc 30 phút soak.
- [ ] **Q05:** manual keyboard/screen reader/mobile, HTTPS/WSS và Nginx/proxy được kiểm chứng; dependency/security review không còn lỗi nghiêm trọng ảnh hưởng feature.
- [ ] **Q06:** mỗi test có Actual và evidence/bug ID; case chưa chạy/blocked không ghi PASS. Hai AC gốc có video/trace hai browser dùng backend thật; PR được review/merge, checklist Trello cập nhật theo kết quả.

## 6. Kế hoạch testing

### 6.1. Tầng test và nguyên tắc ghi kết quả

| Tầng | Cách kiểm | Mục đích |
|---|---|---|
| Unit | Parser/planner/dedupe/backoff; fake clock + seeded jitter; fake GET promises có thể điều khiển thứ tự | Biên dữ liệu, gom event, cancel và request ordering xác định được |
| Hook/component | React StrictMode + fake STOMP adapter + mock REST; tương tác DnD/modal/filter | Kiểm lifecycle, UI state, bảo toàn draft, request counts, cleanup |
| Contract/backend integration | JSON fixture từ records Java, backend/STOMP client thật với PostgreSQL; kiểm rollback/quyền/quota | Chứng minh wire/auth/delivery thật, không dựa hoàn toàn vào mock |
| Browser E2E | Playwright đề xuất: hai context tài khoản A/B; thêm hai browser engine/profile ở demo | Mutation REST thật → STOMP thật → GET thật → UI B; offline/restart/proxy |
| Manual/phi chức năng | Keyboard/mobile, performance, soak, network traces và log/metrics đã che secret | Những hành vi không thể kết luận từ build hoặc mock |

Mỗi ID có thể gồm nhiều subcase; khi chạy tách thành `ID.a`, `ID.b`... nếu kết quả khác nhau. Trạng thái: `CHƯA CHẠY`, `PASS`, `FAIL`, `BLOCKED`, `PARTIAL`; chỉ PASS khi toàn bộ subcase có evidence. Actual điền theo mẫu: `trạng thái · kết quả quan sát · thời điểm/commit/env · evidence · bug/retest`. Expected là thiết kế, không phải kết quả quan sát.

### 6.2. Dữ liệu và điều kiện chạy

- A/B cùng workspace W1/board X; C là outsider hoặc chỉ thuộc W2/board Y. Tài khoản khác nhau; thêm hai tab cùng A để test không bỏ self-event. Dùng tài khoản/fixture riêng, không ghi email/token thật vào artifact.
- Board X có TODO/IN_PROGRESS/IN_REVIEW/DONE, cột rỗng, hai cột cùng tên/category, task title tiếng Việt/emoji, đủ các priority, assignee null/A/B và dueDate null/có hạn.
- Fixtures task-list: 0, 1, 99, 100, 101, 201 task; vượt 50 trang trong test mock riêng; comments 0/1/21/41, timestamp trùng. Có board archived, task/column/board có thể xóa trong lúc GET đang chờ.
- Dùng fixture/API hỗ trợ tạo và cleanup theo đúng quyền. Create/delete chưa có UI được phát bằng REST với token/quota/Turnstile hợp lệ; không dùng STOMP SEND giả làm mutation hệ thống.
- Có backend + PostgreSQL thật, origins hợp lệ và Turnstile config rõ cho test. Test stub Turnstile chỉ dùng profile kiểm thử được cho phép; kiểm rejection với bảo vệ bật ở suite riêng.
- Fake transport tạo event trùng, đảo thứ tự, JSON sai, schema mới, silent loss; fake GET tạo response out-of-order, timeout/abort/429/5xx. Không coi các ca này là bằng chứng E2E thật.
- Browser dev/prod: Vite 5173, Docker/Nginx 3000; ít nhất Chromium và Firefox khi toolchain sẵn sàng, thêm Chrome/Edge hai profile cho demo; viewport 360/768/1440 và zoom 200%.

### 6.3. Budget đo lường đề xuất

| Chỉ tiêu | Protocol và Expected |
|---|---|
| Realtime ổn định | Board 4 cột/500 task, 2 user, tab visible, LAN RTT <= 50 ms, backend một instance/PostgreSQL thật. Warm-up 10 mutation, đo ít nhất 100 mẫu move/reorder/update. Mục tiêu từng mẫu < 1.000 ms; ghi P50/P95/max, số mẫu vượt và điều kiện lỗi; không tự giảm AC thành chỉ P95 đạt |
| Mốc đo | Test runner dùng một đồng hồ monotonic quan sát commit đã được backend xác nhận tới lúc DOM B khớp vị trí/nội dung. Có thể lấy REST success của A làm mốc đại diện và ghi rõ giới hạn; B có thể render trước response A, khi đó ghi đã cập nhật trước mốc, không tạo số âm giả. Nếu đo từ commit chính xác, cần trace backend/đồng hồ được đồng bộ |
| Reconnect | Khi mạng/backend đã khỏe, có auth hợp lệ, tab visible, board fixture trên: hội tụ <= 45s (gồm backoff cap 30s và GET); nếu token/quota làm chậm phải ghi riêng. Đo từ thời điểm network khả dụng, không từ khi người dùng bấm retry |
| Silent loss | Drop một event khi socket vẫn connected: tự hội tụ trong <= 45s ở fixture chuẩn (chu kỳ tối đa35s + GET). Chứng minh đường realtime bằng test riêng, không dùng polling 30s thay AC01 |
| Burst | 100 event/giây trong 10s trên board có 4 cột mỗi cột 1 trang: staging/dirty có giới hạn, không hơn 4 batch starts/giây và 4 GET task-lists đồng thời; sau dừng burst <= 5s hội tụ trong môi trường khỏe |
| Soak/lifecycle | 30 phút với tối thiểu 300 mutation và 30 lần đổi board; sau settle có một socket/subscription board đang xem, unmount không còn timers/listeners feature; không tăng đơn điệu retained objects từ feature |

Ghi máy/CPU/RAM, commit backend/frontend, dataset, browser, network shaping, cấu hình retry/heartbeat/Turnstile và số request. Nếu budget không đạt, ghi FAIL hoặc BLOCKED theo nguyên nhân, không lấy bundle size/build pass thay số đo latency. Mục tiêu CRUD P95 < 200ms của dự án được đo API riêng khi kiểm tra tải do resync.

### 6.4. Bộ test kết nối và lifecycle

| ID | Thiết lập / bước kiểm | Expected | Thực tế (Actual) |
|---|---|---|---|
| CN01 | Login A, mở X với REST/WS khỏe | CONNECT JWT, đúng một SUBSCRIBE X; initial và post-connect reconcile; dữ liệu đúng | CHƯA CHẠY |
| CN02 | Mở route board khi auth chưa ready/chưa login/UUID sai | Không connect trái phép; đi login hoặc báo route lỗi, không subscribe topic tùy ý | CHƯA CHẠY |
| CN03 | StrictMode mount→cleanup→mount | Phiên cũ disposed, chỉ một client sống sau settle; callbacks cũ không apply | CHƯA CHẠY |
| CN04 | X→Y→X nhanh, response/event X đầu về sau | Chỉ generation hiện tại apply; filter/context không lẫn workspace | CHƯA CHẠY |
| CN05 | Đổi workspace, back/forward, rời route/unmount | Unsubscribe/deactivate/abort/clear timers; không callback state muộn | CHƯA CHẠY |
| CN06 | Gõ filter 30 lần, rerender và mở/đóng modal | Không reconnect/subscribe mới chỉ vì callback/props thay đổi | CHƯA CHẠY |
| CN07 | Login cùng A ở hai tab rồi tab1 kéo task | Tab2 vẫn cập nhật; không bỏ event do actorId cùng user | CHƯA CHẠY |
| CN08 | Board 0 cột/0 task, thêm cột/task từ A | B hiện dữ liệu mới đúng ID; không cần thao tác local kích hoạt hook | CHƯA CHẠY |
| CN09 | HTTPS frontend và HTTP local; quan sát URL handshake | WSS/WS đúng origin/path `/ws`, không token/query/SockJS URL | CHƯA CHẠY |
| CN10 | Cleanup trong lúc beforeConnect/refresh/CONNECT đang chờ | Không connect trở lại từ promise muộn, không timer retry mồ côi | CHƯA CHẠY |

### 6.5. Bộ test contract và mutation routing

| ID | Thiết lập / bước kiểm | Expected | Thực tế (Actual) |
|---|---|---|---|
| EV01 | A tạo task đầu/giữa/cuối cột bằng REST thật | B đọc cột, đúng card/totals/order; chỉ một task với ID vừa tạo | CHƯA CHẠY |
| EV02 | A sửa title/priority/assignee/dueDate, các nullable | B hiển thị canonical data; filter áp lại, không giữ giá trị trước | CHƯA CHẠY |
| EV03 | A move qua cột khác, kể cả cột rỗng/cùng tên | B đúng source/destination theo ID và thứ tự toàn hai cột | CHƯA CHẠY |
| EV04 | A reorder trong cột đầu↔cuối, move no-op | B thứ tự đúng; no-op không sinh yêu cầu cập nhật giả từ client | CHƯA CHẠY |
| EV05 | A delete task, B đang xem card/detail | B bỏ card, cập nhật counts/positions, khóa detail/save/comment | CHƯA CHẠY |
| EV06 | A create column, tạo ngay task trong cột mới | B resolve columns trước, task xuất hiện, không GET nhầm cột lạ | CHƯA CHẠY |
| EV07 | A update column name/category hợp lệ rồi reorder | B cập nhật metadata/order, giữ task-list theo ID, detail nhãn mới | CHƯA CHẠY |
| EV08 | A delete cột rỗng khi B còn request cột đó | Cột bị bỏ; response muộn không phục hồi cột; cột khác không mất task | CHƯA CHẠY |
| EV09 | A đổi board name/description, archive rồi restore | B metadata/list chọn board khớp, canEdit khóa/mở sau canonical refresh | CHƯA CHẠY |
| EV10 | A delete board khi B đang xem | B dừng subscription/clear feature state, về lựa chọn board an toàn | CHƯA CHẠY |
| EV11 | A tạo comment; inspect CARD_UPDATED refresh TASK_DETAIL | B chỉ refresh detail/comments cần thiết; task/comment draft không reset | CHƯA CHẠY |
| EV12 | Task mutation400/403/409/412/Turnstile reject hoặc rollback DB | Không có cập nhật thành công ở B từ mutation thất bại; DB/UI khớp | CHƯA CHẠY |

### 6.6. Bộ test parser, chống trùng và hội tụ dữ liệu

| ID | Thiết lập / bước kiểm | Expected | Thực tế (Actual) |
|---|---|---|---|
| SY01 | Phát cùng eventId 100 lần trước/sau refresh | Một invalidation hữu hiệu, không card trùng hoặc request tăng theo 100 event | CHƯA CHẠY |
| SY02 | Refresh lỗi sau event đã vào dedupe cache; server không phát lại | Dirty còn và retry đọc được, không kẹt vì event đã seen | CHƯA CHẠY |
| SY03 | Event move/update/delete tới đảo thứ tự timestamp | Không replay delta cũ; cuối cùng khớp REST, task đã xóa không resurrect | CHƯA CHẠY |
| SY04 | Event của Y tới callback X, kể cả schema đúng | Bỏ qua, không request/read cache của Y theo payload | CHƯA CHẠY |
| SY05 | JSON hỏng, null, array root, sai enum/UUID/nullability | Không crash; safe diagnostic, reconcile X có throttle, không đọc URL tùy ý | CHƯA CHẠY |
| SY06 | Schema/type/refresh target chưa hỗ trợ | Không áp dữ liệu không hiểu; fallback reconcile hữu hạn và báo tương thích nếu cần | CHƯA CHẠY |
| SY07 | Body >64KiB; affected arrays >100; positions âm/float | Bị từ chối theo guard, không block main thread/refetch storm | CHƯA CHẠY |
| SY08 | Event đến khi GET page/cột/board cũ chưa resolve | Dirty mới không bị clear bởi response cũ; request sequence ngăn overwrite | CHƯA CHẠY |
| SY09 | 0/1/99/100/101/201 task, create/delete tại ranh giới page | Replace từ page0, totals/loadedPages/complete đúng; không thiếu/trùng ID | CHƯA CHẠY |
| SY10 | Move đồng thời khi B đọc nhiều page, task trùng xuyên cột | Phát hiện/invalidate lại, không falsely current; sau settle khớp REST toàn board | CHƯA CHẠY |
| SY11 | Mock >50 trang, load more rồi invalidation offset | Hiện partial, khóa DnD cột thiếu; refresh reset page và không append cursor stale | CHƯA CHẠY |
| SY12 | Burst + LRU >1.000 IDs, fake time >5 phút | Cache/scope bounded, eviction đúng, không trailing debounce starvation, eventual convergence | CHƯA CHẠY |

### 6.7. Bộ test mất mạng, reconnect và refetch lỗi

| ID | Thiết lập / bước kiểm | Expected | Thực tế (Actual) |
|---|---|---|---|
| RC01 | B offline, A move/update/delete, B online | Tự reconnect/sub X/full reconcile; state đúng dù không có event mới sau reconnect | CHƯA CHẠY |
| RC02 | Backend restart khi B đang xem | Không F5; retry theo backoff, subscribe mới và bù toàn dữ liệu | CHƯA CHẠY |
| RC03 | Socket half-open, không close nhưng ngừng heartbeat | Phát hiện liveness loss theo ngưỡng, đóng phiên lỗi và retry, UI stale | CHƯA CHẠY |
| RC04 | Handshake treo quá10s; online/close/timeout cùng tới | Timeout có hiệu lực, một retry schedule, không nhiều socket song song | CHƯA CHẠY |
| RC05 | Flap 20 lần rồi giữ kết nối ổn định30s | Backoff/cap/jitter đúng, không reset về1s mỗi CONNECT ngắn; ổn định thì reset | CHƯA CHẠY |
| RC06 | CONNECTED nhưng GET reconcile timeout/500 | Socket connected + data stale/partial; dirty còn, không báo đã đồng bộ | CHƯA CHẠY |
| RC07 | GET429 có Retry-After; nhiều event trong lúc chờ | Không request trước hạn, scopes được gộp; hết hạn retry có giới hạn | CHƯA CHẠY |
| RC08 | Đứt mạng giữa page1/page2, retry đọc lại | Snapshot chưa đủ không ghi đè dữ liệu thành rỗng/current; retry từ page0 | CHƯA CHẠY |
| RC09 | Tab hidden lâu, token hết hạn, visible/BFCache trở lại | Renew token/reconcile trước write; không dùng timer nền để suy ra đang current | CHƯA CHẠY |
| RC10 | Drop một event, socket/heartbeat vẫn tốt | Periodic/focus resync bù đúng budget; không cần event tiếp theo | CHƯA CHẠY |
| RC11 | Mutation trong khoảng REST initial→SUBSCRIBE processing | Lượt bù/periodic phát hiện; không chờ receipt không được simple broker hỗ trợ | CHƯA CHẠY |
| RC12 | Retry đọc lỗi liên tiếp rồi user bấm Thử đồng bộ | Không storm hoặc reset board mất draft; một đợt retry có thể phục hồi trạng thái | CHƯA CHẠY |

### 6.8. Bộ test auth, quyền và quota

| ID | Thiết lập / bước kiểm | Expected | Thực tế (Actual) |
|---|---|---|---|
| AU01 | CONNECT thiếu/sai/expired token hoặc refresh token thay access token | Server từ chối, UI không đọc dữ liệu board; retry/auth flow hữu hạn | CHƯA CHẠY |
| AU02 | Access token gần expiry trong lúc socket vẫn heartbeat | Chủ động renew/reconnect với token mới, subscribe/resync; không kẹt silent drop | CHƯA CHẠY |
| AU03 | GET401 và beforeConnect cùng yêu cầu refresh | Một HTTP refresh/same session generation, cả hai dùng phiên mới | CHƯA CHẠY |
| AU04 | Refresh401/invalid token hoặc network timeout | Theo policy Auth, stop/reauth hoặc lỗi phiên rõ; không auth loop vô hạn | CHƯA CHẠY |
| AU05 | Logout trong lúc refresh đang chờ, resolve refresh sau | Không phục hồi Session/socket/cache từ response muộn | CHƯA CHẠY |
| AU06 | Logout A, login B rồi refresh/GET của A về | B không nhận token/dữ liệu của A; generation guard có hiệu lực | CHƯA CHẠY |
| AU07 | C sửa UUID để SUBSCRIBE/GET X ngoài workspace | Server từ chối/404, không lộ task/title/member trong response hoặc frame | CHƯA CHẠY |
| AU08 | Gỡ B khỏi W1 khi socket đang connected, rồi A phát event | Outbound chặn nội dung; B nhận biết qua GET kiểm quyền, clear cache/stop theo budget | CHƯA CHẠY |
| AU09 | Vượt10/session,25/user,200 tổng bằng fixture phù hợp | Server quota từ chối, client không subscribe loop; cleanup trả tài nguyên | CHƯA CHẠY |
| AU10 | Generic STOMP ERROR không có mã; GET X trả200/401/404/429 | Phân loại bằng contract/REST, UI an toàn; không parse stack trace hoặc gọi refresh vô hạn | CHƯA CHẠY |

### 6.9. Bộ test DnD, optimistic state và mutation đồng thời

| ID | Thiết lập / bước kiểm | Expected | Thực tế (Actual) |
|---|---|---|---|
| MU01 | B đang drag, A move task khác trong source/destination | Drag invalid/hủy rõ, drop không gửi vị trí stale; reconcile rồi có thể kéo lại | CHƯA CHẠY |
| MU02 | B đang drag, A xóa task/cột hoặc archive/delete board | Preview dọn, write bị khóa, terminal/readonly đúng; không resurrect snapshot | CHƯA CHẠY |
| MU03 | B drag, A chỉ thêm comment | Không hủy drag vô lý hoặc reload mọi task-list | CHƯA CHẠY |
| MU04 | Remote event trước response local move và sau response local move | Cả hai thứ tự hội tụ canonical, một task/ID; không stale HTTP patch ghi lùi | CHƯA CHẠY |
| MU05 | Local move lỗi chắc chắn, remote task khác đã đổi | Không restore cả board snapshot đè remote; rollback hẹp/reconcile đúng | CHƯA CHẠY |
| MU06 | Local move commit nhưng mất response; reconnect | GET xác nhận dữ liệu, không tự replay move hoặc falsely báo chưa lưu | CHƯA CHẠY |
| MU07 | API success nhưng GET reconcile lỗi, event cũng tới | Giữ thông báo đã lưu/chưa đồng bộ, dirty và lock còn, retry chỉ GET | CHƯA CHẠY |
| MU08 | Double click menu/drag+save trong cùng tick | Ref lock ngăn mutation xung đột, không gửi hai request do state chưa render | CHƯA CHẠY |
| MU09 | A/B cùng move/reorder nhiều task, có BOARD_BUSY409 | Respect error/no auto replay; sau settle B đúng task IDs/cột/positions/totals | CHƯA CHẠY |
| MU10 | Local pending rồi route X→Y; late success/error của X | Không unlock/notice/patch state Y từ operation cũ | CHƯA CHẠY |

### 6.10. Bộ test modal, comments, filters và UI

| ID | Thiết lập / bước kiểm | Expected | Thực tế (Actual) |
|---|---|---|---|
| MD01 | Modal clean, A sửa task; GET cũ về sau GET mới | Detail/card mới nhất, không task.version lùi hoặc modal remount | CHƯA CHẠY |
| MD02 | Modal dirty, A sửa; B gõ thêm lúc remote GET đang chạy | Giữ toàn draft/base, latest riêng; không overwrite khi response về | CHƯA CHẠY |
| MD03 | B lưu dirty từ version cũ sau remote update | Server412, bản B còn; chỉ dùng version mới sau lựa chọn xử lý conflict | CHƯA CHẠY |
| MD04 | Saving/sending comment thì event tới trước/sau response | Dồn invalidation rồi flush; không auto PUT/POST, không duplicate comment | CHƯA CHẠY |
| MD05 | 41 comments, đang gõ comment, A thêm và timestamp trùng | Refresh pages/dedupe/order đúng, giữ draft/focus/scroll, không mất comment đã xem | CHƯA CHẠY |
| MD06 | Task delete/board archive/mất quyền khi modal dirty | Save/comment khóa; thông báo/clear theo policy, không tạo lại task hoặc lưu draft persistent | CHƯA CHẠY |
| MD07 | Modal X taskA→taskB, remote taskA/GET muộn | Chỉ taskB apply; callback taskA không reset form/comment/member query taskB | CHƯA CHẠY |
| MD08 | Detail/comments GET500/timeout rồi retry | Giữ dữ liệu/draft cũ với trạng thái stale, retry đọc không gửi mutation | CHƯA CHẠY |
| UX01 | Filter title/assignee/priority, remote task vào/ra filter | Kết quả hiển thị và counts đúng, filter còn, task không bị xóa khỏi cache chỉ vì không match | CHƯA CHẠY |
| UX02 | Filter làm selected task biến mất khỏi board | Modal còn theo ID nếu còn quyền; khi close focus về control hợp lệ | CHƯA CHẠY |
| UX03 | B đang scroll board/modal, 20 event tới | Không nhảy về đầu, không loading toàn trang, không toast/aria spam mỗi event | CHƯA CHẠY |
| UX04 | Keyboard/screen reader với offline/stale/retry/readonly | Text+role/aria-live rõ, nút tới được, focus không bị cướp, không chỉ đổi màu | CHƯA CHẠY |
| UX05 | 360/768/1440 px, zoom200%, title/status dài | Status/actions dùng được, không che modal hoặc tràn nút, touch scroll/DnD không hồi quy | CHƯA CHẠY |
| UX06 | Mở/đóng modal30 lần, đổi board30 lần, reduced motion | Không listener/focus/scroll-lock leak; trạng thái kết nối và animation ổn định | CHƯA CHẠY |

### 6.11. Bộ test bảo mật, proxy và chất lượng hệ thống

| ID | Thiết lập / bước kiểm | Expected | Thực tế (Actual) |
|---|---|---|---|
| SE01 | Kết nối origin hợp lệ/lạ và WSS qua Nginx | Origin allowlist server thực thi; WSS Upgrade hoạt động, không nới wildcard | CHƯA CHẠY |
| SE02 | STOMP SEND vào topic hoặc /app/boards thay REST mutation | Server từ chối; không task thay đổi, không event giả do client gửi | CHƯA CHẠY |
| SE03 | Payload chứa script/HTML-looking title/comment/schema lạ | Render/diagnostic an toàn, không executable HTML hoặc URL từ event | CHƯA CHẠY |
| SE04 | Inspect logs/storage/trace sau login, lỗi và logout | Không raw JWT/CONNECT/body/PII mới trong diagnostics; cache feature sạch khi logout | CHƯA CHẠY |
| SE05 | DnD/save qua client sync với Turnstile/quota bật | Không bypass guard; reject giữ draft và trạng thái dữ liệu chuẩn | CHƯA CHẠY |
| SE06 | Nginx idle >2 phút rồi mutation; backend outage/proxy502 | Heartbeat/proxy timeout phù hợp, lỗi phân loại và tự phục hồi, không SPA HTML trên /ws | CHƯA CHẠY |
| QA01 | Unit/hook/component và contract suites tại commit triển khai | Toàn case tự động hóa đã định có assertion meaningful và report; không chỉ snapshot implementation | CHƯA CHẠY |
| QA02 | Frontend build/lint; backend check + boundary verification | Tất cả qua, không foreign impl import; lưu log/commit/môi trường | CHƯA CHẠY |
| QA03 | E2E hai tài khoản/context thật trên Vite và Nginx, browser engine thứ2 | AC01/AC02 và hồi quy T-016/T-017 qua; không dùng mock thay backend evidence | CHƯA CHẠY |
| QA04 | 100 mẫu latency + burst và CRUD latency khi periodic sync | Budget mục6.3 đạt, công bố phân phối/request count và từng mẫu lỗi | CHƯA CHẠY |
| QA05 | Soak30 phút, expiry/reconnect/navigation lặp, inspect heap/resources | Hội tụ cuối, bounded memory/cache, một phiên active; unmount sạch | CHƯA CHẠY |
| QA06 | Dependency/lockfile review, PR/checklist/evidence đối chiếu | Không lỗi bảo mật nghiêm trọng chưa xử lý, hai AC có demo/report; case blocked không đánh Done | CHƯA CHẠY |

### 6.12. Truy vết tiêu chí và trình tự chạy

| Tiêu chí | Các bộ test chứng minh chính |
|---|---|
| AC01 | EV03–EV04, CN07, MU04, QA03–QA04 |
| AC02 | RC01–RC05, RC08–RC09, AU02–AU04, QA03 |
| AC03–AC04 | EV01–EV12, SY01–SY12, RC06–RC12, UX01 |
| AC05 | MU01–MU10, EV09–EV10, AU08 |
| AC06 | MD01–MD08, UX02–UX03 |
| AC07–AC08 | CN01–CN10, AU01–AU10, SE01–SE06, QA05 |
| Q01–Q06 | UX04–UX06, SE01–SE06, QA01–QA06; evidence theo từng ID |

Trình tự: parser/planner/auth/lifecycle unit → component/coordinator/race → backend/STOMP smoke → E2E hai browser → lỗi mạng/quyền/proxy → performance/soak/accessibility → hồi quy các case bị ảnh hưởng. Dừng gate nếu lỗi mất dữ liệu/lộ quyền hoặc stale overwrite; sửa và retest trước khi kết luận nghiệm thu.

Các lệnh **đã tồn tại**, chạy khi triển khai/nghiệm thu, chưa được chạy trong bước chỉ viết plan này:

```powershell
# Tại frontend/
npm run build
npm run lint

# Tại backend/
.\gradlew.bat check
.\gradlew.bat :verifyModuleBoundaries

# Tại root khi kiểm cấu hình/môi trường Docker
docker compose config --quiet
```

Scripts unit/component/E2E chưa tồn tại trong package.json hiện tại. Bước 1 phải thêm, xác nhận chạy được rồi ghi tên lệnh chính xác cùng report vào Actual; không ghi `npm test` đã PASS khi chưa có runner. Nếu Docker/browser/auth fixture không sẵn sàng, ghi BLOCKED đúng case/phụ thuộc, vẫn chạy được unit/component độc lập.

## 7. Rủi ro, đánh đổi và kiểm soát

| Rủi ro | Cách xử lý / bằng chứng bắt buộc |
|---|---|
| Publication sau commit thất lạc, socket vẫn khỏe | Periodic/focus resync; RC10, không hứa exactly-once |
| Subscribe chưa xử lý trước REST snapshot | Lượt bù sau connect + periodic; RC11; không dùng receipt simple broker không hỗ trợ |
| GET phân trang không là snapshot atomic | Staging, dirty/request counters, retry giới hạn và partial rõ; SY08–SY11 |
| Realtime xen optimistic rollback gây mất cập nhật | Một coordinator, lock ref, hủy drag stale, không restore snapshot toàn board; MU01–MU10 |
| Event ghi đè draft/form hoặc version mới | Dirty-at-apply, latest riêng, giữ If-Match base; MD01–MD04 |
| Token/membership mất hiệu lực nhưng socket không đóng | Renew chủ động, GET kiểm quyền định kỳ/focus, outbound server kiểm quyền; AU02/AU08 |
| Refresh muộn khôi phục user đã logout | Session generation trong Auth và hook, test AU05/AU06 |
| Burst/reconnect storm làm tăng REST load | Dirty sets bounded, debounce max-wait, concurrency, exponential backoff và jitter ban đầu; chưa có jitter từng lần hoặc pause offline; SY12/RC04/QA04 |
| Nhiều instance backend với simple broker | Môi trường nghiệm thu một backend; scale-out cần thiết kế broker/coordination riêng, chưa thuộc T-018 |
| Test mock xanh nhưng wire/proxy thật sai | G1/G4 và QA03 trên Vite/Nginx thật, demo hai browser độc lập |

## 8. Kết quả thực tế và bàn giao

### 8.1. Kết quả triển khai hiện tại

- Đã thêm `@stomp/stompjs` `^7.4.0`, native WebSocket `/ws`, STOMP CONNECT Bearer token, subscription theo board, heartbeat, timeout, reconnect và token renewal trước hạn.
- Đã mở rộng AuthContext để bảo vệ session generation khi refresh/logout/đổi user; refresh network/5xx giữ session hiện tại, lỗi từ chối token kết thúc session.
- Đã thêm runtime parser cho schema v1, bounded event dedupe/dirty scopes, coordinator REST staging với giới hạn concurrency/pagination, abort và sequence/generation guards.
- Đã tích hợp reconcile với Board/DnD và modal/comments; offline/stale khóa thao tác, event xóa task khóa detail, draft không bị ghi đè khi refresh, reconnect/focus/visible có resync dự phòng.
- `npm run build` — PASS, gồm `tsc -b` và `vite build`.
- ESLint trên toàn bộ file frontend đã đổi — PASS. Bộ test chức năng chưa được thêm/chạy; backend `check`, `:verifyModuleBoundaries`, E2E, performance/soak và kiểm tra Vite/Nginx chưa chạy.
- Mọi dòng Actual của ma trận vẫn `CHƯA CHẠY`; không kế thừa PASS của T-017. Chưa có bằng chứng hai browser, latency < 1 giây hoặc đạt các acceptance criteria T-018.
- Thử cập nhật card Trello sang In Progress nhưng API trả `401 unauthorized card permission requested`; card vẫn Backlog theo lần đọc trước. Branch làm việc là `feature/T-018-stomp-board-sync`; chưa tạo PR/review/merge.
- Giới hạn đã biết: reconnect dùng exponential của thư viện với jitter ở delay ban đầu, chưa có jitter riêng từng lần. Việc pause khi offline đã được thêm; cần dùng RC/soak để đánh giá reconnect burst và quyết định có cần custom scheduler.

### 8.2. Mẫu cập nhật sau mỗi đợt test

| Lần chạy | Commit FE/BE, môi trường, thời điểm | Test IDs/subcases | Kết quả thực tế / số đo | Evidence / bug / retest |
|---|---|---|---|---|
| 15/10/2026 · working tree, chưa commit | DevFlow frontend local | `npm run build`; ESLint trên các file đổi; unit/component/E2E chưa chạy | Build/lint PASS; chức năng và latency chưa đo | Kết quả lệnh local; chưa có report/video |

Bàn giao gồm: diff/PR và review, config/lệnh chạy test chính xác, report có Actual từng case, video hai người cùng board, trace reconnect/resync đã che secret, số đo end-to-end và các giới hạn còn tồn tại. Chỉ đánh dấu T-018 hoàn thành khi G1–G5 và các tiêu chí ở mục 5 được chứng minh; không coi việc có file plan là triển khai xong feature.
