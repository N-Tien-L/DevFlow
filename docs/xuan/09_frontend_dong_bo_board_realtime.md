# T-018 — Frontend đồng bộ board giữa nhiều người

**Milestone 2 · Phạm vi:** `frontend` · [Thẻ Trello](https://trello.com/c/f2hncr87/24-t-018-implement-frontend-stomp-client-hook-for-multi-client-real-time-board-sync) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-015: server phát bản tin; T-016: giao diện board.

## Task này làm gì?

Người thứ hai tự thấy thay đổi khi người thứ nhất kéo/sửa thẻ, không phải bấm F5.

## Cần làm

1. Viết hook `useBoardSync` kết nối `/ws` qua STOMP, subscribe `/topic/boards/{id}`.
2. Nhận bản tin rồi cập nhật dữ liệu board hoặc lấy lại dữ liệu cần thiết, tránh tạo thẻ trùng.
3. Tự kết nối lại khi mạng phục hồi; tải lại dữ liệu để bù các thay đổi đã bỏ lỡ.
4. Hủy đăng ký/đóng kết nối phù hợp khi đổi board hoặc rời trang; xử lý phiên đăng nhập hết hạn.

## Từ cần biết

- **React hook:** Hàm tái sử dụng logic trong React; ở đây gom logic kết nối và đồng bộ board.
- **STOMP client / reconnect:** Client là phía trình duyệt nhận/gửi bản tin; reconnect là kết nối lại sau khi mất mạng.
- **Subscription:** Đăng ký nhận thông điệp của một topic, cần dọn khi không còn xem board đó.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Mở 2 trình duyệt cùng xem 1 board: kéo card ở tab 1 thì tab 2 tự nhảy vị trí
- [ ] Xử lý tự động reconnect khi mất kết nối mạng

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Sau khi mất mạng rồi nối lại, board khớp dữ liệu server; đổi board không nhận cập nhật của board cũ.
- [ ] Kiểm chứng mục tiêu đồng bộ dưới 1 giây trong điều kiện demo/kiểm thử đã thống nhất.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
