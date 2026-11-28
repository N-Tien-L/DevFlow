---
name: pttk-report
description: Quy trình chuẩn để soạn thảo, vẽ sơ đồ, rà soát chống AI slop và biên dịch PDF cho Hồ sơ Phân tích Thiết kế Hệ thống DevFlow (DF-PTTK-01) trên nhánh docs/pttk-report.
---

# Skill: Soạn thảo Hồ sơ PTTK Hệ thống DevFlow (`DF-PTTK-01`)

Sử dụng skill này mỗi khi viết mới, chỉnh sửa, vẽ sơ đồ hoặc biên dịch PDF cho bộ hồ sơ PTTK của DevFlow trong `docs/pttk/`.

---

## 1. Kiểm tra Bắt buộc Trước khi Bắt đầu (Pre-flight Check)

Trước khi chỉnh sửa bất kỳ tệp nào, bắt buộc thực hiện:
1. Kiểm tra nhánh hiện tại là `docs/pttk-report`:
   ```powershell
   git branch --show-current
   ```
   Nếu không ở nhánh `docs/pttk-report`, **dừng lại** và báo người dùng.
2. Đọc 4 tệp điều phối cốt lõi:
   - `.agents/rules/pttk-report-branch.md` (luật nhánh, luật văn phong, ràng buộc 30–65 trang)
   - `docs/pttk/PHAN_CONG.md` (kiểm tra người phụ trách và phạm vi phần được giao)
   - `docs/pttk/PLAN.md` (kiểm tra ngân sách số trang của phần sắp viết)
   - `docs/pttk/glossary_lock.md` (bắt buộc dùng đúng mã `FR`, `NFR`, `BR`, `UC`, `SC`, `ADR`)
3. Đọc đúng nguồn sự thật kỹ thuật của DevFlow cho phần tương ứng:
   - Phần 1–3, 8, 13: `docs/PRODUCT_SPEC_VI.md`, `docs/IMPLEMENTATION_PLAN_VI.md`
   - Phần 4–5, 7, 9, 11: `docs/ARCHITECTURE_VI.md`, `openspec/changes/**`, `backend/*-api/**`
   - Phần 6: `docs/DATABASE_DESIGN_VI.md`, `docs/diagrams/database-erd.mmd`, `backend/app/src/main/resources/db/migration/V*.sql`
   - Phần 10, 12: `docker-compose.yml`, `.github/workflows/ci.yml`, `infra/**`, `docs/xuan/10`–`20`

---

## 2. Quy tắc Viết LaTeX trong `docs/pttk/sections/*.tex`

Mỗi phần nằm trong 1 tệp riêng (`00_mo_dau.tex` .. `14_phu_luc.tex`). Sử dụng các macro đã định nghĩa sẵn trong `docs/pttk/preamble.tex`:

- **Mở phần mới:** Dùng `\pttkPart{Phần X. Tên phần}` (tự động ngắt trang và căn style thống nhất).
- **Mục con:** Dùng `\subsection{X.Y. Tên mục}` và `\subsubsection{...}` (tối đa 3 cấp).
- **Hộp thông tin ngắn (ví dụ Gherkin, quy tắc nổi bật):**
  ```latex
  \begin{pttkbox}{Tiêu đề hộp}
  Nội dung...
  \end{pttkbox}
  ```
- **Khối code / JSON / Gherkin:**
  ```latex
  \begin{pttkcode}{Ví dụ POST /api/v1/tasks}
  ...
  \end{pttkcode}
  ```
- **Chèn hình hoàn chỉnh:**
  ```latex
  \pttkfigure{diagrams/out/hinh_04_sequence_git.pdf}{Biểu đồ tuần tự luồng Git Webhook đến Board và AI}{fig:seq-git}{0.95}
  ```
- **Placeholder hình khi chưa vẽ xong:**
  ```latex
  \todofig{Hình 4. Biểu đồ tuần tự luồng Git Webhook → Event Bus → Board & AI}
  ```
- **Bảng chuẩn:** Dùng `tabularx` với độ rộng `\textwidth` (cho bảng ngắn dưới 1 trang) hoặc `longtable` (cho bảng dài qua trang như danh sách FR, Từ điển dữ liệu, RTM). Dùng `\rowcolor{pttkNavy}` cho dòng tiêu đề chữ trắng `\tablehead{...}`.

---

## 3. Quy trình Vẽ & Xuất Sơ đồ (Phương án A — Zero-Setup)

Chi tiết danh sách hình nằm tại `docs/pttk/diagrams/DIAGRAM_GUIDE.md`.

### 3.1. Sơ đồ Mermaid (`mmdc`) — AS-IS/TO-BE, Sequence, State, Gantt
1. Lưu mã nguồn vào `docs/pttk/diagrams/src/<ten_hinh>.mmd`.
2. Xuất vector PDF vừa khít khung bằng file cấu hình màu chung:
   ```powershell
   mmdc -i docs/pttk/diagrams/src/<ten_hinh>.mmd -o docs/pttk/diagrams/out/<ten_hinh>.pdf -c docs/pttk/diagrams/mermaid.config.json --pdfFit
   ```
3. Kiểm tra tỷ lệ khung hình: không để sơ đồ quá ngang (`> 2.5:1`) hoặc quá dài dọc (`> 1:1.6`).

### 3.2. Sơ đồ SVG / HTML+CSS — C4 Context, C4 Container, Use Case, ERD tổng thể, Deployment, Wireframe
1. Viết tệp HTML/SVG độc lập trong `docs/pttk/diagrams/src/<ten_hinh>.html` (dùng đúng bảng màu `#1F3A5F`, `#0F766E`, `#D9731A`, `#B42318`, `#F4F6F8`, font `Segoe UI`, canvas rộng `1280px`, cỡ chữ trong node `14–16px`).
2. Render thành PDF vector hoặc PNG độ phân giải cao (scale 2x–3x) qua Chrome/Edge headless hoặc `chrome-devtools-mcp`, lưu vào `docs/pttk/diagrams/out/`.

---

## 4. Lượt Biên tập Chống "AI Slop" (Bắt buộc trước khi báo xong phần)

Sau khi viết xong nháp 1 tệp `sections/XX_*.tex`, chạy lượt kiểm tra 5 điểm:
1. **Quét sáo ngữ:** Xóa sạch các câu rào đón, câu *"không chỉ... mà còn..."*, từ thổi phồng (*đột phá, toàn diện, hoàn hảo*).
2. **Kiểm tra độ dày đoạn:** Không có đoạn văn xuôi nào dài quá 5 dòng liên tục.
3. **Kiểm tra chống spam định dạng:** Không để danh sách 5+ mục mà mục nào cũng bold-lead-in lặp cấu trúc; không có emoji.
4. **Đối chiếu `glossary_lock.md`:** Mọi mã `FR`, `NFR`, `BR`, `UC`, `SC`, `ADR`, tên bảng, tên event khớp chính xác 100%.
5. **Đối chiếu ngân sách trang:** Biên dịch thử và xác nhận phần đó nằm trong ngân sách trang dự kiến tại `docs/pttk/PLAN.md`.

---

## 5. Biên dịch PDF & Kiểm tra Độ dài (30–65 trang)

Chạy script biên dịch chuẩn từ thư mục gốc dự án hoặc `docs/pttk/`:

```powershell
powershell -ExecutionPolicy Bypass -File docs/pttk/build.ps1
```

Script sẽ:
- Chạy `xelatex` 2 lượt vào thư mục `docs/pttk/build/` để khớp mục lục, danh mục hình/bảng và tham chiếu chéo.
- Sao chép tệp kết quả ra `docs/pttk/build/DevFlow_HoSo_PTTK.pdf`.
- Tự động đọc số trang PDF và cảnh báo nếu nằm ngoài khoảng **[30, 65] trang**.
