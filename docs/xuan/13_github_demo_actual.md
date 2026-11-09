# T-023 — Nghiệm thu GitHub thật

**Ngày:** 09/11/2026, Asia/Saigon. Người dùng đã cho phép demo GitHub T-023 sau khi triển khai và quality gate 741 test/build/lint PASS. Repo [lvXuankg/repo-test-DevFlow](https://github.com/lvXuankg/repo-test-DevFlow), base riêng `demo/t023-base-20261109-171949`. [Bằng chứng JSON đã ẩn dữ liệu](13_phat_su_kien_git_github_actual.json) · [Actual tổng](13_phat_su_kien_git_actual.md).

## Môi trường và phạm vi

Dùng PostgreSQL và hai backend staging riêng của T-023, không dùng DB làm việc Compose. Chỉ endpoint webhook được proxy qua tunnel HTTPS; đường `/` và `/actuator/health` công khai đều 404. Token GitHub lấy qua Git Credential Manager, secret dùng cấu hình private hiện có; không in/lưu token, secret, signature, email hay raw payload trong evidence.

Tạm đổi callback của webhook demo, events push/pull_request, JSON, SSL verification bật. Sau nghiệm thu đã khôi phục URL/active/events ban đầu và đọc lại để xác nhận. Các container staging/tunnel đã dừng, dữ liệu giữ lại để tiếp tục. Không thay đổi default branch hoặc merge PR triển khai DevFlow.

**Kết quả cuối:** REL-03 và REL-04 PASS. Ca merge đã được nghiệm thu lại bằng [PR #4](https://github.com/lvXuankg/repo-test-DevFlow/pull/4), nhận đúng closed/merged=true và redelivery 204, một event/Notification cho mỗi logical activity. Không cần người dùng tìm thêm delivery #2.

## Expected → Actual

Baseline trước GitHub: 7 PUBLISHED và 4 Notification từ fixture HTTP tổng hợp đã nghiệm thu riêng; không tính baseline là event GitHub thật.

| Thao tác | Actual |
|---|---|
| Push commit mới có T-001 T-002 và [mở PR #2](https://github.com/lvXuankg/repo-test-DevFlow/pull/2) có title T-001, worker tắt | GitHub trả 204; thêm 2 commit PENDING + 1 opened PENDING, Notification giữ 4 |
| Bật worker và restart hai backend | Cùng 3 row chuyển PUBLISHED, tổng 10; Notification tăng lên 5, không cần webhook mới |
| Đối chiếu payload thật | Hai task UUID resolve đúng A/B; repo/PR URL chuẩn; opened đến task A; occurredAt đúng created_at GitHub |
| Sửa title PR #2 sang T-002 | GitHub edited 204; PR thay association sang B theo pipeline, event/Notification không tăng |
| Merge PR #2 vào base demo riêng | GitHub API xác nhận merged=true, merged_at 17:21:33. Push của merge commit tới backend 204, thêm một commit event. **Chưa có delivery PR closed/merged=true trong danh sách API tại lần kiểm tra lượt đầu; DB PR #2 vẫn OPEN, chưa có business merged event của PR này** |
| [Mở PR #3](https://github.com/lvXuankg/repo-test-DevFlow/pull/3), sau đó đóng không merge | Opened 204 tạo một opened event/Notification đến A. Delivery closed sau lần đóng lại có suffix `40425984`, HTTP 204, payload number=3/merged=false; DB chuyển CLOSED, không thêm merged event |
| Reopened rồi đóng lại PR #3 theo EVT-07 | Thao tác chỉ trên PR dữ liệu giả/base demo; PR cuối CLOSED, chưa merge. Không tạo opened/merged business event bổ sung |
| Redeliver push và opened PR #2 | Cả hai 204, không tăng event/link/Notification; opened cũ không ghi đè title/link mới |

Kết thúc lượt đầu: **12 PUBLISHED, 6 Notification**, tức **5 event và 2 Notification mới từ các delivery GitHub đã nhận**. Không PENDING/PROCESSING/FAILED, không receipt chưa COMPLETED, task column/version giữ nguyên. Không suy ra đã nhận đầy đủ mọi event từ việc backlog rỗng.

## Nghiệm thu lại ca merge — PASS

Ảnh người dùng xác nhận closed duy nhất ở lượt đầu thuộc PR #3. Agent đã dùng quyền demo hiện có để tạo [PR #4](https://github.com/lvXuankg/repo-test-DevFlow/pull/4) trên cùng base demo riêng, title lúc mở T-001 rồi sửa sang T-002 trước merge. Merge thực hiện bằng GitHub GraphQL với expected head SHA, không thay đổi default branch.

| Thao tác | Actual |
|---|---|
| Lần restart staging mới | Startup khoảng 23 giây vượt cửa sổ health probe đầu. Callback ban đầu còn URL tunnel cũ nên opened #4 có HTTP 530. Đã xác minh cả hai backend healthy, cập nhật đúng URL mới và redeliver opened về 204 |
| Opened #4 và edited A → B | Một opened event cho A, PR association hiện tại B; edited không phát business event |
| Merge #4 | Provider mergedAt `2026-11-09T10:43:58Z`; delivery closed/merged=true suffix `03221504`, HTTP **204**, 0,37 giây |
| Payload merged #4 | Đúng task B, repo/URL canonical, occurredAt bằng chính xác provider merged_at; chỉ một outbox merged |
| Redeliver merged #4 | Delivery suffix `90352640`, HTTP **204**, 0,36 giây; bus logical enqueue không tăng và Notification không trùng |
| Kết thúc | **17 PUBLISHED, 8 Notification**; so với baseline tổng hợp trước GitHub là **10 event và 4 Notification mới từ GitHub thật**. 0 pending/processing/failed/incomplete receipt; task column/version nguyên vẹn |

Bảng counts sau merge, sau request replay và sau GitHub xác nhận redelivery giống hệt nhau. Trong ba merged outbox hiện có, hai thuộc fixture tổng hợp trước demo, một thuộc PR #4 thật; không tính fixture thành bằng chứng GitHub.

REL-03 đã đủ bằng chứng ba typed event qua producer thật và Notification; REL-04 đã xác nhận 3 PENDING từ GitHub khi worker dừng, rồi restart/bật hai replica xả đủ. Quality gate code giữ nguyên 741 test, build/lint PASS; lượt này chỉ thay runner private/evidence, không đổi production source nên không chạy lại suite không liên quan.

## Quan sát lịch sử và bàn giao

Delivery closed/merged=true của PR #2 vẫn chưa được thấy trong API/UI tại lần kiểm tra tương ứng; PR #2 fixture trong DB còn OPEN, không tự vá dữ liệu để bù evidence. Đây là quan sát provider riêng của PR #2, không còn là điều kiện chờ người dùng vì ca tương đương đã PASS bằng PR #4. Chưa kết luận nguyên nhân GitHub không phát delivery đó. [GitHub troubleshooting](https://docs.github.com/en/webhooks/testing-and-troubleshooting-webhooks/troubleshooting-webhooks#webhook-deliveries-are-not-immediate).

Computer Use không hỗ trợ URL-policy enforcement cho Cốc Cốc/Windows hiện tại. Người dùng đã đối chiếu UI bằng ảnh; các thao tác/kiểm chứng tiếp theo thực hiện qua API đã được cho phép, không bỏ qua rào chắn browser.

Webhook đã khôi phục URL/active/events ban đầu và đọc lại xác minh. Các container staging/tunnel đã dừng và giữ dữ liệu. Các PR #2/#4 chỉ merge vào base demo riêng, PR #3 CLOSED không merge; nhánh default và PR triển khai DevFlow không đổi. Không cần người dùng cung cấp thêm thông tin cho demo T-023. Peer review/merge code triển khai vẫn theo workflow dự án.

Người dùng đã cung cấp headers/payload đối chiếu của PR #4 (GUID suffix `0c8d73a03cb2`): action closed, merged=true, repository/base id 1442558277, merged_at `2026-11-09T10:43:58Z` khớp tuyệt đối evidence API/outbox đã kiểm tra. Không chép signature hoặc raw payload vào artifact.
