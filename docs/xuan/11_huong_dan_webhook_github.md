# Cấu hình webhook GitHub cho T-021

Webhook mặc định tắt. Chỉ bật sau khi cơ sở dữ liệu đã chạy migration V13, callback được phục vụ qua HTTPS và các giới hạn tại proxy đã được kiểm thử. Đây là webhook cho một repository GitHub, không phải webhook cho organization hoặc GitHub App.

1. Trong database, chuẩn bị một hàng `repositories` có `provider = 'GITHUB'`, `remote_repo_id` là ID số của repository trên GitHub và `webhook_secret_ref` là một nhãn cấu hình riêng. Ghi lại UUID nội bộ của hàng. Không lưu secret trong database. Việc đăng ký repository và phân quyền quản trị vẫn chưa có UI trong T-021; chỉ người vận hành có quyền mới nên thực hiện bước này.
2. Tạo secret ngẫu nhiên riêng cho repository từ ít nhất 32 byte ngẫu nhiên, và lưu trong secret manager của môi trường chạy. Không gửi secret qua chat hoặc commit vào Git. Secret này không phải GitHub API token và không dùng lại JWT secret.
3. Cung cấp cấu hình Spring từ secret manager hoặc file cấu hình riêng nằm ngoài repository. Ví dụ cấu trúc dưới đây là mẫu, không phải secret thật:

   ```yaml
   devflow:
     gitci:
       github-webhook:
         enabled: true
         bindings:
           "<UUID-repository-noi-bo>":
             reference: "<nhan-trung-webhook_secret_ref>"
             current: "<secret-rieng>"
             # previous: "<secret-cu>"
             # previous-valid-until: "2026-12-01T00:00:00Z"
   ```

   Ví dụ dùng `SPRING_CONFIG_ADDITIONAL_LOCATION` trỏ tới file cấu hình đã được bảo vệ, hoặc inject trực tiếp qua secret manager. Các nhãn và giá trị key phải khác nhau giữa các binding; cấu hình không hợp lệ sẽ chặn ứng dụng khởi động khi tính năng được bật. Secret được dùng nguyên văn UTF-8, không tự giải mã Base64.
4. Trong GitHub repository, vào **Settings → Webhooks → Add webhook**, đặt Payload URL là `https://<host>/api/v1/gitci/webhook/github?repositoryId=<UUID-repository-noi-bo>`, chọn `application/json`, điền cùng secret và bật SSL verification. Không đưa secret vào URL. T-021 độc lập chỉ xử lý `ping`; khi đã triển khai T-022 cùng V14/V15, bật thêm **Pushes** và **Pull requests** theo [hướng dẫn liên kết task](12_huong_dan_lien_ket_commit_task.md). `workflow_run` vẫn trả 503.
5. Kiểm tra delivery `ping` nhận 200. GitHub không tự động gửi lại mọi delivery lỗi: sau khi khắc phục, dùng chức năng redeliver trong màn hình delivery. Cùng body được nhận lại trong thời gian lưu receipt sẽ nhận 204. Receipt hoàn tất được dọn theo lô sau bảy ngày; chống replay sau thời gian này cần idempotency ở handler nghiệp vụ.

Giới hạn mặc định là 1 MiB và 16 request đồng thời mỗi instance. Nginx mẫu áp giới hạn 1 MiB và timeout đọc nhàn rỗi ba giây; proxy/TLS khác và đường truy cập trực tiếp backend phải được cấu hình bảo vệ tương ứng. Giới hạn bộ nhớ và quota trong tiến trình không phải giới hạn toàn cụm. Đối chiếu cấu hình edge và chạy thử với kích thước payload thực trước khi bật.
