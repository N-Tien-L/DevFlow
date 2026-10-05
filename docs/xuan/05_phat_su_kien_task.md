# T-014 — Phát sự kiện khi task thay đổi

**Milestone 1 · Phạm vi:** `board-impl, common` · [Thẻ Trello](https://trello.com/c/fYuN9nAn/20-t-014-implement-event-publisher-for-taskcreated-taskstatuschanged) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-013; hợp đồng DevFlowEvent trong common thống nhất với Tiến.

## Task này làm gì?

Báo cho các phần khác của hệ thống biết task vừa được tạo hoặc đổi trạng thái, để AI và Notification có thể phản ứng.

## Cần làm

1. Phát `TaskCreatedEvent` khi tạo task; phát `TaskStatusChangedEvent` khi task thực sự đổi trạng thái/cột.
2. Đặt hợp đồng event dùng chung trong `common`; sử dụng `ApplicationEventPublisher` từ dịch vụ board.
3. Gửi đúng ID task/board và dữ liệu theo hợp đồng; với đổi trạng thái, có trạng thái cũ, mới và nguyên nhân manual/git.
4. Kiểm chứng listener nhận được sự kiện; tránh báo thành công khi thao tác lưu task thất bại.

## Từ cần biết

- **Domain event / payload:** Sự kiện nghiệp vụ mô tả điều vừa xảy ra; payload là dữ liệu đi kèm để bên nhận xử lý.
- **Spring Event Bus:** Cơ chế phát/nhận sự kiện trong cùng ứng dụng Spring, giúp các module phối hợp mà không gọi phần nội bộ của nhau.
- **Publisher / listener:** Publisher phát thông báo; listener đăng ký nhận và xử lý thông báo.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Bắn đúng typed event với đầy đủ payload lên Spring Event Bus
- [ ] Skeleton listener nhận được event và ghi log kiểm chứng

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Tạo task và đổi cột phát đúng loại sự kiện; reorder trong cùng cột không bị hiểu nhầm thành đổi trạng thái.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
