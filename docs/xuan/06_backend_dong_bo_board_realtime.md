# T-015 — Backend gửi cập nhật board realtime

**Milestone 2 · Phạm vi:** `board-impl` · [Thẻ Trello](https://trello.com/c/wcmdeeXc/21-t-015-configure-websocket-stomp-broadcaster-for-board-state-sync) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-012–T-014; cấu hình WebSocket và xác thực dùng chung.

## Task này làm gì?

Khi một người sửa hoặc kéo thẻ, backend gửi thông tin thay đổi tới những người đang xem cùng board.

## Cần làm

1. Dùng `SimpMessagingTemplate` gửi bản tin tới `/topic/boards/{boardId}` khi card/column thay đổi.
2. Thống nhất dữ liệu thông điệp cho tạo/sửa/xóa/di chuyển thẻ và cập nhật cột.
3. Chỉ gửi thay đổi đã lưu thành công, đúng board; tích hợp kiểm tra quyền kết nối/đăng ký nhận board.
4. Phối hợp T-018 để frontend hiểu và áp dụng đúng mỗi loại bản tin.

## Từ cần biết

- **WebSocket:** Kết nối hai chiều giữ mở giữa trình duyệt và server, để server chủ động gửi dữ liệu mới.
- **STOMP:** Giao thức bản tin thường dùng trên WebSocket, hỗ trợ gửi và đăng ký nhận theo kênh.
- **Topic / subscribe / broadcast:** Topic là kênh theo board; subscribe là đăng ký nhận; broadcast là gửi tới các client đã đăng ký và được phép nhận.
- **SimpMessagingTemplate:** Công cụ Spring dùng để gửi thông điệp STOMP từ backend.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] STOMP client kết nối và subscribe /topic/boards/{id} nhận được message tức thì
- [ ] Payload message phản ánh chính xác hành động (CARD_MOVED, CARD_CREATED, v.v.)

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Client xem board khác không nhận nhầm dữ liệu; tài khoản ngoài workspace không được subscribe board.
- [ ] Có thể dùng client thử nghiệm để xác nhận nội dung bản tin trước khi T-018 hoàn tất.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
