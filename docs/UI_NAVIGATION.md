# Quy tắc giao diện Telegram

Áp dụng chung cho ADMIN, LANDLORD và MEMBER. Các handler nhận một `BotMessageSender`
gắn với lượt tương tác hiện tại; `BotUiSessionManager` gửi tin nhắn mới và giữ
ngữ cảnh Quay lại/phân trang. Không sửa nội dung, gỡ bàn phím hoặc xóa tin nhắn cũ.
Không thêm cột hoặc bảng SQLite; giữ nguyên nội dung và keyboard hiện có.

## Chọn cách hiển thị

| Loại tương tác | Cách xử lý |
| --- | --- |
| Người dùng nhập `/start` hoặc `/menu` | Gửi menu mới bằng `SendMessage`. Không thay đổi nội dung hoặc keyboard của menu trước. Lệnh có hậu tố `@bot` hoặc tham số vẫn được xử lý như hiện tại. |
| Nhấn inline button: Menu chính, menu chức năng, danh sách, chi tiết, tài khoản, trợ giúp, quay lại | Gửi màn hình mới bằng `SendMessage` với nội dung và keyboard hiện có. |
| Phân trang | Gửi trang được chọn bằng tin nhắn mới. Giữ nguyên cách chia trang và các bộ lọc hiện tại. |
| Kết quả tạo, sửa, xóa, chuyển phòng, thanh toán, phân quyền, báo sự cố | `sendResultAndMenu`: gửi biên nhận riêng trong lịch sử, rồi gửi menu tương ứng bằng tin nhắn mới. |
| Thông báo hóa đơn, sự cố và các sự kiện từ observer/service | Tiếp tục dùng `sendMessage`, độc lập với phiên menu. |
| Nhập dữ liệu | `sendPrompt`: gửi câu hỏi mới, giữ nguyên luồng nhập liệu và toàn bộ tin nhắn/keyboard trước đó. Bước chọn và xác nhận cũng gửi tin nhắn mới. |
| Lỗi ngắn trên callback | `sendNotice`: gửi tin nhắn phản hồi mới; nếu đang trong luồng nhập nhiều bước thì thông báo có nút **❌ Hủy** và giữ bước hiện tại. `answerCallbackQuery` chỉ xác nhận đã nhận callback, không thay cho phản hồi trong chat. |
| Lỗi từ nhập văn bản hoặc thông báo trên 200 ký tự | Gửi tin nhắn riêng để người dùng đọc được đầy đủ. Lỗi hệ thống/SQLite quan trọng vẫn được ghi rõ trong chat. |
| Hủy nhập liệu | Mỗi lời nhắc và thông báo nhập sai khi luồng còn mở có nút **❌ Hủy**, không hiển thị hướng dẫn gửi lệnh hủy trong nội dung. Nút tiếp tục gửi `/cancel` nội bộ, đi vào handler đang giữ wizard và trả về menu của chức năng đó. Các bước nhập/chọn/xác nhận bỏ Quay lại và Menu chính; vẫn giữ lựa chọn, Bỏ qua và Xác nhận khi phù hợp. Các bước chọn/xác nhận giữ nút hủy trên mọi trang, không thêm nút trùng. Với lời nhắc một bước không có wizard, quay về menu đã mở lời nhắc. |

`sendMessageWithKeyboard` luôn gửi màn hình mới. API tương thích
`sendOrEditMessageWithKeyboard` cũng chỉ gửi tin nhắn mới bằng văn bản thuần;
không dùng message ID để sửa tin nhắn. Observer và nghiệp vụ service giữ nguyên.

## Phiên menu và quay lại

- `/start`, `/menu` và mọi inline button đều gửi tin nhắn phản hồi mới.
  Không có thao tác vô hiệu hóa, gỡ keyboard hoặc xóa menu cũ.
- Callback từ menu cũ vẫn đi qua kiểm tra role, quyền ADMIN được cấu hình và
  phạm vi dữ liệu hiện tại trong bot/handler/service trước khi thực hiện nghiệp vụ.
- Quay lại từ chi tiết hoặc bộ chọn lọc chạy lại hành động **đọc** của màn hình
  trước và khôi phục bộ lọc/trang. Không chạy lại hành động tạo, sửa, xóa hoặc xác nhận.
- Chỉ thêm Quay lại khi mở màn hình con với bộ chức năng khác. Danh sách, kết quả
  tìm kiếm hoặc cập nhật nội dung giữ nguyên chức năng không tạo thêm cấp điều hướng.
  Chọn xong bộ lọc quay về cấp danh sách; Menu chính giữ trên các màn hình chức năng ngoài luồng nhập nhiều bước.
- Tầng và trang phòng được giữ qua callback phòng hiện có. Bộ lọc hóa đơn và
  điện nước dùng khóa riêng `invoiceFilter...` và `meterFilter...`, tránh ghi đè nhau.
- Ngữ cảnh Quay lại/phân trang gắn với tin nhắn được bấm, tránh nhầm với màn hình
  mới hơn. Đây chỉ là dữ liệu điều hướng, không dùng để cấp quyền hoặc phục hồi wizard.
- Mỗi lần quay lại vẫn qua kiểm tra phân quyền và phạm vi dữ liệu hiện tại.
  Phân trang dùng bản chụp của màn hình; đổi role hoặc mất quyền truy cập sẽ chặn xem tiếp.
- Lịch sử điều hướng giới hạn 30 màn hình; bộ lọc và lịch sử giao diện nằm trong bộ nhớ.

## Quản lý phòng của LANDLORD

- Menu hiển thị tổng phòng, tổng tầng xác định được từ mã phòng, phòng còn chỗ và
  phòng đã đầy. Hai thao tác chính là Thêm phòng và Danh sách phòng.
- Danh sách tầng chỉ gồm các nhóm có phòng; hiển thị tổng phòng và số phòng còn
  chỗ của từng tầng. Mã phòng cũ không suy ra được tầng vẫn truy cập được trong
  nhóm Chưa xác định tầng, không tính nhóm này như một tầng thật trong thống kê.
- Trong từng tầng, bộ lọc Tất cả / Còn chỗ / Đã đầy dùng số người hiện tại trong
  SQLite, không dùng trạng thái AVAILABLE/OCCUPIED để suy ra sức chứa. Phòng trống
  vẫn thuộc bộ lọc Còn chỗ. Phòng có sức chứa không hợp lệ vẫn hiện trong Tất cả.
- Chuyển bộ lọc về trang đầu của chính tầng đang chọn; phân trang giữ bộ lọc.
  Callback chi tiết giữ tầng, trang và bộ lọc để quay lại đúng danh sách. Đổi bộ
  lọc không tạo thêm cấp điều hướng, kể cả khi danh sách kết quả trống.
- Thông tin và thao tác ở chi tiết phòng giữ nguyên. Các callback menu/danh sách
  cũ vẫn hoạt động. Mỗi lần mở menu, danh sách tầng/phòng hoặc quay lại đều đọc lại
  dữ liệu hiện tại; mọi màn hình tiếp tục gửi tin nhắn mới và giữ các menu cũ.
- Chi tiết phòng bố trí Người thuê / Chuyển phòng; Thêm người thuê / Xóa người
  thuê; Sửa giá phòng / Sửa mô tả; Xóa phòng. Hai luồng sửa có nhập giá trị mới,
  xác nhận và Hủy, sau đó quay về chi tiết phòng. Sửa giá phòng chỉ cập nhật giá
  của phòng, không thay đổi tiền thuê đã thỏa thuận trong hợp đồng.

## Quản lý hợp đồng của LANDLORD

- Menu gồm năm hàng: Tạo hợp đồng; Danh sách tất cả hợp đồng; Đang hiệu lực /
  Đã kết thúc; Sắp hết hạn; Menu chính. Giữ nguyên callback và bộ lọc hiện có.
- Bốn danh sách dùng cùng mẫu: ID hợp đồng, tên người đứng tên, phòng, thời hạn,
  giá thuê và trạng thái. Không hiển thị lệnh xem chi tiết trong danh sách.
- Các nút `📄 HĐ #ID – P…` bố trí hai nút một hàng và gửi callback
  `CONTRACT_DETAIL_ID` đến màn hình chi tiết chung. Danh sách không có dữ liệu vẫn
  có thông báo và điều hướng quay về Quản lý hợp đồng.
- Chi tiết dùng cơ chế Quay lại theo tin nhắn nguồn để trở về đúng danh sách/bộ
  lọc, đọc lại dữ liệu hiện tại. Callback và lệnh xem chi tiết cũ vẫn được kiểm tra
  quyền như trước. Mọi màn hình tiếp tục gửi tin nhắn mới và giữ menu cũ.

## Dự phòng và giới hạn

### Thao tác bằng nút và nhập từng thông tin

- Thêm người thuê từ menu: nhập Telegram ID, chọn phòng còn chỗ bằng nút, nhập
  số điện thoại bắt buộc. Khi bắt đầu từ chi tiết phòng, giữ phòng đã chọn.
- Xóa người thuê từ menu: nhập Telegram ID, xem lại người/phòng và xác nhận.
  Chỉ lưu sau xác nhận, kiểm tra lại đúng phòng; người đại diện có hợp đồng đang
  hiệu lực phải kết thúc hợp đồng trước.
- Kết thúc hợp đồng: màn hình xác nhận nêu rõ sẽ gỡ toàn bộ người thuê đang ở
  phòng. Sau xác nhận, bot đóng lịch sử cư trú, gỡ liên kết phòng của từng người
  và cập nhật phòng trống trong cùng giao dịch; giữ nguyên tài khoản, hợp đồng
  và hóa đơn cũ. Luồng thanh toán đủ hóa đơn trả phòng dùng chung thao tác này.
- Chuyển phòng từ menu hoặc chi tiết phòng: nhập Telegram ID, chọn phòng mới,
  xác nhận. Khi bắt đầu từ chi tiết phòng, ID phải thuộc phòng nguồn đó.
- ADMIN cấp/thu hồi LANDLORD: nhập Telegram ID, xem lại và xác nhận. Kiểm tra
  quyền ADMIN cấu hình cả khi nhập và xác nhận; Hủy không thay đổi quyền.
- Các danh sách hóa đơn, điện nước và sự cố dùng nút xem chi tiết theo ID.
  Không yêu cầu nhập cú pháp lệnh trong lời nhắc hoặc dòng hướng dẫn danh sách.
- Người thuê bấm Báo sự cố từ menu chính để nhập toàn bộ mô tả trong một lần,
  không qua menu phụ hay danh sách lịch sử. Trong lúc nhập chỉ có Hủy;
  gửi thành công hoặc hủy sẽ trở về menu chính.
- Chủ trọ bấm Bảo trì để chọn một trong hai nhóm Cần xử lý và Đã xử lý.
  Nhóm Cần xử lý chứa mọi sự cố chưa hoàn thành, kể cả dữ liệu Đang xử lý cũ.
  Mỗi sự cố chỉ có nút Hoàn thành, không có bước Đang xử lý. Mỗi sự cố là một nút
  phòng và mô tả ngắn. Chi tiết chỉ có thao tác trạng thái phù hợp và
  quay về danh sách tương ứng.
  Các lệnh/callback cũ tiếp tục được kiểm tra quyền để tương thích menu cũ.

### Bước nhập có thể bỏ qua

- Chủ trọ cập nhật trạng thái sự cố trực tiếp bằng nút, không nhập ghi chú xử lý.
- Hộp thư người thuê chỉ lưu khi phòng có hợp đồng ACTIVE, gồm cả người ở cùng.
  Lập hợp đồng bắt đầu hộp thư mới; kết thúc hợp đồng xóa toàn bộ thông báo của
  người thuê rời phòng. Khi khởi động bot, dọn thông báo cũ của MEMBER không có
  hợp đồng phòng đang hiệu lực. Việc gửi tin nhắn Telegram vẫn độc lập với lưu hộp thư.
- Thông báo có hai nhóm Chưa đọc và Đã đọc. Mỗi thông báo là một nút để xem
  chi tiết, không tự đánh dấu khi mở. Chi tiết chưa đọc có nút Đã đọc; bấm mới
  chuyển sang nhóm Đã đọc. Nút quay lại dẫn về danh sách tương ứng, không có nút
  chuyển nhóm trong danh sách. Danh sách Chưa đọc có nút Đánh dấu tất cả đã đọc
  khi còn thông báo; thao tác chỉ áp dụng cho tài khoản hiện tại.
- Mô tả khi thêm phòng có thông báo
  "có thể bỏ qua", kèm hai nút Bỏ qua / Hủy. Không hướng dẫn nhập lệnh hoặc ký hiệu.
- Callback Bỏ qua gắn với bước hiện tại, đi qua cùng handler và kiểm tra role như
  nhập liệu thường. Bước bắt buộc và nút của bước đã hết hiệu lực bị từ chối.
- Bỏ qua mô tả phòng dùng Chưa có mô tả; bỏ qua ghi chú dùng chuỗi rỗng. Giữ nguyên
  bước xác nhận/thao tác lưu hiện có và cơ chế Hủy, không thay đổi nghiệp vụ.

### Số điện thoại người thuê

- Thêm người thuê từ chi tiết phòng hoặc menu Người thuê đều
  yêu cầu chủ trọ nhập số điện thoại trước khi hoàn tất xếp phòng. Không cho bỏ qua.
- Danh sách người thuê không còn dòng hướng dẫn lệnh xóa; số điện thoại được hiển
  thị trong danh sách chung, danh sách người thuê theo phòng, chi tiết phòng và
  thông tin tài khoản MEMBER.
- Sửa thông tin người thuê: nhập Telegram ID của người đang được xếp phòng, nhập
  số điện thoại mới, xem lại và xác nhận lưu. Mọi bước đều có nút Hủy;
  chỉ bước xác nhận mới lưu thay đổi.
- Chấp nhận số trong nước 10 chữ số bắt đầu bằng 0 hoặc số quốc tế có dấu +;
  bỏ khoảng trắng, dấu chấm, gạch nối và ngoặc khi lưu, giữ số 0 đầu.
- `users.phone_number` được thêm bởi cơ chế cập nhật schema Hibernate hiện có khi
  khởi động bot. Tài khoản cũ chưa có số hiển thị Chưa cập nhật để chủ trọ bổ sung,
  không gán số giả hay xóa dữ liệu cũ.

- Không sử dụng `EditMessageText`, `EditMessageReplyMarkup` hoặc `DeleteMessage`.
  Lỗi gửi tin nhắn được ghi nhận và thông báo rõ trong chat khi có thể.
- Nếu nội dung không hợp lệ với Markdown, thử lại bằng văn bản thuần.
- Ngữ cảnh giao diện trong bộ nhớ không còn sau khi bot khởi động lại. Callback
  nghiệp vụ/điều hướng vẫn được phân quyền như hiện tại; Quay lại dạng `UI_BACK`
  không có ngữ cảnh sẽ về Menu chính.

## Các file triển khai

- `src/main/java/com/boardinghousebot/bot/ui/BotUiSessionManager.java`: cơ chế màn hình chung.
- `src/main/java/com/boardinghousebot/bot/BoardingHouseTelegramBot.java`: tạo adapter theo lượt,
  kiểm tra callback, xử lý Back/Page, định tuyến `/cancel` và dự phòng văn bản thuần.
- `src/main/java/com/boardinghousebot/bot/command/BotMessageSender.java`: phân biệt màn hình,
  lời nhắc, lỗi ngắn và biên nhận nghiệp vụ.
- `src/main/java/com/boardinghousebot/state/ConversationStateService.java`: lưu bộ lọc khi
  không có wizard và khôi phục riêng dữ liệu điều hướng.
- `src/main/java/com/boardinghousebot/util/TelegramKeyboardUtil.java`: keyboard quay lại dùng chung.
- Các handler đã điều chỉnh cách hiển thị: `MenuHandler`, `RoomCommandHandler`,
  `TenantCommandHandler`, `ContractCommandHandler`, `MeterCommandHandler`,
  `InvoiceCommandHandler`, `MaintenanceCommandHandler`, `NotificationCommandHandler`.
  `StartHandler` và `HelpHandler` sử dụng adapter chung qua điểm gọi bot sẵn có.

## Kiểm tra

Các kiểm thử `BotUiSessionManagerTest`, `UnifiedUiNavigationTest`,
`MenuCommandSessionTest` và `RoomCallbackRoutingTest` xác nhận gửi mới cho cả ba
role; không sửa/xóa tin nhắn hoặc keyboard; thông báo lỗi hiển thị trong chat;
callback menu cũ vẫn được phân quyền; Quay lại/phân trang giữ đúng ngữ cảnh nguồn.
Các kiểm thử handler cũ được cập nhật theo ý nghĩa hiển thị mới; kiểm thử nghiệp vụ,
phân quyền và SQLite vẫn chạy trong toàn bộ suite.

Môi trường hiện tại dùng Java 26 với Byte Buddy cũ, nên kiểm thử được chạy cùng
`-Dnet.bytebuddy.experimental=true`. Không thay dependency hoặc cấu hình dự án
chỉ để xử lý khác biệt của máy này. Không commit hoặc push.
