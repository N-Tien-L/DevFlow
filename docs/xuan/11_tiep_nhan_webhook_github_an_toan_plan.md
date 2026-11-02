# T-021 — Kế hoạch triển khai tiếp nhận webhook GitHub an toàn

**Milestone 3 · Phụ trách:** Xuân Lê (@xuanle2) · **Ngày đối chiếu:** 16/10/2026, Asia/Saigon.
**Baseline:** `fa22083` trên branch `feature/T-020-gitci-schema`; working tree sạch trước khi lập plan.
**Trạng thái:** ĐÃ LẬP PLAN — chưa sửa controller/security, chưa có verifier/receipt migration và chưa chạy các test T-021 dưới đây.

[Task gốc](11_tiep_nhan_webhook_github_an_toan.md) · [Trello T-021](https://trello.com/c/SJbiORpo/26-t-021-implement-webhook-ingestion-controller-with-hmac-sha-256-validation) · [Tổng quan](00_nhiemvu.md) · [Schema T-020 và Actual](10_thiet_ke_du_lieu_git_ci_plan.md).

## 1. Mục tiêu, căn cứ và phạm vi

### 1.1. Kết quả cần đạt

GitHub gọi được một callback có kiểm soát mà không dùng phiên đăng nhập DevFlow. Backend chỉ chuyển nội dung tới xử lý tiếp sau khi xác minh HMAC SHA-256 trên đúng byte body nhận được và ràng buộc đúng integration/repository. Request bị từ chối không ghi dữ liệu nghiệp vụ, không tạo receipt thành công và không phát domain event.

Hai AC gốc trên Trello là: chữ ký không hợp lệ trả 401; chữ ký hợp lệ cho phép xử lý tiếp payload. Khi đọc ngày 16/10/2026, card thuộc Xuân, ở Backlog, hai AC chưa check; branch đề nghị `feature/T-021-webhook-hmac-controller`. Yêu cầu hiện tại là tạo tài liệu, không thay trạng thái/card hoặc triển khai code.

### 1.2. Hiện trạng đã đọc trong dự án

| Nguồn | Hiện trạng / điều cần xử lý |
|---|---|
| [ARCHITECTURE.md](../ARCHITECTURE.md), mục 2/4/6/9.2/10 | GitCI sở hữu ingestion; typed events theo AGENTS; không truy cập internal/service/repository của module khác |
| [PRODUCT_SPEC.md](../PRODUCT_SPEC.md), mục 5.2a/8 | Liên kết Git/task, validation, anti-abuse, privacy, tracing và xử lý lỗi là yêu cầu nền |
| [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md), Phase 3 và DoD | Endpoint chuẩn `/api/v1/gitci/webhook/github`; parser/event/CI log là phần việc tiếp nối |
| [GitWebhookController](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitWebhookController.java) | Chỉ có `/api/v1/gitci/webhooks/git` và `/ci`, trả 501; chưa có endpoint chuẩn hoặc HMAC |
| [GitCiService](../../backend/gitci-impl/src/main/java/io/devflow/gitci/internal/GitCiService.java) | Stub; `findFailure` chưa triển khai, chưa có handler Git thật để dùng làm positive path |
| [SecurityConfig](../../backend/auth-impl/src/main/java/io/devflow/auth/internal/SecurityConfig.java) | CSRF đang tắt; stateless JWT; mọi route GitCI ngoài health hiện yêu cầu JWT |
| [JwtAuthenticationFilter](../../backend/auth-impl/src/main/java/io/devflow/auth/internal/security/JwtAuthenticationFilter.java) | Vẫn thử parse Bearer nếu có; cần giữ xác thực callback độc lập với JWT, kể cả Authorization rác |
| [WebMvcConfig](../../backend/auth-impl/src/main/java/io/devflow/auth/internal/security/WebMvcConfig.java) | Rate limiter Auth chỉ được gắn vào login; không bảo vệ webhook |
| [RateLimitingInterceptor](../../backend/auth-impl/src/main/java/io/devflow/auth/internal/security/RateLimitingInterceptor.java), [RateLimiterService](../../backend/auth-impl/src/main/java/io/devflow/auth/internal/security/RateLimiterService.java) | Impl thuộc Auth, map key chưa có bound/expiry, tin X-Forwarded-For trực tiếp; không import/tái dùng chéo cho GitCI |
| [GlobalExceptionHandler](../../backend/auth-impl/src/main/java/io/devflow/auth/internal/exception/GlobalExceptionHandler.java) | Advice toàn cục có catch-all và log exception; lỗi JSON có thể chứa nội dung request nếu để rơi vào nhánh này |
| [BoardRequestCorrelationFilter](../../backend/board-impl/src/main/java/io/devflow/board/internal/BoardRequestCorrelationFilter.java) | Tên Board nhưng hiện áp dụng toàn request, đặt UUID correlation/MDC; cần phối hợp, không import internal Board hoặc ghi/clear MDC chồng chéo |
| [V6 schema](../../backend/app/src/main/resources/db/migration/V6__gitci_schema.sql) | Đã có `repositories.id/workspace_id/provider/remote_repo_id/webhook_secret_ref`; remote repo có thể đăng ký ở hai workspace; chưa có delivery receipt |
| [gitci-impl build](../../backend/gitci-impl/build.gradle.kts), [app build](../../backend/app/build.gradle.kts) | Có Web/JPA; app có Flyway/PostgreSQL/JUnit/Testcontainers; GitCI chưa có bộ test HMAC hoặc HTTP |
| [application.yml](../../backend/app/src/main/resources/application.yml), [.env.example](../../.env.example) | Chưa có registry secret/config webhook; không suy ra có secret từ JWT_SECRET hoặc Turnstile |
| [Nginx](../../frontend/nginx.conf), [Vite proxy](../../frontend/vite.config.ts) | Có proxy `/api`; Nginx chưa có policy webhook riêng về body size/read timeout/error/tracing |

Một số tài liệu cũ còn nói gọi public API liên module; plan theo AGENTS hiện hành: giao tiếp nghiệp vụ liên module bằng typed `DevFlowEvent` trong common. Sửa cấu hình route trong Auth là thay đổi hạ tầng có phối hợp, không cho GitCI gọi Auth internal.

### 1.3. Phạm vi và phần mở rộng kỹ thuật được đề xuất

**Phần chính T-021:** endpoint, đọc body có giới hạn, cấu hình/resolve secret, verifier HMAC, ràng buộc repository, envelope validation, internal handler contract, response/error, quota, metrics/log an toàn và bộ test đầy đủ.

**Các điểm cần chạm ngoài `gitci-impl`:** matcher và JWT skip đúng callback trong `auth-impl`; wiring/config tại `app`; cấu hình mẫu/proxy nếu cần triển khai qua Nginx; test HTTP/PostgreSQL tại app. Đây là ảnh hưởng kỹ thuật cần review với Tiến, không thêm dependency impl→impl.

**Mở rộng đề xuất để chống trùng:** migration mới `V7__github_webhook_receipts.sql`, chỉ lưu metadata và digest, không lưu raw payload/secret. V7 là version dự kiến; kiểm tra lại nhánh tích hợp trước khi code. Phần này chưa có trên checklist gốc, được đưa vào kế hoạch để xử lý concurrency/replay trong cửa sổ retention, không âm thầm sửa V6 hay gọi map RAM là dedupe bền vững.

**Bàn giao, không triển khai thay:** parser/tra cứu task T-022; domain events/outbox khi cần T-023; tự chuyển cột T-024; tải log và lỗi CI T-025; frontend T-026. Chưa làm GitLab, GitHub App OAuth/install flow, quản trị integration bằng UI/API, tải URL từ payload, tự tạo webhook qua GitHub API hoặc worker queue xử lý nền.

## 2. Kế hoạch nghiệp vụ — diễn tả theo luồng người dùng/vận hành

### NV-01. Chuẩn bị kết nối một repository

Người quản trị chuẩn bị một repository đã đăng ký trong workspace DevFlow, chọn callback HTTPS, tạo webhook secret ngẫu nhiên và cấu hình cùng giá trị ở GitHub/backend. Chọn content type JSON và chỉ đăng ký nhóm sự kiện cần dùng. Callback dùng một ID integration để chọn đúng cấu hình; ID này không phải mật khẩu.

Secret được đặt trong môi trường/secret manager; database chỉ giữ reference. Mỗi integration có secret riêng, đặc biệt khi cùng remote repo được theo dõi ở hai workspace. Chưa có màn hình quản trị trong T-021 nên bước cấu hình là thao tác vận hành có hướng dẫn và fixture test, không tạo endpoint admin không phân quyền để nhập secret.

### NV-02. GitHub gửi thông báo hợp lệ

Khi GitHub gửi webhook, backend chọn integration, kiểm tra dữ liệu đầu vào có nằm trong giới hạn, xác minh chữ ký trên nguyên bản body, rồi đối chiếu repository của nội dung với repository đã đăng ký. Nội dung chỉ được đưa tới handler sau khi các bước này thành công.

Ping hợp lệ nhận phản hồi kiểm tra kết nối. Một sự kiện đã có handler được xác nhận thành công sau khi transaction xử lý và receipt hoàn tất; receipt chỉ ghi một lần cho cùng nội dung trong phạm vi đã chốt. Đây chưa phải bằng chứng task tự đổi cột hoặc AI đã nhận log.

### NV-03. Chữ ký sai hoặc nội dung bị thay đổi

Request thiếu/sai chữ ký bị từ chối 401 khi đã vượt qua các kiểm tra giao thức/tải cơ bản. Chỉ sửa một byte nội dung cũng không được đưa tới handler nếu chữ ký vẫn của bản cũ. Đăng nhập DevFlow hoặc gửi đúng tên header GitHub không thay được chữ ký hợp lệ.

Phản hồi không nói secret nào được dùng, repository có tồn tại hay digest đúng phải là gì. Hệ thống chỉ tăng metric/ghi log an toàn; không lưu request bị từ chối vào bảng nghiệp vụ hoặc receipt.

### NV-04. Chữ ký đúng nhưng payload không dùng được

JSON hỏng, sai loại dữ liệu, thiếu thông tin bắt buộc hoặc event header không khớp hình dạng body bị trả lỗi rõ ràng sau xác thực, không trở thành sự kiện nghiệp vụ. Sự kiện ngoài phạm vi được bỏ qua theo policy đã công bố; không mở rộng quyền bằng cách đổi event/header.

Nếu event thuộc nhóm đã định hỗ trợ nhưng handler chưa sẵn sàng, trả 503 và thông báo vận hành. Không trả thành công chỉ để che stub/no-op. T-021 có thể kiểm chứng cổng xác thực bằng ping thật và handler fixture trong test; push/PR/CI production chỉ bật khi handler tương ứng đã được ghép và nghiệm thu.

### NV-05. GitHub gửi lại hoặc request chạy đồng thời

Một delivery được gửi lại sau thành công nhận xác nhận trùng và không gọi handler lần nữa. Cùng body nhưng bị thay delivery header vẫn cần được phát hiện trong cửa sổ dedupe. Nếu lần trước lỗi và transaction chưa commit, lần gửi lại có thể thử xử lý lại.

Nếu backend đã commit nhưng kết nối phản hồi bị mất, lần gửi lại phải nhận biết kết quả đã hoàn tất. Nếu đang xử lý mà hết thời gian chờ, trả lỗi tạm thời, không ghi nhận hoàn tất giả. Chống lặp tác động nghiệp vụ ngoài cửa sổ receipt vẫn cần idempotency/freshness trong các ticket sau.

### NV-06. Thay secret, lỗi hạ tầng và khôi phục

Vận hành thay secret theo giai đoạn current/previous có hạn dùng rõ; secret hết grace bị từ chối. Cấu hình sai hoặc thiếu key khi bật tính năng phải được phát hiện trước khi sử dụng, không rơi về key rỗng/JWT key hoặc bỏ kiểm chữ ký.

Khi backend/DB lỗi, người vận hành thấy metric/log có correlation ID và kiểm tra delivery thất bại trên GitHub. Sau khắc phục, redeliver theo hướng dẫn. GitHub không tự gửi lại mọi delivery thất bại; không coi `Retry-After` là một cam kết GitHub sẽ tự retry. Xem [handling failed deliveries](https://docs.github.com/en/webhooks/using-webhooks/handling-failed-webhook-deliveries).

## 3. Kế hoạch kỹ thuật — contract, luồng và các yếu tố cần suy xét

### 3.1. Endpoint và chọn integration trước khi parse JSON

Callback chuẩn: `POST /api/v1/gitci/webhook/github?repositoryId=<UUID-nội-bộ>`.

Query `repositoryId` là đề xuất kỹ thuật bổ sung để chọn đúng hàng `repositories` và `webhook_secret_ref` trước khi parse body. Không chứa secret/token trong URL. Chính path vẫn đúng AC gốc; cấu hình từng webhook GitHub phải điền đầy đủ query. Không lookup “repo đầu tiên” qua remote ID không unique theo workspace, không dùng sender/User-Agent/IP làm identity.

1. Bắt buộc đúng một `repositoryId`, đúng UUID; thiếu/sai/trùng → 400 trước DB. Loại query lạ theo allowlist, không chọn selector từ body/header khác nếu query bị thiếu.
2. Đọc projection của chính module GitCI: id, workspace_id, provider, remote_repo_id, webhook_secret_ref. Unknown ID/provider khác GITHUB → 401 generic; read failure/DB timeout → 503.
3. Resolve reference bằng registry cấu hình allowlist gắn với integration ID. Không hỗ trợ reference tùy ý dạng file/HTTP hoặc quét nhiều key để tìm một chữ ký khớp.
4. HMAC thành công rồi mới parse JSON; `repository.id` trong payload phải khớp remote ID của binding. Workspace lấy từ DB/binding, không từ body hay query tùy ý. Mismatch → cùng nhóm 401 generic, không handler/receipt.
5. Nếu repo bị xóa/đổi ref giữa lookup và xử lý, xác minh lại binding trong transaction khi ghi receipt; FK/lock và so sánh provider/remote/ref phải từ chối binding cũ, rollback và trả lỗi tạm thời. Không xử lý tenant cũ từ cache hết hạn.

HMAC không ký URL/query. Vì vậy tái sử dụng cùng key giữa hai integration có cùng remote repo sẽ làm mất khả năng phân biệt callback; registry phải từ chối duplicate key material/reference xuyên integration khi bật tính năng. Fingerprint để kiểm tra collision không được đưa vào log/response. Trường hợp cần một shared organization/App hook là thiết kế routing khác, ngoài baseline repository webhook này.

### 3.2. Pipeline kỹ thuật tổng thể

```text
HTTP/TLS + proxy limits
  -> matcher đúng POST callback; correlation + global/peer quota + in-flight limit
  -> kiểm selector / signature shape / content type / content encoding
  -> đọc raw bytes tối đa N+1, có timeout đọc thật ở connector/proxy
  -> lookup binding + resolve tối đa hai key (current/previous có hạn)
  -> HmacSHA256(raw bytes), so sánh digest 32 byte
  -> parse JSON riêng có bounds + check envelope/repository/event family
  -> begin local transaction, xác minh lại binding
       -> receipt conflict/digest lookup: duplicate hoặc conflict, không gọi handler
       -> insert receipt PROCESSING chưa commit
       -> internal verified handler
       -> update COMPLETED + commit
  -> 200 ping / 204 processed-or-duplicate / 204 policy-ignored
Lỗi -> rollback nếu có, ProblemDetail an toàn; không ACK thành công trước commit
```

Unsupported event được xác thực và kiểm repository rồi trả 204 ignored theo policy; không tạo receipt dedupe có thể chặn một delivery được gửi lại với header đúng. Không dùng `@Async`, executor fire-and-forget hoặc event bus RAM rồi trả 202. Nếu đổi sang ACK trước xử lý trong tương lai, phải có durable inbox và worker/retry contract riêng; receipt metadata này không phải inbox chứa payload.

### 3.3. Raw body, giới hạn và thời gian đọc

- Dùng filter/HTTP guard thuộc GitCI đọc `InputStream` vào buffer có giới hạn, trước binding `@RequestBody byte[]`. Không chỉ kiểm `Content-Length`; request thiếu length/chunked vẫn phải dừng ở N+1 và trả 413.
- Wrapper cung cấp lại **cùng byte array** cho verifier/controller; không normalize CRLF, trim, strip HTML, đổi encoding, parse/serialize hoặc URL-decode trước HMAC. `ContentCachingRequestWrapper` đơn thuần không phải cơ chế chặn đọc vượt bound.
- Đề xuất default N=1.048.576 byte, tối đa 16 request đang đọc/xử lý trên một instance. Property phải được validate; tuning N phải xem đồng thời heap/copy/JSON overhead và proxy limit, không coi N × 16 là toàn bộ heap sử dụng.
- Chỉ nhận `application/json`, charset UTF-8 hoặc không khai báo; Content-Encoding absent/identity. Form payload, gzip hoặc charset khác → 415. Cấu hình GitHub phải chọn JSON. Giới hạn 1 MiB là policy DevFlow, nhỏ hơn cap provider; đo payload mẫu trước rollout, báo 413 rõ và có hướng xử lý/tuning thay vì làm mất nội dung im lặng.
- Body rỗng hoặc UTF-8 không hợp lệ/BOM bị policy loại bỏ: HMAC vẫn được kiểm trên byte nhận được trước, sau đó 400 ở validation nếu chữ ký hợp lệ. Không cho byte decoder thay ký tự lỗi rồi xác thực trên bản đã sửa.
- Cấu hình read/upload timeout và header/body limits thực sự ở Tomcat/proxy; test chunked/slow client qua cổng HTTP thật. MVC async timeout hoặc kiểm thời gian giữa hai lần `read` không tự ngắt một blocking read đang chờ.
- Budget đề xuất: body read khoảng 3s, lookup/lock hữu hạn, transaction handler tối đa khoảng 3s; target trả phản hồi trong 8s ở tải chuẩn. Không trả 503 vì future timeout rồi để thread nền tiếp tục commit. Timeout/connection reset phải được test theo transaction outcome và redelivery.

### 3.4. Secret và thuật toán HMAC

**Cấu hình mới dự kiến:** prefix `devflow.gitci.github-webhook`, gồm enabled, max-body-bytes, max-in-flight, hạn mức, receipt-retention và bindings theo repository ID/reference. Mỗi binding resolve current secret từ biến môi trường/secret store cấu hình sẵn; previous key có previous-valid-until. Không ghi key vào YAML commit, `.env.example`, SQL fixture thật, health endpoint, Actuator env hoặc log.

Production key đề xuất là chuỗi entropy cao được sinh từ ít nhất 32 random byte, lưu dạng text và sử dụng đúng chuỗi text đó ở cả hai đầu. Kiểm config không rỗng, chiều dài có bound, không có newline ngoài ý muốn; không tự trim hoặc Base64-decode key khi GitHub đang dùng chính chuỗi text làm secret. Key HMAC khác JWT_SECRET, Turnstile secret và GitHub API token.

Verifier dùng JCA `Mac` với `HmacSHA256`, key bytes UTF-8, raw body bytes. `Mac` là stateful: tạo instance theo request/key hoặc cơ chế thread confinement đã kiểm chứng, không dùng một instance mutable chung giữa các thread. Header chỉ có một giá trị, prefix đúng `sha256=`, sau đó đúng 64 hex; chấp nhận hex hoa/thường như hai biểu diễn cùng digest, không fallback SHA-1. Header quá dài/sai format → 401.

Decode hex thành 32 byte và dùng `MessageDigest.isEqual(expected, supplied)` với hai mảng đủ độ dài; không so sánh String bằng equals hoặc tự loop dừng ở byte đầu. Với rotation, tính đủ các key còn hợp lệ, không trả/lưu key nào khớp; giới hạn hai key, Clock được inject để test grace cutoff. Không hứa toàn bộ HTTP request có latency constant-time vì DB, format validation và quota là các nhánh riêng.

Nguồn đối chiếu: [GitHub validation](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries), [JDK17 MessageDigest](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html). Test vector chính thức: secret `It's a Secret to Everybody`, payload `Hello, World!`, digest `757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17`. Vector này chỉ kiểm thuật toán; không coi payload text đó là webhook JSON hợp lệ hoặc key ví dụ là production secret đạt policy.

### 3.5. HTTP headers, JSON envelope và trusted handoff

- Sau HMAC, yêu cầu đúng một `X-GitHub-Delivery` và `X-GitHub-Event`, mỗi header có length/ASCII allowlist. Đề xuất delivery là opaque ASCII token 1–128 ký tự chữ/số/dấu gạch; event 1–64 ký tự chữ thường/số/underscore. Reject nhiều header hoặc dạng comma-joined mơ hồ, không chọn giá trị đầu/cuối tùy ý.
- Digest chỉ bảo vệ body, không bind `X-GitHub-Event`, delivery, Hook-ID, sender identity hoặc URL. Đây là suy luận trực tiếp từ công thức HMAC(body), vì vậy các header chỉ là metadata, không được làm quyền tenant hoặc bằng chứng chống replay duy nhất. Đổi delivery header không được tạo tác động mới từ cùng signed body trong retention.
- Dùng ObjectMapper/JsonFactory riêng cho webhook, bật duplicate-field detection, từ chối trailing tokens; đề xuất max nesting depth 32, max string length không vượt N, max number length 64. Xác nhận API Jackson đúng version BOM khi code; không mutate global ObjectMapper làm hỏng Auth/Board.
- Root phải là object, có repository.id hợp lệ và khớp binding; remote ID so sánh canonical, không đọc qua double làm mất precision số nguyên. Unknown fields không liên quan được bỏ qua để tương thích provider, còn duplicate critical keys, sai kiểu hoặc payload mơ hồ phải bị từ chối.
- `ping`: envelope ping repository webhook hợp lệ, xử lý kiểm tra kết nối. `push`, `pull_request`, `workflow_run`: check tối thiểu hình dạng family theo schema provider rồi chuyển handler đã đăng ký. Family thiếu/sai field → 400; action chi tiết và data mapping thuộc ticket sau.
- Event header đổi sang một family khác phải thất bại schema guard; không dùng header `pull_request` để suy ra PR đã merge, không map header/body không nhất quán vào domain event. Unknown family được ignored sau các guard chung, không ghi completed receipt làm poison lần xử lý đúng sau đó.
- Handoff dùng type internal `VerifiedGitHubWebhook`: binding IDs, digest, delivery/event và payload snapshot/defensive copy. Chỉ factory sau xác minh tạo type này; không bind trực tiếp từ request hoặc nhận cờ `verified=true` của client. Không chia sẻ mutable byte[]/JsonNode giữa request; handler không được đổi binding/digest đã xác minh.
- Handler trả kết quả rõ `PROCESSED`, `IGNORED`, `NOT_READY`, hoặc lỗi; ping có handler thật trong T-021. Với family thuộc planned scope nhưng chưa có handler, NOT_READY → 503, rollback receipt. Positive test có spy/fixture handler để chứng minh chuyển đúng payload, không dùng no-op production để báo đã xử lý Git thật.

[GitHub headers/payloads](https://docs.github.com/en/webhooks/webhook-events-and-payloads) được đối chiếu cho tên header, JSON content type và body cap. Baseline này chỉ hỗ trợ repository webhook GitHub.com; org/App/Enterprise routing phải có thiết kế riêng trước khi bật.

### 3.6. Receipt, transaction và replay

Đề xuất thêm một bảng `github_webhook_receipts` do GitCI sở hữu trong V7, không sửa V6:

| Trường | Thiết kế dự kiến |
|---|---|
| id | UUID PK, default gen_random_uuid() |
| repository_id | UUID NOT NULL, FK repositories(id) ON DELETE CASCADE |
| delivery_id | VARCHAR(128) NOT NULL, token không rỗng |
| payload_sha256 | VARCHAR(64) NOT NULL, đúng 64 lowercase hex; SHA-256 của byte body gốc, không phải HMAC/key |
| event_name | VARCHAR(64) NOT NULL, family đã kiểm tra, không dùng làm quyền |
| status | VARCHAR(16) NOT NULL; PROCESSING hoặc COMPLETED |
| completed_at | TIMESTAMPTZ NULL; CHECK chỉ COMPLETED mới có timestamp, PROCESSING phải NULL |
| created_at, updated_at | TIMESTAMPTZ NOT NULL, default CURRENT_TIMESTAMP; writer cập nhật khi hoàn tất |

Unique `(repository_id, delivery_id)` và `(repository_id, payload_sha256)`; index created_at cho cleanup có bound. Receipt không có FK/task/workspace ngoài module, không raw payload, chữ ký HMAC, email, message hoặc secret. Digest vẫn là metadata cần bảo vệ; không coi hash là đã ẩn danh tuyệt đối.

Sau MAC/envelope check, transaction READ COMMITTED đọc lại repository bằng `SELECT ... FOR SHARE`, so sánh provider/remote/ref với binding đã xác thực, rồi `INSERT ... ON CONFLICT DO NOTHING` có RETURNING. Giữ shared row lock tới commit để chặn xóa hoặc đổi binding trong lúc handler chạy; không dựa riêng vào FK/key-share lock vì nó không chặn mọi cập nhật cột ngoài khóa. Đặt lock timeout hữu hạn; nhiều delivery khác nhau vẫn có thể giữ shared lock đồng thời. Row PROCESSING chỉ tồn tại chưa commit trong đường xử lý bình thường. Handler ghi GitCI trong **cùng transaction**, trả kết quả rồi set COMPLETED và commit; controller ACK sau khi transaction đã commit thật, không đặt @Transactional ngoài phạm vi tạo response khiến tưởng đã commit sớm.

Nếu insert conflict, xử lý transaction/snapshot đúng PostgreSQL: ưu tiên so delivery ID; cùng ID mà khác digest → 409, không overwrite. Cùng digest dưới ID khác hoặc cùng ID/digest đã COMPLETED → 204 duplicate, handler không chạy. Conflict với transaction chưa kết thúc chờ trong lock timeout hữu hạn; timeout/row PROCESSING đã commit bất thường → 503, không ACK completed. Không catch unique exception rồi tiếp tục trên transaction aborted.

Lỗi handler/DB, request timeout được phát hiện trước commit hoặc instance chết trước commit → rollback receipt và các ghi GitCI trong transaction. Commit xong nhưng response mất → duplicate sau redelivery không chạy handler lại. Sự kiện thuộc module khác chỉ qua typed event ở T-023; receipt không làm Spring event bus trở thành durable và không bảo đảm exactly-once cho email/AI/board side effects ngoài transaction. Outbox/idempotent consumer thuộc scope cần ghép trước khi cam kết delivery liên module không mất.

Retention đề xuất 7 ngày, cấu hình và review trước release; cleanup batch tối đa 1.000 hàng/lượt, không giữ transaction dài hoặc đụng row đang xử lý. Sau TTL, signed body cũ vẫn có thể hợp lệ về HMAC: replay protection chỉ có cửa sổ hữu hạn. Business state/upsert/freshness T-022/T-023/T-025 phải chịu được replay ngoài cửa sổ; không xóa constraint để giải quyết retry.

### 3.7. Security chain, quota và proxy

1. Trong Auth, dùng một matcher nhất quán cho **đúng POST canonical path** để permit callback và skip JwtAuthenticationFilter ở chính route đó, kể cả filter được servlet auto-register. Không mở `/api/v1/gitci/**` hoặc toàn bộ `/webhooks/**`. JWT hợp lệ không làm signature sai trở thành hợp lệ; Bearer rác không được làm log parser token hoặc quyết định HMAC thay đổi.
2. Giữ bảo vệ route khác: `/auth/me`, workspace, Board, GitCI admin tương lai và legacy webhook. Loại bỏ/giữ protected các stub cũ có quyết định rõ; không tạo alias bỏ qua verifier. Với CSRF hiện tắt không cần tắt thêm; nếu bật lại sau này chỉ exempt callback M2M đúng scope. GitHub delivery không phải mutation form người dùng nên không đòi Turnstile/browser cookie.
3. Early guard thuộc GitCI, đăng ký một lần; đặt order rõ với security/correlation filter, không hai registration cho cùng filter. Request wrapper và MDC phải cleanup/restore trong finally, kiểm cả lỗi/ERROR dispatch/unmount connection; không đọc body hai lần hoặc xử lý lại trên ERROR dispatch.
4. Quota đề xuất để thử tải: global 30 request/s burst 60, peer 300/phút burst 100 trước lookup/HMAC; integration 120/phút burst 60 sau xác thực. Các số này là default cần đo với nguồn GitHub thực, không phải SLA. Vượt quota → 429 + Retry-After; đầy in-flight → 503 hữu hạn, không queue RAM vô hạn.
5. Cache limiter tối đa 10.000 peer key, expiry 15 phút, có policy khi đầy; không tạo map vô hạn từ UUID/delivery/event của client. Integration key chỉ tạo sau khi xác thực đúng binding. Fake Clock trong test; giới hạn request/byte/in-flight khác nhau phải test riêng.
6. Không tin trực tiếp X-Forwarded-For/X-Real-IP từ internet. Mặc định dùng peer address; chỉ giải IP client khi đi qua proxy CIDR được cấu hình tin cậy và proxy đã overwrite header. Nginx hiện overwrite X-Real-IP nhưng chưa có trust policy backend; chưa được coi là đã giải quyết. Peer/IP allowlist chỉ là defense-in-depth, không thay HMAC.
7. Proxy/connector áp body cap/timeout thống nhất, không rewrite/normalize body, không redirect POST callback. Lỗi do proxy phát 413/timeout có thể khác ProblemDetail của app; ghi rõ trong test và cấu hình JSON an toàn tại edge nếu yêu cầu contract thống nhất. TLS chứng chỉ hợp lệ; không tắt SSL verification để demo qua GitHub.

### 3.8. Response, lỗi và observability

Thứ tự ưu tiên: route/method và early quota/feature gate → shape selector/header/media/size → binding/HMAC → envelope → transaction/handler. AC 401 được kiểm ở trạng thái tính năng sẵn sàng, request trong giới hạn; oversized/quota/unsupported media có thể bị chặn trước chữ ký. Không đọc body quá mức chỉ để trả 401 cho mọi request lỗi kép.

| Tình huống | HTTP / kết quả |
|---|---|
| Ping đã xác thực và commit receipt | 200, body tối thiểu `{ "status": "ok" }` |
| Handler đã hoàn tất, hoặc duplicate đã COMPLETED | 204, không body |
| Family ngoài scope, đã qua MAC/binding/JSON guard chung | 204 ignored, không handler/receipt nghiệp vụ |
| Signature thiếu/sai/format sai; repo không hợp lệ hoặc không khớp binding | 401, code WEBHOOK_UNAUTHORIZED, detail generic |
| Selector/header/envelope/JSON/UTF-8 sai sau các guard tương ứng | 400, code theo nhóm lỗi, không echo giá trị |
| Vượt raw-body bound / content type hoặc encoding không hỗ trợ | 413 / 415 |
| Cùng delivery ID nhưng digest khác | 409, không thay receipt cũ |
| Quota hết | 429 + Retry-After, correlation ID |
| Feature disabled, handler chưa sẵn sàng, DB/key service unavailable, lock/processing timeout | 503 + Retry-After, không kết luận đã nhận thành công |
| Lỗi không dự kiến | 500 generic, rollback; không lộ payload/key/stack trace |

GET/PUT/OPTIONS không được permit như POST: anonymous chịu policy JWT hiện hành; request đã qua auth tới MVC nhận 405/404 theo mapping thực tế, không handler. Không cần bật CORS/browser login cho GitHub.

Filter errors dùng `GitHubWebhookProblemWriter`; MVC dùng advice scoped GitCI có precedence phù hợp trước Auth catch-all. Chuẩn app: application/problem+json, status/title/detail/code/instance không có query nhạy cảm, timestamp và correlationId. Không cho parse exception chảy tới handler log toàn cục đang ghi ex.getMessage()/stacktrace; writer không serialize exception hoặc raw URI chứa query.

Log whitelist outcome/reason code, duration, correlationId; identity repository/delivery nếu cần chỉ trong log bảo vệ và không kèm payload/secret/signature/Authorization. Không gán sender GitHub thành userId DevFlow. Tận dụng correlation hiện có khi hợp lệ, fallback nếu thiếu; không import Board internal hoặc làm clear MDC của filter ngoài. Structured JSON logging phải được kiểm trên môi trường thật; việc có MDC không chứng minh encoder JSON đã được cấu hình.

Micrometer: counter kết quả và rejection reason, timer tổng/HMAC/DB/handler, in-flight gauge, receipt duplicate/conflict, cleanup count. Labels chỉ provider/family allowlist/outcome; unknown family = other, không label repo/delivery/IP/email/raw header. Nếu cần micrometer-core, thêm dependency do BOM quản lý trong GitCI, không import metrics interceptor của Board/Auth. Readiness feature-scoped phát hiện key/DB không sẵn sàng, không expose secret qua Actuator; lỗi webhook không tự làm liveness toàn app chết.

### 3.9. Cấu hình cần cung cấp và triển khai vận hành

Để viết unit/integration tests dùng secret giả, không cần API key thật. Để demo GitHub thật cần: một hàng repository GitHub đúng workspace/remote ID; secret riêng mỗi integration được cấu hình local; URL HTTPS callback có repositoryId; quyền quản trị webhook của repo và môi trường DB đã migrate. Người dùng cấu hình secret tại môi trường chạy, không gửi key trong chat hoặc commit.

GitHub API token không cần để xác minh HMAC nhận webhook; chỉ cần quyền/API credential riêng nếu chọn tự quản lý/redeliver bằng API hoặc lấy CI log ở T-025. Hai loại credential không dùng thay nhau.

Quy trình rollout: validate config và unique key binding → migrate receipt trên clone → test HTTP local/proxy → cấu hình HTTPS/JSON/secret trên repo demo → ping thật → thử tamper/redelivery → bật từng event khi handler ready → theo dõi reject/latency và lưu evidence đã che dữ liệu. Kế hoạch thao tác ngoài GitHub chỉ được thực hiện khi có yêu cầu triển khai/demo tương ứng; hiện chưa tạo webhook/tunnel hoặc sửa môi trường thật.

GitHub khuyến nghị phản hồi trong 10 giây; DevFlow đặt budget test 8 giây để có khoảng dự phòng. Xem [best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks). Khi rollback code, tắt feature/ghi nhận delivery thất bại để redeliver sau; không trả fake 200 hoặc xóa receipt đã commit. Không hứa retry tự động của provider.

## 4. Trình tự triển khai và artifact dự kiến

| Bước | Công việc | Bằng chứng cần có |
|---|---|---|
| K1 | Chốt query selector, key registry/rotation, receipt V7, sync commit/ACK, status/error và handler readiness với đầu mối Auth/GitCI | Contract/schema/test expected thống nhất; các bổ sung ngoài AC gốc được thể hiện rõ trong PR |
| K2 | Kiểm baseline/migration version, tạo branch T-021 khi bắt đầu code; thêm properties/secret resolver/lookup binding/verifier | Unit vector, config validation, không secret trong diff |
| K3 | Canonical controller, early bounded body guard, exact security matcher/JWT skip, ProblemDetail, envelope và verified handler port | App HTTP tests đi qua security/filter thật, không chỉ MockMvc standalone |
| K4 | Receipt migration/repository/service transaction, cleanup, quota/metrics/log | PostgreSQL thật: conflict, rollback, multi-instance, TTL; logging và limiter bounds |
| K5 | Ghép ping thật, handler fixture trong test; khi ghép T-022/T-025 kiểm event production riêng | Chứng minh AC2 và ghi rõ readiness; không gọi stub findFailure hoặc no-op để ACK push |
| K6 | Full check, hồi quy Auth/Board/schema, proxy/load/demo GitHub có cấu hình thật | Report, Actual từng case, before/after DB và trace đã che secret |
| K7 | Review PR, bàn giao contract cho T-022/T-023/T-025, cập nhật Trello bằng evidence | Không chuyển Done khi thiếu AC/bằng chứng; review/merge tách với việc lập plan |

Artifact dự kiến, chưa tồn tại: `GitHubWebhookProperties`, `GitHubWebhookSecretResolver`, `GitHubWebhookBindingLookup`, `GitHubWebhookSignatureVerifier`, `GitHubWebhookRequestFilter`, `VerifiedGitHubWebhook`, `GitHubWebhookHandler`, `GitHubWebhookReceiptService`, limiter/metrics/problem writer/advice trong `io.devflow.gitci.internal.*`; chỉnh controller có sẵn, security matcher/skip trong Auth và config/proxy cần thiết. Migration V7 và receipt mapping/test tại app/GitCI; `gitci-api` không nhận JPA/controller/secret DTO. Không cần thêm event common chỉ để gọi handler trong cùng module.

## 5. Tiêu chí hoàn thành

### 5.1. AC gốc trên Trello

- [ ] **AC01:** Request có signature thiếu/sai trong điều kiện request hợp lệ về protocol/bounds bị 401; không handler, receipt hoặc mutation/event.
- [ ] **AC02:** Chữ ký đúng trên raw bytes cho phép xử lý tiếp: ping thật hoàn tất; test handler được gọi đúng trusted envelope một lần; transaction thành công mới ACK. Không đánh đồng chữ ký đúng với payload/handler luôn thành công.

### 5.2. Điều kiện bổ sung để nghiệm thu rõ ràng

- [ ] **AC03:** Đúng canonical endpoint, exact POST public matcher/JWT skip; JWT/cookie/Turnstile không thay HMAC; route khác giữ policy hiện tại.
- [ ] **AC04:** Chọn đúng integration trước parse, secret ref/rotation/key uniqueness fail closed, signed repository khớp binding; không lẫn tenant khi cùng remote repo có nhiều workspace.
- [ ] **AC05:** Body/headers/JSON/CPU-concurrency/read time có bound; reject tamper Unicode/CRLF/bytes, format/algorithm yếu, malformed JSON và event family mismatch.
- [ ] **AC06:** Receipt PostgreSQL mới đúng ownership/version, dedupe delivery+digest, chống conflict/replay trong TTL, rollback/commit-before-ACK/multi-instance có test.
- [ ] **AC07:** Lỗi có ProblemDetail an toàn, quota/trusted proxy đúng, metric cardinality hữu hạn và không lộ payload/key/token trong response/log/report/health.
- [ ] **AC08:** Handler chưa ready không ACK giả; contract không cam kết durable delivery liên module khi chưa có outbox/idempotency; T-022/T-025 có gate ghép rõ.
- [ ] **AC09:** Có HTTP thật/proxy/PostgreSQL/load evidence, boundary/check/hồi quy PASS; demo GitHub thật được ghi riêng nếu đủ cấu hình, không dùng mock làm bằng chứng production.
- [ ] **AC10:** Documentation/Actual/secret provisioning/redelivery/retention rõ ràng; PR peer review và merge theo workflow trước Done.

## 6. Kế hoạch testing — Expected và Actual

### 6.1. Harness, fixture và quy tắc ghi nhận

- Unit: JUnit5, fake Clock, golden signature/vector độc lập; không dùng chính verifier tạo expected cho tất cả test. Key ví dụ ngắn chỉ dùng crypto vector; test properties dùng key giả đủ policy.
- App HTTP: embedded server RANDOM_PORT, security/JWT/filter/advice thật; gửi byte[] nguyên bản bằng HTTP client. MockMvc hỗ trợ routing/service spy nhưng không thay test chunked/slow-read/container header/proxy.
- DB: Testcontainers PostgreSQL17, Flyway từ production resources và migration receipt mới, ddl-auto validate; DB/schema cách ly; không dùng H2/create-drop để nghiệm thu receipt/lock/unique.
- Fixtures: integration A/B khác workspace có cùng remote ID, key khác nhau; integration C repo khác; unknown/disabled/GITLAB; current/previous/expired keys; ping/push/PR/workflow JSON hợp lệ; synthetic handler viết một test marker trong cùng DB transaction, có fault injection trước/sau commit.
- Mọi case từ chối xác minh thêm handler invocation=0, domain event=0 và before/after business+receipt không đổi, trừ outcome duplicate đã có receipt hoặc giới hạn transaction được mô tả. Metrics/log an toàn được phép thay đổi; không ghi body/key để làm evidence.
- Actual hiện **CHƯA CHẠY** cho mọi case. Khi chạy ghi PASS/FAIL/BLOCKED/SKIPPED, commit/môi trường/config, test IDs/subcases, số đo và evidence. Thiếu secret/demo endpoint là BLOCKED cho demo sau khi xác định, không cản unit; không thừa kế PASS 84 test schema T-020.

### 6.2. Bộ HM — HMAC, signature và key material

| ID | Case / thao tác | Expected | Actual |
|---|---|---|---|
| HM01 | Vector GitHub chính thức và expected digest cố định | Digest khớp; riêng HTTP text vector vẫn không phải JSON webhook hợp lệ | CHƯA CHẠY |
| HM02 | Ping JSON ký đúng raw bytes với key binding | HMAC PASS, chuyển tiếp đúng bytes/envelope | CHƯA CHẠY |
| HM03 | Thiếu/rỗng signature, chỉ có header SHA-1 | 401, không fallback thuật toán cũ | CHƯA CHẠY |
| HM04 | Sai prefix/case prefix, không hex, digest 63/65 ký tự | 401, không exception/parser leak | CHƯA CHẠY |
| HM05 | Digest khác byte đầu/giữa/cuối | Cả ba 401; dùng fixed-length digest compare | CHƯA CHẠY |
| HM06 | Digest hex hoa/thường của cùng byte digest | Đều xác minh được theo policy; prefix vẫn đúng sha256= | CHƯA CHẠY |
| HM07 | Thêm/bớt một byte body, giữ chữ ký cũ | 401 trước JSON/handler/receipt | CHƯA CHẠY |
| HM08 | Cùng JSON đổi thứ tự key/whitespace, giữ signature cũ | 401; không canonicalize JSON trước verify | CHƯA CHẠY |
| HM09 | LF/CRLF/trailing newline thay đổi sau khi ký | Chỉ bản bytes gốc khớp; các bản sửa bị 401 | CHƯA CHẠY |
| HM10 | Tiếng Việt/emoji/multibyte, secret có ký tự đặc biệt hợp lệ | Kết quả khớp vector độc lập theo UTF-8 bytes, không platform default encoding | CHƯA CHẠY |
| HM11 | Sai secret/key JWT/key integration khác | 401 dù client có JWT hợp lệ | CHƯA CHẠY |
| HM12 | Nhiều signature headers hoặc comma-joined signature | 401; không chọn first/last tùy ý | CHƯA CHẠY |
| HM13 | Signature quá dài, whitespace nội bộ/rác nối sau digest | Reject có bound; 401, không đọc/allocate vô hạn | CHƯA CHẠY |
| HM14 | Current key và previous key trong grace | Cả hai hợp lệ, không lộ key matched, không bypass payload guards | CHƯA CHẠY |
| HM15 | Previous key tại/sau cutoff và clock dịch chuyển có kiểm soát | Chỉ chấp nhận theo quy ước cutoff; hết hạn bị 401; không ngủ thật để test | CHƯA CHẠY |
| HM16 | Hàng trăm verifier calls song song, nhiều key/body | Không state bleed từ Mac dùng chung, kết quả từng request chính xác | CHƯA CHẠY |

### 6.3. Bộ BD — Byte stream, media, size và timeout

| ID | Case / thao tác | Expected | Actual |
|---|---|---|---|
| BD01 | Raw body N-1/N/N+1 với Content-Length đúng | Trong bound tiếp tục verify/validate; N+1 trả 413, không handler | CHƯA CHẠY |
| BD02 | Không Content-Length và chunked N/N+1 qua HTTP thật | Đếm bytes thực, N+1 bị 413 trước binding unbounded | CHƯA CHẠY |
| BD03 | Length khai nhỏ/lớn hơn bytes, CL+TE mơ hồ trên container/proxy | Container/edge từ chối hoặc giới hạn theo bytes thật; không request smuggling/side effect | CHƯA CHẠY |
| BD04 | Empty body có HMAC đúng; empty body với chữ ký sai | Đúng HMAC rồi 400 JSON; sai HMAC 401 | CHƯA CHẠY |
| BD05 | application/json không charset và charset UTF-8 | Body bytes không đổi, payload hợp lệ được xử lý | CHƯA CHẠY |
| BD06 | Form/text media, charset UTF-16, missing Content-Type | 415 trước parse; hướng dẫn cấu hình GitHub JSON | CHƯA CHẠY |
| BD07 | Content-Encoding gzip/br, body nén nhỏ nhưng giãn lớn | 415, không tự giải nén rồi ký body đã đổi | CHƯA CHẠY |
| BD08 | Byte UTF-8 lỗi/BOM, ký đúng các bytes đó | MAC tính đúng trước; encoding policy trả 400, không decoder replacement hoặc handler | CHƯA CHẠY |
| BD09 | Middleware đọc body trước, wrapper replay, ERROR dispatch | Verifier/handler nhận đúng raw bytes một lần; lỗi cấu hình bị phát hiện, không verify body rỗng thay thế | CHƯA CHẠY |
| BD10 | Client gửi chậm/ngừng giữa chunks qua server/proxy thật | Read timeout thực sự giải phóng slot; không chỉ MVC timeout/future còn chạy | CHƯA CHẠY |
| BD11 | Ngắt kết nối khi đọc và ngay trước commit | Không receipt completed nửa chừng; transaction outcome rõ, redelivery có thể xử lý đúng | CHƯA CHẠY |
| BD12 | Vượt in-flight rồi phục hồi sau success/error/cancel | 503 hữu hạn khi đầy; semaphore/buffer được giải phóng mọi nhánh | CHƯA CHẠY |

### 6.4. Bộ RT — Routing integration, secret config và tenant

| ID | Case / thao tác | Expected | Actual |
|---|---|---|---|
| RT01 | repositoryId đúng, GITHUB, remote ID và ref hợp lệ | Binding đúng workspace; chỉ resolve key của integration đó | CHƯA CHẠY |
| RT02 | Thiếu/sai UUID, query trùng, selector ngoài allowlist | 400, không fallback sang header/body repo selector | CHƯA CHẠY |
| RT03 | UUID không tồn tại hoặc provider GITLAB | 401 generic cùng nhóm chữ ký sai, không lộ binding | CHƯA CHẠY |
| RT04 | A/B cùng remote ID khác workspace/key; ký A nhưng đổi query sang B | 401 ở B; A hợp lệ không lẫn tenant | CHƯA CHẠY |
| RT05 | Header/payload chứa workspace/task ID khác | Workspace chỉ từ binding; ID client không cấp quyền hoặc đổi route | CHƯA CHẠY |
| RT06 | MAC đúng nhưng repository.id sai/missing/sai kiểu/precision | Không handler; mismatch identity 401, malformed envelope 400 theo contract | CHƯA CHẠY |
| RT07 | Unknown ref, empty key hoặc key service unavailable | Fail closed; config enabled sai không startup khỏe, runtime unavailable 503; không key rỗng/JWT fallback | CHƯA CHẠY |
| RT08 | Hai bindings dùng cùng reference hoặc cùng key material qua alias | Validate từ chối config không tách được integration, không log fingerprint/key | CHƯA CHẠY |
| RT09 | Secret có space/newline ngoài ý muốn, base64 text, độ dài biên | Validation báo lỗi an toàn hoặc dùng literal đúng policy; không trim/decode ngầm | CHƯA CHẠY |
| RT10 | Rotate current/previous nguyên tử khi có request đang chạy | Mỗi request dùng binding snapshot nhất quán; cutoff có Clock, không trộn key/version | CHƯA CHẠY |
| RT11 | Xóa/đổi provider/remote/ref sau lookup nhưng trước receipt transaction | Recheck/lock từ chối binding cũ hoặc rollback; không ghi sai repository | CHƯA CHẠY |
| RT12 | Chữ ký của repo C được gửi vào A; giả Hook-ID/User-Agent/IP | Vẫn 401, các header nguồn không thay HMAC/repo binding | CHƯA CHẠY |

### 6.5. Bộ SC — Spring Security, route và chống lạm dụng

| ID | Case / thao tác | Expected | Actual |
|---|---|---|---|
| SC01 | POST canonical path, không JWT, HMAC đúng/sai | Đúng đi tới verifier/handler; sai 401 từ GitCI, không AUTHENTICATION_REQUIRED thay thế AC | CHƯA CHẠY |
| SC02 | JWT hợp lệ nhưng chữ ký sai/thiếu | 401, không có user bypass | CHƯA CHẠY |
| SC03 | Authorization rác/expired/access/refresh ở callback | Quyết định HMAC không đổi, JWT parse không chạy/log token tại callback | CHƯA CHẠY |
| SC04 | GET/PUT/OPTIONS, suffix/prefix/trailing slash/encoded path/legacy URL | Không route bypass; anonymous giữ policy JWT ngoài exact POST, auth qua MVC không gọi webhook handler sai mapping | CHƯA CHẠY |
| SC05 | Auth me/workspaces, Board mutations, GitCI protected endpoint | Quyền/JWT/Turnstile hiện hành không bị mở rộng bởi permit matcher | CHƯA CHẠY |
| SC06 | Đạt/vượt global, peer và integration quota với fake Clock | 429 + Retry-After hợp lệ; đúng key/stage, phục hồi theo refill | CHƯA CHẠY |
| SC07 | Hàng loạt delivery/UUID/XFF/IP giả thay đổi, cache chạm bound | Không map vô hạn hoặc reset quota bằng unsigned metadata; expiry/bound xử lý đúng | CHƯA CHẠY |
| SC08 | XFF/X-Real-IP giả từ peer không trusted; request qua proxy trusted | Không tin header trực tiếp; nguồn thật theo policy CIDR đã cấu hình, HMAC luôn bắt buộc | CHƯA CHẠY |
| SC09 | Đăng ký filter/dispatch/nested error, correlation có sẵn/rác | Guard chạy một lần; UUID correlation an toàn; MDC/slot được restore/clear đúng, không import Board impl | CHƯA CHẠY |
| SC10 | Feature disabled, thiếu secret, health/liveness/readiness | Callback fail closed 503; không expose key; trạng thái feature đúng, app khác không bị vô hiệu hóa ngoài ý muốn | CHƯA CHẠY |

### 6.6. Bộ EV — Envelope, event family và handoff

| ID | Case / thao tác | Expected | Actual |
|---|---|---|---|
| EV01 | Ping thật có chữ ký, repository và headers đúng | Handler ping chạy, receipt commit, 200 tối thiểu | CHƯA CHẠY |
| EV02 | Push/PR/workflow fixtures hợp lệ với spy handler sẵn sàng | Verified envelope đúng binding/raw bytes; handler đúng family được gọi một lần, 204 sau commit | CHƯA CHẠY |
| EV03 | Family thuộc scope nhưng handler chưa đăng ký/NOT_READY | 503, rollback receipt; không gọi stub/no-op rồi 2xx | CHƯA CHẠY |
| EV04 | JSON hỏng, root array/null/string, trailing JSON | Nếu MAC sai 401 trước parse; MAC đúng thì 400, không echo parser content | CHƯA CHẠY |
| EV05 | Duplicate critical keys, nesting/string/number vượt bound | Reject 400 có bound, không dùng last-key-wins cho identity | CHƯA CHẠY |
| EV06 | Headers delivery/event thiếu, quá dài, comma-joined hoặc nhiều giá trị | 400 sau MAC hợp lệ; không chọn header tùy ý, không receipt | CHƯA CHẠY |
| EV07 | Giữ signed body nhưng đổi event sang family khác | Family schema mismatch 400; không domain event theo header giả | CHƯA CHẠY |
| EV08 | Unknown event đã xác thực, repository hợp lệ | 204 ignored theo policy, không handler/receipt có thể poison delivery đúng | CHƯA CHẠY |
| EV09 | Unknown fields tương thích; sender ghost/nullable | Không dùng sender làm DevFlow actor; dữ liệu ngoài envelope không phá HMAC/routing | CHƯA CHẠY |
| EV10 | Payload cố set verified=true/repositoryId/server digest, mutation shared buffer giữa requests | Type trusted chỉ từ verifier; defensive copy; client không thay binding hoặc làm state bleed | CHƯA CHẠY |

### 6.7. Bộ DD — Receipt, dedupe, transaction và replay

| ID | Case / thao tác | Expected | Actual |
|---|---|---|---|
| DD01 | Migrate từ V6, fresh DB, migrate lại và validate | Bảng receipt đúng metadata/FK/index; version mới đúng một lần; V1–V6/data giữ nguyên | CHƯA CHẠY |
| DD02 | Header/body/HMAC/tenant guard bị từ chối | Business/receipt before-after nguyên vẹn; handler/event 0 | CHƯA CHẠY |
| DD03 | Cùng delivery ID và digest gửi lại sau success | 204 duplicate, giữ receipt cũ, handler/marker tổng cộng một lần | CHƯA CHẠY |
| DD04 | Cùng ID nhưng digest khác, cả hai request đều ký hợp lệ | 409, không overwrite nội dung/identity receipt cũ | CHƯA CHẠY |
| DD05 | Cùng raw body ký hợp lệ, thay delivery ID | Digest unique bắt trùng trong retention, 204 và không thêm tác động | CHƯA CHẠY |
| DD06 | Cùng remote ID ở A/B với keys khác, replay/cross-tenant | Không bypass binding; receipt scope theo internal repo, chữ ký A không dùng cho B | CHƯA CHẠY |
| DD07 | Hai instance/connection gửi đồng thời cùng ID hoặc cùng digest khác ID | Một handler commit; loser duplicate hoặc timeout hữu hạn 503; không map RAM giả multi-instance | CHƯA CHẠY |
| DD08 | Handler ghi marker/GitCI rồi lỗi giữa chừng | Rollback marker+receipt; gửi lại có thể thành công, không poisoned COMPLETED | CHƯA CHẠY |
| DD09 | DB outage/lock timeout/unique conflict exception | 503 hữu hạn hoặc duplicate đã committed; không tiếp tục transaction aborted, không ACK giả | CHƯA CHẠY |
| DD10 | Instance chết trước commit; commit xong nhưng mất response | Trước commit rollback/retry được; sau commit redelivery duplicate, không gọi handler lại | CHƯA CHẠY |
| DD11 | Request invalid/unknown-family tới trước rồi request cùng body đúng metadata | Không receipt poison; bản hợp lệ vẫn tới đúng handler nếu chưa processed | CHƯA CHẠY |
| DD12 | TTL cutoff/cleanup batch, replay sau TTL và xóa repository | Cleanup đúng giới hạn, không hàng đang xử lý; cascade nội bộ; test ghi rõ finite-window và handoff business idempotency, không claim chống replay vĩnh viễn | CHƯA CHẠY |

### 6.8. Bộ ER — Error contract, privacy và quan sát

| ID | Case / thao tác | Expected | Actual |
|---|---|---|---|
| ER01 | Tất cả lỗi app 400/401/409/413/415/429/500/503 | application/problem+json đúng status/code, correlation và timestamp; không raw query/payload/exception | CHƯA CHẠY |
| ER02 | Parser exception chứa secret marker giả và JSON fragment | Advice/filter writer an toàn xử lý, response/log không có marker hoặc stack chứa payload | CHƯA CHẠY |
| ER03 | Sai HMAC/unknown repo/wrong provider/mismatch repo | 401 generic không trả expected digest, matched key hoặc existence detail | CHƯA CHẠY |
| ER04 | Lỗi từ filter trước MVC và handler trong MVC | Cùng error contract, không bị Auth catch-all log untrusted message | CHƯA CHẠY |
| ER05 | Key/signature/Authorization/author/message chứa marker tổng hợp | Không marker trong log, metrics, health, report; key ref không bị coi là plaintext key | CHƯA CHẠY |
| ER06 | 10.000 delivery/event/IP/repo giá trị khác nhau | Metric label series hữu hạn theo allowlist, không dynamic user labels | CHƯA CHẠY |
| ER07 | Success/duplicate/conflict/invalid/ignored/error/cleanup | Counter/timer/gauge đúng một lần theo stage; in-flight trở về 0; không double count ERROR dispatch | CHƯA CHẠY |
| ER08 | Correlation có sẵn/hỏng/missing, hai request trên cùng thread | Response/log đúng request, MDC không rò user/repo/ID sang request sau | CHƯA CHẠY |
| ER09 | 429/503, các lỗi kép signature+oversize+quota theo thứ tự ưu tiên | Retry-After có bound, mã lỗi theo priority đã công bố; không đọc body unbounded để ưu tiên 401 | CHƯA CHẠY |
| ER10 | Log encoder/readiness thực tế trong app/proxy | JSON/tracing hoạt động theo cấu hình đã kiểm; proxy lỗi riêng được ghi rõ; liveness không lộ/mặc định expose key | CHƯA CHẠY |

### 6.9. Bộ QA — Tích hợp thật, performance và hồi quy

| ID | Case / thao tác | Expected | Actual |
|---|---|---|---|
| QA01 | App RANDOM_PORT + security/filter thật + PostgreSQL + secret giả | AC01/AC02 qua HTTP thật, không chỉ verifier unit/MockMvc standalone | CHƯA CHẠY |
| QA02 | Test qua Nginx/Vite/tunnel dùng cùng raw bytes, path/query/headers | HMAC không đổi, không redirect/rewrite; body cap/timeouts và trusted proxy khớp app | CHƯA CHẠY |
| QA03 | GitHub demo repo HTTPS, JSON và secret thật cấu hình local | Ping thật 200; evidence delivery đã che secret; thiếu cấu hình thì ghi BLOCKED cho ca này | CHƯA CHẠY |
| QA04 | Redeliver GitHub thật sau success/failure đã khắc phục | Duplicate không xử lý lặp; failure chưa commit xử lý lại; không giả định auto-redelivery | CHƯA CHẠY |
| QA05 | Ghép push/PR/workflow handler T-022/T-025 khi đã có | Chỉ event có handler ready được bật; receipt/transaction và payload contract đúng, không dùng fake event auto-move để nghiệm thu T-021 | CHƯA CHẠY |
| QA06 | Benchmark payload 1KiB/64KiB/1MiB, valid/invalid, concurrency 1/16/32 | Ghi p50/p95/p99, heap/GC/DB/pool/reject; target normal p95 < 200ms cho payload nhỏ, responses trong budget 8s tải chuẩn; số thực quyết định PASS/FAIL | CHƯA CHẠY |
| QA07 | Soak/rate burst/slow reads/cleanup/rotation lặp 30 phút | Không map/heap/connection/slot leak hoặc queue unbounded; refill/rotation/cleanup đúng, ghi tài nguyên và config | CHƯA CHẠY |
| QA08 | Hồi quy Auth security/JWT, Board/API, T-020 schema/migration | Không mở route khác, không sửa V1–V6; test cũ vẫn qua, không xóa assertion để che lỗi | CHƯA CHẠY |
| QA09 | verifyModuleBoundaries, full backend check và frontend build nếu đổi proxy/config | Build/test/boundary PASS; thiếu Docker không skip rồi coi AC đã qua; ghi phạm vi frontend thực tế | CHƯA CHẠY |
| QA10 | Review PR và evidence/config/runbook/Actual | AC có bằng chứng; giới hạn chưa ghép/production demo được ghi rõ; chỉ Done khi gate thuộc scope đã đạt và review/merge hoàn tất | CHƯA CHẠY |

### 6.10. Truy vết, lệnh chạy và evidence

| Tiêu chí | Bộ test chính |
|---|---|
| AC01 | HM03–HM13, SC01–SC03, DD02, ER01–ER04, QA01 |
| AC02 | HM01/HM02, EV01/EV02, DD03/DD08/DD10, QA01/QA03; readiness theo EV03/QA05 |
| AC03, AC04 | RT01–RT12, SC01–SC05/SC10, QA08 |
| AC05 | HM07–HM16, BD01–BD12, EV04–EV10 |
| AC06 | DD01–DD12, RT11, QA04 |
| AC07 | SC06–SC09, ER01–ER10, QA02/QA06/QA07 |
| AC08–AC10 | EV03, DD08–DD12, QA01–QA10 và review phạm vi/tài liệu |

Tại `backend/`, sau khi các test class được tạo và tên package thực tế đã chốt:

```powershell
.\gradlew.bat :gitci-impl:test
.\gradlew.bat :app:test --tests 'io.devflow.gitci.webhook.*'
.\gradlew.bat :auth-impl:test --tests 'io.devflow.auth.internal.security.*'
.\gradlew.bat :app:test --tests 'io.devflow.gitci.schema.*' --tests 'io.devflow.board.schema.*'
.\gradlew.bat :verifyModuleBoundaries
.\gradlew.bat check
```

Các lớp dự kiến gồm `GitHubWebhookSignatureVerifierTest`, config/limiter/secret tests trong GitCI; app có `GitHubWebhookHttpIntegrationTest`, `GitHubWebhookReceiptIntegrationTest`, transport/proxy và load harness riêng. Hiện chưa có những test T-021 này; không ghi lệnh đã PASS nếu chỉ tìm thấy test T-020. Test secret và expected signature tạo bằng helper độc lập/golden vector, không dùng key production. Dependency test HTTP/security/Micrometer bổ sung đúng module/BOM khi cần; không đặt JPA vào gitci-api.

Khi diff có proxy/frontend config, chạy `npm run build` và `npm run lint` tại frontend; syntax nginx/config check và HTTP proxy test vẫn cần riêng vì Vite build không kiểm chứng Nginx/HMAC. Ghi rõ mọi lệnh, commit, environment, JDK/PG/image, key-ID giả, quota/max-body/timeouts và handler readiness.

Evidence cần: JUnit XML/HTML; status/header/ProblemDetail samples đã che dữ liệu; handler call/event count; before-after DB/receipt; traces concurrency/kill-before-after-commit; metrics/log scan; query/lock/timing/heap graphs nếu có; delivery GitHub thật và redelivery đã che secret. Không attach full body/signature/key/token hoặc URL chứa credential. Trình tự: unit HM/RT → app SC/BD/EV/ER → PostgreSQL DD → proxy/QA → load/soak → review. Dừng gate nếu có bypass auth, tenant leak, ACK giả hoặc tác động sau lỗi bị tuyên bố rollback.

## 7. Rủi ro và phối hợp

| Rủi ro / quyết định | Hướng xử lý / đầu mối |
|---|---|
| Canonical URL thiếu integration selector | Xuân chốt repositoryId query trong config GitHub; không parse body unverified để chọn secret |
| Auth permit rộng hoặc JWT filter vẫn parse callback | Xuân + Tiến review matcher/skip và hồi quy toàn bộ route liên quan |
| Cùng key ở nhiều tenant hoặc nhiều ref alias cùng material | Config uniqueness gate; secret riêng từng integration; App/org hook là thiết kế khác |
| Receipt V7 ngoài checklist gốc, version có thể bị chiếm | Ghi scope trong PR; kiểm migration nhánh tích hợp; forward migration, không sửa V6 |
| HMAC không ký event/delivery/URL, không có timestamp chống replay tự thân | Bind repository, check event schema, unique digest và finite TTL; business freshness/idempotency ở ticket sau |
| Handler chưa có hoặc xử lý external call lâu | T-021 ping thật, non-ready 503; ghép T-022/T-025 theo gate; log fetch/AI không nằm trong transaction callback |
| Event bus không durable, ACK sync không bảo đảm side effect liên module | T-023 cần outbox/idempotent consumer theo yêu cầu độ bền, không hứa exactly-once |
| Proxy thay body/ẩn IP thật, giới hạn khác app | Test HTTP qua proxy, overwrite/trust headers đúng; không dùng User-Agent hoặc IP thay chữ ký |
| GitHub không auto retry, receipt không chứa payload để replay nội bộ | Có runbook redelivery, chưa bật event không ready; inbox/worker hoặc API recovery là scope riêng |
| Filter/advice/MDC hiện có áp dụng ngoài tên module | Không import internal chéo; scoped error writer và kiểm filter order/MDC thực tế |
| Log JSON/key resolver/config/TLS chưa có | Ghi thiếu cụ thể ở Actual, không thừa nhận đã sẵn sàng từ tài liệu kiến trúc hoặc T-020 |

## 8. Kết quả thực tế và mẫu cập nhật

### 8.1. Actual tại thời điểm lập plan

| Hạng mục | Kết quả thực tế ngày 16/10/2026 |
|---|---|
| Đọc repository | Đã đối chiếu controller/service stub, security/JWT, Auth rate/error handler, correlation filter, V6, app/config/proxy và mẫu test hiện có |
| Trello | Đọc được card T-021 và task được giao; hai AC chưa check; không cập nhật/move card trong yêu cầu lập plan |
| Artifact | Tạo duy nhất file plan này ở docs/xuan; không thêm verifier/controller/migration/security config hoặc test code |
| Kiểm tra tài liệu | Đã kiểm tra 92 case ID duy nhất, 22 liên kết nội bộ tồn tại, số cột bảng nhất quán và không có whitespace cuối dòng; đây không phải kết quả runtime T-021 |
| Các quyết định mới | repositoryId query, per-integration key binding, receipt V7 và sync ACK là đề xuất; cần review contract khi triển khai, chưa phải hành vi hiện có |
| HM/BD/RT/SC/EV/DD/ER/QA | Toàn bộ CHƯA CHẠY; chưa có test report T-021 hoặc số đo HMAC/HTTP |
| Backend check / boundary / frontend build | Chưa chạy trong đợt lập plan tài liệu; kết quả T-020 không dùng làm Actual T-021 |
| GitHub thật / secret / HTTPS / PR / merge | Chưa cấu hình/chạy demo, chưa tạo webhook/tunnel, chưa PR/review/merge T-021 |

### 8.2. Mẫu ghi nhận sau mỗi lần triển khai/test

| Lần chạy / thời điểm | Commit và môi trường | Case IDs / subcases | Actual / số đo | Evidence / lỗi / retest |
|---|---|---|---|---|
| Chưa chạy | Baseline fa22083, chưa có commit triển khai T-021 | HM/BD/RT/SC/EV/DD/ER/QA | CHƯA CHẠY | Chưa có report runtime |

Nếu thiếu cấu hình demo, ghi BLOCKED đúng QA03/QA04 và vẫn chạy unit/HTTP giả lập/DB cách ly. Nếu handler Git chưa có, ghi NOT_READY và chưa xác nhận QA05, không thay bằng test mock rồi ghi production PASS. Mỗi bug phải giữ expected, actual cụ thể, commit sửa và lần retest; peer review/merge là gate riêng với việc đã có tài liệu/code.

### 8.3. Nguồn kỹ thuật chính thức đối chiếu thêm

- [GitHub — Validating webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries): raw payload, signature SHA-256, key và vector kiểm tra.
- [GitHub — Webhook events and payloads](https://docs.github.com/en/webhooks/webhook-events-and-payloads): headers, event families, content type và provider payload cap.
- [GitHub — Best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks): HTTPS, ACK deadline, event/action và delivery handling.
- [GitHub — Failed deliveries](https://docs.github.com/en/webhooks/using-webhooks/handling-failed-webhook-deliveries): recovery/redelivery phải được vận hành rõ, không giả định auto retry.
- [Java 17 — MessageDigest.isEqual](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html): API so sánh digest.

Nguồn được đối chiếu ngày 16/10/2026. Các cấu hình quota/size/TTL/budget và schema receipt trong tài liệu là quyết định đề xuất của DevFlow, không phải mặc định hoặc yêu cầu bắt buộc từ GitHub.

## 9. Kết quả triển khai cục bộ ngày 02/11/2026

- Đã triển khai callback GitHub, xác minh HMAC, binding theo repository, quota trong tiến trình, giới hạn body, receipt V13 và xử lý ping đồng bộ. Các handler push/PR/workflow chưa sẵn sàng trả 503; không coi đó là đã xử lý nghiệp vụ.
- Test HTTP thực với PostgreSQL Testcontainers đã kiểm tra signature sai, payload hỏng, ping hợp lệ, duplicate và nhiều delivery đồng thời. Test migration, verifier, cấu hình key và `:verifyModuleBoundaries` đạt. `gradlew check` đạt trước những chỉnh sửa cuối; sau đó đã chạy lại test webhook và boundary. Frontend build, lint và `nginx -t` đạt.
- Thử qua tunnel HTTPS với fixture: signature sai 401, content type sai 415, ping đúng 200, lặp lại 204. Một ping thật từ repository `lvXuankg/repo-test-DevFlow` có receipt COMPLETED vào 13:04:56 giờ Việt Nam; receipt xác nhận commit, không thay cho ảnh chụp HTTP response trên GitHub.
- Kiểm thử bổ sung qua Cloudflare/nginx ngày 02/11: 20 request có chữ ký hợp lệ chạy đồng thời với event không hỗ trợ đều nhận 204 (thời gian dài nhất 1,347 giây); request trên 1 MiB nhận 413 ở nginx; kết nối ngừng gửi body bị nginx đóng sau khoảng 3,004 giây. Đây là kiểm thử tải nhỏ, không phải kiểm thử tải kéo dài hay chứng minh khả năng chịu tải tối đa. Timeout nginx đo thời gian không có dữ liệu, không chứng minh giới hạn tổng thời gian upload khi client gửi nhỏ giọt liên tục.
- Chưa có bằng chứng đầy đủ cho toàn bộ bộ case mục 6, thử tải/slow-client/proxy failure, feature readiness và review/merge. Chưa xác nhận ticket đạt mọi tiêu chí AC03–AC10. Tunnel hiện tại chỉ dùng thử, secret đã xuất hiện trong chat cần thay trước khi tiếp tục sử dụng. Việc chuyển Trello bị từ chối quyền HTTP 401; không đánh dấu Done.
