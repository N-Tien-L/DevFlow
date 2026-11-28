# Kế Hoạch Soạn Thảo Hồ Sơ PTTK — DevFlow (`DF-PTTK-01`)

> **Mục tiêu:** Hoàn thành bản PDF hồ sơ PTTK đầy đủ 13 phần + Phụ lục cho dự án **DevFlow**, đạt chuẩn trình bày học thuật/kỹ thuật, **30 ≤ số trang ≤ 65** (mục tiêu **48–56 trang**), không bị văn phong AI slop.

---

## 1. Ngân sách Số trang theo Phần (Page Budget)

| Phần | Mục tiêu trang | Giới hạn mềm | Chiến lược kiểm soát độ dài |
|---|---:|---:|---|
| Mở đầu (Bìa, Thông tin, Mục lục, Danh mục hình/bảng) | 3.0 | 2.5–3.5 | Tự động sinh từ LaTeX (`\tableofcontents`, `\listoffigures`, `\listoftables`) |
| Phần 1. Tổng quan và khởi tạo dự án | 4.0 | 3.5–4.5 | Dùng bảng cho OKR, Scope, Stakeholders, RACI, Chi phí–Lợi ích, Rủi ro |
| Phần 2. Khảo sát hiện trạng (AS-IS) | 3.0 | 2.5–3.5 | Tập trung 3 phân khúc dev, 1 sơ đồ AS-IS, 6 điểm đau có số liệu khảo sát |
| Phần 3. Phân tích yêu cầu (TO-BE) | 5.0 | 4.5–5.5 | 22 FR (MoSCoW), 3 User Story Gherkin tiêu biểu, 10 NFR ISO 25010, 10 BR, 1 sơ đồ TO-BE |
| Phần 4. Mô hình hóa phân tích | 6.0 | 5.5–7.0 | 1 Use Case tổng quát, đặc tả sâu 3 UC lõi, 2 Sequence, 1 State Machine + bảng chuyển, 1 bảng DDD |
| Phần 5. Thiết kế kiến trúc | 5.0 | 4.5–5.5 | Bảng so sánh Modular Monolith vs Microservices, C4 Context, C4 Container, Tech Stack, 6 ADR (1 ADR đầy đủ) |
| Phần 6. Thiết kế dữ liệu | 5.0 | 4.5–6.0 | Giải thích Soft Ref UUID, 1 ERD logic 5 cụm, Từ điển dữ liệu trích 3 bảng lõi, Index, Lưu trữ & GDPR |
| Phần 7. Thiết kế tích hợp và API | 5.0 | 4.5–5.5 | Nguyên tắc 3 cổng (REST/STOMP/MCP), Bảng endpoint & MCP tool, 2 mẫu JSON/RFC 7807, Event Catalog, Tích hợp ngoài |
| Phần 8. Thiết kế UI/UX *(Chưa giao)* | 4.0 | 3.5–4.5 | 3 Personas, 1 User Journey, 10 màn hình, 1–2 Wireframe/UI Kanban + Chat, Design System, WCAG 2.1 AA |
| Phần 9. Thiết kế bảo mật và tuân thủ | 3.5 | 3.0–4.0 | Bảng STRIDE (6 mục), Ma trận RBAC/ABAC, lớp bảo vệ dữ liệu, bảng Pháp lý, Audit Logging |
| Phần 10. Thiết kế hạ tầng và vận hành (DevOps) | 3.0 | 2.5–3.5 | 1 Sơ đồ triển khai, 3 môi trường, CI/CD 5 bước, SLO/Prometheus/Grafana, Backup/PITR |
| Phần 11. Thành phần trí tuệ nhân tạo (AI) | 3.0 | 2.5–3.5 | 4 tình huống AI + MCP, 1 Model Card đầy đủ, 6 cơ chế Guardrails |
| Phần 12. Kiểm thử và đảm bảo chất lượng | 3.5 | 3.0–4.0 | Chiến lược 7 mức, 6–8 Test Case mẫu, Bảng RTM tiêu biểu (10–12 dòng), Definition of Done |
| Phần 13. Kế hoạch triển khai & chuyển giao *(Chưa giao)* | 2.5 | 2.0–3.0 | 1 Gantt 5 Phase, bảng chọn Go-live, Onboarding (Webhook + MCP), SLA P1–P4, Đo lường sau go-live |
| Phụ lục A, B, C | 2.5 | 2.0–3.0 | 1 Biên bản phỏng vấn mẫu, 1 Trích bảng hỏi khảo sát, Danh mục tài liệu tham khảo |
| **TỔNG CỘNG** | **55.0** | **48–62** | **An toàn tuyệt đối trong ràng buộc 30–65 trang** |

---

## 2. Lộ trình Thực hiện Đề xuất (Gấp — 5 Ngày)

| Giai đoạn | Thời điểm | Việc cần làm | Kết quả đầu ra (Deliverable) |
|---|---|---|---|
| **Bước 0: Khởi tạo** | Đã hoàn thành | Tạo nhánh `docs/pttk-report`, dựng rules, skill, `preamble.tex`, `main.tex`, 15 file section, `PHAN_CONG.md`, `glossary_lock.md`, `HANDOFF_PACKET.md` | Biên dịch thử ra PDF khung thành công bằng `build.ps1` |
| **Bước 1: Chốt phân công & Viết nháp đợt 1** | Ngày 1–2 | Gửi `HANDOFF_PACKET.md` cho Thành viên 3; chốt người làm Phần 8 & 13; cả 3 người viết song song phần được giao với placeholder hình `\todofig` | 100% văn bản thô có mặt trong `sections/*.tex` |
| **Bước 2: Vẽ & Nhúng Sơ đồ** | Ngày 3 | Xuất toàn bộ 11 hình chuẩn vào `diagrams/out/` (Mermaid `--pdfFit` + SVG/HTML→PDF + chụp UI DevFlow), thay toàn bộ `\todofig` | Toàn bộ 11 hình hiển thị sắc nét, không tràn lề |
| **Bước 3: Review Chéo & Chống AI Slop** | Ngày 4 | Rà soát chéo mã số (`FR` ↔ `UC` ↔ `RTM`), quét văn phong chống AI slop theo `.agents/rules/pttk-report-branch.md`, kiểm tra không lọt chi tiết mẫu TaskHub | 0 lỗi mâu thuẫn kỹ thuật, 0 cảnh báo `Overfull \hbox` nghiêm trọng |
| **Bước 4: Biên dịch & Nghiệm thu PDF** | Ngày 5 | Chạy `build.ps1`, rà soát từng trang PDF (ngắt trang, bảng qua trang, mục lục, số trang trong `[30, 65]`), chốt phiên bản `1.0` | `DevFlow_HoSo_PTTK.pdf` sẵn sàng nộp |

---

## 3. Tiêu chí Nghiệm thu Trước khi Nộp (Final Checklist)

- [ ] Tổng số trang PDF nằm trong khoảng **30–65 trang** (lý tưởng **48–56 trang**).
- [ ] Mục lục, Danh mục hình ảnh, Danh mục bảng biểu và toàn bộ `\ref` khớp số trang (không có `??`).
- [ ] Không có bảng bị tràn lề phải; không có hình bị mờ/chữ quá nhỏ không đọc được.
- [ ] Không còn placeholder `\todofig` hay ghi chú `TODO` nào trong văn bản.
- [ ] Mọi mã `FR`, `NFR`, `BR`, `UC`, `SC`, `ADR` nhất quán 100% với `glossary_lock.md` và bảng RTM (Mục 12.3).
- [ ] Không lọt bất kỳ chi tiết giả định nào từ mẫu TaskHub (SSO Entra ID, Zalo OA, HRM, Flutter, NestJS, RabbitMQ, Kubernetes).
- [ ] Nhánh `docs/pttk-report` không sửa bất kỳ file code nào trong `backend/`, `frontend/`, `infra/` và không tạo PR vào `develop`/`main`.
