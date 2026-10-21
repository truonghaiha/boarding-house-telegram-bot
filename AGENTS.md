# Hướng dẫn làm việc với dự án

## Bối cảnh và tài liệu

Đây là Telegram Bot quản lý nhà trọ cho bài tập lớn Java/OOP tại PTIT.
Ưu tiên giải pháp đơn giản, dễ demo và giải thích khi bảo vệ; tránh mở rộng ngoài yêu cầu.
Dự án dùng Java 17, Maven, Spring Boot, Spring Data JPA/Hibernate và SQLite.
Phiên bản thư viện được khai báo trong `pom.xml`.

Đọc mã nguồn và kiểm thử liên quan trước khi sửa. Tra cứu tài liệu theo phạm vi công việc:

- [README](README.md): tổng quan, vai trò, cấu hình và cách chạy.
- [Kỳ thuê và hóa đơn](docs/BILLING_CYCLES.md): điện nước, chốt kỳ, thanh toán và trả phòng.
- [Phòng và đơn giá](docs/ROOMS_AND_UTILITY_RATES.md): phòng còn chỗ, giá điện nước và dữ liệu tháng cũ.
- [Giao diện Telegram](docs/UI_NAVIGATION.md): menu, callback, phân trang, quay lại và hủy nhập liệu.

Không dùng ghi chú tiến độ cũ để suy ra hành vi hiện tại. Khi tài liệu và mã nguồn khác nhau,
đối chiếu kiểm thử và yêu cầu của người dùng trước khi thay đổi nghiệp vụ.

## Cấu trúc mã nguồn

Mã chính nằm tại `src/main/java/com/boardinghousebot`; kiểm thử nằm tại `src/test/java/com/boardinghousebot`.

| Package | Trách nhiệm |
| --- | --- |
| `bot`, `bot/command`, `bot/handler` | Nhận Telegram update, điều phối lệnh, kiểm tra quyền thao tác và giao diện. |
| `bot/ui`, `state` | Phiên menu, điều hướng và trạng thái hội thoại. |
| `domain`, `repository` | Entity, trạng thái nghiệp vụ và truy vấn dữ liệu. |
| `service` | Nghiệp vụ, phân quyền, giao dịch, thống kê và lịch sử cư trú. |
| `observer`, `scheduler` | Xử lý sự kiện, thông báo và nhắc hạn. |
| `config`, `strategy`, `util` | Cấu hình, migration, chiến lược tính phí và tiện ích dùng chung. |

Giữ cách phân lớp và các mẫu Command, State, Strategy, Observer hiện có.
Đặt tính toán, validation nghiệp vụ và ranh giới giao dịch tại service/domain; tránh dồn vào handler.

## Quy tắc nghiệp vụ cần giữ

- Phạm vi là **một nhà trọ, nhiều LANDLORD quản lý chung**. Không tự thêm `landlord_id`
  vào entity nghiệp vụ hoặc phân chia dữ liệu theo chủ trọ.
- ADMIN quản trị tài khoản và hệ thống; LANDLORD quản lý nghiệp vụ nhà trọ;
  MEMBER chỉ truy cập dữ liệu thuộc quyền của mình. ADMIN được xác định bởi `TELEGRAM_ADMIN_ID`.
- Kiểm tra quyền ở cả lệnh, callback, luồng nhập liệu và truy cập bản ghi cụ thể.
  Callback cũ và dữ liệu điều hướng không được dùng để bỏ qua kiểm tra quyền hiện tại.
- Giữ lịch sử cư trú trong `room_stays` và quyền xem dữ liệu lịch sử sau khi rời/chuyển phòng.
  Không suy đoán thời gian cư trú chưa được ghi nhận; giữ giới hạn xem hợp đồng theo người đứng tên.
- Luồng điện nước/hóa đơn hiện tại theo kỳ thuê; bảo toàn dữ liệu tháng cũ.
  Không khôi phục quy tắc cũ như hạn thanh toán cố định ngày 15 hoặc nhập số tiền thanh toán từng phần
  vào giao diện hiện tại. Chi tiết áp dụng theo tài liệu kỳ thuê.
- Giữ thông báo trong cùng giao dịch nghiệp vụ khi luồng hiện tại yêu cầu;
  chỉ gửi Telegram sau commit và giữ cơ chế chống trùng theo từng người nhận.
- Giữ quy tắc gửi tin nhắn mới, quay lại chỉ đọc và hủy wizard trong tài liệu giao diện.
  Thay đổi giao diện không được làm mất nghiệp vụ hóa đơn/thanh toán.

## Bảo toàn dữ liệu và cấu hình

- `boarding_house.db` chứa dữ liệu đang dùng. Không reset, xóa hoặc tạo lại database khi sửa/dọn dự án.
  Giữ bản sao lưu database, `.env` và cấu hình IDE.
- Không đưa token, nội dung `.env` hoặc thông tin bí mật vào mã nguồn, tài liệu, log hay commit.
  Dùng `.env.example` để mô tả cấu hình; không tự thay token thật.
- Chỉ thay schema khi thuộc phạm vi yêu cầu. Migration phải chạy lại an toàn và bảo toàn dữ liệu.
- Xử lý quan hệ LAZY bằng truy vấn fetch hoặc transaction phù hợp; không đổi hàng loạt sang EAGER.
  Tránh vòng lặp giữa các entity trong `toString`, `equals` và `hashCode`.
- Giữ các thay đổi đang có của người dùng; không hoàn tác hoặc xóa mã đang phát triển chỉ để dọn gọn.
  `target/` là kết quả build có thể tạo lại.

## Kiểm tra và cập nhật tài liệu

- Với thay đổi mã nguồn/cấu hình, chạy `mvn clean test` hoặc `mvn compile test-compile test` bằng Java 17.
  Kiểm thử dùng SQLite trong bộ nhớ, không đọc `.env` và không kết nối bot Telegram thật.
  Không chạy ứng dụng trên database thật chỉ để xác minh một bản sửa.
- Với thay đổi chỉ ở tài liệu, kiểm tra liên kết và `git diff --check`; không cần chạy lại toàn bộ kiểm thử.
- Chỉ báo kiểm thử thành công khi đã chạy và có kết quả thực tế; nêu rõ nếu chưa chạy hoặc bị chặn.
- Khi đổi hành vi, cập nhật README hoặc tài liệu chức năng tương ứng. Giữ file này ngắn gọn,
  chỉ lưu hướng dẫn lâu dài; không thêm nhật ký công việc, số lượng test hay cây liệt kê từng class.
