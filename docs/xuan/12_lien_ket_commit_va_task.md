# T-022 — Tìm mã task trong commit, branch và PR

**Milestone 3 · Phạm vi:** `gitci-impl` · [Thẻ Trello](https://trello.com/c/noosB1vt/27-t-022-build-regex-parser-to-link-git-commits-branch-names-to-task-ids) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-020, T-021; cách tra cứu task qua hợp đồng công khai board-api.

## Task này làm gì?

Tự nhận biết đoạn code đang phục vụ task nào nhờ mã như T-010 trong thông điệp commit, tên nhánh hoặc tiêu đề PR.

## Cần làm

1. Viết parser tìm mã dạng `T-xxx` hoặc `#T-xxx`, trong đó phần số có 3–4 chữ số theo ticket.
2. Đọc commit message, branch name và tiêu đề PR; trích xuất mã để tìm task đúng phạm vi repository/workspace.
3. Lưu commit và liên kết task tương ứng trong dữ liệu Git/CI.
4. Bỏ qua an toàn khi không có mã hoặc không tìm thấy task; tránh ghi liên kết trùng khi mã lặp trong cùng nội dung.

## Từ cần biết

- **Regex:** Biểu thức mô tả mẫu ký tự để tìm kiếm, ví dụ tìm T-003 trong một câu dài.
- **Parser:** Bộ đọc và phân tích nội dung để lấy thông tin cần dùng, ở đây là mã task.
- **Branch / commit message:** Branch là nhánh phát triển code; commit message là lời mô tả lần lưu thay đổi.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Trích xuất chính xác task ID từ chuỗi như feat(auth): T-003 setup jwt
- [ ] Bỏ qua an toàn các commit không chứa định dạng task ID

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Kiểm thử branch `feature/T-010-board`, PR có `#T-010`, mã lặp, mã sai và task không tồn tại; không liên kết nhầm workspace.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

## Lưu ý

Ví dụ: `feat(board): T-010 add schema` → tìm T-010 → liên kết commit với task đó.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
