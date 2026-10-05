# T-043 — Triển khai backend và database lên cloud

**Milestone 4 · Phạm vi:** `:app, infra/` · [Thẻ Trello](https://trello.com/c/KQhJKHm6/42-t-043-setup-multi-stage-dockerfile-and-deploy-backend-postgresql-to-paas) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** Backend đã kiểm thử; tài khoản PaaS; phối hợp Tiến với frontend T-044 và cấu hình production.

## Task này làm gì?

Đưa backend DevFlow ra Internet cùng database lưu dữ liệu bền vững, để frontend và webhook GitHub truy cập được.

## Cần làm

1. Tạo/tối ưu Dockerfile multi-stage: giai đoạn build tạo app.jar, giai đoạn chạy chỉ chứa phần cần thiết.
2. Deploy backend lên PaaS đã chọn và tạo PostgreSQL managed; cấu hình kết nối database, JWT, webhook và các biến môi trường cần dùng.
3. Cho Flyway khởi tạo/nâng cấp DB; kiểm tra ứng dụng và `/actuator/health` qua HTTPS.
4. Phối hợp Tiến cấu hình frontend gọi API, WebSocket và webhook tới backend public; kiểm tra dữ liệu còn sau restart.

## Từ cần biết

- **Dockerfile / image / container:** Dockerfile mô tả cách đóng gói; image là gói ứng dụng; container là phiên chạy của image.
- **Multi-stage build:** Tách môi trường biên dịch và môi trường chạy, giúp image chạy nhỏ và ít thành phần dư.
- **PaaS / managed PostgreSQL:** PaaS là nền tảng vận hành ứng dụng; managed PostgreSQL là database do nhà cung cấp quản lý hạ tầng.
- **Environment variable / healthcheck:** Biến môi trường là cấu hình theo môi trường chạy; healthcheck báo tình trạng ứng dụng.
- **HTTPS:** Kết nối HTTP có mã hóa, dùng cho URL công khai của ứng dụng.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Backend online với HTTPS public URL
- [ ] Endpoint /actuator/health trả trạng thái UP

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] API và WebSocket hoạt động từ frontend public; webhook hợp lệ tới được backend.
- [ ] Dữ liệu còn sau restart; secret không nằm trong repository/image; có ghi lại cách deploy và biến cấu hình.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

## Lưu ý

Trello gán T-043 cho Xuân dù phân công tổng quan để Tiến dẫn phần production. Xuân thực hiện backend/DB của ticket, Tiến điều phối release và frontend; hai người thống nhất cấu hình khi ghép.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
