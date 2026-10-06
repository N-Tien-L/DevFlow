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
            alt Tài khoản chưa kích hoạt email (status == PENDING_VERIFICATION)
                Svc-->>ExHandler: Ném AccountPendingVerificationException
                ExHandler-->>Client: HTTP 403 Forbidden ("Email verification required")
            else Tài khoản đã ACTIVE
                Svc->>Enc: matches(rawPassword, user.passwordHash)
                alt Mật khẩu không trùng khớp
                    Enc-->>Svc: false
                    Svc-->>ExHandler: Ném BadCredentialsException ("Invalid credentials")
                    ExHandler-->>Client: HTTP 401 Unauthorized (RFC 7807 ProblemDetail)
                else Mật khẩu trùng khớp
                    Enc-->>Svc: true
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
    participant Validator as EmailIntegrityValidator
    participant RegSvc as RegistrationService
    participant UserRepo as UserRepository
    participant TokenRepo as VerificationTokenRepository
    participant Mailer as EmailSenderService (@Async)

    Client->>RateLimit: POST /api/v1/auth/register { email, password, fullName, turnstileToken }
    RateLimit->>Ctrl: Kiểm tra rate limit IP hợp lệ
    Ctrl->>Turnstile: verifyToken(turnstileToken, clientIp)
    alt Token Turnstile không hợp lệ / Bot phát hiện
        Turnstile-->>Ctrl: false
        Ctrl-->>Client: HTTP 400 Bad Request ("Bot verification failed")
    else Token Turnstile hợp lệ
        Turnstile-->>Ctrl: true
        Ctrl->>Validator: validateEmailIntegrity(email)
        alt Domain là disposable email hoặc DNS không có MX record
            Validator-->>Ctrl: Ném InvalidEmailDomainException
            Ctrl-->>Client: HTTP 422 Unprocessable Entity ("Invalid email domain")
        else Email hợp lệ và domain tin cậy
            Ctrl->>RegSvc: register(RegisterRequest)
            RegSvc->>UserRepo: existsByEmail(email)
            alt Email đã tồn tại
                UserRepo-->>RegSvc: true
                RegSvc-->>Client: HTTP 409 Conflict ("Email already registered")
            else Email mới
                RegSvc->>UserRepo: save(UserEntity: status = PENDING_VERIFICATION)
                RegSvc->>TokenRepo: save(VerificationTokenEntity: token, expiresAt = +24h)
                RegSvc-)Mailer: dispatchVerificationEmailAsync(email, token)
                RegSvc-->>Ctrl: RegistrationResult.PENDING
                Ctrl-->>Client: HTTP 201 Created ("Registration successful. Please verify your email.")
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
    RegSvc->>TokenRepo: findByToken(token)
    alt Token không tồn tại hoặc đã bị sử dụng (usedAt != null)
        TokenRepo-->>RegSvc: Optional.empty() hoặc already used
        RegSvc-->>Client: HTTP 400 Bad Request ("Invalid or expired verification token")
    else Token hợp lệ
        alt Token đã quá hạn (expiresAt < now)
            RegSvc-->>Client: HTTP 410 Gone ("Token expired. Please request a new one.")
        else Token còn hạn
            RegSvc->>UserRepo: findById(token.userId)
            RegSvc->>UserRepo: update user.status = ACTIVE
            RegSvc->>TokenRepo: update token.usedAt = now()
            RegSvc-->>Ctrl: VerificationSuccess
            Ctrl-->>Client: HTTP 200 OK ("Account activated successfully. You can now login.")
        end
    end
```

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
| **Rate Limiter Interceptor** | `RateLimitingInterceptor.java` | `T-004` | Giới hạn tần suất gọi API công khai bằng thuật toán Token Bucket (Bucket4j). |
| **REST Controller** | [AuthController.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/AuthController.java) | `T-004`, `T-004A` | Endpoints cho Login, Refresh, Me, Workspaces, Register, Verify Email. |
| **Core Auth Service** | [AuthService.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/AuthService.java) | `T-004` | Xử lý đăng nhập, cấp token JWT, kiểm tra trạng thái active và cài đặt `AuthApi`. |
| **Registration & Email Service** | `RegistrationService.java`, `EmailSenderService.java` | `T-004A` | Xử lý tạo user `PENDING`, sinh verification token, gửi email xác thực bất đồng bộ và kích hoạt. |
| **Anti-Abuse & Bot Service** | `TurnstileService.java`, `DisposableEmailValidator.java` | `T-004B` | Kiểm tra token Cloudflare Turnstile, lọc disposable email, kiểm tra bản ghi DNS MX. |
| **JPA Entities & Repositories** | `entity/UserEntity.java`, `VerificationTokenEntity.java`, `WorkspaceEntity.java` | `T-002`, `T-004A` | Quản lý dữ liệu bảng `users` (thêm `status`), `verification_tokens`, `workspaces`, `workspace_members`. |

