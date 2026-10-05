# T-024 — Tự chuyển cột task theo Git

**Milestone 3 · Phạm vi:** `board-impl` · [Thẻ Trello](https://trello.com/c/un30KKfG/29-t-024-implement-gitactivityeventlistener-in-board-impl-to-auto-transition-task-columns) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-023: Git events; T-013/T-014: cập nhật task và phát sự kiện; T-015: gửi realtime.

## Task này làm gì?

Giúp bảng Kanban phản ánh tiến độ code: bắt đầu code → đang làm, mở PR → đang review, merge → hoàn thành.

## Cần làm

1. Viết `GitActivityEventListener` trong `board-impl` nhận các Git events từ common.
2. Áp dụng luồng commit linked → In Progress, PR opened → In Review, PR merged → Done.
3. Tái sử dụng nghiệp vụ cập nhật task của board, ghi dữ liệu và phát task.status_changed cùng bản tin STOMP.
4. Có quy tắc chuyển trạng thái hợp lệ: không kéo task Done ngược về In Progress bởi commit cũ/gửi lại; xử lý an toàn task hoặc cột đích không tồn tại.

## Từ cần biết

- **Event listener:** Bộ xử lý tự chạy khi nhận loại sự kiện mà nó đã đăng ký.
- **Auto-transition:** Tự chuyển trạng thái theo quy tắc đã định nghĩa, thay vì người dùng kéo thẻ bằng tay.
- **State transition:** Sự chuyển từ trạng thái cũ sang mới; cần kiểm tra để không đi ngược tiến độ ngoài ý muốn.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Task tự động đổi cột theo đúng luồng sự kiện Git
- [ ] Gửi tín hiệu STOMP cập nhật UI cho mọi client đang xem board

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Task ở To Do đi đúng chuỗi In Progress → In Review → Done; dữ liệu vẫn đúng sau khi tải lại.
- [ ] Sự kiện cũ/trùng không làm lùi task đã hoàn thành; không import gitci.internal.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
