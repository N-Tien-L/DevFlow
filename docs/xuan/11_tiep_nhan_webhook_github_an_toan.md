# T-021 — Nhận webhook GitHub an toàn

**Milestone 3 · Phạm vi:** `gitci-impl` · [Thẻ Trello](https://trello.com/c/SJbiORpo/26-t-021-implement-webhook-ingestion-controller-with-hmac-sha-256-validation) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-020; repository GitHub và webhook secret cấu hình cho môi trường.

## Task này làm gì?

Tạo cửa nhận thông báo từ GitHub và xác minh thông báo hợp lệ trước khi xử lý dữ liệu Git/CI.

## Cần làm

1. Tạo `GitWebhookController` nhận `POST /api/v1/gitci/webhook/github`.
2. Tính HMAC SHA-256 từ nguyên bản body request bằng webhook secret; đối chiếu header `X-Hub-Signature-256` trước khi xử lý.
3. Từ chối chữ ký thiếu/sai với HTTP 401; chỉ chuyển payload hợp lệ tới xử lý nghiệp vụ.
4. Giữ secret trong cấu hình môi trường, không ghi vào log. Phạm vi ticket này là GitHub; GitLab phải có cách xác thực riêng theo nhà cung cấp.

## Từ cần biết

- **Webhook:** GitHub chủ động gửi một yêu cầu HTTP tới DevFlow khi có sự kiện, thay vì DevFlow liên tục hỏi GitHub.
- **HMAC SHA-256 / signature:** Cách tạo mã kiểm tra từ nội dung và khóa bí mật; signature là chữ ký được đối chiếu để phát hiện nguồn không hợp lệ/nội dung bị sửa.
- **Webhook secret / payload:** Secret là khóa bí mật dùng chung giữa GitHub và backend; payload là dữ liệu sự kiện trong body.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Từ chối request có signature không hợp lệ với mã 401 Unauthorized
- [ ] Xác thực thành công cho phép xử lý tiếp payload

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Kiểm thử body gốc có chữ ký đúng, body bị sửa và request thiếu chữ ký; trường hợp bị từ chối không ghi dữ liệu/phát event.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

## Lưu ý

Dùng đúng body gốc, không parse rồi serialize lại trước khi kiểm chữ ký. Xem [hướng dẫn GitHub](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries).

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
