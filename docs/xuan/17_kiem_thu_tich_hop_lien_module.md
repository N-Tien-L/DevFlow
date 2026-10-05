# T-041 — Kiểm thử luồng liên module

**Milestone 4 · Phạm vi:** `:app` · [Thẻ Trello](https://trello.com/c/Nec2nAGv/40-t-041-author-multi-module-integration-test-suite-validating-in-process-event-bus) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** Milestone 1–3; Notification T-040 của Tiến cho chu trình có thông báo.

## Task này làm gì?

Chứng minh các phần đã ghép chạy đúng từ đầu đến cuối, thay vì chỉ từng module chạy tốt khi đứng riêng.

## Cần làm

1. Viết integration test trong `app` cho chuỗi webhook push → Git event → board đổi trạng thái → notification được lưu.
2. Chuẩn bị database và dữ liệu thử rõ ràng; kiểm tra kết quả lưu thực tế, không chỉ kiểm tra có gọi hàm.
3. Thêm ca chữ ký sai, không tìm thấy task và webhook lặp để kiểm chứng không sinh tác động sai.
4. Chạy Gradle check và kiểm tra ranh giới module; phối hợp Tiến khi lỗi nằm ở consumer Notification/AI.

## Từ cần biết

- **Integration test:** Kiểm thử nhiều thành phần làm việc cùng nhau, ví dụ controller, Event Bus và database.
- **In-process Event Bus:** Bus sự kiện chạy trong cùng tiến trình backend, không cần Kafka/RabbitMQ.
- **Test fixture:** Dữ liệu chuẩn bị cho bài test, ví dụ user, workspace, task và payload webhook mẫu.
- **Module boundary:** Ranh giới trách nhiệm/dependency; không được truy cập internals của module khác.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] ./gradlew check chạy pass 100% tất cả test
- [ ] Kiểm thử không có vi phạm ranh giới module (:verifyModuleBoundaries)

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Có bằng chứng test kiểm tra đúng task đổi cột và thông báo lưu cho đúng người; ca bị từ chối không làm đổi dữ liệu.
- [ ] Bài test có thể chạy lại với dữ liệu độc lập và kết quả ổn định.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
