# Nhiệm vụ của Xuân — DevFlow

**Vai trò:** Technical Co-Lead, phụ trách Kanban, kết nối Git/CI và môi trường local/giám sát. Bạn làm cả backend và frontend của các tính năng này.

Backend xử lý nghiệp vụ và dữ liệu; frontend là phần người dùng thao tác. Module là một phần chức năng riêng của hệ thống; DevFlowEvent là hợp đồng sự kiện chung để các module trao đổi.

**Kết quả cần tạo:** Nhóm dùng được board realtime; task tự đổi trạng thái theo code; lỗi CI được ghi nhận để AI xử lý; hệ thống có kiểm thử, giám sát và chạy được trên cloud.

## Trách nhiệm của bạn

- **Module board:** Dữ liệu, API, kéo thả, chi tiết task, realtime và tự chuyển cột theo Git.
- **Module gitci:** Nhận webhook an toàn, liên kết commit/PR với task, ghi lỗi CI và hiển thị thông tin Git/CI trên thẻ.
- **Hạ tầng và chất lượng:** Docker Compose local, Prometheus/Grafana, test tích hợp; thực hiện backend/database cloud theo T-043.
- **Phối hợp:** Cùng Tiến ghép Auth, AI, Notification, frontend cloud và chuẩn bị nghiệm thu. Tiến điều phối dự án và release.

## Milestone và thứ tự thực hiện

**Milestone** là một mốc có kết quả kiểm chứng được. Các mốc dưới đây chia nhỏ phần của Xuân; không phải thêm sprint hay thêm ticket mới.

### Chuẩn bị — môi trường và hợp đồng chung

- Chạy được Docker Compose local; có backend, frontend, PostgreSQL và môi trường giám sát.
- Phối hợp Tiến về Auth/Workspace, quyền truy cập, cấu trúc API và DevFlowEvent.
- Đây là trách nhiệm chung/nền tảng; bản đồng bộ hiện không có ticket riêng cho thiết lập Compose.

### Milestone 1 — Dữ liệu và API Kanban

**Theo Sprint 2 · T-010 → T-014.** Xây nơi lưu dữ liệu, lớp Java, API bảng/cột/task và sự kiện thay đổi.

**Đạt mốc khi:** Tạo/sửa/xóa bảng, cột, task đúng quyền; đổi vị trí lưu đúng; tạo task/đổi cột phát được sự kiện.

### Milestone 2 — Kanban dùng được và đồng bộ realtime

**Theo Sprint 2 · T-015 → T-018.** Làm backend gửi thay đổi, giao diện kéo thả, cửa sổ task và hook nhận cập nhật.

**Đạt mốc khi:** Hai người xem cùng board, kéo ở màn hình thứ nhất thì màn hình thứ hai cập nhật; sửa task lưu được; lỗi API khôi phục giao diện; mất mạng có kết nối lại.

### Milestone 3 — Tự động hóa Git và CI

**Theo Sprint 3 · T-020 → T-026.** Lưu dữ liệu Git/CI, nhận webhook, tìm mã task, phát event, đổi cột tự động và hiển thị badge.

**Đạt mốc khi:** Commit liên kết → In Progress; mở PR → In Review; merge → Done. CI thất bại được lưu và phát event cho AI; card có link code/cảnh báo.

**Ghép ở Sprint 4:** Bàn giao dữ liệu board, Git/CI và event cho Tiến làm AI/MCP; cùng thử AI đọc lỗi CI và tra cứu task. Xuân không có ticket T-030–T-036 trong danh sách được giao.

### Milestone 4 — Kiểm chứng, giám sát và phát hành

**Theo Sprint 5 · T-041, T-042, T-043, T-045.** Kiểm thử chuỗi liên module, dashboard metrics, backend/database cloud và hồ sơ nghiệm thu.

**Đạt mốc khi:** Test và boundary check qua; dashboard có dữ liệu thật; backend HTTPS khỏe và ghép được frontend; đủ slide, video demo và báo cáo DoD.

T-043 là phần Xuân triển khai backend/database theo Trello, phối hợp Tiến dẫn release. T-045 do cả hai phụ trách. T-041/T-042 có thể chuẩn bị sớm để phát hiện lỗi.

## 20 task chi tiết

| STT | Mã | Nhiệm vụ / chức năng | Milestone |
|---:|---|---|---:|
| 1 | T-010 | [Thiết kế dữ liệu Kanban](01_thiet_ke_du_lieu_kanban.md) | 1 |
| 2 | T-011 | [Ánh xạ và truy xuất dữ liệu Kanban](02_entity_va_repository_kanban.md) | 1 |
| 3 | T-012 | [API quản lý bảng và cột](03_api_quan_ly_board_va_column.md) | 1 |
| 4 | T-013 | [API quản lý và di chuyển task](04_api_quan_ly_va_di_chuyen_task.md) | 1 |
| 5 | T-014 | [Phát sự kiện khi task thay đổi](05_phat_su_kien_task.md) | 1 |
| 6 | T-015 | [Backend gửi cập nhật board realtime](06_backend_dong_bo_board_realtime.md) | 2 |
| 7 | T-016 | [Giao diện Kanban kéo thả](07_giao_dien_kanban_keo_tha.md) | 2 |
| 8 | T-017 | [Cửa sổ chi tiết task](08_cua_so_chi_tiet_task.md) | 2 |
| 9 | T-018 | [Frontend đồng bộ board giữa nhiều người](09_frontend_dong_bo_board_realtime.md) | 2 |
| 10 | T-020 | [Thiết kế dữ liệu Git và CI](10_thiet_ke_du_lieu_git_ci.md) | 3 |
| 11 | T-021 | [Nhận webhook GitHub an toàn](11_tiep_nhan_webhook_github_an_toan.md) | 3 |
| 12 | T-022 | [Tìm mã task trong commit, branch và PR](12_lien_ket_commit_va_task.md) | 3 |
| 13 | T-023 | [Phát sự kiện commit và Pull Request](13_phat_su_kien_git.md) | 3 |
| 14 | T-024 | [Tự chuyển cột task theo Git](14_tu_dong_chuyen_trang_thai_task.md) | 3 |
| 15 | T-025 | [Nhận lỗi CI và chuyển dữ liệu cho AI](15_tiep_nhan_loi_ci.md) | 3 |
| 16 | T-026 | [Hiển thị Git và trạng thái CI trên thẻ](16_hien_thi_git_va_ci_tren_task.md) | 3 |
| 17 | T-041 | [Kiểm thử luồng liên module](17_kiem_thu_tich_hop_lien_module.md) | 4 |
| 18 | T-042 | [Dashboard giám sát hệ thống](18_dashboard_giam_sat_he_thong.md) | 4 |
| 19 | T-043 | [Triển khai backend và database lên cloud](19_trien_khai_backend_va_database.md) | 4 |
| 20 | T-045 | [Tài liệu, video và demo nghiệm thu](20_tai_lieu_va_demo_nghiem_thu.md) | 4 |

## Tiêu chí hoàn thành chung

- **Đúng chức năng:** Đạt checklist gốc trên Trello và kiểm chứng luồng sử dụng; không chỉ dựng UI hoặc API mẫu.
- **Đúng dữ liệu và quyền:** Validation ở server, giới hạn theo workspace/board; lỗi API theo RFC 7807 khi áp dụng. RFC 7807 là định dạng phản hồi lỗi thống nhất để frontend hiểu và hiển thị.
- **Đúng ranh giới:** Không import `internal` hoặc dùng repository/entity của module khác; phối hợp bằng DevFlowEvent trong common hoặc public contract ở module `-api`.
- **Có kiểm thử phù hợp:** Nghiệp vụ có test; API/luồng liên module có kiểm thử tích hợp. Backend: trong `backend/` chạy `./gradlew check` và `./gradlew :verifyModuleBoundaries`.
- **Frontend đạt kiểm tra:** Khi thay đổi frontend, trong `frontend/` chạy `npm run build` và `npm run lint`.
- **Có review và bằng chứng:** Branch/commit theo quy ước, PR được review trước merge; cập nhật checklist/Trello với kết quả thực tế. Task tài liệu/demo được nghiệm thu qua artifact, không cần tạo test code riêng.

Mục tiêu chất lượng của dự án: CRUD P95 < 200 ms, đồng bộ realtime < 1 giây trong điều kiện kiểm thử đã thống nhất. P95 là mốc độ trễ mà 95% request không vượt quá.

## Cách đọc và nguồn

Đọc milestone trước, mở file task theo STT, rồi làm theo phần phụ thuộc. Checklist trong file là điều kiện cần làm, không phải xác nhận task đã hoàn thành.

Phạm vi gồm **20 task** có member @xuanle2 trong [bản đồng bộ Trello](00_all-tasks.md) ngày 05/10/2026. Thẻ `[Team]` là thông tin phân công, không tạo file task riêng. Trạng thái Trello khi đồng bộ: T-010 In Progress, các task còn lại Backlog.

Đối chiếu: [Đặc tả sản phẩm](../PRODUCT_SPEC_VI.md), [Kiến trúc](../ARCHITECTURE.md), [Kiến trúc tiếng Việt](../ARCHITECTURE_VI.md), [Kế hoạch triển khai](../IMPLEMENTATION_PLAN_VI.md). Quyền sở hữu task dùng phân công hiện tại trên Trello; lịch sprint là tham chiếu từ kế hoạch, không đặt hạn mới.
