# T-020 — Thiết kế dữ liệu Git và CI

**Milestone 3 · Phạm vi:** `:app, gitci-impl` · [Thẻ Trello](https://trello.com/c/BK5P5I6g/25-t-020-setup-flyway-migration-for-git-ci-schema-v3gitcischemasql) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** Migration V1/V2; mô hình dữ liệu Git/CI đã thống nhất.

## Task này làm gì?

Lưu kho code, commit, Pull Request, lần chạy pipeline và lỗi CI để liên kết với task và truy vết khi có vấn đề.

## Cần làm

1. Viết `V3__gitci_schema.sql` tạo `repositories`, `git_commits`, `pull_requests`, `ci_pipelines`, `ci_failures`.
2. Lưu thông tin kho code, mã commit, URL PR, trạng thái pipeline và dữ liệu lỗi theo thiết kế.
3. Thiết lập khóa ngoại nội bộ Git/CI, ràng buộc và index để truy vấn theo repo/commit/pipeline.
4. Tham chiếu task/workspace của module khác bằng ID; không tạo khóa ngoại hay quan hệ JPA xuyên module.

## Từ cần biết

- **Flyway V3:** Bản thay đổi database thứ ba, bổ sung bảng Git/CI sau các migration nền tảng và Kanban.
- **Repository / commit SHA:** Repository ở đây là kho mã nguồn Git; SHA là mã định danh một commit.
- **Pull Request (PR):** Yêu cầu review và gộp thay đổi code từ nhánh này vào nhánh khác.
- **CI / pipeline:** CI tự động build/test khi code thay đổi; pipeline là chuỗi bước thực hiện và kết quả của lần chạy.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Flyway migrate V3 thành công trên PostgreSQL
- [ ] Các bảng lưu vết Git hoạt động trơn tru

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Lưu và đọc lại được commit, PR, pipeline và lỗi liên quan; nâng cấp DB có V2 không mất dữ liệu board.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
