# Vận hành T-022 — mã task và liên kết Git

## Hành vi sau triển khai

Board cấp mã `T-001…T-9999` trong từng workspace. Mã có trên card, trong chi tiết task và JSON `taskKey` của API tạo/đọc/danh sách task. Dùng mã của **DevFlow**, không suy từ mã Trello. Cùng mã ở workspace khác không đại diện cùng task. Mã không đổi khi đổi tiêu đề/cột, và không cấp lại sau xóa. Hết 9999 mã trả `409 TASK_KEY_RANGE_EXHAUSTED`; không reset counter để tái sử dụng.

Ví dụ: task có mã T-003 → commit `feat(auth): T-003 setup jwt`. Một commit có thể nhắc `T-003 T-010`. Message không có mã mới dùng tên branch, ví dụ `feature/T-003-auth`. Mã hợp lệ nhưng không tồn tại chặn fallback. PR ưu tiên title, sau đó head branch; không lấy body/base branch hay email người gửi làm quyền hạn. Task DONE vẫn được liên kết; task dưới board archived không được liên kết mới.

T-022 lưu Git/link và receipt atomically; chưa phát các business Git events của T-023, chưa tự chuyển cột của T-024. API/badge Git hoàn chỉnh thuộc T-026. PR cũ đến muộn không ghi đè bản mới; cùng provider timestamp nhưng nội dung khác trả 409. Commit đã lưu không được đổi liên kết qua replay/branch khác. Task tạo sau commit không tự được reconcile.

## Chuẩn bị migration

Các thay đổi mới là `V14__board_task_keys.sql` và `V15__git_task_associations.sql`, sau V13 của T-021. Không sửa migration đã áp dụng. Lấy backup theo quy trình môi trường trước khi nâng cấp. Không chạy migration vào production khi chưa review/merge và kiểm tra backup.

Dry-run chỉ đọc, chạy trong PostgreSQL hiện tại trước V14:

```sql
BEGIN READ ONLY;
SELECT b.workspace_id, count(*) AS tasks, count(*) > 9999 AS blocks_migration
FROM tasks t JOIN columns c ON c.id=t.column_id JOIN boards b ON b.id=c.board_id
GROUP BY b.workspace_id ORDER BY b.workspace_id;

SELECT t.id, b.workspace_id,
       row_number() OVER (PARTITION BY b.workspace_id ORDER BY t.created_at,t.id) AS proposed_number
FROM tasks t JOIN columns c ON c.id=t.column_id JOIN boards b ON b.id=c.board_id
ORDER BY b.workspace_id,t.created_at,t.id;
ROLLBACK;
```

Có workspace trên 9999 task thì migration dừng trước khi backfill; cần thiết kế nới grammar/dải số, không xóa task để né kiểm tra. Thứ tự backfill là `created_at,id`, không phải vị trí trên board. Sau migration, đối chiếu count/unique và `flyway validate`; xem test `TaskReferenceMigrationTest`.

Counter thuộc Board và được giữ như tombstone namespace không chứa nội dung task, kể cả khi xóa hết board, để mã không được tái dùng. Các liên kết cũ từ scalar `task_id` chuyển vào association với nguồn `LEGACY` vì không có bằng chứng nguồn message/title. Link mới chỉ dùng MESSAGE/TITLE/BRANCH. Scalar chỉ là mirror khi có đúng một liên kết. PR cũ thiếu version/fingerprint phải reconcile có kiểm soát; không đoán version để overwrite.

## Bật webhook và demo thật

1. Hoàn tất cấu hình HMAC/binding/HTTPS theo [hướng dẫn T-021](11_huong_dan_webhook_github.md). Dùng secret mới nằm trong cấu hình riêng/secret manager; không gửi secret hoặc token qua chat. Cấu hình webhook chỉ chứa UUID repository nội bộ và nhãn secret; workspace lấy từ hàng `repositories`.
2. Chạy bản backend đã có V14/V15 và frontend mới. Mở DevFlow, vào workspace của binding repository, tạo task và ghi lại `taskKey` thực tế. Nếu task cũ được backfill, kiểm mã ngay trên UI/API.
3. Trong repo demo mà bạn có quyền quản trị, vào **Settings → Webhooks → webhook đang dùng → Edit**. Chọn **Let me select individual events**, bật **Pushes** và **Pull requests**, giữ Content type `application/json`, SSL verification bật. Không bật Workflow runs cho T-022. Save; ping đầu tiên trả 200, replay trả 204.
4. Trên một nhánh demo riêng, dùng mã vừa lấy (ví dụ bên dưới phải thay mã thực):

   ```powershell
   git switch -c demo/T-003-linking
   git commit --allow-empty -m "test: T-003 verify DevFlow linking"
   git push -u origin demo/T-003-linking
   ```

   Không dùng working tree có thay đổi chưa commit cho demo này. Chỉ chạy các lệnh ở repo demo đã chọn.
5. Mở PR từ nhánh đó với title chứa mã task. Đổi title sang mã task thứ hai để thử replace links. Trong GitHub webhook **Recent Deliveries**, chọn delivery tương ứng, xem response và dùng **Redeliver**. Expected: 204; count Git/link không tăng do replay, receipt COMPLETED. Task không tự đổi cột.
6. Người vận hành kiểm tra theo SHA/PR number trong `git_commits` / `pull_requests` và association tương ứng; đối chiếu UUID task với API Board. Chỉ chia sẻ event, status, SHA demo/PR URL và correlation ID; che payload, email, signature và secret. UI Git badge chưa có trong T-022.

Push vượt 100 commits hoặc khai báo thiếu danh sách trả 422; message >65536 byte UTF-8, branch/title >255 code points, >20 mã mỗi nguồn hoặc >100 mã duy nhất/delivery cũng trả 422. Body mặc định 1 MiB. Không tải `compare`/URL trong payload. Với lịch sử lớn, chia push nhỏ cho demo; backfill lịch sử Git cần công cụ nhập có phân quyền riêng, không sửa payload đã ký để redeliver.

## Khắc phục lỗi

| Code / HTTP | Hành động |
|---|---|
| 401 WEBHOOK_UNAUTHORIZED | Kiểm UUID binding, remote repo ID, nhãn cấu hình và secret GitHub khớp; không in secret ra log |
| 400 GIT_INVALID_PAYLOAD / TIMESTAMP | Kiểm event schema; delivery bị từ chối toàn bộ, không sửa JSON của delivery đã ký |
| 422 GIT_BATCH_LIMIT / GIT_INCOMPLETE_PUSH / TEXT_LIMIT / REFERENCE_LIMIT | Giảm batch/text/reference; trường hợp cần nhập lịch sử phải có thiết kế importer riêng |
| 409 GIT_COMMIT_CONFLICT | Kiểm SHA và metadata immutable; không overwrite lịch sử qua retry |
| 409 GIT_PR_VERSION_CONFLICT / VERSION_REQUIRED | Đối chiếu snapshot provider và dữ liệu legacy; không chọn ngẫu nhiên snapshot cùng timestamp |
| 503 GIT_RESOLVER_UNAVAILABLE / WEBHOOK_UNAVAILABLE | Kiểm DB/Board listener/lock contention, khắc phục rồi redeliver từ GitHub; không xem NOT_FOUND là outage |
| 503 GIT_REPOSITORY_CONFIG_INVALID | Binding PR chỉ nhận URL HTTPS github.com dạng owner/repo, không user-info/query/fragment/port tùy ý |

Callback giữ transaction timeout 6 giây, lock timeout 2 giây, statement timeout 4 giây. Mục tiêu fixture <8 giây không phải SLA upload: timeout đọc nhàn rỗi của Nginx không phải deadline toàn bộ request; slow upload qua proxy/direct backend cần đo tại môi trường triển khai. Metrics parse/resolve/persist và parsed/resolved/unresolved/duplicate dùng label hữu hạn, không dùng mã task/repo/email làm label.

## Khôi phục Trello khi báo `401 invalid key`

1. Đăng nhập tài khoản Trello có quyền trên board development. Mở [Trello Apps admin](https://trello.com/apps/admin), chọn app/Power-Up của bạn hoặc tạo app riêng.
2. Mở tab **Trello Auth**, chọn **Generate a new API Key**. Dùng link **Token** cạnh API key, xem quyền yêu cầu và chọn **Allow** để cấp token cá nhân. Luồng này theo [tài liệu Atlassian](https://developer.atlassian.com/cloud/trello/guides/rest-api/api-introduction/).
3. Sửa `.env.trello` ở thư mục gốc dự án bằng editor cục bộ, không dán credential vào chat:

   ```dotenv
   TRELLO_API_KEY=<key-moi>
   TRELLO_TOKEN=<token-moi>
   TRELLO_DEFAULT_BOARD_ID=6aa2a6d90ff05c484f131687
   TRELLO_MEMBER_ID=6aa2664e0ad1266d74a8efdc
   ```

   CLI ưu tiên biến môi trường, rồi `.agents/skills/trello-management/.credentials.json`, sau đó `.env.trello`. Nếu key cũ còn ở hai nơi ưu tiên cao hơn, cập nhật chúng hoặc xóa riêng giá trị Trello cũ để CLI dùng file mới. Không xóa cả file credential khi có cấu hình khác cần giữ.
4. Chạy từ gốc repo:

   ```powershell
   node .agents/skills/trello-management/scripts/trello.js search T-022
   ```

   Khi đọc được card, báo lại “đã sửa Trello”. Agent sẽ đọc lại AC và cập nhật tiến độ. Không đánh dấu Done trước review/merge và các gate nghiệm thu còn lại.
