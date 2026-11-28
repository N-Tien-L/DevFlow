# Gói Bàn Giao Nhanh cho Thành Viên Thứ 3 (`HANDOFF_PACKET.md`)

> Chào bạn! Đây là tài liệu tóm tắt toàn bộ thông tin cần biết về dự án **DevFlow** để bạn có thể viết chuẩn xác các phần được giao trong Hồ sơ Phân tích Thiết kế Hệ thống mà **không cần đọc mã nguồn**.

---

## 1. Phần việc Bạn Phụ Trách

Theo `docs/pttk/PHAN_CONG.md`, bạn đảm nhận các phần sau *(lưu ý: Phần 8 và Phần 13 hiện tạm thời **chưa giao**)*:

| Phần | Ngân sách trang | Tệp đích (nếu viết LaTeX) | Lưu ý quan trọng |
|---|---:|---|---|
| **Mở đầu** (Trang bìa, Lịch sử phiên bản, Phê duyệt) | 2–3 trang | `sections/00_mo_dau.tex` | Điền thông tin nhóm, mã tài liệu `DF-PTTK-01` |
| **Phần 1.** Tổng quan và khởi tạo dự án | 4 trang | `sections/01_tong_quan.tex` | OKR đo được, In/Out-scope, Stakeholders, RACI, Khả thi 4 mặt, Rủi ro |
| **Phần 2.** Khảo sát hiện trạng (AS-IS) | 3 trang | `sections/02_hien_trang.tex` | 3 nhóm lập trình viên mục tiêu, quy trình AS-IS, 6 điểm đau có số liệu khảo sát |
| **Phần 3.** Phân tích yêu cầu (TO-BE) | 5 trang | `sections/03_yeu_cau.tex` | Bám chặt mã `FR-01..FR-22`, `NFR-01..NFR-10`, `BR-01..BR-10` trong `glossary_lock.md` |
| **Phụ lục A, B, C** | 2.5 trang | `sections/14_phu_luc.tex` | 1 Biên bản phỏng vấn mẫu, 1 Trích bảng hỏi khảo sát, Tài liệu tham khảo |

> **Cách nộp bài linh hoạt:** Nếu bạn chưa quen LaTeX, bạn cứ viết trực tiếp bằng **Markdown (`.md`)** hoặc **Google Docs** đúng thứ tự từng mục nhỏ (1.1, 1.2, ...), gửi lại để Tiến chuyển vào tệp `.tex` đúng định dạng. **Tuyệt đối không chỉnh sửa thư mục `backend/`, `frontend/`, `infra/`.**

---

## 2. DevFlow Là Gì? (Tóm tắt 1 trang)

- **Khẩu hiệu:** *"Trello dành riêng cho lập trình viên — một công cụ thực sự thấu hiểu code của bạn, chứ không chỉ là danh sách đầu việc."*
- **Bản chất sản phẩm:** Nền tảng SaaS web (React + Spring Boot + PostgreSQL) quản lý dự án phần mềm theo bảng Kanban, giải quyết 5 vấn đề lớn của lập trình viên:
  1. Bảng việc (Trello/Jira) tách rời hoàn toàn khỏi code → lập trình viên quên kéo thẻ, trạng thái bảng luôn lỗi thời.
  2. Tốn thời gian bóc tách đặc tả kỹ thuật hoặc báo cáo lỗi thành các đầu việc nhỏ.
  3. Log lỗi CI/CD dài hàng nghìn dòng, khó đọc và tốn thời gian truy tìm nguyên nhân gốc.
  4. Muốn hỏi nhanh tiến độ dự án (*"Sprint này còn mấy task?", "Ai đang làm auth?"*) phải lọc qua nhiều màn hình.
  5. Phải thoát khỏi IDE (Cursor, Claude Code, VS Code) mở trình duyệt để xem task và lỗi CI gây mất tập trung.
- **3 trụ cột tính năng nổi bật của DevFlow:**
  1. **Tích hợp Git & CI/CD tự động:** Commit/PR chứa mã `T-XXX` tự liên kết và tự động đổi cột thẻ (`To Do` → `In Progress` → `In Review` → `Done`); khi CI fail, hệ thống tự thu thập log lỗi.
  2. **Trợ lý AI hội thoại (In-app ChatPanel):** Tự động tóm tắt nguyên nhân gốc lỗi CI/CD, phân rã yêu cầu thành subtask kèm nút 1-click tạo thẻ, hỏi đáp tiến độ dự án bằng ngôn ngữ tự nhiên, cảnh báo task sắp trễ hạn.
  3. **Cổng MCP Server cho IDE:** Cho phép trợ lý AI ngay trong Cursor / Claude Code truy vấn task đang làm và xem tóm tắt lỗi CI trực tiếp mà không cần rời trình soạn thảo.
- **3 phân khúc người dùng mục tiêu:**
  1. **Lập trình viên độc lập (Solo Dev):** Cần bảng gọn nhẹ, AI chia nhỏ việc.
  2. **Nhóm nhỏ (Sinh viên làm đồ án, Startup 3–10 người):** Cần tự động cập nhật trạng thái theo Git commit/PR và tóm tắt lỗi CI/CD nhanh.
  3. **Nhóm phát triển nhiều module:** Cần theo dõi tiến độ tổng thể, đồng bộ real-time và kiểm soát lịch sử thay đổi.
- **Quy mô dự án hiện tại:** Nhóm 2 lập trình viên chính (Tiến và Xuân) phát triển trong 1 học kỳ (10–12 tuần, 5 Sprint) với mục tiêu triển khai thực tế lên Internet (Cloudflare Pages + PaaS + Managed PostgreSQL).

---

## 3. DANH SÁCH CẤM NHẦM LẪN (So với file mẫu TaskHub)

File mẫu giảng dạy viết cho công ty hành chính **TaskHub**, khác hoàn toàn bối cảnh kỹ thuật của **DevFlow**. Khi viết, **TUYỆT ĐỐI KHÔNG đưa các chi tiết sau vào DevFlow**:

| Chi tiết có trong mẫu TaskHub | Thực tế của DevFlow (Dùng cột này!) |
|---|---|
| Công ty ABC 300 nhân viên văn phòng (Kinh doanh, Kế toán, Marketing, Nhân sự) | Cộng đồng & nhóm **lập trình viên phần mềm** (Solo Dev, nhóm sinh viên/startup, đội phát triển phần mềm) |
| Đăng nhập SSO Microsoft Entra ID / Google Workspace | Tự đăng ký email + xác thực email + chống bot **Cloudflare Turnstile** + **Stateless JWT** |
| Đồng bộ danh sách nhân sự từ hệ thống HRM nội bộ | Không có HRM; quản lý thành viên bằng **Workspace & lời mời qua email** |
| Gửi thông báo qua Zalo OA | Thông báo **In-app thời gian thực (WebSocket STOMP)** + Email giao dịch (xác thực, mời thành viên, đặt lại mật khẩu) |
| Có ứng dụng Mobile Flutter (iOS/Android) | **Chỉ có Web Application (React SPA)** đáp ứng giao diện responsive + **IDE MCP Client** |
| Quy trình nộp kết quả và duyệt nhiều cấp của quản lý văn phòng | Quy trình kỹ thuật của dev: **Code → Commit/PR → Review trên Git → Merge PR tự động Done** (chủ động **LOẠI BỎ** duyệt hành chính đa cấp) |
| Công nghệ NestJS, Python FastAPI, RabbitMQ, OpenSearch, Kubernetes | **Java 17, Spring Boot 3.4.5 (Modular Monolith), Spring AI, PostgreSQL 17, React 19, Docker Compose, PaaS + Cloudflare Pages** |

---

## 4. Lưu ý Về Số liệu Khảo sát (Phần 2 & Phụ lục A, B) & Kinh tế (Mục 1.6b)

- Vì đây là hồ sơ môn học, bạn **được phép xây dựng bộ số liệu khảo sát giả định hợp lý** (ví dụ: khảo sát 60–80 sinh viên CNTT / lập trình viên nhóm nhỏ, phỏng vấn sâu 6–8 trưởng nhóm kỹ thuật, quan sát thực tế 3 nhóm đồ án/startup) — miễn là **ghi chú rõ số liệu khảo sát phục vụ học tập** và **con số phải nhất quán giữa Phần 1 (OKR), Phần 2 (Điểm đau) và Phụ lục A, B**.
- Luôn kiểm tra mã số trong `docs/pttk/glossary_lock.md` trước khi viết Phần 3.
- Viết câu ngắn (đoạn văn tối đa 3–5 dòng), tránh sáo ngữ kiểu *"Trong bối cảnh chuyển đổi số..."*, *"không chỉ... mà còn..."*, *"mang tính đột phá"*.
