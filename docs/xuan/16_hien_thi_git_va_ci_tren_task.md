# T-026 — Hiển thị Git và trạng thái CI trên thẻ

**Milestone 3 · Phạm vi:** `frontend` · [Thẻ Trello](https://trello.com/c/e49E75p2/31-t-026-render-git-badges-commit-links-and-ci-status-on-frontend-task-cards) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-016, T-018; dữ liệu Git/CI từ T-022, T-023, T-025 và API đọc công khai.

## Task này làm gì?

Nhìn ngay trên board để biết task đã có commit/PR chưa và build có đang lỗi không; bấm để mở đúng code liên quan.

## Cần làm

1. Bổ sung `TaskCard.tsx` với số commit liên kết, biểu tượng/link PR và cảnh báo đỏ khi CI liên quan thất bại.
2. Lấy dữ liệu qua API backend; nếu cần tổng hợp liên module thì backend dùng hợp đồng public API/event, không đọc repository nội bộ chéo.
3. Mở đúng commit/PR trong tab mới; task chưa có Git không bị gắn cảnh báo sai.
4. Cập nhật thông tin sau khi có Git/CI mới, phối hợp luồng realtime hoặc tải lại dữ liệu board.

## Từ cần biết

- **Badge:** Nhãn nhỏ trên thẻ biểu diễn số lượng/trạng thái, ví dụ 3 commits hoặc CI failed.
- **CI status:** Trạng thái của lần build/test liên quan, như đang chạy, thành công hoặc thất bại.
- **Commit link / PR link:** Đường dẫn mở chi tiết code hoặc yêu cầu review trên GitHub.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Thẻ Kanban hiển thị trực quan thông tin Git
- [ ] Click vào icon mở tab mới tới đúng commit/PR trên GitHub

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Số commit và cảnh báo CI khớp dữ liệu API; thay đổi liên quan được hiển thị mà không nhầm task.
- [ ] Không lỗi giao diện khi task chưa có commit/PR/CI.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
