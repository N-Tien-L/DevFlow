# Hướng Dẫn Vẽ & Xuất Sơ Đồ cho Hồ Sơ PTTK DevFlow

Tất cả sơ đồ trong hồ sơ tuân theo **Phương án A (Zero-Setup)**: dùng `mmdc` (Mermaid CLI đã cài sẵn) kết hợp HTML/SVG (render qua Chrome headless / `chrome-devtools-mcp`) và ảnh chụp UI thật của DevFlow.

---

## 1. Quy chuẩn Hình ảnh Bắt buộc

1. **Định dạng xuất:**
   - Sơ đồ kỹ thuật: **Vector PDF (`.pdf`)** lưu tại `docs/pttk/diagrams/out/`.
   - Ảnh chụp giao diện / Wireframe raster: **PNG độ phân giải cao** (chiều rộng ≥ `2000px`).
2. **Bảng màu thống nhất 100%:**
   - Navy chính: `#1F3A5F` (nền nhạt `#EBF1F6`)
   - Teal nhấn: `#0F766E` (nền nhạt `#E6F4F1`)
   - Amber hành động: `#D9731A` (nền nhạt `#FEF3E7`)
   - Đỏ cảnh báo: `#B42318` (nền nhạt `#FEE2E2`)
   - Nền cụm / khung: `#F4F6F8`, viền xám `#94A3B8`, nền ngoài `#FFFFFF`.
3. **Tỷ lệ khung hình vừa trang A4:**
   - Ưu tiên ngang **16:9 hoặc 4:3**; tránh sơ đồ siêu ngang (`> 2.5:1`) hoặc siêu dọc (`> 1:1.6`).
   - Canvas HTML/SVG chuẩn rộng `1280px`, cỡ chữ trong node `14–16px` để khi co vào trang A4 chữ đọc rõ tương đương `9–10pt`.
   - Tối đa **7–12 node** trên mỗi sơ đồ quy trình/kiến trúc.

---

## 2. Danh mục 11 Hình chính trong Hồ sơ

| Hình | Tên hình | Phần | Công cụ đề xuất | Tệp đích (`diagrams/out/`) | Phụ trách |
|---|---|---|---|---|---|
| **Hình 1** | Quy trình quản lý task & xử lý lỗi CI hiện tại (AS-IS) | Phần 2 | Mermaid (`mmdc`) | `hinh_01_asis.pdf` | Thành viên 3 *(Tiến hỗ trợ xuất)* |
| **Hình 2** | Quy trình phối hợp tự động trên DevFlow (TO-BE) | Phần 3 | Mermaid (`mmdc`) | `hinh_02_tobe.pdf` | Thành viên 3 *(Tiến hỗ trợ xuất)* |
| **Hình 3** | Biểu đồ Use Case tổng quát của DevFlow | Phần 4 | HTML/SVG → PDF | `hinh_03_usecase.pdf` | Xuân (Dev 2) |
| **Hình 4** | Biểu đồ tuần tự luồng Git Webhook → Event Bus → Board & AI | Phần 4 | Mermaid (`mmdc`) | `hinh_04_sequence_git.pdf` | Xuân (Dev 2) |
| **Hình 5** | Máy trạng thái đối tượng Task/Card (Thủ công & Tự động Git) | Phần 4 | Mermaid (`mmdc`) | `hinh_05_state_task.pdf` | Xuân (Dev 2) |
| **Hình 6** | Sơ đồ ngữ cảnh hệ thống DevFlow (C4 – Mức 1) | Phần 5 | HTML/SVG → PDF | `hinh_06_c4_context.pdf` | Tiến (Dev 1) |
| **Hình 7** | Sơ đồ Container hệ thống DevFlow (C4 – Mức 2) | Phần 5 | HTML/SVG → PDF | `hinh_07_c4_container.pdf` | Tiến (Dev 1) |
| **Hình 8** | Sơ đồ ERD mức logic theo 5 module (Hard FK vs Soft Ref UUID) | Phần 6 | HTML/SVG → PDF *(dựa trên `docs/diagrams/database-erd.mmd`)* | `hinh_08_erd.pdf` | Xuân (Dev 2) |
| **Hình 9** | Wireframe / Giao diện Bảng Kanban kèm Git/CI Badges & AI ChatPanel | Phần 8 | Chụp UI thật + chú thích SVG/HTML | `hinh_09_wireframe_kanban.png` | *Chưa giao* |
| **Hình 10** | Sơ đồ triển khai DevFlow (Local Docker Compose & Production Cloud) | Phần 10 | HTML/SVG → PDF | `hinh_10_deployment.pdf` | Xuân (Dev 2) |
| **Hình 11** | Lộ trình 5 giai đoạn phát triển DevFlow (Gantt 12 tuần) | Phần 13 | Mermaid (`mmdc`) | `hinh_11_roadmap.pdf` | *Chưa giao* |

---

## 3. Lệnh Xuất Nhanh Mermaid sang PDF Vector

```powershell
# Chạy từ thư mục gốc DevFlow
mmdc -i docs/pttk/diagrams/src/hinh_04_sequence_git.mmd `
     -o docs/pttk/diagrams/out/hinh_04_sequence_git.pdf `
     -c docs/pttk/diagrams/mermaid.config.json `
     --pdfFit
```
