# T-016 — Giao diện Kanban kéo thả

**Milestone 2 · Phạm vi:** `frontend` · [Thẻ Trello](https://trello.com/c/Ufb9l5NX/22-t-016-refactor-frontend-boardpage-with-drag-and-drop-column-card-ui) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-012, T-013: API board/column/task; phiên đăng nhập của Tiến.

## Task này làm gì?

Cho người dùng quản lý công việc bằng cách kéo thẻ giữa các cột hoặc đổi thứ tự thẻ/cột trên màn hình.

## Cần làm

1. Hoàn thiện `BoardPage.tsx`: lấy dữ liệu thật, hiển thị cột/thẻ, xử lý trạng thái tải, rỗng và lỗi.
2. Tích hợp kéo thả thẻ trong cột, giữa các cột và đổi thứ tự cột bằng thư viện/cơ chế đã chọn.
3. Cập nhật giao diện ngay khi kéo, rồi gọi API lưu vị trí; nếu API lỗi thì trả giao diện về trạng thái trước đó.
4. Giữ bộ lọc tên, người phụ trách, độ ưu tiên theo kế hoạch BoardPage; phối hợp modal và realtime ở T-017/T-018.

## Từ cần biết

- **Kanban / Drag & Drop:** Kanban là bảng các cột trạng thái; Drag & Drop là thao tác kéo rồi thả để di chuyển thẻ/cột.
- **Optimistic UI:** Hiển thị kết quả ngay trước khi server trả lời, giúp thao tác nhanh và mượt.
- **Rollback:** Khôi phục trạng thái trước đó nếu việc lưu phía server thất bại.
- **State:** Dữ liệu hiện tại mà React dùng để vẽ giao diện board.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Kéo thả card giữa các cột hoặc reorder trong cùng cột không bị giật lag
- [ ] Gọi API đồng bộ vị trí ngầm và rollback trạng thái nếu request lỗi

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Tải lại trang vẫn đúng vị trí đã lưu; lỗi API có thông báo và không làm mất thẻ.
- [ ] Đổi thứ tự cột được lưu đúng; các bộ lọc hiển thị đúng task.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
