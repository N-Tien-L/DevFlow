# Quy tắc làm việc trên nhánh `docs/pttk-report` (Hồ sơ PTTK DevFlow)

> **Phạm vi áp dụng:** Chỉ áp dụng khi đang làm việc trên nhánh `docs/pttk-report`. Đây là nhánh chuyên biệt để soạn thảo và biên dịch **Hồ sơ Phân tích Thiết kế Hệ thống Thông tin DevFlow (`DF-PTTK-01`)**.

---

## 1. Luật Git & Cách ly Mã nguồn (Bắt buộc — Zero-Tolerance)

1. **Nhánh chỉ dùng để làm tài liệu:**
   - Mọi thay đổi trên nhánh `docs/pttk-report` **CHỈ được phép nằm trong**:
     - `docs/pttk/**`
     - `.agents/rules/pttk-report-branch.md`
     - `.agents/skills/pttk-report/**`
   - **TUYỆT ĐỐI KHÔNG** chỉnh sửa bất kỳ tệp nào trong `backend/`, `frontend/`, `infra/`, `docker-compose.yml`, `.github/`, `AGENTS.md` hay các tài liệu nguồn gốc (`docs/PRODUCT_SPEC*.md`, `docs/ARCHITECTURE*.md`, `docs/DATABASE_DESIGN*.md`, `docs/IMPLEMENTATION_PLAN*.md`).
2. **Đồng bộ một chiều (One-way Sync ONLY):**
   - **ĐƯỢC PHÉP:** Kéo code và tài liệu mới nhất từ `develop` hoặc `main` **vào** nhánh này để cập nhật ngữ cảnh:
     ```bash
     git fetch origin
     git merge origin/develop --no-ff -m "docs(pttk): sync latest context from develop"
     ```
   - **CẤM TUYỆT ĐỐI:** Không bao giờ merge, rebase, cherry-pick, hoặc tạo Pull Request từ `docs/pttk-report` **ngược vào `develop` hoặc `main`**.
3. **Phân quyền biên tập theo tệp:**
   - Mỗi thành viên chỉ chỉnh sửa đúng các tệp `docs/pttk/sections/XX_*.tex` được giao trong `docs/pttk/PHAN_CONG.md`.
   - Không tự ý sửa `docs/pttk/preamble.tex` hoặc đổi mã số trong `docs/pttk/glossary_lock.md` khi chưa thống nhất với người chủ trì (Tiến).

---

## 2. Ràng buộc Tài liệu & Ngân sách Trang

- **Độ dài bắt buộc của PDF xuất cuối cùng:** Tối thiểu **30 trang**, tối đa **65 trang** (khổ A4).
- **Mục tiêu an toàn:** **48–56 trang** (bao gồm Trang bìa, Mục lục, 13 Phần chính và Phụ lục).
- **Công cụ biên dịch chuẩn:** `XeLaTeX` (`docs/pttk/build.ps1`), không dùng `pdflatex`.
- **Mã định danh nhất quán:** Mọi ký hiệu `FR-xx`, `NFR-xx`, `BR-xx`, `UCxx`, `SC-xx`, `ADR-xxx` phải khớp chính xác 100% với `docs/pttk/glossary_lock.md`.

---

## 3. Quy tắc Văn phong Kỹ thuật & Chống "AI Slop" (Việt hóa từ tư duy `humanizer`)

Mọi đoạn văn bản trước khi chốt phải qua lượt kiểm tra chống văn mẫu AI:

### 3.1. Cấm tuyệt đối (Red Flags)
- **Câu mở đầu sáo rỗng:** *"Trong bối cảnh chuyển đổi số mạnh mẽ hiện nay..."*, *"Trong kỷ nguyên công nghệ 4.0..."*, *"Không thể phủ nhận rằng..."*.
- **Cấu trúc đối lập rập khuôn:** *"không chỉ là X mà còn là Y"*, *"không đơn thuần là X, mà là một bước ngoặt..."*.
- **Từ ngữ thổi phồng vô căn cứ:** *"mang tính đột phá"*, *"tối ưu hóa toàn diện"*, *"giải pháp hoàn hảo"*, *"minh chứng rõ nét"*, *"nâng tầm trải nghiệm"*.
- **Spam định dạng kiểu LLM:**
  - Không viết danh sách gạch đầu dòng mà dòng nào cũng in đậm tiêu đề kiểu `**Tính năng A:** mô tả...` lặp lại 6–8 lần liên tiếp — hãy chuyển thành bảng nếu có từ 4 mục trở lên.
  - Không ép mọi lập luận thành bộ ba (triad) máy móc.
  - Không dùng emoji trong văn bản LaTeX chính thức.
  - Không dùng dấu gạch ngang dài (`—`) dày đặc để nối mệnh đề trong một đoạn văn.

### 3.2. Yêu cầu bắt buộc (Clean Technical Writing)
- **Đoạn văn ngắn:** Mỗi đoạn tối đa **3–5 dòng**; một ý chính mỗi đoạn.
- **Khách quan, chủ động, có định lượng:** Thay *"hệ thống phản hồi nhanh và bảo mật tốt"* bằng *"95% yêu cầu CRUD API phản hồi dưới 200 ms; mọi endpoint công khai được bảo vệ bởi Cloudflare Turnstile và Bucket4j rate limiting"*.
- **Neo bằng tên thực tế của DevFlow:** Dùng đúng tên bảng (`tasks`, `workspace_members`, `git_domain_event_outbox`), tên event (`task.status_changed`, `ci.failure_detected`, `user.account_deleted`), tên công nghệ (`Spring Boot 3.4.5`, `PostgreSQL 17`, `React 19`, `Spring AI`).
- **Không sao chép nhầm bối cảnh mẫu TaskHub:** Tuyệt đối không nhắc tới SSO Microsoft Entra ID/Google Workspace, hệ thống HRM nội bộ, Zalo OA, Flutter mobile, NestJS, RabbitMQ hay Kubernetes như thành phần hiện hữu của DevFlow.

---

## 4. Quy tắc Sơ đồ & Minh họa

- Mọi sơ đồ phải lưu mã nguồn trong `docs/pttk/diagrams/src/` và tệp xuất (ưu tiên Vector `.pdf`, ảnh chụp UI dùng `.png` rộng ≥ `2000px`) trong `docs/pttk/diagrams/out/`.
- Không dùng AI sinh ảnh nghệ thuật (DALL-E, Midjourney, image-generator) để vẽ UML, ERD, C4 hay Wireframe.
- Mọi hình nhúng vào LaTeX phải giới hạn khung vừa trang A4 (`width=\textwidth, height=0.72\textheight, keepaspectratio`), có Caption đầy đủ và có 2–3 câu giải thích ngắn ngay trước hoặc sau hình.
