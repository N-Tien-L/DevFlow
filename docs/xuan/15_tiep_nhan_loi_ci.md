# T-025 — Nhận lỗi CI và chuyển dữ liệu cho AI

**Milestone 3 · Phạm vi:** `gitci-impl, common` · [Thẻ Trello](https://trello.com/c/J7oKhrJB/30-t-025-implement-ci-failure-webhook-receiver-and-publish-cifailuredetected) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** T-020, T-021; thống nhất CiFailureDetectedEvent và nguồn log với Tiến.

## Task này làm gì?

Ghi nhận lần build/test thất bại và đưa log cho module AI để Tiến triển khai phần tóm tắt nguyên nhân.

## Cần làm

1. Nhận webhook `workflow_run` khi GitHub Actions kết thúc thất bại; xác thực qua luồng webhook an toàn.
2. Ghi pipeline/repository/commit liên quan, lấy log lỗi và lưu bản ghi trong `ci_failures`.
3. Phát `CiFailureDetectedEvent` gồm pipelineId, thông tin repo/commit và raw log hoặc tham chiếu log theo hợp đồng.
4. Không mặc định webhook có toàn bộ log: cần lấy từ GitHub API khi cần, với quyền phù hợp; xử lý lỗi lấy log và không lưu lộ secret trong log.

## Từ cần biết

- **GitHub Actions / workflow_run:** Dịch vụ chạy build/test tự động của GitHub; workflow_run là thông báo về một lần chạy workflow.
- **Raw log / error trace:** Nhật ký lỗi chưa tóm tắt, thường gồm thông báo và chuỗi lời gọi dẫn tới lỗi.
- **LLM:** Mô hình ngôn ngữ dùng để phân tích/tóm tắt; phần gọi mô hình thuộc module ai do Tiến phụ trách.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Lưu thông tin thất bại vào bảng ci_failures
- [ ] Phát event ci.failure_detected sẵn sàng cho module AI xử lý

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Thử pipeline thất bại và thành công: chỉ trường hợp thất bại tạo bản ghi lỗi/event tương ứng.
- [ ] Có log đầu vào dùng được cho AI; khi lấy log lỗi, hệ thống ghi nhận rõ tình trạng để xử lý tiếp.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

## Lưu ý

GitHub có API tải log workflow; phần xác thực API này khác webhook secret. Xem [tài liệu workflow runs](https://docs.github.com/en/rest/actions/workflow-runs#download-workflow-run-logs).

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
