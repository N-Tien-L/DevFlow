# DevFlow — Kiến trúc Chi tiết Module Auth & Các Luồng Xử lý

> 🌐 **Tài liệu đặc tả kiến trúc thành phần và các luồng tương tác bảo mật của module `auth`**  
> Phiên bản: 1.1 (Cập nhật chuẩn Production SaaS: Phân tách các ticket `T-004`, `T-004A`, `T-004B`)  
> Phạm vi: `backend/auth-api` và `backend/auth-impl`

---

## 1. Sơ đồ Kiến trúc Thành phần (Component Architecture)

Module `auth` chịu trách nhiệm quản lý danh tính người dùng (Users), không gian làm việc (Workspaces), quyền thành viên (Workspace Members), quy trình xác thực email (Verification Tokens), cơ chế phòng chống bot (Turnstile) và cung cấp cơ chế bảo mật xác thực phi trạng thái (Stateless JWT) cho toàn bộ ứng dụng DevFlow.

```mermaid
flowchart TD
    subgraph ClientLayer ["Client Layer"]
        SPA["Web Client (React / Vite)"]
        CLI["MCP Server / API Client"]
    end

    subgraph SecurityFilterChain ["Spring Security 6 Filter Chain & Protection"]
        direction TB
        CORS["CorsFilter"]
        RATE_LIMIT["RateLimitingFilter / Interceptor<br/>(Bucket4j Token Bucket)"]
        JWT_FILTER["JwtAuthenticationFilter<br/>(OncePerRequestFilter)"]
        USER_PASS["UsernamePasswordAuthenticationFilter<br/>(Bypass / Unused)"]
        AUTH_FILTER["AuthorizationFilter<br/>(URL Pattern Rules)"]
        EX_FILTER["ExceptionTranslationFilter"]
        ENTRY_POINT["JwtAuthenticationEntryPoint<br/>(RFC 7807 401 ProblemDetail)"]
    end

    subgraph AuthSecurityCore ["Security Core Components"]
        TOKEN_PROVIDER["JwtTokenProvider<br/>(HS256 / Claims / Sign / Verify)"]
        USER_PRINCIPAL["UserPrincipal<br/>(UserDetails adapter)"]
        SEC_CONTEXT["SecurityContextHolder<br/>(ThreadLocal SecurityContext)"]
    end

    subgraph ControllerLayer ["REST Controller Layer"]
        AUTH_CTRL["AuthController<br/>(/api/v1/auth)"]
        HEALTH_CTRL["AuthHealthController<br/>(/api/v1/auth/health)"]
        EX_HANDLER["GlobalExceptionHandler<br/>(RFC 7807 ProblemDetail)"]
    end

    subgraph ServiceLayer ["Service & Protection Layer"]
        AUTH_SERVICE["AuthServiceImpl<br/>(Login, Refresh, /me)"]
        REG_SERVICE["RegistrationServiceImpl<br/>(Register, Verify Email)"]
        TURNSTILE_SVC["TurnstileService<br/>(Cloudflare Siteverify)"]
        EMAIL_SVC["EmailSenderService<br/>(Async Transactional Mail)"]
        AUTH_API["AuthApi (Interface in auth-api)<br/>(findUser, isWorkspaceMember)"]
        PWD_ENC["PasswordEncoder<br/>(BCrypt)"]
    end

    subgraph RepositoryLayer ["Data Access Layer (Spring Data JPA)"]
        USER_REPO["UserRepository"]
        TOKEN_REPO["VerificationTokenRepository"]
        WS_REPO["WorkspaceRepository"]
        MEMBER_REPO["WorkspaceMemberRepository"]
    end

    subgraph DatabaseLayer ["PostgreSQL Database (Schema devflow)"]
        DB_USERS[("users (status: PENDING/ACTIVE)")]
        DB_TOKENS[("verification_tokens")]
        DB_WS[("workspaces")]
        DB_MEMBERS[("workspace_members")]
    end

    %% Client requests
    SPA --> CORS
    CLI --> CORS
    CORS --> RATE_LIMIT
    RATE_LIMIT --> JWT_FILTER
    JWT_FILTER --> USER_PASS
    USER_PASS --> AUTH_FILTER

    %% Filter & Security Core interaction
    JWT_FILTER -->|1. Validate & Parse| TOKEN_PROVIDER
    JWT_FILTER -->|2. Build Principal| USER_PRINCIPAL
    JWT_FILTER -->|3. Set Authentication| SEC_CONTEXT

    %% Exception handling
    AUTH_FILTER -->|Deny / Unauthenticated| EX_FILTER
    EX_FILTER -->|Trigger on 401| ENTRY_POINT
    ENTRY_POINT -.->|Response 401 ProblemDetail| SPA

    %% Dispatch to Controller
    AUTH_FILTER -->|Permitted / Authenticated| AUTH_CTRL
    AUTH_FILTER -->|Permitted| HEALTH_CTRL
    AUTH_CTRL -.->|Exceptions caught by| EX_HANDLER
    EX_HANDLER -.->|RFC 7807 JSON| SPA

    %% Controller to Services
    AUTH_CTRL --> AUTH_SERVICE
    AUTH_CTRL --> REG_SERVICE
    AUTH_SERVICE --> PWD_ENC
    AUTH_SERVICE --> TOKEN_PROVIDER
    AUTH_SERVICE -.->|Implements| AUTH_API

    REG_SERVICE --> TURNSTILE_SVC
    REG_SERVICE --> PWD_ENC
    REG_SERVICE --> EMAIL_SVC

    %% Services to Repositories
    AUTH_SERVICE --> USER_REPO
    AUTH_SERVICE --> WS_REPO
    AUTH_SERVICE --> MEMBER_REPO
    REG_SERVICE --> USER_REPO
    REG_SERVICE --> TOKEN_REPO

    %% Repositories to Database
    USER_REPO --> DB_USERS
    TOKEN_REPO --> DB_TOKENS
    WS_REPO --> DB_WS
    MEMBER_REPO --> DB_MEMBERS
```

---

## 2. Luồng Xử lý 1: Request Hợp lệ Gửi Kèm JWT Token

Áp dụng cho mọi API được bảo vệ (ví dụ: `GET /api/v1/boards`, `GET /api/v1/auth/me`).

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client (React SPA)
    participant Filter as JwtAuthenticationFilter
    participant Provider as JwtTokenProvider
    participant Context as SecurityContextHolder
    participant Authz as AuthorizationFilter
    participant Controller as Business Controller

    Client->>Filter: HTTP Request (Header: "Authorization: Bearer <jwt_token>")
    Filter->>Filter: resolveToken(): Tách chuỗi Bearer lấy JWT
    
    alt Token có dạng văn bản và có giá trị
        Filter->>Provider: validateToken(jwt)
        Provider-->>Filter: true (Chữ ký hợp lệ, chưa hết hạn)
        
        Filter->>Provider: getTokenTypeFromToken(jwt)
        Provider-->>Filter: "access"
        
        Filter->>Provider: getUserIdFromToken(jwt)
        Provider-->>Filter: userId (UUID)
        
        Filter->>Provider: getEmailFromToken(jwt)
        Provider-->>Filter: email (String)
        
        Filter->>Filter: Tạo UserPrincipal(userId, email, roles)
        Filter->>Filter: Tạo UsernamePasswordAuthenticationToken
        Filter->>Context: setAuthentication(authentication)
    else Token không có hoặc không hợp lệ
        Filter->>Filter: Bỏ qua (không ghi nhận authentication)
    end

    Filter->>Authz: filterChain.doFilter(request, response)
    Authz->>Context: getAuthentication()
    Context-->>Authz: Trả về Authentication đã nạp
    Authz->>Authz: Kiểm tra quyền truy cập (Đạt)
    
    Authz->>Controller: Chuyển tiếp Request vào Controller
    Controller-->>Client: HTTP 200 OK (Kèm dữ liệu nghiệp vụ)
```

---

## 3. Luồng Xử lý 2: Request Bị Từ Chối Xác Thực (Xử lý HTTP 401)

Khi Client không gửi token, token hết hạn, hoặc chữ ký token không đúng khi truy cập vào một tài nguyên yêu cầu xác thực (`.anyRequest().authenticated()`).

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client (React SPA)
    participant Filter as JwtAuthenticationFilter
    participant Authz as AuthorizationFilter
    participant ExFilter as ExceptionTranslationFilter
    participant EntryPoint as JwtAuthenticationEntryPoint

    Client->>Filter: HTTP GET /api/v1/tasks/123 (Không có token / Token rác)
    Filter->>Filter: resolveToken() -> null hoặc validateToken() -> false
    Note over Filter: Không set Authentication vào SecurityContextHolder
    
    Filter->>Authz: filterChain.doFilter(request, response)
    Authz->>Authz: Kiểm tra cấu hình SecurityConfig:<br/>Endpoint này yêu cầu authenticated()
    
    Note over Authz: Phát hiện SecurityContext rỗng!<br/>Ném AuthenticationException
    Authz-->>ExFilter: catch (AuthenticationException ex)
    
    ExFilter->>EntryPoint: commence(request, response, ex)
    Note over EntryPoint: Thiết lập HTTP 401 Unauthorized<br/>Content-Type: application/json
    EntryPoint->>EntryPoint: Đóng gói JSON body:<br/>- timestamp<br/>- status: 401<br/>- error: "Unauthorized"<br/>- message: ex.getMessage()<br/>- path: request.getRequestURI()
    
    EntryPoint-->>Client: Trả về HTTP 401 JSON Payload
```

---

## 4. Luồng Xử lý 3: Đăng nhập Người dùng (`POST /api/v1/auth/login` - Ticket `T-004`)

Luồng đăng nhập được bảo vệ bởi bộ đệm giới hạn tần suất (Rate Limiter) và bắt buộc tài khoản phải ở trạng thái `ACTIVE` (đã qua bước xác thực email).

> **Thứ tự kiểm tra (T-004A):** mật khẩu được kiểm tra **trước** trạng thái tài khoản. Nhờ vậy chỉ người biết đúng mật khẩu mới biết được email đó đã xác thực hay chưa; sai mật khẩu luôn trả 401 chung.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client (React SPA)
    participant RateLimit as RateLimitingInterceptor (Bucket4j)
    participant Ctrl as AuthController
    participant Svc as AuthService
    participant Repo as UserRepository
    participant Enc as PasswordEncoder (BCrypt)
    participant Prov as JwtTokenProvider
    participant ExHandler as GlobalExceptionHandler

    Client->>RateLimit: POST /api/v1/auth/login { email, password }
    alt Quá tần suất cho phép (Rate limit exceeded)
        RateLimit-->>Client: HTTP 429 Too Many Requests (RFC 7807 ProblemDetail)
    else Trong giới hạn tần suất hợp lệ
        RateLimit->>Ctrl: Cho phép đi tiếp vào Controller
        Ctrl->>Svc: login(LoginRequest)
        Svc->>Repo: findByEmail(request.email())
        alt Người dùng không tồn tại
            Repo-->>Svc: Optional.empty()
            Svc-->>ExHandler: Ném BadCredentialsException ("Invalid credentials")
            ExHandler-->>Client: HTTP 401 Unauthorized (RFC 7807 ProblemDetail)
        else Người dùng tồn tại
            Repo-->>Svc: Optional.of(UserEntity)
            Svc->>Enc: matches(rawPassword, user.passwordHash)
            alt Mật khẩu không trùng khớp
                Enc-->>Svc: false
                Svc-->>ExHandler: Ném BadCredentialsException ("Invalid credentials")
                ExHandler-->>Client: HTTP 401 Unauthorized (RFC 7807 ProblemDetail)
            else Mật khẩu trùng khớp
                Enc-->>Svc: true
                alt Tài khoản chưa xác thực email (status == PENDING_VERIFICATION)
                    Svc-->>ExHandler: Ném AccountNotVerifiedException
                    ExHandler-->>Client: HTTP 403 Forbidden (code: EMAIL_NOT_VERIFIED)
                else Tài khoản đã ACTIVE
                    Svc->>Prov: generateAccessToken(user.getId(), user.getEmail())
                    Prov-->>Svc: accessTokenString (Hạn dùng 1 giờ)
                    Svc->>Prov: generateRefreshToken(user.getId())
                    Prov-->>Svc: refreshTokenString (Hạn dùng 7 ngày)
                    Svc-->>Ctrl: AuthResponse(accessToken, refreshToken, userSummary)
                    Ctrl-->>Client: HTTP 200 OK { token, user, ... }
                end
            end
        end
    end
```

---

## 5. Luồng Xử lý 4: Đăng ký Tài khoản, Chống Bot & Gửi Mail Xác thực (`T-004A` & `T-004B`)

Đảm bảo ngăn chặn bot tự động đăng ký, lọc email dùng một lần và kích hoạt quy trình gửi email xác thực bất đồng bộ.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client (React SPA)
    participant RateLimit as RateLimitingInterceptor
    participant Ctrl as AuthController
    participant Turnstile as TurnstileService (Cloudflare)
    participant Validator as EmailDomainValidator
    participant RegSvc as RegistrationService
    participant UserRepo as UserRepository
    participant TokenRepo as VerificationTokenRepository
    participant Mailer as EmailSenderService (@Async)

    Client->>RateLimit: POST /api/v1/auth/register { email, password, fullName, turnstileToken }
    RateLimit->>Ctrl: Kiểm tra rate limit IP hợp lệ (3 req / 10 phút)
    Ctrl->>RegSvc: register(request, clientIp)
    RegSvc->>Turnstile: verify(turnstileToken, clientIp) [timeout 2s/3s + CircuitBreaker, không retry]
    alt Token thiếu / bị Cloudflare từ chối
        Turnstile-->>Client: HTTP 400 code BOT_CHALLENGE_FAILED
    else Siteverify không phản hồi / circuit mở
        Turnstile-->>Client: HTTP 503 code BOT_PROTECTION_UNAVAILABLE (fail closed)
    else Token hợp lệ
        RegSvc->>Validator: validate(normalizedEmail)
        alt Domain disposable
            Validator-->>Client: HTTP 400 code DISPOSABLE_EMAIL (errors.email)
        else Domain không có MX / null MX / NXDOMAIN
            Validator-->>Client: HTTP 400 code EMAIL_DOMAIN_UNDELIVERABLE (errors.email)
        else Có MX, hoặc DNS lỗi/timeout (fail open, cache kết quả chắc chắn 1h)
            RegSvc->>UserRepo: existsByEmail(email)
            alt Email đã tồn tại
                UserRepo-->>RegSvc: true
                RegSvc-->>Client: HTTP 409 Conflict ("Email already registered")
            else Email mới
                RegSvc->>UserRepo: saveAndFlush(UserEntity: status = PENDING_VERIFICATION)
                RegSvc->>TokenRepo: save(VerificationTokenEntity: SHA-256(token), expiresAt = +24h)
                RegSvc->>RegSvc: publish VerificationEmailRequestedEvent (nội bộ auth)
                RegSvc-->>Ctrl: RegisterResponse(email, PENDING_VERIFICATION)
                Ctrl-->>Client: HTTP 201 Created (không trả JWT)
                Note over RegSvc,Mailer: @TransactionalEventListener(AFTER_COMMIT)<br/>→ pool riêng auth-mail-*
                RegSvc-)Mailer: send(event) [Retry + CircuitBreaker]
            end
        end
    end
```

---

## 6. Luồng Xử lý 5: Kích hoạt Tài khoản qua Email (`POST /api/v1/auth/verify-email` - `T-004A`)

Xác nhận mã/token xác thực được gửi qua email để nâng cấp tài khoản sang trạng thái `ACTIVE`.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client (Trình duyệt click link / Nhập OTP)
    participant Ctrl as AuthController
    participant RegSvc as RegistrationService
    participant TokenRepo as VerificationTokenRepository
    participant UserRepo as UserRepository

    Client->>Ctrl: POST /api/v1/auth/verify-email { token }
    Ctrl->>RegSvc: verifyEmail(token)
    RegSvc->>TokenRepo: findByTokenHash(SHA-256(token))
    alt Token không tồn tại hoặc đã bị sử dụng (usedAt != null)
        TokenRepo-->>RegSvc: Optional.empty() hoặc already used
        RegSvc-->>Client: HTTP 400 Bad Request (code: VERIFICATION_TOKEN_INVALID)
    else Token hợp lệ
        alt Token đã quá hạn (expiresAt <= now)
            RegSvc-->>Client: HTTP 410 Gone (code: VERIFICATION_TOKEN_EXPIRED)
        else Token còn hạn
            RegSvc->>TokenRepo: markUsed: UPDATE ... SET used_at = now WHERE id = ? AND used_at IS NULL
            alt 0 dòng bị cập nhật (request song song đã dùng token)
                RegSvc-->>Client: HTTP 400 Bad Request (code: VERIFICATION_TOKEN_INVALID)
            else 1 dòng
                RegSvc->>UserRepo: findById(userId) → activate(now): status = ACTIVE, email_verified_at = now
                RegSvc-->>Ctrl: MessageResponse
                Ctrl-->>Client: HTTP 200 OK ("Email verified successfully. You can now sign in.")
            end
        end
    end
```

### 6.1. Gửi lại email xác thực (`POST /api/v1/auth/resend-verification` - `T-004A`)

- Giới hạn tần suất 2 lớp: theo IP (interceptor, 5 req/giờ) và theo **địa chỉ email** (3 req/giờ) để chặn việc spam một hộp thư từ nhiều IP.
- Nếu email thuộc tài khoản `PENDING_VERIFICATION`: vô hiệu mọi token cũ (`used_at = now`), phát token mới và gửi email.
- **Luôn trả HTTP 202 với cùng một nội dung**, dù email không tồn tại hay đã `ACTIVE`, nên endpoint này không thể dùng để dò email đã đăng ký.

### 6.2. Dọn dữ liệu định kỳ

`VerificationCleanupJob` (cron `devflow.verification.purge-cron`, mặc định 03:00 UTC) xóa token hết hạn hoặc đã dùng quá 7 ngày, và tài khoản `PENDING_VERIFICATION` quá 7 ngày (token bị xóa theo `ON DELETE CASCADE`).

---

## 7. Luồng Xử lý 6: Giao tiếp Liên Module qua Interface `AuthApi`

Các module khác (như `board-impl`, `git-impl`) không được phép gọi trực tiếp JPA Entity hay Repo của Auth mà phải đi qua interface `AuthApi` nằm trong `auth-api`.

```mermaid
sequenceDiagram
    autonumber
    participant Board as BoardServiceImpl (module: board-impl)
    participant AuthApi as AuthApi (interface: auth-api)
    participant AuthImpl as AuthServiceImpl (module: auth-impl)
    participant MemberRepo as WorkspaceMemberRepository
    participant UserRepo as UserRepository

    Note over Board: Cần kiểm tra User A có quyền<br/>truy cập Workspace B hay không
    Board->>AuthApi: isWorkspaceMember(userId, workspaceId)
    AuthApi->>AuthImpl: Gọi implementation nội bộ
    AuthImpl->>MemberRepo: findByWorkspaceIdAndUserId(workspaceId, userId)
    MemberRepo-->>AuthImpl: Optional<WorkspaceMemberEntity>
    AuthImpl-->>Board: boolean (true / false)

    Note over Board: Cần lấy thông tin Assignee để gán vào Task
    Board->>AuthApi: findUserById(userId)
    AuthApi->>AuthImpl: Gọi implementation nội bộ
    AuthImpl->>UserRepo: findById(userId)
    UserRepo-->>AuthImpl: Optional<UserEntity>
    AuthImpl->>AuthImpl: Map UserEntity -> UserSummaryDto
    AuthImpl-->>Board: UserSummaryDto (id, email, fullName, avatarUrl)
```

---

## 8. Bảng Ánh xạ Thành phần Mã Nguồn (Code Mapping)

| Thành phần | Đường dẫn mã nguồn | Ticket | Vai trò / Trách nhiệm chính |
|---|---|---|---|
| **Security Configuration** | [SecurityConfig.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/SecurityConfig.java) | `T-003` | Định nghĩa `SecurityFilterChain`, các URL `permitAll()` vs `authenticated()`, đăng ký Filter. |
| **Authentication Filter** | [JwtAuthenticationFilter.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/security/JwtAuthenticationFilter.java) | `T-003` | Bóc tách Bearer token, gọi validator, tạo `UserPrincipal` và nạp vào `SecurityContextHolder`. |
| **Token Provider** | [JwtTokenProvider.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/security/JwtTokenProvider.java) | `T-003` | Ký token HMAC SHA-256, parse claims, kiểm tra hạn dùng access/refresh token. |
| **Exception Handler & EntryPoint** | `JwtAuthenticationEntryPoint.java`, `GlobalExceptionHandler.java` | `T-004` | Xử lý lỗi toàn cục và định dạng phản hồi chuẩn RFC 7807 `ProblemDetail`, giấu stack trace. |
| **Rate Limiter** | `security/RateLimitingInterceptor.java`, `RateLimiterService.java`, `RateLimitProperties.java` | `T-004`, `T-004A` | Token Bucket (Bucket4j) theo policy có tên (`devflow.rate-limit.policies.*`), bucket lưu trong Caffeine có TTL. IP lấy từ `getRemoteAddr()`; `X-Forwarded-For` chỉ được tin qua `server.forward-headers-strategy` (proxy nội bộ). |
| **REST Controller** | [AuthController.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/AuthController.java) | `T-004`, `T-004A` | Endpoints cho Login, Refresh, Me, Workspaces, Register, Verify Email, Resend Verification. |
| **Core Auth Service** | [AuthService.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/AuthService.java) | `T-004`, `T-004A` | Xử lý đăng nhập, cấp token JWT, chặn tài khoản chưa `ACTIVE` (login & refresh) và cài đặt `AuthApi`. |
| **Registration Service** | `RegistrationService.java` | `T-004A` | Tạo user `PENDING_VERIFICATION`, phát token, verify (consume nguyên tử), resend chống dò email. |
| **Verification Infrastructure** | `verification/VerificationTokenGenerator.java`, `VerificationEmailListener.java`, `VerificationEmailSender.java`, `VerificationCleanupJob.java`, `VerificationProperties.java` | `T-004A` | Token 256-bit + SHA-256; gửi mail sau commit qua pool riêng; SMTP với Resilience4j Retry/CircuitBreaker; template `templates/email/verify-email.{html,txt}`; job dọn dữ liệu hằng đêm. |
| **Anti-Abuse & Bot Service** | `antiabuse/TurnstileService.java`, `antiabuse/EmailDomainValidator.java`, `antiabuse/JndiMxLookup.java`, `resources/anti-abuse/disposable-email-domains.txt` | `T-004B` | Kiểm tra token Cloudflare Turnstile (fail closed), lọc disposable email (gồm subdomain + `REGISTRATION_EXTRA_BLOCKED_DOMAINS`), kiểm tra bản ghi DNS MX (fail open khi DNS lỗi). Metric `devflow.auth.anti_abuse{check,result}`. |
| **JPA Entities & Repositories** | `entity/UserEntity.java`, `VerificationTokenEntity.java`, `WorkspaceEntity.java` | `T-002`, `T-004A` | Quản lý dữ liệu bảng `users` (thêm `status`), `verification_tokens`, `workspaces`, `workspace_members`. |

