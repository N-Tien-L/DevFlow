# T-013 — API quản lý và di chuyển task

**Milestone 1 · Phạm vi:** `board-impl` · [Thẻ Trello](https://trello.com/c/77BVUDYF/19-t-013-implement-rest-endpoints-for-task-crud-reorder-move-operations) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-011, T-012: board/column tồn tại và kiểm tra quyền đã thống nhất.

## Task này làm gì?

Lưu các thao tác trên thẻ Kanban vào database: tạo, xem, sửa, xóa, đổi vị trí và chuyển cột.

## Cần làm

1. Xây API tạo task trong cột, đọc thông tin task, cập nhật và xóa task.
2. Cập nhật tiêu đề, mô tả, người phụ trách, hạn, độ ưu tiên theo dữ liệu task.
3. Xử lý move/reorder: cập nhật cột và thứ tự của thẻ, giữ thứ tự hợp lệ ở cột nguồn và cột đích.
4. Kiểm tra task/cột thuộc đúng board/workspace và người gọi có quyền; nhóm các thay đổi liên quan trong một transaction.

## Từ cần biết

- **Move / reorder:** Move chuyển thẻ sang cột khác; reorder đổi thứ tự trong một cột.
- **Position:** Giá trị thể hiện vị trí của cột/thẻ, dùng để dựng lại thứ tự trên giao diện.
- **Transaction:** Một nhóm thao tác database cùng thành công hoặc cùng hủy, tránh chuyển thẻ dở dang.
- **Validation:** Kiểm tra dữ liệu đầu vào, ví dụ tiêu đề rỗng hoặc cột đích không hợp lệ.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Tạo, sửa, xóa task thành công
- [ ] API move task xử lý mượt mà cả ca đổi vị trí trong cột lẫn chuyển sang cột khác

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Tải lại board vẫn thấy đúng nội dung, cột và thứ tự; lỗi giữa chừng không để dữ liệu ở trạng thái nửa cập nhật.
- [ ] Có kiểm thử thiếu quyền, task không tồn tại và cột đích không hợp lệ.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
