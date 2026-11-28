# Hồ sơ Phân tích Thiết kế Hệ thống Thông tin — DevFlow (`DF-PTTK-01`)

Thư mục này chứa toàn bộ mã nguồn LaTeX, sơ đồ, kế hoạch và phân công để biên dịch bộ **Hồ sơ PTTK Hệ thống DevFlow** theo mẫu chuẩn 13 phần (ràng buộc **30–65 trang A4**, mục tiêu **48–56 trang**).

## 1. Quy định Nhánh `docs/pttk-report` (Đọc trước khi làm)
- Nhánh `docs/pttk-report` **CHỈ dùng để viết tài liệu** trong `docs/pttk/`.
- **ĐƯỢC PHÉP:** Kéo code/docs mới từ `develop` hoặc `main` về nhánh này (`git merge origin/develop`).
- **CẤM TUYỆT ĐỐI:** Không merge `docs/pttk-report` ngược vào `develop` hoặc `main`; không sửa code trong `backend/`, `frontend/`, `infra/`.

## 2. Danh mục Tệp Điều phối
| Tệp | Mục đích |
|---|---|
| `PHAN_CONG.md` | Bảng phân công chi tiết cho 3 thành viên (Phần 8 & Phần 13 hiện đang để **CHƯA GIAO**) |
| `PLAN.md` | Lộ trình thực hiện, ngân sách số trang từng phần và checklist nghiệm thu |
| `glossary_lock.md` | Bảng khóa cứng mã số (`FR`, `NFR`, `BR`, `UC`, `SC`, `ADR`) và thuật ngữ dùng chung |
| `HANDOFF_PACKET.md` | Gói tóm tắt gửi cho thành viên thứ 3 (người ngoài dự án) để viết đúng ngữ cảnh DevFlow |
| `diagrams/DIAGRAM_GUIDE.md` | Danh sách sơ đồ cần vẽ, công cụ xuất và tiêu chuẩn kích thước A4 |
| `preamble.tex` | Khai báo font (`Segoe UI`, `Cascadia Mono`), bảng màu, macro bảng/hình (không tự ý sửa) |
| `main.tex` | Tệp gốc ghép toàn bộ `sections/00_mo_dau.tex` → `sections/14_phu_luc.tex` |
| `build.ps1` | Script PowerShell biên dịch PDF bằng `XeLaTeX` (2 lượt) và kiểm tra số trang |

## 3. Lệnh Biên dịch PDF (Windows PowerShell)

```powershell
powershell -ExecutionPolicy Bypass -File docs/pttk/build.ps1
```

Tệp PDF xuất ra nằm tại: `docs/pttk/build/DevFlow_HoSo_PTTK.pdf` (thư mục `build/` được git-ignore tự động).

## 4. Đồng bộ ngữ cảnh mới nhất từ `develop` (một chiều)

```powershell
git fetch origin
git merge origin/develop --no-ff -m "docs(pttk): sync latest context from develop"
```
