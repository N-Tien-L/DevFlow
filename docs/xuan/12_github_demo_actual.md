# T-022 — Nghiệm thu GitHub thật

**Ngày:** 08/11/2026, Asia/Saigon. Các delivery dưới đây xảy ra khoảng 23:28–23:30. Không chứa payload, signature, token, email hoặc secret.

## Môi trường và thao tác

- Repo demo: [lvXuankg/repo-test-DevFlow](https://github.com/lvXuankg/repo-test-DevFlow).
- Nhánh riêng: `demo/t022-links-20261108-232845`; thêm một file Markdown dữ liệu giả trong `devflow-demo/`, không sửa nhánh main.
- Commit: `d00b81d3d90ba93a94f40c2d961a0fdcc6499b52`, message `test: T-001 verify DevFlow task linking`.
- [PR demo #1](https://github.com/lvXuankg/repo-test-DevFlow/pull/1) ở trạng thái draft, OPEN, chưa merge. Title ban đầu T-001, sau sửa sang T-002.
- Docker backend/frontend đã rebuild và khởi động lại với cấu hình webhook riêng. Health UP; Flyway V14/V15 áp dụng thành công. Bốn task được backfill T-001…T-004, không mất task.
- Đã lấy backup PostgreSQL trước nâng cấp: `.env.t022-backup-20261108-232213.dump`, 70.060 byte, git-ignored; nội dung không đưa vào report/Git.
- Secret mới được sinh trong file cấu hình riêng và đồng bộ vào đúng webhook GitHub. Dùng Git Credential Manager hiện có theo quyền tài khoản, không in/chép token ra artifact.
- Callback cũ không phân giải DNS. Callback mới đã được cấu hình trực tiếp trên GitHub với events `push,pull_request`, JSON và SSL verification bật:

  `https://pockets-family-causing-identification.trycloudflare.com/api/v1/gitci/webhook/github?repositoryId=5165bd7f-15d5-4923-b0b4-c0e356cd3759`

Hai container phụ `devflow-t022-tunnel` và `devflow-t022-webhook-gateway` đang chạy. Gateway chỉ proxy POST đúng callback qua frontend Nginx tới backend; `/` và `/actuator/health` trên hostname công khai trả 404. HMAC sai tại callback trả 401. Cấu hình và state demo nằm ở `.env.t022-*` git-ignored, không chứa trong commit source.

## Expected → Actual

| Thao tác | Delivery ID / HTTP | Actual |
|---|---|---|
| GitHub ping thật | 3852919206238486528 / 200 | Secret/binding/HTTPS hoạt động |
| Tạo nhánh demo | 3852919229900652544 / 204 | Không dựng commit giả từ branch-only event |
| Push commit T-001 | 3852919231871975424 / 204 | Một commit, một link MESSAGE tới T-001; scalar mirror khớp |
| PR opened title T-001 | 3852919236934500352 / 204 | Một PR, một link TITLE tới T-001 |
| PR edited title T-002 | 3852919320495538176 / 204 | PR vẫn một row; link T-001 được thay bằng T-002; mirror khớp |
| Redeliver push | 3852919411528695808 / 204 | Không tăng commit/link/receipt |
| Redeliver PR opened cũ sau edited | 3852919413558755328 / 204 | Không tăng row; PR giữ liên kết T-002, không khôi phục T-001 |

Sau các thao tác: **1 commit, 1 commit link, 1 PR, 1 PR link**. Receipts tăng từ 4 có sẵn lên 9 sau các delivery mới, giữ 9 sau hai redelivery; không có receipt chưa COMPLETED. Snapshot cả bốn task trước/sau có cùng ID, column và version 0. Số notification Git `PR_OPENED/PR_MERGED` là 0. T-022 không tự chuyển cột.

Thời gian GitHub báo cho các delivery trên lần lượt 0,72 / 0,40 / 0,46 / 0,39 / 1,20 / 0,44 / 0,40 giây. Đây là các mẫu callback thật khác loại, không phải benchmark P95/SLA.

## Đo upload có giới hạn — IT11

Client gửi Content-Length 65.536 nhưng cố ý không hoàn tất body; mọi request dùng HMAC sai và client có deadline tự hủy. Không tạo Git/link/receipt. Đây là probe 6 kết nối, không phải load test.

| Đường đi | Idle | Gửi nhỏ giọt khoảng mỗi giây |
|---|---|---|
| Direct backend :8080 | Chưa có response sau 6,11s; client hủy | Chưa có response sau 10,08s; client hủy |
| Frontend Nginx :3000 | Server đóng kết nối sau khoảng 3,01s, không có HTTP status line đọc được | Chưa có response sau 10,08s; client hủy |
| Cloudflare HTTPS → gateway → Nginx | Chưa có response sau 6,14s; client hủy | Chưa có response sau 10,07s; client hủy |

**Gap được xác nhận:** idle timeout 3s không tạo absolute upload deadline. Giới hạn transaction chỉ bắt đầu sau khi đọc/validate body. Không thể tuyên bố callback luôn kết thúc trong 8s với client gửi nhỏ giọt. Quick Tunnel này chỉ phục vụ demo; thiết kế absolute upload deadline/edge connection controls và đo tải vẫn là gate hardening trước rollout production.

## Bàn giao

IT13 demo thật đã PASS. IT11 đã có số đo và gap được ghi rõ. [Actual tổng hợp](12_lien_ket_commit_va_task_actual.md) giữ các trường hợp fault injection/profiling khác chưa đủ coverage.

PR nền [T-021 #32](https://github.com/N-Tien-L/DevFlow/pull/32) vẫn OPEN, chưa merge vào develop. Không tự merge PR nền hoặc nhánh protected. PR triển khai T-022 phải ghi rõ dependency này và được peer review trước khi Done.
