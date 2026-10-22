# T-016 — Plan triển khai giao diện Kanban kéo thả

**Task gốc:** [07_giao_dien_kanban_keo_tha.md](07_giao_dien_kanban_keo_tha.md). **Trello:** [T-016: Refactor Frontend BoardPage with Drag-and-Drop Column & Card UI](https://trello.com/c/Ufb9l5NX/22-t-016-refactor-frontend-boardpage-with-drag-and-drop-column-card-ui).

**Milestone:** 2. **Owner:** Xuân Lê. **Phạm vi chính:** `frontend/`. **Branch khi triển khai:** `feature/T-016-kanban-drag-and-drop` theo card. **Ngày lập:** 10/10/2026, GMT+7.

**Trạng thái:** Đang triển khai trên `feature/T-016-kanban-drag-and-drop`, dựa trên T-015 vì `develop` chưa có API board/task cần dùng. Card Trello được đọc trực tiếp; lệnh chuyển sang In Progress bị Trello trả HTTP 401 nên trạng thái chưa được cập nhật. Hai acceptance criteria gốc chưa được đánh dấu hoàn thành. Frontend build/lint đã chạy pass; E2E/backend chưa chạy vì Docker engine hiện không truy cập được.

## 1. Căn cứ, hiện trạng và ranh giới công việc

### 1.1. Yêu cầu gốc và nguồn đối chiếu

Hai acceptance criteria gốc trên card:

- Kéo thả card giữa các cột hoặc reorder trong cùng cột không bị giật lag.
- Gọi API đồng bộ vị trí ngầm và rollback trạng thái nếu request lỗi.

Task nội bộ bổ sung tải dữ liệu thật; loading/empty/error; reorder cột; lọc tên/người phụ trách/độ ưu tiên; phối hợp cửa sổ chi tiết T-017 và realtime T-018. Không suy ra T-016 phải làm toàn bộ task CRUD, Markdown, bình luận, Git badges hay STOMP client.

| Nguồn | Nội dung áp dụng |
|---|---|
| [ARCHITECTURE](../ARCHITECTURE.md), mục 2–6, 9.2, 10 | SPA gọi REST; Board sở hữu thứ tự; Auth sở hữu identity/membership; không truy cập DB hoặc backend internal từ UI |
| [PRODUCT_SPEC](../PRODUCT_SPEC.md), mục 5.1, 8 | Kanban, lọc công việc, validation, quyền truy cập, resilience, privacy, CRUD P95 < 200 ms |
| [IMPLEMENTATION_PLAN](../IMPLEMENTATION_PLAN.md), Phase 2 | Cột động, optimistic UI, move API; modal và STOMP thuộc các task phối hợp |
| [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) | Build/lint, test phù hợp, review và evidence; realtime < 1 giây là mục tiêu phối hợp T-018 |
| [Plan API task](04_api_quan_ly_va_di_chuyen_task_plan.md), [plan realtime backend](06_backend_dong_bo_board_realtime_plan.md) | Vị trí zero-based, transaction, refetch source/target, best-effort events; không lấy kết quả test T-015 làm PASS cho T-016 |
| [T-017](08_cua_so_chi_tiet_task.md), [T-018](09_frontend_dong_bo_board_realtime.md) | Điểm mở detail theo task ID; giao diện state/invalidation để realtime không ghi đè mutation đang chờ |
| [Frontend skill](../../.agents/skills/frontend-dev/SKILL.md), [coding conventions](../../.agents/rules/coding-conventions.md), [git workflow](../../.agents/rules/git-workflow.md) | React 19, TypeScript strict, Tailwind 4, REST relative paths, component/hook tách trách nhiệm |

### 1.2. Hiện trạng đọc từ source

Đường dẫn dưới đây tính từ root repository; đây là baseline source, không phải test runtime.

| Thành phần | Hiện trạng | Hệ quả khi triển khai |
|---|---|---|
| `frontend/src/pages/BoardPage.tsx` | Ba cột và thẻ mẫu hardcode | Thay bằng board/column/task thật; không xem scaffold là Kanban hoàn chỉnh |
| `frontend/src/App.tsx`, `main.tsx` | Wildcard route vào BoardPage; BrowserRouter và React StrictMode; ChatPanel toàn app | Bổ sung route board rõ ID, bảo vệ phiên, route không tồn tại; kiểm cleanup effect và vùng chat không che drop zone |
| `frontend/src/pages/LoginPage.tsx` | Nút Google mô phỏng bằng delay, chưa gắn route/login thật | Phiên đăng nhập frontend là dependency thực; không coi login/refresh provider đã có |
| `frontend/package.json` | React 19.1, Router 7.6, Vite 6.3.5, TS 5.8, Tailwind 4; chưa có DnD/cache/test packages hoặc scripts test | Chốt dependency tương thích, lockfile và thiết lập test trước khi triển khai |
| `frontend/src/types/route.ts` | Chỉ comment; chưa có DTO board/task, API client hay hooks | Không sao chép model mẫu trong skill: phải khớp record REST thực tế |
| `frontend/vite.config.ts`, `nginx.conf` | Có proxy `/api` và WebSocket | REST dùng `/api/v1/...`; E2E phải kiểm cả refresh deep link qua Nginx |
| `BoardController`, `ColumnController` | Get/list board, cột và PATCH reorder có sẵn | BoardResponse đã kèm columns; không cần tự tạo năm cột mỗi lần mở trang |
| `TaskController`, `TaskPageResponse` | GET task theo cột có phân trang; PATCH move trả task và source/destination IDs | Không lấy trang đầu làm toàn bộ danh sách; không tính vị trí từ thứ tự đang lọc |
| `TaskManagementService` | Page size 1..100; position sau khi loại task nguồn; board lock; archived/capacity guards; mặc định 1.000 task/cột | Server là nguồn chuẩn; bộ dữ liệu lớn và lỗi cạnh tranh phải được xét |
| `TaskMutationRequestGuard` | Task move yêu cầu `X-Turnstile-Token` khi Turnstile bật; có actor quota | Không chỉ gửi Bearer rồi cho rằng production move sẽ chạy |
| `AuthController` | Có login/refresh/me/workspaces; chưa thấy endpoint liệt kê thành viên workspace | Không bịa `/workspaces/{id}/members`; tên/avatar cần public contract từ Auth |
| T-015 | Có backend broadcaster và contract trong repo; T-018 client chưa triển khai | T-016 chuẩn bị state seam, không cam kết hai browser tự đồng bộ trong task này |

### 1.3. Phạm vi và dependency cần giải quyết

- **Trong T-016:** route/chọn board tối thiểu, tải card/cột, render, lọc, card move/reorder, column reorder, optimistic state, lỗi/đối soát, accessibility, responsive và các test của những luồng này.
- **Phiên đăng nhập:** phối hợp Tiến để dùng một `AuthProvider`/API client chung. Nếu dependency frontend chưa có lúc bắt đầu, hoàn thiện bridge đăng nhập tối thiểu bằng API login hiện hữu trong thay đổi được phối hợp; không tự dựng OAuth, dùng JWT mẫu hay yêu cầu người dùng dán token vào màn hình Kanban. Đây là gate trước E2E thật, không được bỏ bằng mock-only nghiệm thu.
- **Thành viên:** khi chưa có API tên thành viên, lọc theo UUID người được giao lấy từ task đã tải, có lựa chọn “Được giao cho tôi” và “Chưa giao”; dùng nhãn ID ổn định khi chưa resolve tên. Nâng cấp picker tên/avatar khi Auth public endpoint được bàn giao. Không lấy email từ JWT để dựng danh bạ.
- **Ngoài scope:** task create/edit/delete đầy đủ, modal/Markdown/comments T-017; STOMP/reconnect nhiều người T-018; Git/CI badges. Không hiển thị nút chức năng chưa thực hiện như thể đã dùng được.
- **Cột:** card mô tả Backlog/To Do/In Progress/In Review/Done, nhưng cột thực tế theo server. “Backlog” là tên cột, không phải giá trị enum mới; category hiện có TODO/IN_PROGRESS/IN_REVIEW/DONE. Board thiếu cột phải hiển thị đúng tình trạng, không tự POST seed trong GET/effect.

## 2. Kế hoạch nghiệp vụ — luồng bằng ngôn ngữ

### 2.1. Vào board và đọc dữ liệu

1. Người dùng đăng nhập, chọn workspace và board được phép truy cập; đường dẫn board có thể mở trực tiếp hoặc tải lại.
2. Trang hiển thị tên board, các cột theo thứ tự đã lưu, số task và thẻ gồm tiêu đề, ưu tiên, người được giao nếu có và hạn nếu có. Cột trùng tên vẫn là hai cột khác nhau.
3. Khi đang tải, hiển thị skeleton/loading; cột không có task có vùng thả rõ ràng. Board không có cột và bộ lọc không có kết quả là hai trạng thái khác nhau.
4. Nếu chỉ mới tải một phần cột, cho xem và tải thêm; sắp xếp chỉ bật khi danh sách liên quan đã đầy đủ. Thông báo dễ hiểu như “Tải đủ công việc để sắp xếp”. Lỗi tải một cột không biến cột đó thành rỗng.
5. Board archived vẫn đọc được, có nhãn trạng thái; thao tác kéo/sắp xếp bị khóa. Mất quyền hoặc board đã xóa có thông báo và đường quay về danh sách board.

### 2.2. Kéo thẻ và lưu thứ tự

1. Người dùng dùng handle để kéo task. Bấm card là thao tác xem, không tự bắt đầu kéo. Có cách di chuyển bằng bàn phím và menu thay thế.
2. Khi kéo, thấy vị trí chèn, cột đích và bản xem của thẻ; có thể cuộn ngang board, cuộn dọc cột, thả vào cột rỗng.
3. Thả hợp lệ làm giao diện đổi ngay và hiện trạng thái đang lưu. Chỉ gửi một request sau khi thả; kéo qua nhiều cột không ghi nhiều lần.
4. Lưu thành công: lấy dữ liệu chuẩn từ server cho các cột bị ảnh hưởng, giữ task không mất/trùng; tải lại trang vẫn đúng vị trí.
5. Server từ chối chắc chắn: trả giao diện về trước thao tác, thông báo nguyên nhân phù hợp và đồng bộ lại để phản ánh thay đổi của người khác.
6. Mất mạng/timeout sau khi đã gửi: thông báo “Chưa xác nhận được thay đổi”, tạm ngừng thao tác sắp xếp và kiểm tra lại dữ liệu server. Không thông báo chắc chắn “chưa lưu” khi server có thể đã commit.
7. Escape, thả ngoài vùng hợp lệ hoặc về đúng chỗ cũ: khôi phục bản xem, không gửi request. Không cho bắt đầu thao tác sắp xếp thứ hai trong khi thao tác đầu chưa kết thúc lưu/đối soát.

### 2.3. Sắp xếp cột và bộ lọc

- Kéo header handle để đổi thứ tự cột; card nằm nguyên trong cột của nó. Lưu/rollback dùng cùng quy tắc với card. Không kéo cột sang board khác.
- Tìm theo tiêu đề, lọc người phụ trách và mức ưu tiên; các điều kiện kết hợp bằng AND. Có “Xóa bộ lọc”; hiển thị số lượng phù hợp và tổng khi biết chắc.
- Bộ lọc toàn board chỉ có kết quả đầy đủ khi các trang cần thiết đã tải hết. Trong lúc tải, thể hiện đang tìm/tải; lỗi không được hiển thị “Không có kết quả”.
- Khi có bộ lọc, vẫn xem được task nhưng khóa sắp xếp card và menu đổi vị trí; hướng dẫn xóa bộ lọc trước khi sắp xếp. Reorder cột có thể hoạt động vì danh sách cột vẫn đầy đủ. Không thay đổi bộ lọc giữa một lần kéo.
- Điểm mở chi tiết nhận task ID để T-017 tiếp nối; trạng thái đang kéo không làm bật modal. T-018 sẽ bổ sung tự cập nhật từ người khác, còn T-016 có nút tải lại để lấy dữ liệu mới.

## 3. Kế hoạch kỹ thuật — contract, state và xử lý lỗi

### 3.1. API và kiểu dữ liệu thực tế

| Nhu cầu | Contract hiện tại | Cách dùng |
|---|---|---|
| Workspace của phiên | `GET /api/v1/auth/workspaces` | Dùng session đã xác thực; không bịa workspace ID |
| Danh sách board | `GET /api/v1/workspaces/{workspaceId}/boards?page=0&size=20&includeArchived=false` | Đọc BoardPageResponse, hỗ trợ chuyển trang; lựa chọn archived tường minh |
| Board detail | `GET /api/v1/boards/{boardId}` | BoardResponse: id, workspaceId, name, description, archived, timestamps, columns |
| Danh sách cột | `GET /api/v1/boards/{boardId}/columns` | ColumnResponse: id, boardId, name, position, statusCategory, timestamps; dùng khi reconcile cột |
| Task theo cột | `GET /api/v1/columns/{columnId}/tasks?page=0&size=100` | TaskPageResponse: items, page, size, totalElements, totalPages; items là TaskListItem, không có description/full entity |
| Move/reorder task | `PATCH /api/v1/tasks/{taskId}/move` body `{columnId, position}` | Bearer + token Turnstile mới nếu bật; TaskMoveResponse gồm task/full TaskResponse, sourceColumnId, destinationColumnId |
| Reorder cột | `PATCH /api/v1/columns/{columnId}/reorder` body `{position}` | Trả danh sách ColumnResponse; hiện controller không yêu cầu Turnstile header cho thao tác này |

`TaskListItem` dùng đúng id, columnId, title, priority, assigneeId nullable, dueDate nullable, position, statusCategory, updatedAt. Priority là LOW/MEDIUM/HIGH/URGENT. Card status từ category cột; không thêm trường `status` giả hoặc dùng tên cột để suy luận enum. TaskResponse sau move phải được ánh xạ về list model; không dùng response task đơn lẻ để đoán vị trí mới của mọi task khác.

API client đọc JSON có kiểm tra shape tối thiểu, status/content type và nullability; thiếu/malformed data phải thành lỗi tải/sync, không tự coi là danh sách rỗng. Request timeout đề xuất 10 giây, cấu hình tập trung. GET có retry giới hạn cho lỗi tạm thời với backoff/jitter; PATCH không tự retry. `AbortController` hủy request đọc khi đổi board/unmount; abort PATCH không đồng nghĩa server rollback.

### 3.2. Dependency và cấu trúc dự kiến

Đề xuất dnd-kit qua API React hiện hành `@dnd-kit/react`, `DragDropProvider`, sortable hooks, overlay và helpers nếu cần. Tài liệu hiện hành phân biệt với bộ legacy `@dnd-kit/core`/`@dnd-kit/sortable`; không trộn ví dụ/import hai đời API. Chọn bản phát hành ổn định sau spike với React 19/StrictMode, nested card-column và touch/keyboard, commit lockfile. Nếu spike không đạt, ghi quyết định thay thế và cập nhật test trước khi triển khai tiếp. [Nguồn chính thức: quickstart](https://dndkit.com/react/quickstart/), [provider](https://dndkit.com/react/components/drag-drop-provider/), [multiple lists](https://dndkit.com/react/guides/multiple-sortable-lists/).

Dùng hook/reducer cho state board và fetch wrapper chung; chưa cần thêm global store hoặc query library nếu một owner state đủ đáp ứng. Nếu dự án có shared cache/session khi triển khai, dùng lại, không tạo hai nguồn dữ liệu song song. Test đề xuất Vitest, React Testing Library cho state/component và Playwright cho trình duyệt thật; kiểm peerDependencies/engines của phiên bản trước khi cài. Tài liệu Vitest hiện tại yêu cầu Vite >= 6.4 và Node >= 22.12, trong khi repo đang Vite 6.3.5: chọn phiên bản test runner tương thích hoặc tách thay đổi nâng toolchain có kiểm chứng, không cài `latest` rồi sửa lan man. [Vitest](https://vitest.dev/guide/), [Playwright](https://playwright.dev/docs/intro).

| File/thư mục dự kiến | Trách nhiệm |
|---|---|
| `frontend/src/types/board.ts` | REST DTOs/enums, board state, mutation operation và error shape |
| `frontend/src/api/httpClient.ts`, `boardApi.ts` | Session integration, relative URL, timeout, ProblemDetail, correlation, API move/reorder/get |
| `frontend/src/hooks/useBoard.ts`, `useBoardMutation.ts` | Fetch lifecycle, pagination, operation guard, optimistic/reconcile; chia nhỏ khi cần |
| `frontend/src/components/board/` | KanbanBoard, BoardColumn, TaskCard, DragPreview, BoardFilters, loading/error và menu di chuyển |
| `frontend/src/board/boardState.ts`, `moveTask.ts` | Reducer/pure mapping vị trí, selectors; độc lập thư viện DnD để test invariant |
| `frontend/src/hooks/useTurnstileToken.ts` | Bọc widget lifecycle dùng chung; không nhúng challenge logic vào TaskCard |
| `frontend/src/pages/BoardPage.tsx`, `App.tsx` | Route `/boards/:boardId`, workspace/board selection và protected route theo auth chung |
| `frontend/src/**/*.test.*`, `frontend/e2e/` | Unit/component/network/browser cases; cấu hình test và scripts trong package.json |

Tên file là đề xuất, được điều chỉnh theo code mới nhất. Component hiển thị không gọi raw fetch. Inline styles chỉ dùng khi DnD bắt buộc cho geometry/transform; style giao diện thông thường dùng Tailwind, không lint-disable diện rộng.

### 3.3. Canonical state, phân trang và invariant

- Một owner giữ `board`, `columnOrder`, `columnsById`, `tasksById`, `taskIdsByColumn`; mỗi cột có page metadata, `complete`, loading/error và generation. Bộ lọc là derived view, không sửa canonical order.
- Task xuất hiện đúng một lần, thuộc đúng columnId; arrays không trùng ID; cột sắp theo position và ID làm tie-breaker. Không dùng array index hoặc tên làm React key/DnD ID. DnD IDs có namespace `task:<uuid>`/`column:<uuid>` và metadata kind/boardId để tránh collision.
- Tải trang đầu với size <= 100; concurrency GET tối đa 4 theo cấu hình. Chỉ bật card drag/menu khi source và target complete, không loading/error, chưa có mutation pending và board active. Cột chưa complete có nút tải đủ; cap số trang/budget theo cấu hình để không vòng lặp vô hạn. Đạt cap vẫn cho đọc/tải tiếp chủ động, không giả vờ complete.
- Trang phân trang offset không có snapshot/version: dedupe UUID, kiểm total/count/page progress, phát hiện scope/position bất thường và refetch có giới hạn. Không thể bảo đảm snapshot nguyên tử chỉ bằng count; server move và reconcile quyết định kết quả cuối khi có người khác sửa đồng thời.
- Lọc toàn board yêu cầu tải đủ mọi cột trước khi công bố kết quả hoàn chỉnh. Có hủy tải, retry cột lỗi và trạng thái progress; không gửi thêm request sau unmount. Search trim và chuẩn hóa hoa/thường, bảo toàn dấu tiếng Việt theo policy đã chốt; không tự hứa tìm không dấu. Filter assignee null riêng, priority whitelist, không render raw HTML.

### 3.4. Vòng đời kéo và cách tính vị trí

1. **Start:** kiểm gate, capture snapshot bất biến và revision local; lưu task/cột nguồn, ID thứ tự, board generation. Handle có activation threshold để tách click/scroll và drag; đề xuất pointer khoảng 6–8 px, touch delay khoảng 180–250 ms rồi chỉnh qua browser test.
2. **Preview:** overlay giữ kích thước, placeholder và insertion marker; chỉ cập nhật draft khi đổi container/index, không clone toàn board mỗi pointer event. Card type chỉ thả vào card-list/drop-area; column type chỉ vào column-order, không bắt nhầm vùng nested.
3. **End:** dùng ID đang active/over và danh sách canonical đầy đủ; loại task khỏi source/target trước khi chèn. Position là index cuối zero-based của target sau bước loại bỏ, không cộng/trừ mơ hồ theo DOM index cũ.
4. **Ví dụ bắt buộc:** `[A,B,C,D]`, đưa B xuống cuối => `[A,C,D,B]`, gửi position=3. Chuyển B từ `[A,B,C]` sang sau X trong `[X,Y]` => source `[A,C]`, target `[X,B,Y]`, gửi position=1. Cột rỗng position=0; cuối cột có N task position=N.
5. **No-op/cancel:** exact same column/order, Escape, pointer cancel, target null/sai board hoặc target incomplete => bỏ draft, không PATCH, không xin token không cần thiết.
6. **Commit UI:** merge draft vào optimistic state, đánh dấu pending và khóa mọi thao tác reorder của board cho đến reconcile xong; chỉ một operation active/board trong T-016. Không queue vô hạn những lần thả liên tiếp.
7. **Column reorder:** cùng thuật toán remove-then-insert trên `columnOrder`; gửi index cuối và áp dụng danh sách server trả. Không sửa `taskIdsByColumn` ngoài việc render theo cột mới.

### 3.5. Optimistic mutation và đối soát

Operation có local operationId, boardId, generation, base revision, affected scopes, snapshot, intent và phase: `preview → authorizing → saving → reconciling → idle/error/unknown`. OperationId chỉ giúp điều phối client, không phải idempotency key server vì API chưa có contract đó.

| Kết quả | Xử lý state và UI |
|---|---|
| Challenge/token thất bại trước PATCH | Rollback snapshot của operation hiện tại, báo chưa gửi lưu; có retry chủ động |
| PATCH 2xx hợp lệ | Apply task/list response, refetch đầy đủ source và target (cùng cột chỉ một scope); chỉ bỏ pending khi reconcile đạt hoặc có trạng thái sync-error rõ |
| PATCH thành công nhưng GET reconcile lỗi | Giữ trạng thái đã lưu, hiển thị không tải được bản mới, khóa sắp xếp scope chưa tin cậy; retry GET. Không rollback mutation server đã xác nhận |
| 400/403/404/409/429 có ProblemDetail từ server | Xem là từ chối theo contract; rollback operation còn hiện hành, refetch scopes/board; không ghi đè revision mới bằng snapshot cũ |
| Timeout, disconnect sau khi gửi, 5xx không xác định, body 2xx malformed | Mark unknown, bỏ trạng thái “đã lưu” giả, khóa move và GET reconcile; không tự gửi lại PATCH. Nếu GET vẫn lỗi, giữ cảnh báo/preview được gắn nhãn chưa xác nhận |
| Response đến sau đổi board/logout | Bỏ apply theo generation/session guard; request cũ có thể đã commit nhưng không được sửa board hoặc phiên mới |

Trong khi drag/pending, manual refresh và invalidation T-018 được gom vào dirty scopes, không thay canonical dưới con trỏ. Reconcile chạy sau phase thích hợp; invalidation trong lúc GET đang chạy buộc refetch thêm một lượt có giới hạn. Không merge payload realtime thành full task. Nếu phát hiện task/cột bị xóa hoặc board archived/revoked, hủy draft, refetch và đóng quyền thao tác. Nếu mutation của T-017 dùng chung board state, cần cùng operation coordinator; T-016 chưa có provider mới thì bàn giao contract này, không tự nhận test UI T-017/T-018 đã pass.

API chưa có version/ETag/compare-and-set: board lock bảo vệ transaction, không bảo đảm ý định reorder của client cũ thắng. Chấp nhận kết quả server và refetch; không hứa conflict detection đầy đủ. Nếu cần chống lost-update theo phiên bản, mở thay đổi backend riêng, có public contract/test và `gradlew check`/boundaries.

### 3.6. Auth, Turnstile, lỗi và privacy

- Dùng auth/session chung, Bearer header, refresh single-flight nếu provider đã hỗ trợ. GET có thể tiếp tục sau refresh hợp lệ; mutation đã gửi không được tự replay. Sau 401, phục hồi phiên và reconcile, yêu cầu người dùng thực hiện lại nếu cần. Logout clear board cache/challenge và hủy đọc đang chạy; không lưu token vào URL, console hoặc test artifact.
- Turnstile dùng site key công khai `VITE_TURNSTILE_SITE_KEY`; secret chỉ ở backend. Khi move được thả hợp lệ, lấy token mới trong phase authorizing, hỗ trợ widget managed/execute và expired/error/timeout callbacks; gửi `X-Turnstile-Token` một lần, reset/discard sau attempt. Token có hạn 300 giây và single-use theo [Cloudflare server validation](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/); không dùng lại token nếu retry. Chi tiết render/execute xem [widget configurations](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/widget-configurations/).
- Không vô hiệu hóa Turnstile production để kéo thả tiện hơn. Thiếu site key/script bị chặn/challenge hết hạn => trạng thái lỗi có cách thử lại. Test key/mock widget chỉ trong môi trường test; cần một luồng integration thật kiểm server guard đang bật. Backend hiện verify từng mutation nên chi phí challenge là rủi ro UX cần đo và phối hợp, không lặng lẽ bỏ header.
- Parse ProblemDetail `code`, `status`, `correlationId` và `Retry-After`/`retryAfterSeconds` nếu có; fallback an toàn nếu response không phải JSON. Map `INVALID_POSITION`, `TASK_LIMIT_REACHED`, `BOARD_ARCHIVED`, `BOT_CHALLENGE_REQUIRED`, `BOT_CHALLENGE_REJECTED`, `BOT_PROTECTION_UNAVAILABLE`, `RATE_LIMIT_EXCEEDED` thành thông báo và hành động phù hợp. Phân biệt 403 bot với 403 quyền; không tự logout mọi 403.
- Rate limit tôn trọng khoảng chờ; không request storm từ effect, drop lặp hoặc retry GET. Log client tối thiểu gồm operation/outcome/correlation, không task body/JWT/token/email. Không render server detail/raw HTML tùy ý. Trace/screenshot test phải dùng dữ liệu giả và che credentials.

### 3.7. Trải nghiệm, accessibility và hiệu năng

- Board cuộn ngang với cột có min-width; cột cuộn dọc, empty drop zone không co về 0; auto-scroll không làm cuộn cả trang ngoài ý muốn. Kiểm cạnh màn hình, zoom 200%, chat mở và mobile viewport.
- Handle là button có accessible name, focus ring; hỗ trợ bắt đầu/thả bằng phím, di chuyển theo hướng, Escape hủy; live region thông báo task/cột/vị trí/lưu/lỗi. Menu “Di chuyển” chọn cột/vị trí là cách thay thế kéo và dùng cùng mutation coordinator/gates.
- Không dùng màu làm dấu hiệu duy nhất; tôn trọng reduced-motion, restore focus sau drop/rollback, bảo đảm button/menu không kích hoạt drag. Touch-action điều chỉnh ở handle để giữ cuộn tự nhiên vùng nội dung.
- Memo card/column theo props ổn định, normalize state và isolate drag preview; không fetch/validate challenge mỗi onDragMove. Cột lớn cần profile trước khi chọn virtualization; nếu virtualize, phải kiểm drop geometry/keyboard/offscreen target thật.
- Đề xuất profile nghiệm thu: 5 cột, 200 task, 30 thao tác; CPU throttling x4, mạng mô phỏng 150 ms RTT; UI preview/drop P95 <= 100 ms, frame time P95 <= 32 ms trong drag. Đây là budget đề xuất cần ghi thiết bị/browser/build khi đo, không phải số đã đạt. Thêm stress 1.000 task/cột theo limit cấu hình để kiểm lỗi/hang; không suy SLA từ demo local.
- Báo riêng thời gian drag feedback, challenge, PATCH và reconcile; đối chiếu mục tiêu CRUD P95 < 200 ms của dự án bằng phép đo API có ghi rõ phạm vi và môi trường, không suy ra từ độ mượt giao diện. Đồng bộ hai browser < 1 giây nghiệm thu ở T-018.

## 4. Thứ tự triển khai và bàn giao

| Bước | Công việc | Điều kiện chuyển bước |
|---|---|---|
| 1. Chốt baseline | Đọc card/source mới nhất; kiểm API, auth bridge, member display policy, site key/test keys; ghi version dependencies | Có đường đăng nhập và dữ liệu thật để E2E; không bịa endpoint/contract |
| 2. Spike + test setup | DnD React 19/StrictMode/nested lists/touch/keyboard; chọn test runner tương thích Vite/Node; lockfile | Demo spike có kiểm chứng; `build`/`lint` qua, scripts test tồn tại |
| 3. Data foundation | DTOs, API wrapper, route board/workspace, reducer, pagination/loading/empty/error và filter completeness | Tải board thật, deep link/reload đúng, scope và stale response được test |
| 4. Card/cột UI | Card fields, responsive scroll, empty targets, filters, read-only/archive, handles/menu | Reading/filter UX rõ; accessibility cơ bản; không còn sample task hardcode |
| 5. Move pipeline | Pure position calculation, optimistic draft, Turnstile, PATCH, rollback/unknown/reconcile, column reorder | Unit/component và fault-injection test pass trước E2E |
| 6. Integration và polish | Browser thật + backend/PostgreSQL, production guard bật, reload persistence, keyboard/touch, profile | Hai AC gốc có evidence; không còn P0/P1 fail/skip không có lý do |
| 7. Review/bàn giao | Cập nhật actual/case reports, contract T-017/T-018, commit/PR theo repo; cập nhật Trello khi triển khai | PR được review trước merge; Done sau khi merge và đủ gate, không chỉ sau build |

T-016 hiện đã bắt đầu trên branch theo card và dùng contract/backend T-015 làm base do các dependency T-012–T-015 chưa nằm trong `develop`. Lệnh chuyển card sang In Progress bị Trello từ chối quyền (HTTP 401), cần xử lý quyền Trello để đồng bộ trạng thái. Nếu sửa API/guard ngoài scope frontend phải nêu thay đổi và chạy backend check/boundaries. Không chuyển card In Review khi chưa có PR theo workflow repo.

## 5. Tiêu chí hoàn thành

| ID | Tiêu chí | Evidence bắt buộc |
|---|---|---|
| AC-01 | Card kéo giữa cột và reorder cùng cột mượt; empty/first/middle/last, no-op/cancel đúng | DR/UX/PF: browser thật và profile ghi điều kiện; không chỉ gọi handler trong unit test |
| AC-02 | Optimistic update, một PATCH/drop; reject rollback đúng; unknown reconcile an toàn | SV/SE: network control, DOM trước/sau, request/body/count và server order |
| AC-03 | Route/session và dữ liệu thật; pagination/filter không làm sai thứ tự; reload giữ kết quả | LD/FL/IN: >100 task/cột, deep link, canonical/refetch assertions |
| AC-04 | Column reorder lưu/rollback đúng và không làm chuyển task sang cột khác | CO: API + persistence + cancel/failure |
| AC-05 | Archived/quyền/Turnstile/expiry/rate limit xử lý rõ; không lộ dữ liệu phiên khác | SE/SV: auth thật và guard bật; kiểm logs/artifacts |
| AC-06 | Keyboard/menu/touch dùng được; mobile/zoom/chat không cản thao tác; đủ loading/error states | UX: automated + manual focus/screen reader/touch checks |
| AC-07 | Build/lint và test phù hợp pass; dependency tương thích; có review và actual không thổi phồng | QA/IN và reports; backend gates nếu có backend change |
| AC-08 | T-017/T-018 nhận được task selection và state/invalidation seam có quy tắc pending | IN: contract test/harness; UI modal/STOMP thật ghi riêng ở task sở hữu |

**Gate đóng T-016:** Hai AC gốc và tất cả ca P0/P1 áp dụng có bằng chứng đạt; không còn lỗi sai vị trí, mất/trùng thẻ, rollback ghi đè dữ liệu mới, bypass quyền hoặc mutation mơ hồ bị replay. Ca P2/ngoài scope có backlog và lý do rõ. `npm run build` pass không thay cho E2E, kiểm Turnstile hay đo độ mượt. Mục tiêu và lựa chọn thiết kế thay đổi phải cập nhật plan/case tương ứng trước nghiệm thu.

## 6. Kế hoạch testing — Expected và thực tế

### 6.1. Fixture, lớp kiểm thử và cách ghi nhận

P0 = dữ liệu/quyền/reliability chặn release; P1 = chức năng/UX bắt buộc; P2 = stress hoặc mở rộng. UT = pure reducer/mapper; CT = component/hook với API mock có chủ đích; E2E = browser thật + backend/PostgreSQL; MAN = kiểm thủ công; PERF = profiling. Mock test không chứng minh persistence hoặc server authorization.

Fixture: W1/W2, U1/U2 thuộc W1 và U3 thuộc W2; B1/B2 của W1, B3 của W2, BA archived, BX không tồn tại. B1 có năm cột ID khác nhau, thêm cột trùng tên; C0 rỗng, C1 có `[A,B,C,D]`, C2 có `[X,Y]`, Cbig 101/201 task, Cfull đạt configured capacity. Task có đủ LOW/MEDIUM/HIGH/URGENT, assignee null/current/other/unresolved, dueDate null/quá hạn/tương lai, tiêu đề Unicode/HTML-like. Sinh UUID riêng theo test, không dùng dữ liệu production.

- UT parameterize remove/insert index và invariants, assert input snapshot không bị mutate; kiểm số task mỗi scope và membership task exactly once, không snapshot test DOM lớn thay cho logic.
- CT fake/deferred network điều khiển thứ tự response, Abort/timeout/token callbacks; assert trạng thái và request count/body. Mock widget riêng và có test production guard thật bổ sung.
- E2E dùng login thật, seed qua fixture hợp lệ, backend/PostgreSQL cô lập; chờ GET/DOM/response predicate, không sleep theo phỏng đoán. Thực sự kéo pointer/touch/keyboard; kiểm PATCH, GET sau đó, reload và server order.
- Có Chrome/Chromium, Firefox, WebKit desktop cho core cases; mobile emulation và ít nhất một touch device thật cho nghiệm thu cảm ứng. Manual screen-reader/focus kết hợp automated accessibility; emulator không thay bằng chứng thiết bị thật.
- Failure tests phải chứng minh cả rollback/reconcile và không gửi thừa PATCH; test absence sau cancel dùng network recorder + cửa sổ quan sát có deadline.
- Ma trận dưới gồm **64 nhóm case**; mở rộng parameterized variants nêu trong cột thao tác. Các ca chức năng hiện vẫn `Chưa chạy`; QA-01/QA-02 ghi riêng kiểm tra build, lint và dependency đã thực hiện. Khi chạy ghi PASS/FAIL/BLOCKED, biến thể đã/chưa chạy, ngày, commit, browser/env và report. Skip không tính PASS; partial chỉ ghi phần có evidence.

### 6.2. Tải board, API và phân trang

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| LD-01 / P1 | CT/E2E | Login thật, chọn workspace/board, mở deep link và reload | Board đúng ID/quyền; không route wildcard sang board mẫu; session dùng được | Chưa chạy |
| LD-02 / P1 | CT/E2E | Board có 0/1/5 cột, cột trùng tên và tên tùy chỉnh | Render theo position/ID, không tự seed, không hardcode năm cột hoặc mất cột trùng tên | Chưa chạy |
| LD-03 / P1 | CT/E2E | Loading, board không có cột, cột không có thẻ, API lỗi một cột | Loading/empty/error phân biệt; retry đúng scope; empty target giữ kích thước | Chưa chạy |
| LD-04 / P0 | CT/E2E | Cột 0/1/100/101/201 task, nhiều page | Không thiếu/trùng ID; tổng đúng; complete chỉ sau đủ trang; size <=100 | Chưa chạy |
| LD-05 / P0 | CT | Source/target chưa đủ trang hoặc lỗi trang thứ hai | Drag/menu disabled đúng scope; load đủ/retry mở lại; không dùng index trang đầu gửi move | Chưa chạy |
| LD-06 / P1 | CT | Offset pages có ID trùng, count thay đổi, không tiến triển, cap tải | Dedupe, bounded refetch/budget và cảnh báo; không loop hoặc báo complete giả | Chưa chạy |
| LD-07 / P0 | CT/E2E | Chuyển nhanh B1→B2, response B1 về sau, unmount/StrictMode | Không apply dữ liệu cũ, cleanup abort đọc; không tạo mutation/effect trùng | Chưa chạy |
| LD-08 / P0 | CT | Response 2xx sai schema, null items, malformed JSON/HTML lỗi | Báo lỗi tải/sync an toàn; không dựng danh sách rỗng hoặc crash page | Chưa chạy |
| LD-09 / P1 | E2E | Vite/Nginx proxy, route `/boards/{id}` refresh | Relative REST chạy được; SPA fallback đúng, không 404 asset/deep link | Chưa chạy |

### 6.3. Card drag, vị trí và column reorder

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| DR-01 / P0 | UT/E2E | Cùng cột: đầu→cuối, cuối→đầu, giữa→giữa, kề lên/xuống | Remove-then-insert đúng final index; task duy nhất; PATCH đúng columnId/position | Chưa chạy |
| DR-02 / P0 | UT/E2E | Khác cột: đầu/giữa/cuối nguồn vào đầu/giữa/cuối đích | Normalize hai scope; không mất/trùng task; source/destination đúng ID | Chưa chạy |
| DR-03 / P0 | UT/E2E | Chuyển vào cột rỗng; nguồn chỉ có một task | Position=0; source empty target giữ đúng; count/UI chính xác | Chưa chạy |
| DR-04 / P0 | UT/E2E | Cột trùng tên/category; task trùng title | Dùng UUID, không tìm theo tên hoặc array key index; move đúng task/cột | Chưa chạy |
| DR-05 / P1 | CT/E2E | Về chỗ cũ, Escape, ngoài board, over=null, pointer cancel | Restore preview; 0 PATCH; không xin Turnstile khi no-op | Chưa chạy |
| DR-06 / P1 | E2E | Kéo qua nhiều cột rồi quay về/thả đích cuối | Chỉ một PATCH cho final drop; preview không ghi DB dọc đường | Chưa chạy |
| DR-07 / P0 | CT | Sai kind, target board khác, active task biến mất | Reject intent, bỏ draft/refetch; không tạo request với scope sai | Chưa chạy |
| DR-08 / P1 | E2E | Click card/handle, click menu/link, text selection, kéo dưới ngưỡng | Click không thành drag, drag không bật modal; control tương tác bình thường | Chưa chạy |
| DR-09 / P1 | E2E | Cuộn ngang board/dọc cột, kéo qua cạnh, overlay trên chat | Insertion đúng; scroll không nhảy bất thường; preview không chặn drop | Chưa chạy |
| CO-01 / P0 | UT/E2E | Reorder cột đầu/giữa/cuối, 1 cột, trùng tên | PATCH position cuối đúng; task không đổi columnId; reload giữ column order | Chưa chạy |
| CO-02 / P0 | CT/E2E | Column reorder lỗi/cancel/no-op | Rollback đúng order, 0 PATCH cho cancel/no-op, task order không bị sửa | Chưa chạy |
| CO-03 / P1 | E2E | Kéo card vào header hoặc kéo cột vào task list | Không lẫn hai sortable kind; không vô tình gọi API của kind khác | Chưa chạy |

### 6.4. Optimistic save, rollback, concurrency và network faults

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| SV-01 / P0 | CT/E2E | Delay PATCH thành công, quan sát trước response | UI đổi ngay, pending rõ, đúng 1 PATCH; không đợi request xong mới đổi | Chưa chạy |
| SV-02 / P0 | CT/E2E | 2xx task response và thứ tự chuẩn khác optimistic | Reconcile source/target, accept server order; chỉ refetch một scope khi cùng cột | Chưa chạy |
| SV-03 / P0 | CT/E2E | 400 invalid position / 403 denied / 404 task/column mất | Rollback current operation và refetch; không mất thẻ, không replay PATCH | Chưa chạy |
| SV-04 / P0 | CT/E2E | 409 BOARD_ARCHIVED/TASK_LIMIT_REACHED | Báo đúng nguyên nhân, rollback, archived chuyển read-only; không loại task khỏi source | Chưa chạy |
| SV-05 / P0 | CT/E2E | PATCH 2xx rồi GET reconcile 500/timeout | Giữ saved/needs-sync rõ; không rollback thành công; retry GET và khóa reorder đến khi tin cậy | Chưa chạy |
| SV-06 / P0 | CT/E2E | Timeout trước commit, timeout sau commit, mất response/5xx mơ hồ | Unknown→GET canonical; không khẳng định thất bại/đã lưu giả; không PATCH tự retry | Chưa chạy |
| SV-07 / P0 | CT | Body 2xx thiếu task/scope hoặc khác task ID | Không apply nhầm; unknown/reconcile; thông báo lỗi protocol an toàn | Chưa chạy |
| SV-08 / P0 | CT/E2E | Drop lần hai/column reorder/menu move khi request đầu pending | Chỉ một operation active; không queue phát lại, pending thứ hai không phá snapshot đầu | Chưa chạy |
| SV-09 / P0 | CT | Đổi board/logout/refresh generation trong lúc PATCH chờ | Response/rollback cũ không sửa board/phiên mới; reconcile khi quay lại | Chưa chạy |
| SV-10 / P0 | CT | Stale GET/manual refresh/invalidation trong drag hoặc pending | Dirty scopes deferred; không giật task dưới pointer, không snapshot overwrite revision mới | Chưa chạy |
| SV-11 / P0 | E2E | Client khác sửa/xóa task/cột hoặc archive board trước drop | Từ chối hoặc server order cuối có đối soát; no lost/duplicate; không hứa CAS chưa có | Chưa chạy |
| SV-12 / P1 | CT/E2E | Offline trước drag/trước gửi; reconnect và nút retry | Báo trạng thái, không move storm; tải lại trước khi cho thao tác mới; retry mutation là ý định mới | Chưa chạy |
| SV-13 / P0 | UT/CT | Snapshot arrays bị sửa ngoài reducer, rollback lặp, out-of-order completions | Snapshot immutable; guard operationId/generation; cleanup idempotent, input không bị mutate | Chưa chạy |

### 6.5. Filter, hiển thị và phối hợp task khác

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| FL-01 / P1 | UT/CT | Search rỗng/space, hoa thường, dấu tiếng Việt, emoji/Unicode | Trim/case policy đúng, không lỗi Unicode; không tự tìm không dấu nếu chưa hỗ trợ | Chưa chạy |
| FL-02 / P1 | UT/E2E | Lọc null/current/other assignee và đủ bốn priority | UUID/enum đúng, nhãn unresolved rõ; AND khi kết hợp, reset khôi phục order | Chưa chạy |
| FL-03 / P0 | CT/E2E | Có filter rồi thử pointer/keyboard/menu sắp xếp card | Disabled nhất quán, giải thích/xóa lọc; 0 PATCH, không gửi visible index | Chưa chạy |
| FL-04 / P0 | CT/E2E | Filter khi chỉ tải trang đầu, kết quả nằm ở trang sau | Tải đủ/tiến trình rõ; không false-empty/đếm thiếu; lỗi tải thành error | Chưa chạy |
| FL-05 / P1 | CT/E2E | Không kết quả, filter đổi nhanh, đổi trong drag | Derived state không sửa order; cancel/lock policy đúng; không stale search | Chưa chạy |
| FL-06 / P1 | CT/E2E | Reorder cột khi filter active | Column order lưu được; không sửa card order hoặc bỏ hidden task | Chưa chạy |
| FL-07 / P1 | CT/MAN | Date null/quá hạn/tương lai, timezone, title dài, assignee chưa resolve | Không Invalid Date; text wrap/truncate có accessible name; không bịa tên/avatar | Chưa chạy |
| IN-01 / P1 | CT | Chọn task và đóng selection; drop không click-open | taskId đúng cho T-017, không task object stale; contract test không tính modal thật pass | Chưa chạy |
| IN-02 / P0 | CT | T-018 harness gửi invalidate cùng/khác scope, cả khi GET reconcile chạy | Không duplicate, gom dirty/refetch bounded; không apply board cũ; STOMP network thuộc T-018 | Chưa chạy |

### 6.6. Auth, Turnstile, lỗi và bảo mật

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| SE-01 / P0 | E2E | Thiếu/hết hạn JWT, login/refresh, logout rồi user khác | Protected route đúng, cache xóa; không dữ liệu/token phiên cũ; mutation không silent replay | Chưa chạy |
| SE-02 / P0 | E2E | U3 mở board W1, giả URL board/task/cột ngoài scope | Server từ chối; UI không lộ cached board, scope từ response không bị tin mù | Chưa chạy |
| SE-03 / P0 | CT/E2E | Guard bật, token mới hợp lệ cho move | X-Turnstile-Token gửi đúng 1 lần; PATCH lưu, token reset; không bỏ challenge production | Chưa chạy |
| SE-04 / P0 | CT/E2E | Token thiếu/sai/hết hạn/đã dùng; challenge 400/403/503 | Rollback/authorizing error đúng, xin token mới cho attempt mới; không logout sai vì bot 403 | Chưa chạy |
| SE-05 / P1 | CT/MAN | Script Turnstile bị chặn, sitekey thiếu, widget expired/error/unmount | UI retry rõ, cleanup widget; không pending vô hạn, không vô hiệu hóa guard để vượt lỗi | Chưa chạy |
| SE-06 / P0 | CT/E2E | 429 có Retry-After / thiếu header; drop/thử lại liên tục | Cooldown hữu hạn/fallback, không retry storm, token không reuse | Chưa chạy |
| SE-07 / P0 | CT/E2E | ProblemDetail chuẩn, lỗi HTML/JSON lỗi, detail có HTML/stack | Thông báo an toàn/correlation; không render raw markup hoặc leak stack/secret | Chưa chạy |
| SE-08 / P0 | CT/MAN | Task/cột có script/HTML-like, title CRLF; log/trace chứa credentials giả | React text an toàn; không execute HTML/log injection; không token/email/task-body trong log/artifact | Chưa chạy |
| SE-09 / P0 | E2E | Board archived từ đầu và bị archived trong pending | Read-only khi biết trạng thái; handle/menu đều disabled; server reject xử lý không mất task | Chưa chạy |

### 6.7. Accessibility, responsive, hiệu năng và nghiệm thu

| ID / mức | Lớp | Case / thao tác | Expected — kết quả mong đợi | Thực tế |
|---|---|---|---|---|
| UX-01 / P1 | E2E/MAN | Keyboard pick/move/drop card/cột; Escape hủy | Dùng được không chuột; announce vị trí; focus không mất sau save/rollback | Chưa chạy |
| UX-02 / P1 | E2E/MAN | Menu di chuyển, Tab/Shift+Tab, lỗi lưu bằng screen reader | Cùng validation/API với drag; focus/aria/live region đúng, controls có nhãn | Chưa chạy |
| UX-03 / P1 | E2E/MAN | Touch kéo và cuộn, long press, pointercancel/multi-touch | Không giành scroll sai, không accidental move/double request; thử thiết bị thật | Chưa chạy |
| UX-04 / P1 | E2E/MAN | 375/768/1440 px, zoom 200%, title dài, chat mở | Cuộn board có kiểm soát, handle/error không bị che, empty target reachable | Chưa chạy |
| UX-05 / P1 | E2E/MAN | Reduced motion, contrast, pending/error không chỉ bằng màu | Hoạt ảnh hợp lý, status text/icon rõ, focus ring nhìn thấy | Chưa chạy |
| PF-01 / P1 | PERF | 5 cột/200 task/30 drag, production build, x4 CPU/150ms RTT | Đo P95 feedback <=100ms, frame <=32ms theo budget đề xuất; báo env, max/outliers và trace | Chưa chạy |
| PF-02 / P2 | PERF | 1.000 task/cột, pagination churn, scroll/drag, nếu có virtualize | Không freeze/leak; drop đúng geometry/ID, load budget hữu hạn; báo resource/latency thực tế | Chưa chạy |
| PF-03 / P1 | PERF | 100 lần đổi board/mount/unmount/drag-cancel | Listener/request/widget về baseline, không leak hoặc duplicate PATCH dưới StrictMode | Chưa chạy |
| QA-01 / P1 | Build | `npm run build`, `npm run lint`, test unit/component, E2E browsers | 0 compile/lint/test failures; tests đã executed, không chỉ exit 0 do không có test | PARTIAL: build/lint pass; unit/component/E2E chưa được cấu hình/chạy |
| QA-02 / P1 | Build/review | Lockfile, peerDeps/engines, bundle và secrets/env | Reproducible install; không trộn legacy/new DnD hoặc nâng Vite ngầm; không secret trong bundle | PASS: dnd-kit 0.5 hỗ trợ React 19; Vite 6.4.3 đã có trong lockfile baseline; `npm audit fix` còn 0 advisory; site key đi qua build arg công khai |
| QA-03 / P1 | E2E/UAT | Demo create fixture→drag card→reorder cột→reload, rồi lỗi save | Hai AC gốc có video/trace/requests/server order; UI rollback/refetch đúng trong browser thật | Chưa chạy: Docker engine không truy cập được trong môi trường này |
| QA-04 / P1 | Review | Handoff T-017/T-018, PR/review/actual report | Contract/limitations rõ; không đánh dấu modal/realtime/backend task khác pass từ T-016 | Chưa chạy: chưa mở PR/review |

### 6.8. Lệnh dự kiến và mẫu evidence

Các script `test`/`test:e2e` chưa tồn tại ở baseline; phải thêm/cấu hình khi triển khai trước khi chạy. Trong `frontend/`:

```text
npm ci
npm run build
npm run lint
npm run test -- --run
npm run test:e2e
```

Đề xuất `test` chạy Vitest, `test:e2e` chạy Playwright với projects/browsers đã chốt; CI dùng reports và browser binaries được cài rõ. Khi cần chạy với API thật, khởi động môi trường backend/PostgreSQL/Turnstile test configuration, seed fixture và kiểm health trước E2E; không tự thay server thật bằng mock sau một lần lỗi mà vẫn gọi là E2E. Nếu có backend change, chạy `backend/gradlew check` và `:verifyModuleBoundaries` theo DoD.

| Case/variant | Ngày, commit, browser/env | Expected | Actual quan sát | Kết quả | Evidence |
|---|---|---|---|---|---|
| DR-01: B xuống cuối `[A,B,C,D]` | Chưa chạy | `[A,C,D,B]`, PATCH position=3, reload giữ đúng | Chưa có quan sát | NOT RUN | Điền report/video và REST order khi chạy |
| SV-06: server commit nhưng mất response | Chưa chạy | Không replay PATCH; unknown→GET server order | Chưa có quan sát | NOT RUN | Điền network trace và request count khi chạy |

## 7. Tổng hợp thực tế tại ngày lập plan

| Hạng mục | Thực tế 10/10/2026 |
|---|---|
| Đọc dự án | Đã đối chiếu task/card T-016, source frontend, controllers/DTOs/move/guard backend, architecture/product/master plan và T-017/T-018 |
| Trello | Đọc card trực tiếp; card trước đó ở Backlog, 0/2 AC; lệnh move sang In Progress nhận HTTP 401 và không cập nhật được |
| Branch | `feature/T-016-kanban-drag-and-drop`, kế thừa `feature/T-015-board-stomp-broadcaster` để dùng APIs T-012–T-015 chưa có trên `develop` |
| Implementation T-016 | Đã thay BoardPage mẫu bằng route board/workspace, đăng nhập email/password + refresh, DTO/API client, tải task phân trang, lọc, kéo thẻ/cột, optimistic update, rollback và reconcile; bổ sung cấu hình Turnstile frontend/Docker |
| Dependency | `@dnd-kit/react` 0.5.0; `npm audit fix` cập nhật advisory tương thích, audit sau cập nhật báo 0 vulnerability |
| Verification | `npm run build` PASS; `npm run lint` PASS; `docker compose config --quiet` PASS; chưa chạy test chức năng/E2E |
| Testing | Các case chức năng/performance/UAT ở mục 6 chưa chạy; Docker engine trả Access denied nên chưa xác minh API/backend/browser thật |
| Artifact | Plan này cùng cấp task gốc; cần tiếp tục cập nhật actual sau khi có browser E2E và test API thật |
