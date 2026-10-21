# Boarding House Management Telegram Bot

Ứng dụng Java/Spring Boot quản lý nhà trọ qua Telegram, phục vụ bài tập lớn OOP.
Phạm vi: **một nhà trọ, nhiều LANDLORD cùng quản lý dữ liệu chung**.

## Vai trò và quyền

| Vai trò | Chức năng |
| --- | --- |
| ADMIN | Xem/tìm kiếm người dùng theo tên, username hoặc Telegram ID; cấp/thu hồi LANDLORD; xem nhật ký, thống kê tài khoản và thông báo cá nhân. Không thao tác nghiệp vụ nhà trọ. |
| LANDLORD | Quản lý phòng, người thuê, hợp đồng, điện nước, hóa đơn, ghi nhận thanh toán, xử lý bảo trì; xem tiền thực thu trong tháng, công nợ và tỷ lệ lấp đầy. Các LANDLORD cùng quản lý một nhà trọ. |
| MEMBER | Xem phòng và bạn cùng phòng; xem hợp đồng đứng tên mình; xem hóa đơn/điện nước thuộc phạm vi thuê được ghi nhận; báo sự cố và theo dõi yêu cầu của mình; đọc thông báo cá nhân. |

- Người mới mặc định là MEMBER; Telegram ID khớp `TELEGRAM_ADMIN_ID` được cấp ADMIN.
- Chỉ ID khớp `TELEGRAM_ADMIN_ID` mới có quyền ADMIN, kể cả khi database còn lưu ADMIN của ID cũ.
- Khi khởi động, bot tự đưa các ADMIN cũ về MEMBER và cấp ADMIN cho tài khoản cấu hình nếu đã đăng ký. ID mới chưa đăng ký sẽ được cấp ADMIN khi tương tác lần đầu. Việc đồng bộ được ghi nhật ký và hủy hội thoại đang nhập; các LANDLORD khác giữ nguyên quyền.
- ADMIN dùng `/promote <Telegram_ID>` và `/revoke <Telegram_ID>`; không thể đổi quyền ADMIN bằng hai lệnh này.
- Cần kết thúc hợp đồng ACTIVE và đưa người thuê khỏi phòng trước khi cấp LANDLORD. Thu hồi LANDLORD không tự xếp người đó vào phòng.
- Các thao tác đổi role hủy luồng nhập liệu đang chờ của tài khoản bị thay đổi.
- Menu, lệnh trực tiếp, callback và các luồng nhập liệu đều kiểm tra quyền. Xem chi tiết theo ID còn kiểm tra quyền trên bản ghi cụ thể.

## Phạm vi lịch sử thuê

Bảng `room_stays` lưu thời điểm vào/rời phòng. Xếp phòng, chuyển phòng và rời phòng cập nhật lịch sử trong cùng giao dịch; chuyển phòng không xóa lịch sử cũ.

- Hóa đơn đứng tên MEMBER vẫn được MEMBER đó xem sau khi rời phòng.
- Người ở cùng chỉ được xem hóa đơn chung nếu kỳ tháng giao với khoảng cư trú ghi nhận và hóa đơn được tạo từ thời điểm họ vào phòng trở đi.
- Điện nước áp dụng cùng quy tắc theo kỳ tháng và thời điểm tạo bản ghi.
- Quy tắc này áp dụng cả danh sách, màn hình chi tiết và người nhận thông báo hóa đơn/điện nước/thanh toán.
- Sau khi rời/chuyển phòng, MEMBER vẫn xem được lịch sử có quyền; không được xem các kỳ thuộc khoảng thời gian chưa ở phòng.
- Hợp đồng đầy đủ chỉ được xem bởi người đứng tên hoặc LANDLORD. Thông báo hợp đồng mới cho người cùng phòng chỉ chứa thông tin chung của phòng.

**Dữ liệu cũ:** Khi khởi động, JPA bổ sung bảng `room_stays`; ứng dụng bổ sung khoảng cư trú cho các MEMBER hiện đang có phòng một lần. Người đứng tên hợp đồng ACTIVE khớp phòng được lấy mốc bắt đầu từ hợp đồng; người ở cùng chưa có bằng chứng thời gian vào phòng được lấy mốc nâng cấp. Không tự suy đoán lịch sử chưa được ghi nhận. Hóa đơn đứng tên người thuê cũ vẫn được giữ và truy cập theo người đứng tên.

## Công nghệ và cấu trúc

- Java 17+, Spring Boot 3.3.4, Maven.
- Spring Data JPA/Hibernate, SQLite, Telegram Bot API.
- `bot/handler`: giao diện Telegram và xử lý lệnh.
- `service`: nghiệp vụ, phân quyền, thống kê và lịch sử cư trú.
- `domain`, `repository`: mô hình dữ liệu và truy vấn.
- `state`, `strategy`, `observer`, `scheduler`: luồng hội thoại, tính điện nước, thông báo và nhắc hạn.
- Command, State, Strategy và Observer là các mẫu thiết kế chính.

## Cấu hình và chạy

Tạo `.env` theo `.env.example`, điền `TELEGRAM_BOT_USERNAME`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ADMIN_ID`.
Có thể cấu hình đầu mối liên hệ công khai của nhà trọ bằng `BOARDING_HOUSE_CONTACT_NAME`,
`BOARDING_HOUSE_CONTACT_PHONE` và `BOARDING_HOUSE_CONTACT_TELEGRAM`. Nếu để trống, bot chỉ nhắc MEMBER liên hệ trực tiếp với chủ trọ.
Không đưa token thật hoặc `.env` lên Git.

`TELEGRAM_ADMIN_ID` bắt buộc là một Telegram ID nguyên dương hợp lệ. Để chuyển ADMIN, thay ID trong `.env` rồi khởi động lại bot; không cần sửa database. Nếu biến môi trường hệ điều hành hoặc tham số chạy cũng đặt `TELEGRAM_ADMIN_ID`/`telegram.admin-id`, cần cập nhật chúng để tránh ghi đè cấu hình `.env`.

Nếu ID bị thiếu/sai hoặc tài khoản ADMIN mới còn phòng/hợp đồng ACTIVE, ứng dụng dừng khởi động với lỗi rõ ràng trước khi nhận tin nhắn Telegram. Không chuyển quyền dở dang. Với người đang thuê, giữ ID ADMIN hiện tại để chạy bot, xử lý hợp đồng và đưa người đó ra khỏi phòng, rồi đổi cấu hình sang ID mới và khởi động lại.

```sh
mvn spring-boot:run
```

Lệnh cơ bản: `/start`, `/menu`, `/help`, `/cancel`.
ADMIN có thể dùng `/search_users <từ khóa>` hoặc nút Tìm kiếm.

## Kiểm thử

```sh
mvn test
```

Kiểm thử dùng SQLite trong bộ nhớ và tắt kết nối bot Telegram; không dùng database thật hoặc token trong `.env`.
Lượt xác minh ngày 21/10/2026 với Java 17, chạy `mvn compile test-compile test`: **662 kiểm thử, 0 lỗi, 0 thất bại, 0 bỏ qua**.

## Dữ liệu và dọn dẹp

`boarding_house.db` chứa dữ liệu đang sử dụng; không reset hoặc xóa khi dọn dự án. Giữ các bản sao lưu database cho đến khi xác nhận không còn cần khôi phục.
`.env` chứa cấu hình riêng; `.idea/` chứa cấu hình IDE. Các file này và database được bỏ qua bởi Git.
`target/` chỉ chứa kết quả biên dịch và báo cáo kiểm thử, có thể tạo lại bằng Maven.

## Tài liệu chức năng

- [Kỳ thuê, điện nước và hóa đơn](docs/BILLING_CYCLES.md).
- [Phòng còn chỗ và đơn giá điện nước](docs/ROOMS_AND_UTILITY_RATES.md).
- [Điều hướng giao diện Telegram](docs/UI_NAVIGATION.md).
- [Hướng dẫn cho trợ lý lập trình](AGENTS.md).
