# T-012 — API quản lý bảng và cột

**Milestone 1 · Phạm vi:** `board-impl, board-api` · [Thẻ Trello](https://trello.com/c/T7HtO3t8/18-t-012-implement-rest-endpoints-for-board-and-column-crud) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-011; AuthApi và kiểm tra quyền workspace của Tiến.

## Task này làm gì?

Cho giao diện tạo, xem, sửa, xóa bảng/cột và đổi thứ tự cột trong đúng workspace của người dùng.

## Cần làm

1. Xây API CRUD Board/Column. Các thao tác nêu trên thẻ gồm tạo board, lấy board theo workspace, thêm cột và reorder cột.
2. Đưa controller và xử lý nghiệp vụ vào `board-impl`; hợp đồng công khai/DTO phù hợp nằm trong `board-api`.
3. Kiểm tra người gọi là thành viên được phép truy cập workspace; chặn truy cập board của workspace khác.
4. Kiểm tra tên, ID và vị trí cột; trả lỗi rõ ràng theo quy chuẩn chung. Duy trì BoardApi cho các module cần tra cứu công khai.

## Từ cần biết

- **REST API / endpoint:** Cách frontend gửi yêu cầu HTTP đến backend; endpoint là địa chỉ cho một thao tác cụ thể.
- **CRUD:** Create, Read, Update, Delete: tạo, đọc, sửa, xóa dữ liệu.
- **Controller / DTO:** Controller nhận yêu cầu HTTP; DTO là gói dữ liệu gửi/nhận, giúp không lộ entity nội bộ.
- **Workspace / reorder:** Workspace là không gian làm việc của nhóm; reorder là thay đổi thứ tự cột.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Tạo board mới và lấy danh sách board theo workspace chính xác
- [ ] Đổi thứ tự hiển thị của các cột trạng thái thành công

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Đọc/sửa/xóa board và column đúng phạm vi; lưu thứ tự rồi tải lại vẫn giữ nguyên.
- [ ] Yêu cầu thiếu quyền hoặc dữ liệu không hợp lệ bị từ chối; không đọc được dữ liệu workspace khác.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
