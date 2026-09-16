# DevFlow — Đặc tả Sản phẩm & Tính năng (Product & Feature Specification)

> 🌐 **Ngôn ngữ:** **Tiếng Việt** (Dành cho Developer đọc & theo dõi) | [English Version (Best Practices for AI Agents)](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/docs/PRODUCT_SPEC.md)
>
> **Mục đích tài liệu:**  
> Đây là tài liệu đặc tả sản phẩm gốc (source-of-truth product spec) của DevFlow. Tài liệu này nhằm giúp cả thành viên phát triển lẫn các AI coding agent (Cursor, Claude Code, Antigravity...) có cùng một cách hiểu thống nhất về sản phẩm làm *cái gì* và *tại sao* mỗi tính năng lại tồn tại, trước khi đưa ra các quyết định kiến trúc hoặc lựa chọn công nghệ.
>
> **Trạng thái:** Ý tưởng và phạm vi tính năng đã được chốt cố định. Chi tiết về kiến trúc kỹ thuật và tech stack vui lòng tham khảo [docs/ARCHITECTURE_VI.md](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/docs/ARCHITECTURE_VI.md) hoặc [docs/ARCHITECTURE.md](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/docs/ARCHITECTURE.md).

---

## 1. Tổng quan Dự án

**Khẩu hiệu (Tagline):** *"Trello dành riêng cho lập trình viên — một công cụ thực sự thấu hiểu code của bạn, chứ không chỉ là danh sách đầu việc."*

DevFlow là công cụ quản lý dự án dạng thẻ/bảng Kanban (phong cách Trello) được thiết kế chuyên biệt cho lập trình viên. Khác với các công cụ quản lý dự án thông thường, DevFlow kết nối trực tiếp với hoạt động viết code thực tế (Git commits, Pull Requests, CI/CD pipelines) để tự động cập nhật trạng thái công việc một cách chính xác; đồng thời tích hợp một trợ lý AI giúp người dùng tạo thẻ, theo dõi tiến độ và tóm tắt lỗi hệ thống mà không cần thao tác thủ công lặp đi lặp lại.

---

## 2. Vấn đề Thực tế Cần Giải quyết

- Các công cụ quản lý tác vụ thông thường (Trello, Jira) hoàn toàn "mù" trước hoạt động code thực tế — lập trình viên phải tự kéo thả cập nhật trạng thái thẻ thủ công, việc này thường xuyên bị quên hoặc cập nhật trễ.
- Việc tạo task mới và bóc tách các yêu cầu kỹ thuật/báo cáo lỗi phức tạp thành các việc nhỏ tốn nhiều thời gian và công sức.
- Nhật ký lỗi (log) và traceback của CI/CD thường rất dài và rối mắt, làm chậm tốc độ tìm lỗi và sửa code.
- Muốn biết tiến độ dự án (ai đang làm gì, có kịp tiến độ không) thường phải lọc qua nhiều màn hình phức tạp thay vì có thể hỏi đáp trực tiếp.
- Việc phải rời khỏi môi trường lập trình (IDE như Cursor, VS Code) để mở trình duyệt xem trạng thái task là sự chuyển đổi ngữ cảnh (context-switch) gây mất tập trung.

---

## 3. Đối tượng Người dùng Mục tiêu

**Đối tượng trọng tâm: Lập trình viên phần mềm, từ cá nhân đến đội ngũ.**

| Phân khúc | Nhu cầu cốt lõi |
|---|---|
| **Lập trình viên độc lập (Solo Dev)** | Công cụ nhẹ nhàng, không rườm rà quy trình; hưởng lợi từ AI tự động sinh task và cảnh báo khi mở quá nhiều việc cùng lúc. |
| **Nhóm nhỏ (Sinh viên, Startup nhỏ)** | Theo dõi tiến độ sát sao qua Git; hưởng lợi từ tự động đổi trạng thái task theo commit/PR và tóm tắt lỗi CI/CD tự động. |
| **Nhóm lớn / Nhiều nhóm** | Cần tầm nhìn liên module; hưởng lợi từ việc phát hiện xung đột hoặc phụ thuộc chéo giữa các module code. |

Việc thu hẹp phạm vi vào riêng đối tượng lập trình viên là quyết định có chủ đích — giúp loại bỏ hoàn toàn các tính năng thừa thãi của khối kinh doanh (quy trình phê duyệt đa cấp, bộ tạo form khảo sát, tích hợp marketing) để tập trung 100% vào luồng làm việc kỹ thuật: Code → Commit → PR → CI/CD.

---

## 4. Điểm Khác biệt Nổi bật (Differentiators)

- **So với Trello:** Hỗ trợ AI phân rã công việc; trạng thái thẻ tự động cập nhật theo code Git thực tế thay vì chỉ kéo thả bằng tay.
- **So với Jira:** Tinh gọn, không có gánh nặng cấu hình doanh nghiệp phức tạp; tập trung tối đa vào luồng làm việc của developer.
- **So với GitHub Projects:** Tích hợp sâu tầng trợ lý AI đàm thoại và phân tích tóm tắt lỗi CI/CD, không chỉ đơn thuần là bộ lọc issue.
- **Tính năng độc đáo nhất (Unique Selling Point):** Cho phép các công cụ lập trình AI (Cursor, Claude Code) truy vấn thông tin dự án và phân tích lỗi CI/CD trực tiếp ngay bên trong trình soạn thảo thông qua **MCP Server (Model Context Protocol)** — không cần chuyển ứng dụng.

---

## 5. Thiết kế Tính năng Chi tiết

Tính năng được mô tả ở cấp độ giá trị sản phẩm, chia theo từng nhóm nghiệp vụ:

### 5.1 Các tính năng Quản lý Dự án Cốt lõi (Core PM - Baseline)
- **Tài khoản & Workspace:** Đăng ký/đăng nhập, tạo dự án/workspace, mời thành viên.
- **Bảng Kanban trực quan:** Tạo các cột trạng thái (To Do, In Progress, Done...), tạo/sửa/xóa thẻ, kéo thả mượt mà giữa các cột.
- **Chi tiết Công việc (Task Details):** Người thực hiện (assignee), hạn hoàn thành (due date), nhãn mức độ ưu tiên, mô tả Markdown, bình luận.
- **Lịch sử hoạt động:** Ghi nhận ai đã thay đổi nội dung gì và vào thời điểm nào.
- **Tìm kiếm & Bộ lọc:** Tìm task theo tên, lọc theo người thực hiện, nhãn ưu tiên hoặc trạng thái.
- **Hệ thống thông báo:** Báo khi được giao việc mới, có bình luận mới hoặc sắp đến hạn chót.
- **Đồng bộ thời gian thực (Real-time Sync):** Nhiều người cùng xem một bảng sẽ thấy thẻ di chuyển tức thì không cần tải lại trang.
- **Bảng tiến độ cơ bản:** Thống kê số task đã xong/chưa xong, biểu đồ tiến độ trực quan.

### 5.2 Tính năng Chuyên biệt cho Developer & Trợ lý AI

Xoay quanh 3 trụ cột: (1) Tích hợp Git & CI/CD thực tế, (2) Trợ lý AI hội thoại, (3) Cổng kết nối MCP Server cho AI trong IDE.

#### a) Tích hợp Git & CI/CD
- **Liên kết Task ↔ Commit/PR:** Khi tên nhánh hoặc commit message chứa mã task (ví dụ `T-010`), hệ thống tự động liên kết chúng với nhau.
- **Tự động chuyển trạng thái:** Task tự nhảy sang "In Progress" ở commit đầu tiên, sang "In Review" khi mở Pull Request, và sang "Done" khi PR được merge thành công.
- **Tóm tắt lỗi CI/CD tự động:** Khi pipeline thất bại, AI đọc log build và sinh bản tóm tắt ngắn gọn nguyên nhân cốt lõi thay vì bắt lập trình viên đọc hàng nghìn dòng traceback.
- **Liên kết lỗi với Task/PR:** Gắn trực tiếp phân tích lỗi vào commit/PR gây ra lỗi để xử lý ngay.

#### b) Trợ lý AI Hội thoại (In-app Chat Assistant)
- **Hỏi đáp dự án tự nhiên:** Hỏi trực tiếp trong chat: "Sprint này còn bao nhiêu task?", "Ai đang phụ trách module thanh toán?".
- **Tạo & Phân rã task bằng AI:** Dán một đoạn đặc tả hoặc log lỗi; AI đề xuất chia nhỏ thành các subtask rõ ràng kèm nút 1-click tạo thẻ lên Board.
- **Cảnh báo trễ hạn chủ động (Deadline Risk Alerts):** Dựa trên tiến độ hoàn thành thực tế so với kế hoạch, AI chủ động cảnh báo các task có nguy cơ trễ hạn.

#### c) Cổng Truy vấn cho AI Coding Agent (MCP Server)
- **Truy vấn dự án trực tiếp từ IDE:** Cho phép Cursor, Claude Code gọi tool tra cứu dữ liệu dự án qua giao thức Model Context Protocol.
- **Câu lệnh ngữ cảnh:** Lập trình viên có thể hỏi trong IDE: *"Task hiện tại của tôi là gì, hạn chót khi nào, có lưu ý gì về kiến trúc không?"*.
- **Tra cứu lỗi CI trong editor:** Ngay sau khi push code bị fail CI, hỏi ngay coding agent *"Lỗi CI vừa rồi có liên quan đến đoạn code tôi vừa sửa không?"*.
- **Cùng một bộ não AI:** MCP Server chỉ là adapter mỏng gọi vào cùng các hàm nội bộ của AI Service như Chat Web, không bị phân mảnh logic.

---

## 6. Phân cấp Phạm vi & Mức độ Ưu tiên (MVP Scope)

**Quy ước:**
- **MVP (Bắt buộc):** Phải có trong bản phát hành đầu tiên để đủ điều kiện nghiệm thu.
- **Should-have (Nên có):** Bổ sung nếu tiến độ cho phép, gia tăng trải nghiệm người dùng.
- **Later (Tương lai):** Có giá trị nhưng đòi hỏi dữ liệu tích lũy lâu dài hoặc nhiều người dùng; tạm hoãn trong phạm vi đồ án 1 học kỳ.

### 6.1 Bảng Tính năng Quản lý Dự án Cốt lõi
| Tính năng | Giá trị mang lại | Mức độ ưu tiên |
|---|---|---|
| Tài khoản & Workspace | Điều kiện nền tảng để sử dụng hệ thống | **MVP** |
| Kéo thả Bảng / Thẻ Kanban | Trải nghiệm cốt lõi quen thuộc | **MVP** |
| Assignee, Due date, Labels | Quản lý công việc cơ bản | **MVP** |
| Đồng bộ thời gian thực (WebSocket) | Phối hợp nhóm mượt mà | **MVP** |
| Thông báo cơ bản (In-app) | Không bỏ lỡ cập nhật quan trọng | **MVP** |
| Bình luận & Lịch sử thao tác | Trao đổi nội bộ và theo dõi lịch sử | Should-have |
| Dashboard tiến độ | Nắm bắt nhanh sức khỏe dự án | Should-have |

### 6.2 Bảng Tính năng Developer & AI
| Tính năng | Giá trị mang lại | Mức độ ưu tiên |
|---|---|---|
| Tự động link Git & cập nhật trạng thái | Loại bỏ thao tác tay, dữ liệu luôn chuẩn | **MVP** |
| AI phân rã task từ mô tả | Tiết kiệm thời gian lập kế hoạch ban đầu | **MVP** |
| Tóm tắt lỗi CI/CD tự động | Debug nhanh hơn, không phải đọc log dài | **MVP** |
| Hỏi đáp dự án tự nhiên | Tra cứu nhanh không cần lọc nhiều màn hình | **MVP** |
| Đồng bộ Chat với Board trực tiếp | Giữ ngữ cảnh trực quan khi đang trò chuyện | Should-have |
| Cảnh báo rủi ro deadline chủ động | Chủ động hành động sớm trước khi trễ hạn | Should-have |
| MCP Server cho AI Coding Agents | Truy vấn thông tin dự án ngay trong IDE | Should-have |
| Gom nhóm lỗi CI lặp lại | Giảm nhiễu log | Should-have |
| Khởi tạo dự án tự động bằng AI | Khởi động dự án nhanh | Later |
| Ước lượng độ phức tạp từ lịch sử code | Cần nhiều dữ liệu lịch sử | Later |
| Phát hiện phụ thuộc chéo nhiều team | Cần quy mô nhiều team hoạt động lâu dài | Later |

---

## 7. Các Tính năng Chủ động Loại bỏ (Explicit Non-Goals)

Để giữ phạm vi tập trung tối đa cho lập trình viên, các tính năng sau **chủ động KHÔNG làm**:
- Quy trình phê duyệt, ký duyệt phức tạp nhiều cấp của khối văn phòng.
- Bộ công cụ tạo form tùy biến (custom form builders).
- Các tính năng phục vụ đối tượng phi kỹ thuật (email marketing, quản lý chiến dịch).
- Hệ thống phân quyền cấp doanh nghiệp nhiều tầng phức tạp ngoài phân quyền thành viên cơ bản.
- Cố gắng tích hợp tràn lan với các bộ phần mềm PM thương mại khác.

---

## 8. Bảng Thuật ngữ (Glossary)

| Thuật ngữ | Ý nghĩa |
|---|---|
| **Task / Card** | Đơn vị công việc trên bảng Kanban (hai thuật ngữ dùng thay thế cho nhau). |
| **Board** | Bảng quản lý dự án phong cách Trello chứa các cột trạng thái. |
| **AI Service** | Năng lực backend dùng chung xử lý phân rã task, Q&A dự án và tóm tắt CI/CD. |
| **MCP Server** | Bộ điều hợp (adapter) giao tiếp chuẩn Model Context Protocol kết nối với IDE. |
| **MVP** | Minimum Viable Product — Sản phẩm khả dụng tối thiểu bắt buộc hoàn thành. |

---

## 9. Lưu ý Dành cho Lập trình viên & AI Coding Agents

- Không tự ý suy đoán kiến trúc, công nghệ từ tài liệu này — mọi quyết định kiến trúc kỹ thuật nằm tại [docs/ARCHITECTURE_VI.md](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/docs/ARCHITECTURE_VI.md) hoặc [docs/ARCHITECTURE.md](file:///c:/Users/Tien/university/ServiceOrientedProgramDesign/DevFlow/docs/ARCHITECTURE.md).
- AI Service là **một bộ não duy nhất** phục vụ cả Chat UI lẫn MCP Server — tránh duplicate logic ở 2 nơi.
- Mọi tính năng đánh dấu **Later** đều nằm ngoài phạm vi thực thi trừ khi có chỉ đạo mới.
