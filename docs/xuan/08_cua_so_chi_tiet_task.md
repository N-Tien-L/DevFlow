# T-017 — Cửa sổ chi tiết task

**Milestone 2 · Phạm vi:** `frontend` · [Thẻ Trello](https://trello.com/c/Waxoy1hP/23-t-017-build-task-detail-modal-markdown-description-priority-assignee) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-013, T-016; danh sách thành viên qua API công khai; API bình luận của board khi bật gửi bình luận.

## Task này làm gì?

Khi bấm vào thẻ, người dùng xem và chỉnh sửa đầy đủ thông tin công việc mà không rời bảng Kanban.

## Cần làm

1. Tạo modal mở từ task card; đọc đúng dữ liệu task đang được chọn.
2. Cho sửa tiêu đề, mô tả Markdown, người phụ trách, hạn và độ ưu tiên; có khu vực bình luận.
3. Gọi API lưu, thông báo khi lỗi và cập nhật thẻ trên board sau khi thành công.
4. Hiển thị preview Markdown an toàn. Với bình luận, kết nối API board khi đã có; phần backend còn thiếu phải được hoàn thiện/thống nhất, không chỉ dựng ô nhập.

## Từ cần biết

- **Modal:** Cửa sổ nổi trên trang hiện tại, dùng để xem/sửa thông tin mà không chuyển trang.
- **Markdown / preview:** Cú pháp văn bản để viết tiêu đề, danh sách, code; preview là bản xem đã được định dạng.
- **Assignee / due date / priority:** Người được giao việc / hạn hoàn thành / mức độ ưu tiên của task.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Render Markdown preview mượt mà
- [ ] Lưu các thay đổi thành công qua API và cập nhật lại thẻ trên board

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Các trường đã lưu vẫn đúng khi mở lại modal; nội dung Markdown không thực thi mã nguy hiểm.
- [ ] Khu vực bình luận có trạng thái tải/rỗng/lỗi rõ ràng, gửi và đọc lại được khi API bình luận sẵn sàng.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
