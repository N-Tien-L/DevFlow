# T-045 — Tài liệu, video và demo nghiệm thu

**Milestone 4 · Phạm vi:** `docs/` · [Thẻ Trello](https://trello.com/c/5oXz0M56/44-t-045-compile-course-final-defense-deliverables-demo-video-slides-dod-report) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** Các milestone trước; tính năng Auth, AI/MCP, Notification và frontend cloud của Tiến.

## Task này làm gì?

Chuẩn bị bằng chứng sản phẩm hoạt động và giải thích được kiến trúc khi bảo vệ hai học phần.

## Cần làm

1. Cùng Tiến soạn slide: vấn đề, giải pháp, kiến trúc, ranh giới module, Event Bus, Git/CI và MCP.
2. Quay demo hai người cùng xem board: tạo/kéo task → đồng bộ → commit/PR/merge → tự đổi cột → CI lỗi → AI tóm tắt → tra cứu trong IDE.
3. Tổng hợp báo cáo DoD với bằng chứng test, boundary check, dashboard và URL chạy thật.
4. Xuân chuẩn bị phần Kanban, Git/CI, Docker/Grafana và kết quả tích hợp; Tiến điều phối phần trình bày chung và phần Auth/AI/MCP/Notification.

## Từ cần biết

- **DoD — Definition of Done:** Bộ điều kiện chung để công việc được coi là hoàn thành, gồm tính năng, kiểm thử, chất lượng và review.
- **Modular Monolith:** Một backend triển khai chung nhưng chia module độc lập, mỗi module có trách nhiệm và ranh giới rõ.
- **MCP / AI coding agent:** MCP là giao thức để công cụ AI gọi năng lực dự án; coding agent là trợ lý lập trình trong IDE như Cursor/Claude Code.
- **Nghiệm thu:** Đối chiếu sản phẩm và bằng chứng với yêu cầu đã thống nhất để xác nhận hoàn thành.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Slide nêu bật các điểm sáng kiến trúc hướng dịch vụ và MCP Server
- [ ] Video demo thể hiện trọn vẹn luồng tương tác giữa 2 thành viên và AI coding agent

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Có báo cáo DoD, link sản phẩm và bằng chứng kiểm thử/giám sát; ghi rõ tiêu chí đạt và phần còn hạn chế.
- [ ] Slide, video, báo cáo và kịch bản demo truy cập được; cả hai giải thích được luồng phối hợp module.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
