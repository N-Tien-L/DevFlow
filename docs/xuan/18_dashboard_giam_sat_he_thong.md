# T-042 — Dashboard giám sát hệ thống

**Milestone 4 · Phạm vi:** `infra/` · [Thẻ Trello](https://trello.com/c/Id1JKIsX/41-t-042-build-provisioned-grafana-dashboard-for-jvm-db-pool-http-metrics) · [Tổng quan](00_nhiemvu.md)

**Phụ thuộc:** Docker Compose local; Actuator/Micrometer xuất metrics và Prometheus thu thập được.

## Task này làm gì?

Cho nhóm biết backend đang dùng bao nhiêu tài nguyên, kết nối database có nghẽn không và API phản hồi nhanh hay chậm.

## Cần làm

1. Cấu hình Prometheus đọc metrics backend; Grafana dùng đúng nguồn dữ liệu Prometheus.
2. Lưu cấu hình provisioning và dashboard JSON trong hạ tầng để dashboard tự xuất hiện khi chạy Compose.
3. Tạo biểu đồ JVM memory, CPU, HikariCP connection pool và HTTP latency P95.
4. Gửi thử request để thấy dữ liệu thay đổi, đối chiếu đơn vị, nhãn và khả năng tính P95.

## Từ cần biết

- **Metrics / Prometheus / Grafana:** Metrics là số đo vận hành; Prometheus thu thập/lưu số đo; Grafana hiển thị chúng thành biểu đồ.
- **Actuator / Micrometer:** Actuator cung cấp endpoint vận hành của Spring Boot; Micrometer thu thập và xuất số đo cho hệ giám sát.
- **JVM / HikariCP pool:** JVM là môi trường chạy Java; HikariCP pool là tập kết nối database được tái sử dụng.
- **P95:** Mốc mà 95% request có độ trễ không vượt quá; dùng theo dõi phần lớn request chậm hơn mức trung bình.
- **Provisioning:** Nạp cấu hình/dashboard tự động bằng file, tránh phải thiết lập lại bằng tay mỗi lần chạy.

## Tiêu chí hoàn thành

**Tiêu chí gốc trên Trello:**

- [ ] Chạy docker compose up hiển thị dashboard tự động mà không cần setup tay
- [ ] Biểu đồ hiển thị dữ liệu metrics sống động

**Kiểm tra thêm để nghiệm thu rõ ràng:**

- [ ] Khởi động lại môi trường vẫn có dashboard; biểu đồ có dữ liệu thật, đơn vị đúng và P95 tính được.
- [ ] Dùng dashboard theo dõi mục tiêu CRUD P95 < 200 ms trong điều kiện tải đã thống nhất; biểu đồ có dữ liệu chưa đồng nghĩa đạt hiệu năng.
- [ ] Đáp ứng [DoD chung](00_nhiemvu.md#tiêu-chí-hoàn-thành-chung) cho phần việc có thay đổi.

Nguồn phạm vi và checklist: [bản đồng bộ Trello](00_all-tasks.md). Các bước và kiểm tra thêm là diễn giải để thực hiện, không phải checklist mới đã cập nhật lên Trello.
