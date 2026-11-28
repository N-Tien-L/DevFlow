# Phân Công Nhiệm Vụ Soạn Thảo Hồ Sơ PTTK — DevFlow (`DF-PTTK-01`)

> **Nhánh làm việc:** `docs/pttk-report` (chỉ kéo code từ `develop`/`main` về, **tuyệt đối không merge ngược vào `develop`/`main`**).  
> **Ràng buộc độ dài toàn hồ sơ:** Tối thiểu **30 trang**, tối đa **65 trang** (mục tiêu **48–56 trang**).  
> **Trạng thái đặc biệt:** **Phần 8 (UI/UX)** và **Phần 13 (Kế hoạch triển khai & chuyển giao)** tạm thời để **CHƯA GIAO** — không bàn giao cho người thứ 3.

---

## 1. Cơ cấu Nhóm & Nguyên tắc Cách ly

| Thành viên | Vai trò trong dự án DevFlow | Vai trò trong Hồ sơ PTTK | Phạm vi được phép sửa tệp |
|---|---|---|---|
| **Tiến (`@tienlam15`)** | Dev 1 (Auth, AI, Notification, Cloud/Security) | Chủ trì kiến trúc, API/Event, Bảo mật, AI, Quản lý khung LaTeX & Compile PDF | `docs/pttk/**`, `.agents/rules/pttk-report-branch.md`, `.agents/skills/pttk-report/**` |
| **Xuân (`@xuanle2`)** | Dev 2 (Board, Git & CI, Database, Docker Local) | Chủ trì Phân tích mô hình, Dữ liệu (ERD), DevOps/Docker và Kiểm thử (RTM) | Các tệp `sections/` và `diagrams/` được giao trong `docs/pttk/` |
| **Thành viên 3 (Writer / BA ngoài dự án)** | Không tham gia code DevFlow | Soạn thảo các phần nghiệp vụ, khảo sát AS-IS, yêu cầu cơ bản và Phụ lục | Chỉ sửa các tệp phần được giao trong `docs/pttk/sections/` (hoặc nộp bản Markdown để Dev chuyển sang `.tex`). **Không đụng code dự án.** |

---

## 2. Bảng Phân công Chi tiết theo Từng Phần

| Phần | Tệp phụ trách (`docs/pttk/sections/`) | Người phụ trách | Trạng thái | Ngân sách trang | Sơ đồ / Minh họa cần có |
|---|---|---|---|---|---|
| **Mở đầu** (Trang bìa, Lịch sử phiên bản, Phê duyệt) | `00_mo_dau.tex` | **Thành viên 3** *(Tiến duyệt cuối)* | Đã giao | 2–3 trang | Không |
| **Phần 1.** Tổng quan và khởi tạo dự án | `01_tong_quan.tex` | **Thành viên 3** | Đã giao | 4 trang | Bảng OKR, In/Out-scope, RACI, Khả thi, Rủi ro |
| **Phần 2.** Khảo sát hiện trạng (AS-IS) | `02_hien_trang.tex` | **Thành viên 3** | Đã giao | 3 trang | Hình 1: Quy trình giao việc & xử lý lỗi AS-IS |
| **Phần 3.** Phân tích yêu cầu (TO-BE) | `03_yeu_cau.tex` | **Thành viên 3** *(dựa trên `glossary_lock.md` do 2 Dev chốt)* | Đã giao | 5 trang | Hình 2: Quy trình làm việc mới TO-BE |
| **Phần 4.** Mô hình hóa phân tích | `04_mo_hinh_hoa.tex` | **Xuân (Dev 2)** | Đã giao | 6 trang | Hình 3 (Use Case), Hình 4 (Sequence Git/Board), Hình 5 (State Machine Task) |
| **Phần 5.** Thiết kế kiến trúc | `05_kien_truc.tex` | **Tiến (Dev 1)** | Đã giao | 5 trang | Hình 6 (C4 Context), Hình 7 (C4 Container) |
| **Phần 6.** Thiết kế dữ liệu | `06_du_lieu.tex` | **Xuân (Dev 2)** | Đã giao | 5 trang | Hình 8 (ERD mức logic theo 5 module) |
| **Phần 7.** Thiết kế tích hợp và API | `07_tich_hop_api.tex` | **Tiến (Dev 1)** *(phối hợp Xuân phần Git Webhook)* | Đã giao | 5 trang | Bảng REST/STOMP/MCP, JSON mẫu, Event Catalog |
| **Phần 8.** Thiết kế giao diện và trải nghiệm (UI/UX) | `08_ui_ux.tex` | **CHƯA GIAO** *(để trống, không giao người thứ 3)* | **Chưa giao** | 4 trang | Hình 9: Wireframe / Giao diện Kanban + ChatPanel |
| **Phần 9.** Thiết kế bảo mật và tuân thủ | `09_bao_mat.tex` | **Tiến (Dev 1)** | Đã giao | 3.5 trang | Bảng STRIDE, Ma trận RBAC/ABAC, Pháp lý |
| **Phần 10.** Thiết kế hạ tầng và vận hành (DevOps) | `10_ha_tang_devops.tex` | **Xuân (Dev 2)** *(phối hợp Tiến phần Cloud/CF Pages)* | Đã giao | 3 trang | Hình 10: Sơ đồ triển khai (Local Docker + Cloud) |
| **Phần 11.** Thành phần trí tuệ nhân tạo (AI) | `11_thanh_phan_ai.tex` | **Tiến (Dev 1)** | Đã giao | 3 trang | Bảng Use case AI, Model Card, Guardrails |
| **Phần 12.** Kiểm thử và đảm bảo chất lượng | `12_kiem_thu.tex` | **Xuân (Dev 2)** *(Tiến bổ sung TC Auth/AI/Security)* | Đã giao | 3.5 trang | Chiến lược 7 mức, Test Case mẫu, Bảng RTM, DoD |
| **Phần 13.** Kế hoạch triển khai và chuyển giao | `13_trien_khai.tex` | **CHƯA GIAO** *(để trống, không giao người thứ 3)* | **Chưa giao** | 2.5 trang | Hình 11: Lộ trình Gantt 5 Phase |
| **Phụ lục A, B, C** (Phỏng vấn, Bảng hỏi, Tham khảo) | `14_phu_luc.tex` | **Thành viên 3** | Đã giao | 2.5 trang | Mẫu biên bản PV-01, Trích bảng hỏi, Danh mục tài liệu tham khảo |

### Tổng hợp khối lượng hiện tại
- **Thành viên 3:** Mở đầu, Phần 1, Phần 2, Phần 3, Phụ lục A–C (~16–17 trang) — hoàn toàn không cần đọc code Java/React.
- **Tiến (Dev 1):** Phần 5, Phần 7, Phần 9, Phần 11 + Setup khung LaTeX, Merge & Compile PDF (~16.5 trang).
- **Xuân (Dev 2):** Phần 4, Phần 6, Phần 10, Phần 12 (~17.5 trang).
- **Chưa giao (quyết định sau giữa Tiến và Xuân):** Phần 8 (~4 trang) và Phần 13 (~2.5 trang).

---

## 3. Quy trình Bàn giao & Cổng Kiểm soát Chất lượng

1. **Bàn giao đầu vào cho Thành viên 3:**
   - Gửi trực tiếp 2 tệp: `docs/pttk/HANDOFF_PACKET.md` và `docs/pttk/glossary_lock.md`.
   - Nếu Thành viên 3 không quen viết LaTeX, có thể viết bằng **Markdown hoặc Google Docs** đúng theo từng đề mục trong `sections/01..03, 14` rồi gửi lại để Tiến/AI chuyển sang `.tex` chuẩn macro.
2. **Cổng Review 1 (Duyệt ý chính — tránh lệch bối cảnh):**
   - Sau khi viết xong khung bảng/ý chính của Phần 1–3, Thành viên 3 gửi Tiến kiểm tra nhanh (10 phút) bảo đảm không lẫn bối cảnh của mẫu TaskHub (SSO công ty, Zalo OA, HRM, Flutter).
3. **Cổng Review 2 (Đối chiếu chéo giữa 2 Dev):**
   - Tiến và Xuân đổi chéo đọc Phần 4, 5, 6, 7, 9, 10, 11, 12 để bảo đảm mọi mã `FR`, `UC`, tên bảng DB và tên Domain Event khớp 100% với bảng RTM (Phần 12).
4. **Cổng Review 3 (Chống AI slop & Chốt trang PDF):**
   - Chạy kiểm tra theo `.agents/rules/pttk-report-branch.md` và biên dịch `docs/pttk/build.ps1`.
   - Xác nhận PDF cuối cùng sạch lỗi compile, không tràn bảng, hình rõ nét và nằm gọn trong **30–65 trang**.
