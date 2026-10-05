# T-010 — Thiết kế dữ liệu Kanban

**Milestone 1 · Phạm vi:** `:app, board-impl` · [Thẻ Trello](https://trello.com/c/SeDVPyX8/16-t-010-setup-flyway-migration-for-board-schema-v2boardschemasql) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** Flyway V1 và dữ liệu user/workspace của Tiến; thiết kế database đã thống nhất.

## Task này làm gì?

Tạo nền móng lưu trữ cho bảng Kanban. Nhờ đó, bảng, cột và task vẫn còn nguyên sau khi tải lại trang hoặc khởi động lại backend.

## Cần làm

1. Viết `V2__board_schema.sql` tạo `boards`, `columns`, `tasks`, `task_comments`, `task_tags`.
2. Lưu quan hệ board → column → task; có vị trí để sắp xếp cột/thẻ, thông tin người phụ trách, hạn và độ ưu tiên theo thiết kế.
3. Đặt ràng buộc dữ liệu và chỉ mục truy vấn. Quy định rõ dữ liệu con nào bị xóa khi xóa dữ liệu cha.
4. Quan hệ bên trong `board` dùng khóa ngoại theo thiết kế; tham chiếu user/workspace thuộc `auth` chỉ lưu ID, không tạo quan hệ JPA hay khóa ngoại xuyên module.

## Từ cần biết

- **Flyway / migration:** Flyway là công cụ quản lý thay đổi cấu trúc database bằng các file SQL có phiên bản. Migration là một lần thay đổi; V2 chạy sau V1 và được ghi nhận để không chạy lại tùy tiện.
- **Schema:** Cấu trúc dữ liệu gồm bảng, cột và các ràng buộc; ở task này là thiết kế dữ liệu cho Kanban.
- **Foreign key / index:** Khóa ngoại bảo vệ quan hệ giữa bảng trong cùng module; index là chỉ mục giúp tra cứu nhanh hơn.
- **Cascade delete:** Tự xóa dữ liệu con khi xóa dữ liệu cha, chỉ áp dụng cho những quan hệ đã được thiết kế cho phép.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Flyway migrate V2 thành công trên PostgreSQL
- [ ] Các ràng buộc quan hệ và cascade delete hoạt động đúng thiết kế

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Khởi tạo database mới và nâng cấp database đã có V1 đều thành công; chạy lại ứng dụng không tạo lại bảng.
- [ ] Có kiểm tra lưu task, thứ tự thẻ và xóa dữ liệu con; không để dữ liệu mồ côi.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
