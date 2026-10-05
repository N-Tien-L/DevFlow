# T-023 — Phát sự kiện commit và Pull Request

**Milestone 3 · Phạm vi:** `gitci-impl, common` · [Thẻ Trello](https://trello.com/c/XrmixzON/28-t-023-implement-domain-event-publishing-gitcommitlinked-gitpropened-gitprmerged) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-021, T-022; hợp đồng Git events trong common.

## Task này làm gì?

Biến hoạt động Git thành thông báo nghiệp vụ để board tự cập nhật mà module Git không phải sửa database của board.

## Cần làm

1. Phát `GitCommitLinkedEvent` khi commit được liên kết task.
2. Phát `GitPrOpenedEvent` khi PR mở và `GitPrMergedEvent` khi PR được merge thật; PR chỉ đóng không được coi là merge.
3. Gửi taskId, repo metadata và commitSha/prUrl phù hợp với từng loại sự kiện.
4. Dùng `ApplicationEventPublisher`; không gọi service/repository nội bộ của board. Xử lý lần gửi webhook trùng để tránh tác động lặp.

## Từ cần biết

- **Typed event:** Sự kiện có kiểu dữ liệu cụ thể, giúp bên phát và bên nhận thống nhất thông tin.
- **Merge / metadata:** Merge là gộp code giữa các nhánh; metadata là thông tin kèm theo như repo, tác giả và URL.
- **Idempotent:** Xử lý lại cùng một đầu vào mà không tạo thêm tác động sai, hữu ích khi webhook bị gửi lại.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Event chứa đầy đủ taskId, commitSha, prUrl và repo metadata
- [ ] Bắn event qua Spring ApplicationEventPublisher không lỗi

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Kiểm thử commit, mở PR, merge PR và đóng PR chưa merge; listener nhận đúng sự kiện tương ứng.
- [ ] Webhook gửi lại không tạo liên kết hoặc thông báo nghiệp vụ trùng ngoài ý muốn.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
