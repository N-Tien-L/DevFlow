# DevFlow — Kiến trúc Chi tiết Module Auth & Các Luồng Xử lý

> 🌐 **Tài liệu đặc tả kiến trúc thành phần và các luồng tương tác bảo mật của module `auth`**  
> Phiên bản: 1.0 (Tương ứng với các ticket `T-000` đến `T-004`)  
> Phạm vi: `backend/auth-api` và `backend/auth-impl`

---

## 1. Sơ đồ Kiến trúc Thành phần (Component Architecture)

Module `auth` chịu trách nhiệm quản lý danh tính người dùng (Users), không gian làm việc (Workspaces), quyền thành viên (Workspace Members) và cung cấp cơ chế bảo mật xác thực phi trạng thái (Stateless JWT) cho toàn bộ ứng dụng DevFlow.

```mermaid
flowchart TD
    subgraph ClientLayer ["Client Layer"]
        SPA["Web Client (React / Vite)"]
        CLI["MCP Server / API Client"]
    end

    subgraph SecurityFilterChain ["Spring Security 6 Filter Chain (Stateless)"]
        direction TB
        CORS["CorsFilter"]
        JWT_FILTER["JwtAuthenticationFilter<br/>(OncePerRequestFilter)"]
        USER_PASS["UsernamePasswordAuthenticationFilter<br/>(Bypass / Unused)"]
        AUTH_FILTER["AuthorizationFilter<br/>(URL Pattern Rules)"]
        EX_FILTER["ExceptionTranslationFilter"]
        ENTRY_POINT["JwtAuthenticationEntryPoint<br/>(Custom 401 JSON Handler)"]
    end

    subgraph AuthSecurityCore ["Security Core Components"]
        TOKEN_PROVIDER["JwtTokenProvider<br/>(HS256 / Claims / Sign / Verify)"]
        USER_PRINCIPAL["UserPrincipal<br/>(UserDetails adapter)"]
        SEC_CONTEXT["SecurityContextHolder<br/>(ThreadLocal SecurityContext)"]
    end

    subgraph ControllerLayer ["REST Controller Layer"]
        AUTH_CTRL["AuthController<br/>(/api/v1/auth)"]
        HEALTH_CTRL["AuthHealthController<br/>(/api/v1/auth/health)"]
    end

    subgraph ServiceLayer ["Service & API Contract Layer"]
        AUTH_SERVICE["AuthServiceImpl<br/>(Business Logic)"]
        AUTH_API["AuthApi (Interface in auth-api)<br/>(findUser, isWorkspaceMember)"]
        PWD_ENC["PasswordEncoder<br/>(BCrypt)"]
    end

    subgraph RepositoryLayer ["Data Access Layer (Spring Data JPA)"]
        USER_REPO["UserRepository"]
        WS_REPO["WorkspaceRepository"]
        MEMBER_REPO["WorkspaceMemberRepository"]
    end

    subgraph DatabaseLayer ["PostgreSQL Database (Schema devflow)"]
        DB_USERS[("users")]
        DB_WS[("workspaces")]
        DB_MEMBERS[("workspace_members")]
    end

    %% Client requests
    SPA --> CORS
    CLI --> CORS
    CORS --> JWT_FILTER
    JWT_FILTER --> USER_PASS
    USER_PASS --> AUTH_FILTER

    %% Filter & Security Core interaction
    JWT_FILTER -->|1. Validate & Parse| TOKEN_PROVIDER
    JWT_FILTER -->|2. Build Principal| USER_PRINCIPAL
    JWT_FILTER -->|3. Set Authentication| SEC_CONTEXT

    %% Exception handling
    AUTH_FILTER -->|Deny / Unauthenticated| EX_FILTER
    EX_FILTER -->|Trigger on 401| ENTRY_POINT
    ENTRY_POINT -.->|Response 401 JSON| SPA

    %% Dispatch to Controller
    AUTH_FILTER -->|Permitted / Authenticated| AUTH_CTRL
    AUTH_FILTER -->|Permitted| HEALTH_CTRL

    %% Controller to Service
    AUTH_CTRL --> AUTH_SERVICE
    AUTH_SERVICE --> PWD_ENC
    AUTH_SERVICE --> TOKEN_PROVIDER
    AUTH_SERVICE -.->|Implements| AUTH_API

    %% Service to Repositories
    AUTH_SERVICE --> USER_REPO
    AUTH_SERVICE --> WS_REPO
    AUTH_SERVICE --> MEMBER_REPO

    %% Repositories to Database
    USER_REPO --> DB_USERS
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

## 4. Luồng Xử lý 3: Đăng nhập Người dùng (`POST /api/v1/auth/login`)

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client (React SPA)
    participant Ctrl as AuthController
    participant Svc as AuthService
    participant Repo as UserRepository
    participant Enc as PasswordEncoder (BCrypt)
    participant Prov as JwtTokenProvider

    Client->>Ctrl: POST /api/v1/auth/login { email, password }
    Ctrl->>Svc: login(LoginRequest)
    
    Svc->>Repo: findByEmail(request.email())
    alt Người dùng không tồn tại
        Repo-->>Svc: Optional.empty()
        Svc-->>Ctrl: Ném BadCredentialsException ("Invalid credentials")
        Ctrl-->>Client: HTTP 401 Unauthorized
    else Người dùng tồn tại
        Repo-->>Svc: Optional.of(UserEntity)
        Svc->>Enc: matches(rawPassword, user.passwordHash)
        alt Mật khẩu không trùng khớp
            Enc-->>Svc: false
            Svc-->>Ctrl: Ném BadCredentialsException ("Invalid credentials")
            Ctrl-->>Client: HTTP 401 Unauthorized
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
```

---

## 5. Luồng Xử lý 4: Giao tiếp Liên Module qua Interface `AuthApi`

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

## 6. Bảng Ánh xạ Thành phần Mã Nguồn (Code Mapping)

| Thành phần | Đường dẫn mã nguồn | Vai trò / Trách nhiệm chính |
|---|---|---|
| **Security Configuration** | [SecurityConfig.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/SecurityConfig.java) | Định nghĩa `SecurityFilterChain`, các URL `permitAll()` vs `authenticated()`, đăng ký Filter và Exception EntryPoint. |
| **Authentication Filter** | [JwtAuthenticationFilter.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/security/JwtAuthenticationFilter.java) | Bóc tách Bearer token, gọi validator, tạo `UserPrincipal` và nạp vào `SecurityContextHolder`. |
| **Token Provider** | [JwtTokenProvider.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/security/JwtTokenProvider.java) | Ký token HMAC SHA-256, parse claims, kiểm tra hạn dùng và loại token (`access`/`refresh`). |
| **Authentication Entry Point** | [JwtAuthenticationEntryPoint.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/security/JwtAuthenticationEntryPoint.java) | Bắt ngoại lệ xác thực và định dạng phản hồi JSON chuẩn 401 Unauthorized. |
| **Principal Model** | [UserPrincipal.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/security/UserPrincipal.java) | Implement `UserDetails`, đại diện cho danh tính đang hoạt động của thread request hiện tại. |
| **REST Controller** | [AuthController.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/AuthController.java) | Cung cấp endpoints công khai cho việc Login, Register, Refresh Token, và Me. |
| **Service Layer** | [AuthService.java](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/backend/auth-impl/src/main/java/io/devflow/auth/internal/AuthService.java) | Triển khai logic nghiệp vụ tài khoản và giao diện `AuthApi` liên module. |
| **JPA Entities** | `entity/UserEntity.java`, `WorkspaceEntity.java`, `WorkspaceMemberEntity.java` | Quản lý cấu trúc dữ liệu bảng tương ứng theo schema `V1__init_schema.sql`. |
