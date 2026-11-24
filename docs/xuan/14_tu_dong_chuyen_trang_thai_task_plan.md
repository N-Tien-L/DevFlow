# T-024 — Kế hoạch tự động chuyển trạng thái task theo hoạt động Git

**Milestone 3 · Phụ trách:** Xuân Lê (@xuanle2).

**Ngày khảo sát:** 11/11/2026, Asia/Saigon.

**Trạng thái cập nhật 19/11/2026:** Đã triển khai code, pass 825 test backend/117 test frontend và rollout vào stack Docker local theo lựa chọn người dùng; GitHub thật đã đi qua commit/open/merge/redelivery; người dùng đã xác nhận T-005 DONE và realtime xanh trên UI Docker local. Xem [kết quả thực thi](14_tu_dong_chuyen_trang_thai_task_results.md) và [runbook](14_tu_dong_chuyen_trang_thai_task_operations.md). Các phần khảo sát ngày 11/11 được giữ làm lịch sử; các biến thể nghiệm thu chưa thực thi được ghi PARTIAL/NR, không coi toàn bộ ma trận là PASS.

[Yêu cầu gốc](14_tu_dong_chuyen_trang_thai_task.md) · [Trello T-024](https://trello.com/c/un30KKfG/29-t-024-implement-gitactivityeventlistener-in-board-impl-to-auto-transition-task-columns) · [Bản đồng bộ ticket](00_all-tasks.md) · [Plan T-023](13_phat_su_kien_git_plan.md) · [Vận hành Git outbox](13_phat_su_kien_git_operations.md).

## 1. Mục tiêu, phạm vi và hiện trạng dự án

### 1.1. Kết quả cần đạt

Khi GitCI đã xác thực webhook và liên kết hoạt động Git với task, Board tự chuyển task sang cột phù hợp: commit linked → In Progress; PR opened → In Review; PR merged → Done. Trạng thái phải được lưu bền vững, có audit và thông báo STOMP để những thành viên đang xem board cập nhật được giao diện.

Hai tiêu chí gốc của T-024 trong bản đồng bộ Trello là:

- Task tự động đổi cột theo đúng luồng sự kiện Git.
- Gửi tín hiệu STOMP cập nhật UI cho mọi client đang xem board.

Các quy tắc về cột tùy biến, chuyển tay, dedup, migration và vận hành bên dưới là **thiết kế đề xuất để đáp ứng chất lượng production**, chưa phải tính năng đã tồn tại hay checklist đã cập nhật lên Trello. Lần đọc trực tiếp card ngày khảo sát thất bại với `getaddrinfo ENOTFOUND api.trello.com`; không suy ra trạng thái live của card từ bản đồng bộ. Yêu cầu hiện tại chỉ lập tài liệu, không chuyển card hoặc đánh dấu hoàn thành triển khai.

### 1.2. Nguồn đã đối chiếu với mã nguồn

| Nguồn | Hiện trạng quan sát và tác động tới kế hoạch |
|---|---|
| [ARCHITECTURE §2/4/6/9.2/10](../ARCHITECTURE.md), [PRODUCT_SPEC §5.2/8](../PRODUCT_SPEC.md), [IMPLEMENTATION_PLAN Phase 3/DoD](../IMPLEMENTATION_PLAN.md) | Board sở hữu task; GitCI sở hữu dữ liệu Git. Giao tiếp liên module bằng typed event implement `DevFlowEvent`; không import internal hoặc đọc bảng module khác. |
| [GitActivityEventListener](../../backend/board-impl/src/main/java/io/devflow/board/internal/GitActivityEventListener.java) | Có ba `@EventListener`, nhưng chỉ log và TODO; chưa di chuyển task. |
| [GitDomainEventOutbox](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitDomainEventOutbox.java), [V16](../../backend/app/src/main/resources/db/migration/V16__git_domain_event_outbox.sql) | Đã lưu/phát ba Git event theo at-least-once; batch 50, lease 60 giây, tối đa 8 lần thử. Publish ngoài transaction ingest/claim; exception từ listener có thể dẫn tới retry. |
| [GitCommitLinkedEvent](../../backend/common/src/main/java/io/devflow/common/event/GitCommitLinkedEvent.java), [GitPrOpenedEvent](../../backend/common/src/main/java/io/devflow/common/event/GitPrOpenedEvent.java), [GitPrMergedEvent](../../backend/common/src/main/java/io/devflow/common/event/GitPrMergedEvent.java) | Có task UUID, repo dạng chuỗi, thời điểm provider; PR có actor nullable. Chưa có event ID ổn định, repository UUID hoặc workspace UUID trong record. |
| [TaskManagementService](../../backend/board-impl/src/main/java/io/devflow/board/internal/TaskManagementService.java) | `moveTask` dành cho người dùng đăng nhập, kiểm membership và gắn cause MANUAL. Đã có append/reorder, giới hạn task/cột mặc định 1.000, normalize position, audit, domain event và realtime. Không thể gọi nguyên hàm này từ worker không có JWT. |
| [BoardRepository](../../backend/board-impl/src/main/java/io/devflow/board/internal/repository/BoardRepository.java), [TaskEntity](../../backend/board-impl/src/main/java/io/devflow/board/internal/entity/TaskEntity.java) | Board write dùng `findByIdForUpdate`; task có `@Version`. Luồng Git phải dùng chung giao thức khóa với thao tác tay. |
| [ColumnEntity](../../backend/board-impl/src/main/java/io/devflow/board/internal/entity/ColumnEntity.java), [StatusCategory](../../backend/board-impl/src/main/java/io/devflow/board/internal/entity/StatusCategory.java) | Có đúng bốn category TODO/IN_PROGRESS/IN_REVIEW/DONE; tên cột tùy biến, chưa có unique một category trên một board. Không định tuyến bằng tên hoặc vị trí cột. |
| [BoardAuditService](../../backend/board-impl/src/main/java/io/devflow/board/internal/BoardAuditService.java), [BoardAuditEntity](../../backend/board-impl/src/main/java/io/devflow/board/internal/entity/BoardAuditEntity.java), [V3](../../backend/app/src/main/resources/db/migration/V3__board_audit_events.sql), [V4](../../backend/app/src/main/resources/db/migration/V4__task_audit_events.sql) | Audit MOVE có source/destination nhưng `actor_id NOT NULL`; chưa lưu cause GIT. Truyền null ngay vào audit hiện tại sẽ vi phạm schema. |
| [TaskEventPublisher](../../backend/board-impl/src/main/java/io/devflow/board/internal/TaskEventPublisher.java), [BoardRealtimePublisher](../../backend/board-impl/src/main/java/io/devflow/board/internal/BoardRealtimePublisher.java) | Chụp snapshot và phát sau commit; lỗi phát được bắt/log. Không có durable Board outbox, không tự retry các tín hiệu này. |
| [BoardRealtimeBroadcaster](../../backend/board-impl/src/main/java/io/devflow/board/internal/BoardRealtimeBroadcaster.java), [BoardPage](../../frontend/src/pages/BoardPage.tsx), [useBoardSync](../../frontend/src/hooks/useBoardSync.ts) | Topic `/topic/boards/{boardId}`, mutation `CARD_MOVED`; UI invalidation/refetch cột và task detail, dedup theo message eventId, tải lại khi reconnect. Không cần tạo protocol STOMP riêng cho Git. |

### 1.3. Phạm vi và phụ thuộc

- **Trong phạm vi:** listener và policy Board; dùng chung phần di chuyển nội bộ; audit hệ thống; chống trùng/đảo thứ tự; bổ sung metadata typed event và producer T-023 cần thiết; migration; kiểm thử và hướng dẫn khôi phục.
- **Phụ thuộc:** T-021 xác thực webhook, T-022 liên kết task đúng workspace, T-023 phát typed event/outbox, T-013/014 cập nhật task và task event, T-015/018 realtime Board. Cần baseline đã tích hợp các phần này trước khi bắt đầu code.
- **Ngoài phạm vi:** viết lại parser/HMAC; CI pass/fail quyết định Done; Git badge T-026; cấu hình rule tùy ý theo từng repo; UI lịch sử mới; tự mở lại task khi PR đóng/reopened; quét lịch sử Git để backfill; thêm broker hoặc dịch vụ triển khai mới.
- Multi-repo/multi-PR áp dụng theo từng event hợp lệ: PR merge đầu tiên đủ điều kiện có thể đưa task về Done. Không chờ mọi PR merge hoặc CI xanh; nếu sản phẩm cần điều kiện tổng hợp, phải thiết kế ticket riêng.

## 2. Kế hoạch nghiệp vụ — mô tả luồng bằng ngôn ngữ

### NV-01. Developer bắt đầu làm việc

Developer đưa mã task vào commit/branch theo convention hiện có và push code. Sau khi DevFlow xác định đúng task, nếu task đang ở nhóm To Do thì chuyển sang nhóm In Progress trên chính board đó. Task đang In Progress giữ nguyên; đang In Review hoặc Done cũng giữ nguyên, vì một commit không phải yêu cầu lùi tiến độ.

### NV-02. Developer mở pull request

PR mới mở và đã được liên kết task sẽ đưa task từ To Do hoặc In Progress sang In Review. Cho phép đi thẳng từ To Do vì có thể không nhận được commit trước đó. Task đã In Review hoặc Done không đổi. Chỉnh title, synchronize, reopen hoặc đóng PR chưa merge không tự đổi cột trong T-024; producer hiện tại cũng không phát ba business event cho các action này.

### NV-03. Reviewer merge pull request

PR đã merge đưa task ở To Do, In Progress hoặc In Review sang Done. Không cần dựng các lần chuyển trung gian giả nếu sự kiện merge đến đầu tiên. Nếu task đã Done thì giữ nguyên, không đổi thứ tự task trong cột và không tạo thêm lịch sử chuyển trạng thái.

### NV-04. Board có tên cột riêng hoặc nhiều cột cùng nhóm

Board có thể đặt tên “Đang code”, “Chờ review”, “Hoàn tất”. Hệ thống nhận biết theo nhóm trạng thái được cấu hình, không theo chữ trong tên. Nếu chỉ có một cột đích thuộc nhóm cần đến thì dùng cột đó và thêm task cuối danh sách. Nếu thiếu cột hoặc có nhiều cột cùng nhóm đích, giữ nguyên task, ghi nhận lý do cần xử lý và đưa sự kiện vào luồng retry có giới hạn. Không tự tạo cột hoặc tự đoán cột thay người dùng.

### NV-05. Nhận sự kiện lặp hoặc không đúng thứ tự

Merge có thể được nhận trước opened hoặc commit. Khi đó task về Done và giữ Done khi nhận các sự kiện còn lại. Cùng sự kiện được worker phát lại sau restart chỉ được xử lý nghiệp vụ một lần. Quy tắc này vẫn đúng khi người dùng đã kéo task về cột trước đó giữa hai lần giao cùng sự kiện.

### NV-06. Người dùng chủ động kéo task

Người dùng vẫn có thể chuyển task bằng tay theo quyền hiện tại, kể cả mở lại task đã Done. Hệ thống ghi mốc đổi cột bằng tay; sự kiện Git có thời điểm nghiệp vụ cũ hơn hoặc bằng mốc này không được ghi đè thao tác đó. Hoạt động Git mới hơn có thể tiếp tục đẩy tiến độ lên, nhưng không kéo lùi. Chỉ đổi thứ tự trong cùng cột không tạo mốc đổi trạng thái mới.

Ví dụ: task Done được kéo về To Do lúc 10:00. PR merge xảy ra lúc 09:50 nhưng giao đến lúc 10:01 không đưa task về Done. Commit mới xảy ra lúc 10:05 có thể đưa task sang In Progress. Cùng event merge đã xử lý trước đó luôn bị dedup, dù được phát lại sau thao tác tay.

Đây là policy thận trọng dựa trên thời gian provider, không bảo đảm thứ tự nhân quả tuyệt đối giữa đồng hồ Git và DevFlow. Commit có thể mang timestamp cũ; khi bị bỏ qua, người dùng dùng thao tác tay hoặc quy trình hỗ trợ đã review, không sửa timestamp trong event để ép chạy.

### NV-07. Task, board hoặc cấu hình không còn hợp lệ

Task/board đã xóa được bỏ qua an toàn, không tái tạo. Board archived không bị tự động cập nhật. Event chỉ được áp dụng cho task trong workspace đã được producer xác thực. Cột đích đầy không được vượt giới hạn hoặc chuyển task sang board khác: giữ dữ liệu và retry sau khi giải phóng chỗ. Khi sửa cấu hình hoặc mở chỗ sau khi hết retry, người vận hành requeue đúng event; không yêu cầu GitHub tạo thêm commit giả.

### NV-08. Các thành viên quan sát và khi gặp lỗi

Sau khi lưu thành công, những thành viên hợp lệ đang xem board nhận tín hiệu cập nhật và đọc lại cột nguồn/đích, kể cả task detail đang mở. Tải lại trang vẫn thấy trạng thái đã lưu. Thành viên workspace khác không được nhận hoặc đọc dữ liệu board.

Nếu DB/lock/audit lỗi trước commit, task giữ nguyên và event được retry. Nếu DB đã commit nhưng phát STOMP lỗi, task vẫn ở trạng thái mới; có metric/log để phát hiện, UI phục hồi khi reconnect hoặc tải lại. Baseline chưa bảo đảm phát lại STOMP sau process crash; giới hạn này phải ghi rõ khi nghiệm thu, xem KT-07.

### 2.1. Ma trận chuyển trạng thái đề xuất

Áp dụng sau khi đã qua tenant, dedup, archive, mốc chuyển tay và kiểm tra cấu hình.

| Trạng thái hiện tại | `git.commit_linked` | `git.pr_opened` | `git.pr_merged` |
|---|---|---|---|
| TODO | IN_PROGRESS | IN_REVIEW | DONE |
| IN_PROGRESS | Giữ nguyên | IN_REVIEW | DONE |
| IN_REVIEW | Giữ nguyên | Giữ nguyên | DONE |
| DONE | Giữ nguyên | Giữ nguyên | Giữ nguyên |

Giữ nguyên nghĩa là không đổi column/position/version, không ghi audit MOVE, không phát `task.status_changed` hoặc `CARD_MOVED`. Có thể ghi consumer receipt cho quyết định cuối cùng để chống replay. Thứ tự tiến độ là policy tường minh; không dùng `enum.ordinal()` hay thứ tự cột trên UI.

## 3. Kế hoạch kỹ thuật — thiết kế và các yếu tố cần xử lý

### KT-01. Hoàn thiện contract sự kiện trước khi nối mutation

1. Bổ sung `eventId`, `repositoryId`, `workspaceId` cho ba record Git trong `common`; giữ các constructor cũ để caller hiện hữu vẫn compile. Các kiểu vẫn implement `DevFlowEvent`, không copy mẫu class kế thừa cũ trong tài liệu.
2. `eventId` lấy từ ID outbox V16, giữ nguyên qua claim, retry, requeue và restart; không tạo ID mới ở mỗi lần `Entry.event()`. `repositoryId` lấy từ row đã ghi. `workspaceId` lấy từ repository do GitCI sở hữu; ghi snapshot khi enqueue bằng migration mới, backfill pending cũ qua bảng nội bộ GitCI. Không suy workspace từ repo string hoặc task key.
3. Giữ payload cũ và ý nghĩa `occurredAt` của provider. Không đưa raw webhook, email, title/message hoặc secret vào record. `actorId` PR vẫn nullable; không dùng username/author Git làm UUID người dùng DevFlow.
4. Producer chỉ phát metadata đầy đủ sau khi kiểm tra row còn thuộc claim/repository hợp lệ. Board so `workspaceId` với workspace của task sau khi khóa. Board không đọc bảng `repositories`, association hoặc outbox GitCI, không gọi GitCI internal/API trực tiếp.
5. Constructor tương thích không được tự điền tenant hoặc sinh ID để vượt guard. Event thiếu metadata không được mutation; log/metric `INVALID_EVENT` và để lỗi đi tới worker/retry/quarantine. Rollout phải nâng producer và consumer đồng bộ trước khi bật automation. Test cũ dùng constructor cũ cần kiểm tra tương thích mà không ngầm kỳ vọng đổi cột.
6. Trust boundary là typed event chỉ được producer nội bộ phát sau xác thực T-021/T-022; metadata không phải cơ chế chữ ký trên Event Bus. Event đã được phát hợp lệ là snapshot nghiệp vụ; T-024 không reconcile lại liên kết khi title PR đổi sau đó. Xóa repo sau kiểm tra publish vẫn có cửa sổ race; task còn tồn tại có thể nhận snapshot đã phát. Nếu yêu cầu thu hồi tức thời cả event đang bay, cần protocol kiểm tra quyền integration riêng qua typed request/decision event.

### KT-02. Listener mỏng và đường ghi riêng cho automation

- Thay TODO trong `GitActivityEventListener` bằng ba adapter chuyển event thành yêu cầu nội bộ có type, metadata và target category. Listener chạy đồng bộ; không `@Async` hoặc fire-and-forget, vì worker cần thấy lỗi trước commit để retry.
- Tạo service đề xuất `GitTaskTransitionService` có transaction độc lập, gọi qua Spring proxy từ listener (`REQUIRES_NEW`, timeout đề xuất 6 giây). Không đặt transaction lên private method hoặc self-invocation rồi giả định proxy chạy.
- Tách phần di chuyển thuần Board trong `TaskManagementService` thành collaborator đề xuất `TaskMoveExecutor`: nhận task/board đã khóa, cột đích, vị trí, cause và actor context. Dùng chung capacity, normalize, audit và snapshot publish cho manual/Git.
- Entry manual giữ JWT, membership, anti-abuse và cause MANUAL. Entry Git dùng provenance của KT-01 và actor hệ thống; không tạo fake JWT, không tắt membership của route người dùng, không gọi controller REST bằng tài khoản admin. Không thêm endpoint public để ép trạng thái.

### KT-03. Transaction, khóa và thứ tự quyết định

Luồng đề xuất cho mỗi event:

```text
Git outbox publish (ngoài transaction ingest/claim)
  -> Listener Board (đồng bộ)
  -> Transaction Board riêng
     -> Validate metadata
     -> Resolve board ID từ task; thiếu task thì kết thúc an toàn
     -> Khóa Board bằng findByIdForUpdate
     -> Đọc lại task/column/board; kiểm workspace, archive
     -> Kiểm consumer receipt và fingerprint
     -> Kiểm mốc manual + ma trận tiến độ
     -> Resolve đúng một cột đích + capacity
     -> Move, normalize position, flush/version
     -> Audit + receipt trong cùng transaction
     -> Đăng ký task event và realtime snapshot
  -> Commit
  -> TaskStatusChangedEvent(GIT) + CARD_MOVED
  -> Bus trả về; GitCI đánh dấu PUBLISHED
```

- Dùng cùng khóa Board cho Git, move/reorder tay, sửa/xóa cột và archive. Đọc lại state sau khóa; không mang entity đọc trước khóa vào quyết định. Chỉ thao tác một board mỗi event, tránh giữ khóa nhiều board.
- Set lock timeout đề xuất 2 giây, statement timeout 4 giây trong transaction; lock timeout/deadlock/optimistic conflict là lỗi tạm thời, để worker retry thay vì busy-loop trong listener.
- Task thêm cuối cột đích, position liên tiếp từ 0 ở cả cột nguồn và đích. Task lân cận có thể tăng version vì position đổi; mọi response đọc lại phản ánh version hiện hành. Giữ title/description/key/assignee/priority/due date/comments.
- Kiểm giới hạn hiện tại `devflow.board.tasks.max-per-column`; không hardcode 1.000 trong executor. Không dùng đường Git để vượt capacity.
- Race với delete: đọc lại không thấy task/board thì no-op. Race với archive hoặc đổi category cột: quyết định theo state đã khóa. Workspace cleanup có thể dùng bulk delete ngoài giao thức khóa Board; lỗi FK/optimistic do race phải rollback/retry rồi xác định missing, không coi mọi integrity error là “đã xóa”.

### KT-04. Consumer receipt, replay và chuyển trạng thái bằng tay

**Đề xuất bảng Board-owned `board_git_event_receipts`:** `event_id` primary key; `task_id` FK nội bộ tới `tasks` với `ON DELETE CASCADE`; repository/workspace UUID dạng soft reference; event type; fingerprint của snapshot chuẩn hóa; outcome/reason; received/processed timestamps. Unique event ID bảo vệ nhiều worker, khóa Board serialize quyết định trên cùng task. Bổ sung index theo task để cleanup/tra cứu.

- Receipt cho APPLIED và quyết định cuối NOOP/STALE_MANUAL/ARCHIVED được commit cùng transaction. Replay cùng eventId + fingerprint trả kết quả cũ, không phát side effect mới. Cùng ID nhưng payload khác phải báo conflict/quarantine, không chấp nhận như duplicate.
- Không ghi receipt thành công cho lỗi DB, cột thiếu/trùng hoặc capacity đầy; rollback và ném lỗi để dùng retry T-023. Sau tối đa 8 lần, GitCI FAILED; sửa nguyên nhân và requeue giữ ID. Không tạo một hệ thống retry Board thứ hai.
- Missing task không insert receipt FK giả; task UUID đã xóa không tái sử dụng nên xử lý no-op an toàn. Tenant mismatch là lỗi contract/an ninh: tuyệt đối không mutation, metric riêng và retry/FAILED để điều tra, không ghi như thành công.
- Receipt tồn tại suốt vòng đời task, không xóa chỉ vì outbox GitCI đã hết retention 7 ngày. Nếu cần retention riêng phải giữ tombstone chống replay hoặc thay contract; không tuyên bố chống trùng sau khi đã xóa mọi dấu vết.
- Bổ sung `tasks.last_manual_status_changed_at` nullable. Chỉ move sang cột khác bằng tay cập nhật bằng đồng hồ server trong cùng transaction; chuyển giữa hai cột cùng category cũng cập nhật. Reorder trong cột hoặc sửa title không cập nhật.
- Event mới có `occurredAt <= last_manual_status_changed_at` → STALE_MANUAL. Có thể dùng receipt cho quyết định này. Với task cũ, đề xuất backfill mốc bằng `tasks.updated_at` tại migration để bảo vệ các thao tác tay trước rollout; mốc này có thể đến từ sửa nội dung nên bảo thủ hơn thực tế. Ghi rõ tác động: backlog Git cũ có thể cần xử lý tay. Task mới có mốc null cho tới lần chuyển cột tay đầu tiên.
- Không dùng riêng `occurredAt > lastGitTimestamp`: timestamp commit/PR không phải sequence tiến độ. Event khác ID nhưng thấp hơn category hiện tại vẫn là no-op; event cao hơn được áp dụng nếu qua mốc manual.

### KT-05. Cột đích và lỗi cấu hình

Tra cột theo `(board_id, status_category)`. Chỉ khi policy yêu cầu tiến lên mới resolve đích: đúng một kết quả → dùng; không có → TARGET_MISSING; trên một → TARGET_AMBIGUOUS. Hai lý do này được retry/FAILED như KT-04. Không fallback theo tên, vị trí, cột đầu tiên hoặc cột board khác. Nếu đã ở category đích thì giữ nguyên cột hiện tại, dù có nhiều cột cùng category; không tự di chuyển ngang.

Không ép unique category lên toàn bộ board vì sản phẩm hiện cho phép nhiều cột cùng nhóm. Nếu tương lai cần chọn đích riêng, bổ sung mapping cấu hình và UI trong ticket khác.

### KT-06. Audit, actor, migration và quyền riêng tư

1. Migration Flyway mới sau version cao nhất trên nhánh tích hợp; baseline khảo sát có V1–V16. Không sửa V3/V4/V16 đã phát hành. Các nhóm thay đổi gồm event metadata snapshot GitCI, receipt/mốc manual Board và audit hệ thống.
2. Bổ sung `cause` vào `board_audit_events`, backfill MANUAL và default MANUAL cho caller cũ. Cho phép `actor_id` null chỉ với audit MOVE/TASK có cause GIT; giữ ràng buộc actor bắt buộc cho MANUAL và các action hiện hữu. Đồng bộ annotation JPA và service audit, giữ source/destination bắt buộc cho MOVE. Không dùng `DeletedUser.ID` hoặc UUID giả làm actor hệ thống.
3. Audit lưu cause, event ID nguồn để truy vết, source/destination UUID, workspace và các tên field thay đổi. Actor automation dùng null ở baseline; attribution người Git là dữ liệu riêng, chỉ dùng sau khi có identity mapping xác minh và xử lý vòng đời account phù hợp.
4. Tạo một correlation ID nội bộ cho lần xử lý và giữ trong MDC suốt mutation, audit, after-commit domain event và STOMP; restore/clear MDC trong finally để worker xử lý event kế tiếp không thừa context. Event ID nguồn là khóa truy vết bền qua retry. Không suy request IP người dùng từ webhook worker; dùng giá trị hệ thống hiện hành `unknown`/null theo schema.
5. Receipt FK nội bộ cleanup theo task/board; workspace cleanup phải xóa receipt qua task cascade và audit theo flow hiện có. Repo ID là soft reference, không FK sang GitCI. Receipt còn lại của task đang sống giữ metadata tối thiểu cho dedup, không chứa thông tin tác giả. Account deletion không gán danh tính người đã xóa vào audit hệ thống.

### KT-07. Task event, STOMP và giới hạn bảo đảm

- Trong transaction, gọi `TaskEventPublisher.publishStatusChanged` với snapshot đúng board/workspace/source/destination, tên cột và category cũ/mới, cause `GIT`, actor null. `occurredAt` của task event hiện là thời điểm Board mutation; không thay bằng timestamp commit cũ. Git event giữ thời điểm provider để policy/truy vết.
- Gọi `BoardRealtimePublisher.publish` với `CARD_MOVED`, `taskId`, from/to column và position, hai `affectedColumnIds`, changedFields `columnId/position`, refresh `TASK_LISTS/TASK_DETAIL`, actor null. Sau commit mới gửi tới `/topic/boards/{boardId}` qua pipeline có authorization hiện tại. Không gửi thêm task event/realtime khi skip/replay.
- Dùng lại BoardPage/useBoardSync để refetch, không tự sửa category phía client từ Git metadata. Test task detail mở, pagination, dữ liệu kéo thả đang pending, client reconnect và thành viên mất quyền. Không bỏ qua message chỉ vì actor null.
- **Ranh giới nghiệm thu:** mutation + audit + receipt là atomic/bền vững. Domain event và STOMP hiện là best effort sau commit: crash ngay sau commit có thể mất tín hiệu; replay Git sẽ bị receipt chặn và không tự tái phát tín hiệu. Lỗi dispatch cũng đang được publisher bắt nên không ép GitCI retry bằng cách ném lỗi giả sau commit. Đối chiếu source khi triển khai: BoardPage hiện có refetch định kỳ khoảng 30–35 giây khi trang visible và refetch khi focus/reconnect; cơ chế này có thể giúp bảng hội tụ khi lỡ frame. Không coi đó là durable delivery, không bảo đảm task detail hoặc mọi client hội tụ ngay; reconnect/reload vẫn là đường phục hồi chủ động.
- Gate phát hành: nếu yêu cầu production là không được mất `task.status_changed` hoặc phải tự phục hồi frame cho client vẫn connected, cần thêm Board transactional outbox và cơ chế resync/revision có kiểm thử như hạng mục mở rộng trước khi release. Plan cơ sở T-024 chỉ cam kết STOMP trên đường chạy bình thường và phục hồi qua reconnect/reload, đúng giới hạn đã ghi trong kiến trúc.

### KT-08. Quan sát và xử lý lỗi

- Counter đề xuất `devflow.board.git.transitions` theo eventType/outcome/reason hữu hạn; timer xử lý và lock wait. APPLIED chỉ đếm sau commit. Lỗi tạm thời phải đi tới GitCI worker; không catch-all rồi trả thành công. Metric/log failure không được thay đổi kết quả transaction.
- Theo dõi cùng `devflow.gitci.outbox.*`, `devflow.task.events.dispatch` và `devflow.board.realtime.publications` để phân biệt: chưa đổi DB, đã đổi nhưng chưa phát domain event, đã đổi nhưng STOMP lỗi. Cảnh báo FAILED/backlog tăng hoặc consumer lỗi lặp; health DB và endpoint health hiện có không được báo thành công giả khi DB mất kết nối.
- Log reason code, event ID, task/board ID và correlation theo chính sách nội bộ; không log token, HMAC, raw body, email, message/title, URL có query secret. Không dùng task ID, SHA, repo URL, actor hay correlation làm metric tag.
- Event nội bộ không thêm Turnstile/rate limit HTTP riêng; giữ HMAC/quota webhook và JWT/membership/rate limit route manual. Không gọi GitHub hoặc dịch vụ ngoài từ transaction Board, do đó không phát sinh retry network/circuit breaker mới trong đường chuyển cột này.

### KT-09. Trình tự thực hiện và rollout

| Bước | Công việc | Đầu ra/gate |
|---|---|---|
| P1 | Xác nhận baseline phụ thuộc; review policy matrix, nhiều cột đích, manual watermark, giới hạn realtime và multi-PR với chủ module | Quyết định được ghi trong PR; không mặc định các đề xuất đã được duyệt |
| P2 | Migration + common contract + GitCI snapshot/producer; giữ constructors cũ | Flyway upgrade sạch; replay cùng payload/ID; Notification tương thích |
| P3 | Tách executor dùng chung; giữ nguyên authorization manual; thêm audit GIT | Regression move/reorder/permission pass |
| P4 | Policy + receipt + manual watermark + transaction listener | Unit, PostgreSQL integration, race/retry pass |
| P5 | Nối task event + CARD_MOVED + metric/context | After-commit/rollback và hai client thực pass |
| P6 | Kiểm tra end-to-end webhook thật ký HMAC, failure injection, quality gates | Ma trận Actual có bằng chứng và lỗi còn mở |
| P7 | Migration trước, deploy producer/consumer đầy đủ, rồi bật automation ở môi trường kiểm chứng | Kiểm tra pending cũ có metadata; theo dõi backlog, latency, failure |

Đề xuất công tắc `devflow.board.git.auto-transition-enabled` mặc định tắt trong giai đoạn rollout. Khi tắt mà worker vẫn chạy, listener phải defer bằng lỗi có thể quan sát, không trả thành công làm mất event; worker có thể hết retry nên vận hành ưu tiên pause worker T-023 trước khi tắt consumer. Pause worker cũng trì hoãn Git notification, cần ghi nhận tác động. Sau khi bật, requeue FAILED do trì hoãn và theo dõi backlog.

Khi rollback, pause worker trước; giữ schema mở rộng và receipt, không rollback SQL bằng cách xóa dữ liệu. Chạy lại phiên bản tương thích contract/schema hoặc forward-fix rồi resume; rollback về producer thiếu metadata không được làm âm thầm bỏ event. Lập runbook sửa cột/capacity, truy event FAILED và requeue bằng công cụ nội bộ T-023, không mở endpoint công khai.

## 4. Tiêu chí hoàn thành

Các ô phản ánh phạm vi đã kiểm chứng; các AC tổng hợp còn thiếu biến thể vẫn để mở. Evidence mới nhất: RUN-FINAL-LOCAL trong báo cáo kết quả.

- [x] **AC-01 — Luồng Git:** đúng đủ ma trận 12 tổ hợp; cho phép nhảy tiến độ, không lùi vì commit/opened đến trễ. Đúng cột của task, trạng thái tồn tại sau reload. Evidence: POL-01…POL-06, E2E-01/02.
- [x] **AC-02 — STOMP gốc:** trên đường chạy thành công, các client hợp lệ đang xem board nhận `CARD_MOVED`, cập nhật cả hai cột/task detail; client không có quyền bị chặn. Evidence: RT-01…RT-06, E2E-01.
- [ ] **AC-03 — Atomicity/idempotency:** task, vị trí, audit và receipt cùng commit/rollback; replay/restart/race không tạo move hoặc side effect lặp. Evidence: DAT-01…DAT-07, IDE-01…IDE-06, CON-01…CON-06.
- [ ] **AC-04 — Cấu hình/định danh:** dùng category; missing/ambiguous/full không đoán đích hoặc mất event; event có metadata đầy đủ, workspace mismatch không mutation. Evidence: CFG-01…CFG-06, SEC-01…SEC-06.
- [ ] **AC-05 — Manual/audit:** không ghi đè thao tác tay bằng Git cũ; manual vẫn có JWT/membership; audit GIT actor null hợp lệ, audit manual vẫn bắt buộc actor. Evidence: MAN-01…MAN-06, DAT-04, SEC-04, MIG-01…MIG-03.
- [ ] **AC-06 — Vận hành:** retry/FAILED/requeue, công tắc, cleanup, correlation/metric và giới hạn mất tín hiệu được kiểm chứng; gate về durable downstream KT-07 đã được quyết định trước release. Evidence: OPS-01…OPS-08, RT-04/05.
- [x] **AC-07 — Quality gates:** `:verifyModuleBoundaries`, backend `check`, frontend build/lint pass, không có impl→impl import hoặc truy bảng module khác. Evidence: REG-01…REG-04.
- [ ] **AC-08 — Hồ sơ nghiệm thu:** mọi case bắt buộc có Actual, ngày/môi trường/commit, evidence; không lấy kết quả T-023 làm bằng chứng T-024. Không còn lỗi P0/P1; case BLOCKED/SKIP được giải quyết hoặc có quyết định phạm vi rõ trước khi hoàn thành ticket.

## 5. Kế hoạch testing — Expected và Actual

### 5.1. Quy ước ghi kết quả và mức kiểm thử

**PARTIAL** nghĩa là đã kiểm chứng các phần được nêu rõ trong báo cáo nhưng còn biến thể chưa chạy; không dùng PARTIAL để nghiệm thu toàn bộ AC. **Actual** là kết quả quan sát khi chạy: `PASS`, `FAIL`, `BLOCKED`, `SKIP` kèm lý do và link evidence. `NR` trong các bảng dưới nghĩa là **NOT RUN — chưa chạy**, không phải trạng thái hệ thống đã trả. Kết quả đọc source ở §1.2 chỉ xác định baseline, không thay thế thực thi test.

- **U:** JUnit 5 parameterized test cho policy/contract, không Spring/DB khi không cần.
- **I:** Spring integration với PostgreSQL 17 Testcontainers + Flyway, không thay bằng H2 cho lock/constraint/transaction. Tái sử dụng cách dựng app trong `BoardPersistenceIntegrationTest`, `GitDomainEventHttpIntegrationTest` và các test task event/realtime hiện có.
- **E:** HTTP webhook ký HMAC → Git/link/outbox → bus thật → Board DB → task event/STOMP thật. Tắt polling nền khi test rồi gọi vòng worker có kiểm soát; không chỉ publish event mock và gọi đó là E2E.
- **M:** hai browser session khác người dùng và kiểm chứng UI; automation browser nếu harness hiện có hỗ trợ.
- **O:** failure injection, restart/requeue, migration và observability. Dùng DB/container test riêng; không xóa/reset database Compose đang chứa dữ liệu làm việc.

### 5.2. Fixture và oracle dùng chung

Workspace A/B cùng có mã T-001 nhưng task UUID khác nhau; repo RA thuộc A, RB thuộc B. A có board BA, board BA2 và board archived. BA có bốn cột category chuẩn với tên tùy biến, mỗi cột có task để kiểm thứ tự; BA2 có cột trùng tên với BA. Có member A1/A2, member B, user bị thu hồi membership, task đã xóa và task có assignee không còn trong workspace.

Event fixture có eventId/repositoryId/workspaceId chuẩn, SHA/PR URL giả và timestamp cố định. Có PR nhiều task, hai repo cùng task, fork head khác base, nhiều PR cùng task, pending outbox cũ và batch lớn. Giới hạn cột test cấu hình nhỏ, thêm case đúng giới hạn production; không tạo dữ liệu lớn trong mọi unit test.

Oracle cho một lần **APPLIED**: task đúng column, position cuối; hai danh sách position liên tiếp và không mất/lặp task; version phản ánh ghi DB; một audit MOVE/GIT; một receipt; một lời gọi dispatch `task.status_changed` và một `CARD_MOVED` sau commit. Không assert hai event ID của domain/realtime bằng nhau vì publisher hiện sinh ID riêng; nối bằng correlation và source event ID trong receipt/audit. Kiểm lại DB qua transaction/connection độc lập, không chỉ kiểm entity trong persistence context.

Oracle cho **NOOP/replay**: column/position/version không đổi; không thêm audit MOVE/domain event/CARD_MOVED. Receipt có thể thêm cho lần quyết định cuối đầu tiên. Oracle cho **retryable error**: rollback mọi mutation/receipt thành công, worker PENDING hoặc FAILED theo số lần thử; phân biệt HTTP 204 của ingest thành công với worker xử lý Board thành công.

Dùng clock điều khiển được cho mốc manual, latch/barrier cho concurrency, await điều kiện có timeout cho worker/socket; không dùng sleep cố định làm bằng chứng. Xác nhận subscriber đã nhận STOMP receipt trước khi phát event.

### 5.3. Bộ policy và nghiệp vụ

| ID | Mức | Thiết lập / thao tác | Expected | Actual |
|---|---|---|---|---|
| POL-01 | U/I | Commit lần lượt vào task TODO, IN_PROGRESS, IN_REVIEW, DONE | Chỉ TODO → IN_PROGRESS; ba trạng thái còn lại giữ nguyên; đủ 4 tổ hợp | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| POL-02 | U/I | PR opened lần lượt vào bốn trạng thái | TODO/IN_PROGRESS → IN_REVIEW; IN_REVIEW/DONE giữ nguyên; đủ 4 tổ hợp | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| POL-03 | U/I | PR merged lần lượt vào bốn trạng thái | Ba trạng thái đầu → DONE; DONE giữ nguyên; đủ 4 tổ hợp | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| POL-04 | I | Cùng task nhận đủ ba event theo cả 6 hoán vị | Cuối cùng DONE; chỉ ghi audit/event cho các bước thực sự tiến lên | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| POL-05 | E | PR edited/synchronize/reopened/closed không merge; push không link task | Không có business event T-023 tương ứng, không tự chuyển cột | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| POL-06 | I/E | Nhiều task trong một PR, hai repo cùng task, hai PR cùng task | Xử lý từng task đúng tenant; merge hợp lệ đầu tiên có thể DONE, merge tiếp no-op; không chờ CI/PR khác | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |

### 5.4. Cấu hình cột và giới hạn

| ID | Mức | Thiết lập / thao tác | Expected | Actual |
|---|---|---|---|---|
| CFG-01 | I | Đổi tên cột tiếng Việt, đảo position cột | Định tuyến bằng category vẫn đúng | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CFG-02 | I/O | Xóa cột đích trước publish | TARGET_MISSING; task không đổi, worker retry; sửa cột + requeue áp dụng được | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CFG-03 | I/O | Có hai cột cùng category đích, rồi xóa một | TARGET_AMBIGUOUS trước sửa; không đoán; retry/requeue sau sửa vào cột còn lại | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CFG-04 | I | Task đã ở category đích, board có nhiều cột cùng category | Giữ đúng cột/position hiện tại, không báo ambiguous cho no-op | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CFG-05 | I | Chỉ BA2 có cột category đích cần dùng cho task BA | Không chuyển sang BA2; báo thiếu đích ở BA | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CFG-06 | I/O | Cột rỗng, còn một chỗ, đầy đúng max; giải phóng chỗ rồi retry | Append position 0/cuối; chấp nhận chỗ cuối; đầy không vượt max, sau giải phóng áp dụng một lần | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |

### 5.5. Dữ liệu, transaction, audit và dedup

| ID | Mức | Thiết lập / thao tác | Expected | Actual |
|---|---|---|---|---|
| DAT-01 | I | Task ở đầu/giữa/cuối nguồn, đích có nhiều task | Source/target position 0..n-1; không mất/lặp task, từ/to position đúng | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| DAT-02 | I | Task có đủ description, assignee, due date, priority, key, comment | Chỉ column/position/version/timestamp liên quan thay đổi; nội dung/quan hệ được giữ | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| DAT-03 | I | Chặn transaction trước commit, đọc từ connection khác, rồi commit | Chưa thấy state mới hoặc task/realtime event trước commit; sau commit oracle APPLIED đúng | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| DAT-04 | I | GIT move actor null, manual move actor hợp lệ; insert manual audit null trực tiếp | Audit GIT hợp lệ có cause/event ID; manual đúng actor; DB từ chối manual audit null | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| DAT-05 | I/O | Lần lượt inject lỗi flush task, audit, receipt | Mỗi lỗi rollback toàn bộ; không domain event/STOMP; retry sau bỏ lỗi thành công | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| DAT-06 | I | Sau GIT move, user dùng version cũ update nội dung | Bị từ chối theo precondition hiện hành, không ghi đè task; refetch rồi update hợp lệ | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| DAT-07 | I | Rollback outer test transaction và chạy test có commit thật | Assertions after-commit dùng commit thật, không có test xanh giả do callback chưa chạy | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| IDE-01 | I | Phát cùng event 2–10 lần tuần tự | Một APPLIED/receipt/audit; không thêm side effect sau lần đầu | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| IDE-02 | I/O | Commit Board rồi mô phỏng crash trước GitCI đánh dấu PUBLISHED; restart worker | Cùng ID/payload được replay, Board no-op; outbox hoàn tất sau phục hồi | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| IDE-03 | I | Event APPLIED, user chuyển lùi, replay cùng event | Receipt chặn replay; không tự chuyển tiến lại | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| IDE-04 | I | Event no-op ở DONE, user chuyển lùi, replay event no-op cũ | Kết quả cuối đã có receipt, không chuyển lại | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| IDE-05 | I/O | Cùng eventId nhưng đổi task/workspace/type/timestamp trong payload | Fingerprint conflict hoặc guard tenant; không mutation task nào, có lỗi quan sát được | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| IDE-06 | I/O | Dọn PUBLISHED quá 7 ngày rồi replay snapshot; xóa task/board | Receipt còn bảo vệ task sống; xóa task/board cascade receipt, không orphan | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |

### 5.6. Thao tác tay và concurrency

| ID | Mức | Thiết lập / thao tác | Expected | Actual |
|---|---|---|---|---|
| MAN-01 | I | Task được chuyển tay lúc t; Git event occurredAt < t và = t | STALE_MANUAL cho cả hai, không đổi task/side effect | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| MAN-02 | I | Chuyển tay về TODO lúc t; commit/opened/merged mới > t, từng case độc lập | Tiến tới category tương ứng đúng ma trận | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| MAN-03 | I | Reorder cùng cột, sửa title; sau đó Git event | Không đổi manual watermark; Git vẫn xét theo mốc đổi cột gần nhất | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| MAN-04 | I | Chuyển tay giữa hai cột cùng category | Cập nhật manual watermark, giữ hành vi manual và event cũ bị loại | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| MAN-05 | U/I | Provider timestamp null/sai; commit timestamp cũ nhưng delivery mới | Metadata sai bị từ chối; timestamp cũ đúng format được xét theo policy, không dùng delivery time thay thế | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| MAN-06 | I/O | Task tồn tại trước migration có updated_at mới; backlog Git cũ | Mốc backfill bảo thủ chặn backlog; tác động ghi trong runbook, không tự sửa timestamp để vượt policy | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CON-01 | I | Hai worker giao đồng thời cùng event, dùng barrier | Một receipt/move/audit; không unique exception bị nuốt thành thành công giả | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CON-02 | I | Commit, opened, merged đồng thời trên một task | Khóa serialize; cuối DONE, không backward dù thứ tự commit khác | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CON-03 | I | Manual và Git đồng thời, ép từng thứ tự lấy khóa | State/mốc manual theo transaction đã khóa và policy; không dùng snapshot cũ | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CON-04 | I | Auto-move đồng thời manual move task khác vào chỗ cuối đích | Tối đa một thao tác chiếm chỗ; không vượt capacity/trùng position | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CON-05 | I | Auto-move đồng thời archive, delete task/board, sửa/xóa cột; chạy từng race | Kết quả theo khóa/rollback/retry, không orphan hoặc cập nhật archived board sau khi đọc lại | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| CON-06 | I/O | Giữ board lock vượt timeout; test deadlock/FK race workspace cleanup | Không treo worker; rollback, retry bounded; sau cleanup missing được xử lý an toàn | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |

### 5.7. Bảo mật, contract và ranh giới module

| ID | Mức | Thiết lập / thao tác | Expected | Actual |
|---|---|---|---|---|
| SEC-01 | I | task UUID ở A, event workspace B; A/B có cùng task key | Không mutation hoặc lộ nội dung A; metric tenant mismatch, worker retry/FAILED | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| SEC-02 | U/I | Thiếu eventId/repositoryId/workspaceId/taskId/occurredAt, type không hỗ trợ | Guard fail closed, không tạo ID/tenant giả hoặc mutation | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| SEC-03 | E | HMAC sai/thiếu, repo binding sai; PR fork head thuộc repo khác | Payload sai bị ingestion chặn; fork hợp lệ dùng base workspace, không tenant của head | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| SEC-04 | E/I | Worker không có JWT; user manual thiếu JWT/không membership; assignee đã rời workspace | Automation hợp lệ chạy bằng system context; route manual vẫn chặn trái quyền; không gán giả actor/assignee | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| SEC-05 | U/I | Constructors cũ, record mới, Notification đang nghe hai PR event | Caller cũ compile; legacy không tự mutation; producer mới metadata ổn định; không làm hỏng Notification dedup | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| SEC-06 | I/E | Task đã xóa, board archived, repo/workspace xóa trước publish; race sau publish | Missing/archived no mutation; row xóa trước publish không phát; cửa sổ snapshot đang bay tuân KT-01, không tuyên bố thu hồi tuyệt đối | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |

### 5.8. Realtime và end-to-end

| ID | Mức | Thiết lập / thao tác | Expected | Actual |
|---|---|---|---|---|
| RT-01 | E/M | A1/A2 subscribe BA, B subscribe trái quyền; thực hiện GIT move | A1/A2 nhận schema hợp lệ CARD_MOVED sau commit; B bị chặn, không thấy dữ liệu | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| RT-02 | E/M | Mở task detail; cột phân trang; có drag/drop pending | List/count/detail refetch đúng; không task lặp, không dữ liệu cũ ghi đè bản mới | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| RT-03 | E/M | Duplicate/out-of-order frame; actor null | UI dedup/invalidation/refetch đúng DB, không bỏ frame vì actor null | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| RT-04 | I/O/M | STOMP broadcaster hoặc task-event subscriber ném exception sau commit | DB/audit/receipt vẫn đúng; publisher ghi failure; không rollback giả hoặc move lại; UI reconnect/reload đọc đúng | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| RT-05 | O/M | Kill process sau Board commit trước dispatch, sau đó restart/replay | Không move lặp; xác nhận tín hiệu có thể mất ở baseline; reload/reconnect phục hồi, ghi rõ limitation KT-07 | NR |
| RT-06 | E/M | Mất mạng, reconnect; thu hồi membership/token trong lúc xem | Reconnect hợp lệ resync board/detail; user mất quyền không nhận/đọc thêm dữ liệu theo guard hiện hành | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| E2E-01 | E/M | Webhook push/open/merge ký HMAC qua HTTP thật, đúng task key; hai client | Ingest commit Git/outbox, worker đổi task đúng chuỗi, audit/event/STOMP đúng; reload giữ DONE | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| E2E-02 | E/O | Webhook merge đến trước open/commit; redelivery cùng và khác delivery ID | Cuối DONE, không lùi; Git dedup và Board receipt phối hợp, không audit/event thừa | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| E2E-03 | E/O | HTTP ingest 204, sau đó Board bị lỗi cấu hình/DB | 204 chỉ xác nhận ingest; theo dõi worker retry/FAILED; sửa lỗi + requeue mới hoàn thành Board | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |

### 5.9. Migration, vận hành và regression

| ID | Mức | Thiết lập / thao tác | Expected | Actual |
|---|---|---|---|---|
| MIG-01 | I/O | DB sạch migrate toàn bộ + Hibernate validate | Schema/constraint/index/entity đồng bộ, không sửa checksum migration cũ | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| MIG-02 | I/O | Upgrade DB V16 có audit manual, task cũ và pending/retry/FAILED outbox | Backfill cause/manual watermark/event workspace đúng; ID/thời điểm outbox không đổi, không mất pending | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| MIG-03 | I/O | Vi phạm receipt PK/FK, audit cause/actor, xóa task/board/workspace, account cleanup | DB chặn dữ liệu sai; cascade/privacy đúng; audit GIT null không làm hỏng anonymization cũ | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| OPS-01 | O | Lỗi Board tạm thời 1 lần rồi bỏ; lỗi kéo dài quá 8 attempts | Retry/backoff theo T-023; không mất task mutation, cuối FAILED khi hết budget | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| OPS-02 | O | Sửa cột/capacity, requeue đúng event FAILED | Payload/ID giữ nguyên; APPLIED một lần; backlog giảm | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| OPS-03 | O | Pause/resume worker, tắt/bật automation, rồi xử lý deferred FAILED | Không đánh dấu thành công âm thầm khi tắt; runbook phục hồi hết event bị trì hoãn | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| OPS-04 | O | Hai event khác nhau trên cùng worker, cố ý lỗi giữa chừng | MDC được restore/clear; audit/domain/STOMP cùng correlation trong một lần xử lý, không lẫn user/IP | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| OPS-05 | O | Success/no-op/rollback/error; capture metric/log, inject metric registry failure | Đếm APPLIED sau commit; reason tag bounded; không PII/secret; lỗi metric không làm sai mutation | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| OPS-06 | I/O | Batch 50 event/worker, nhiều worker trên một/nhiều board, fixture fan-out tối đa upstream | Không deadlock kéo dài, không vượt pool/lease; ghi throughput, p95/p99, lock wait và tuổi backlog thực tế | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| OPS-07 | O | So baseline cùng máy với workload CRUD + worker và DB unavailable | Đo CRUD p95 theo mục tiêu spec <200ms; Board transaction nằm trong budget; DB outage không health xanh giả; failure có cảnh báo | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| OPS-08 | O | Deploy lệch phiên bản, rollback/pause theo runbook | Không phát event thiếu metadata vào automation đang bật; schema/receipt giữ nguyên, forward recovery được kiểm chứng | [PARTIAL/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| REG-01 | I | Chạy `:verifyModuleBoundaries`, review import/query mới | Không impl→impl, không Board query bảng GitCI/Auth, common không phụ thuộc module | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| REG-02 | I | Backend `check`, gồm các test manual task/board, account cleanup, GitCI và Notification | Toàn bộ pass; số test/skip và report được ghi, không lấy report cũ làm kết quả mới | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| REG-03 | Build | Frontend `npm run build` và `npm run lint` | Build/typecheck/lint pass theo DoD, không thêm warning/error | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |
| REG-04 | E/M | Regression tạo/sửa/xóa/move/reorder task, archive board, STOMP authorization | Hành vi và permission manual giữ đúng; không event status cho reorder cùng cột | [PASS/evidence](14_tu_dong_chuyen_trang_thai_task_results.md) |

### 5.10. Lệnh kiểm tra dự kiến và bằng chứng

Chạy từ repository root bằng PowerShell khi bắt đầu triển khai; các class test mới bên dưới là tên đề xuất, cần tạo trước khi chạy filter. Không chạy test mã ứng dụng chỉ để xác nhận việc thêm tài liệu này.

```powershell
Set-Location backend
.\gradlew.bat :common:test :board-impl:test :gitci-impl:test
.\gradlew.bat :app:test --tests '*GitTaskTransition*'
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat check
Set-Location ../frontend
npm run build
npm run lint
```

Test placement đề xuất: `board-impl` cho `GitTaskTransitionPolicyTest`, `GitTaskTransitionServiceIntegrationTest` và hồi quy executor; `common` cho record compatibility; `gitci-impl` cho metadata ổn định; `app` cho `GitTaskTransitionHttpIntegrationTest`, STOMP/multi-module/migration. Test listener after-commit phải có commit thật, không chỉ dùng rollback mặc định của test transaction.

Hồ sơ mỗi lần chạy cần ghi: ngày giờ Asia/Saigon, commit/working tree, Java/Node/PostgreSQL, hệ điều hành, worker/config flags, lệnh và exit code, testcase IDs, actual state/count/payload quan sát được, đường dẫn JUnit/HTML report hoặc ảnh/video UI đã loại secret. Report mặc định nằm ở `backend/<module>/build/reports/tests/test/` và `build/test-results/test/`. Load test phải kèm cấu hình máy, concurrency và workload để số đo có thể so sánh.

| Lần chạy | Ngày / commit / môi trường | Phạm vi | Kết quả thực tế | Evidence |
|---|---|---|---|---|
| BASELINE-READ | 11/11/2026; source working tree hiện tại | Đọc tài liệu, listener, producer/outbox, task mutation, audit, realtime và UI | Listener còn TODO; các thiếu hụt contract/audit đã ghi §1.2. Trello trực tiếp lỗi DNS. Đây không phải test tính năng. | Các link source §1.2 |
| RUN-01 | Chưa có | Unit + contract + policy | NOT RUN | Chưa có |
| RUN-02 | Chưa có | PostgreSQL migration/transaction/dedup/concurrency | NOT RUN | Chưa có |
| RUN-03 | Chưa có | Webhook E2E + STOMP + hai client | NOT RUN | Chưa có |
| RUN-04 | Chưa có | Failure/restart/requeue/load/observability | NOT RUN | Chưa có |
| RUN-05 | Chưa có | Full quality gates + manual regression | NOT RUN | Chưa có |

Không điền PASS từ kết quả dự kiến. Khi triển khai, thay NR bằng quan sát thực tế từng case; FAIL phải có defect và lần retest, BLOCKED ghi điều kiện thiếu, SKIP ghi quyết định phạm vi. Chỉ nghiệm thu sau khi các gate bắt buộc và giới hạn vận hành đã được đối chiếu bằng bằng chứng.
