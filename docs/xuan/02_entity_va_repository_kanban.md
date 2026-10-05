# T-011 — Ánh xạ và truy xuất dữ liệu Kanban

**Milestone 1 · Phạm vi:** `board-impl` · [Thẻ Trello](https://trello.com/c/QP1K3XCk/17-t-011-implement-entities-repositories-for-board-column-task-comment) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-010: cấu trúc bảng Kanban đã có.

## Task này làm gì?

Cho backend Java đọc và ghi dữ liệu Kanban bằng các đối tượng thay vì tự viết SQL cho mọi thao tác.

## Cần làm

1. Tạo `BoardEntity`, `ColumnEntity`, `TaskEntity`, `CommentEntity` kế thừa `BaseEntity` và ánh xạ đúng bảng/cột.
2. Thiết lập quan hệ một board có nhiều cột, một cột có nhiều task, một task có nhiều bình luận.
3. Tạo repository phục vụ lưu, tìm, sửa, xóa dữ liệu; truy vấn theo board/column và đúng thứ tự.
4. Chỉ liên kết entity trong `board`; user, workspace và dữ liệu của module khác được tham chiếu bằng ID.

## Từ cần biết

- **Entity:** Đối tượng Java đại diện cho một bản ghi database, ví dụ một TaskEntity tương ứng một task.
- **JPA / Hibernate:** JPA là chuẩn ánh xạ đối tượng Java với database; Hibernate là công cụ thực hiện chuẩn đó trong dự án.
- **Repository:** Lớp giao tiếp với database, cung cấp thao tác lưu và truy vấn entity.
- **OneToMany / ManyToOne:** Quan hệ một–nhiều và nhiều–một, ví dụ board có nhiều cột và mỗi cột thuộc một board.
- **BaseEntity:** Lớp nền dùng chung cho các trường cơ bản của entity theo code dự án.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Entity mapping chuẩn, hỗ trợ cascade hợp lý
- [ ] Repository query pass các ca kiểm thử tích hợp

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Lưu rồi đọc lại đủ thông tin; quan hệ và thứ tự đúng với database V2.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
