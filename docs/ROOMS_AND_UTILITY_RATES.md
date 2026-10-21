# Phòng còn chỗ và đơn giá điện nước chung

Phần ghi điện nước và lập hóa đơn đã được nâng cấp theo [kỳ thuê](BILLING_CYCLES.md). Luồng theo kỳ này thay cho luồng nhập tháng/năm dưới đây; các bản ghi tháng cũ vẫn được giữ làm lịch sử.

## MEMBER

- Menu của người mới, người chưa thuê và người đang thuê đều có **Phòng còn chỗ**.
- Chỉ hiện phòng có số người ở hiện tại nhỏ hơn sức chứa. Phòng 2/3 được hiện, phòng 3/3 không được hiện.
- Mỗi phòng có mã phòng, giá thuê/tháng, diện tích m², số người đang ở dạng 2/3, tiền cọc yêu cầu và giá điện/nước chung.
- Danh sách không có nhãn trạng thái, tên, Telegram ID hoặc số điện thoại của người thuê khác. Có phân trang và nút làm mới.
- Số người ở được tính từ dữ liệu xếp phòng hiện tại, tự cập nhật sau khi thêm, chuyển hoặc đưa người thuê ra khỏi phòng.

## LANDLORD

- **Quản lý phòng → Thêm phòng**: nhập mã phòng, giá thuê, sức chứa, diện tích, tiền cọc riêng và mô tả tùy chọn.
- **Chi tiết phòng**: có nút sửa diện tích và sửa tiền cọc, xem trước và xác nhận trước khi lưu. Tiền cọc có thể bằng 0; diện tích phải lớn hơn 0.
- Thông tin phòng của MEMBER cũng hiển thị diện tích, tiền cọc yêu cầu và đơn giá điện nước chung.
- Khi tạo hợp đồng mới, bước tiền thuê hiển thị giá niêm yết và nút dùng giá đó; bước tiền cọc hiển thị mức cọc yêu cầu và nút dùng mức cọc đó. Chủ trọ cũng có thể nhập trực tiếp mức đã thỏa thuận ở mỗi bước (tiền thuê > 0, tiền cọc >= 0). Phòng chưa có tiền cọc yêu cầu nhập tay, không tự coi là 0. Mức khác niêm yết được đối chiếu trong bản xem trước, chỉ áp dụng cho hợp đồng. Nút cũ hoặc sai phòng không làm thay đổi lựa chọn; giá thay đổi sau khi hiển thị phải xem và chọn lại.
- Sửa tiền cọc yêu cầu của phòng không sửa tiền cọc đã thỏa thuận trong hợp đồng. Khi chuyển phòng, luồng hiện có tiếp tục giữ số tiền cọc của hợp đồng cũ; không tự ghi nhận thu thêm hoặc hoàn cọc.

## Điện nước và kỳ thanh toán

- **Điện nước → Giá điện toàn tòa / Giá nước toàn tòa**: nhập giá mới, xem giá cũ/mới và ngày áp dụng, sau đó xác nhận.
- Giá mới có hiệu lực từ ngày cập nhật (giờ Việt Nam). Không gửi thông báo trùng nếu giá không đổi.
- Đơn giá được lưu theo lịch sử ngày hiệu lực; không lưu đơn giá riêng theo phòng.
- Ghi chỉ số mới tự chọn đơn giá chung của kỳ, không hỏi nhập lại. Điện = kWh tiêu thụ × đơn giá; nước = m³ tiêu thụ × đơn giá, có hỗ trợ số m³ thập phân.
- **Quy tắc đổi giá trong luồng mới:** áp dụng từ kỳ tiếp theo của từng phòng. Kỳ đang mở giữ đơn giá đầu kỳ; không chia tỷ lệ tiêu thụ theo ngày. Các bản ghi chốt và hóa đơn đã phát hành giữ nguyên.
- Sửa chỉ số qua bot giữ đơn giá đã lưu của bản ghi. Khi giá thay đổi sau bản xem trước nhưng trước xác nhận ghi mới, bot yêu cầu xem và xác nhận lại giá mới.
- Không lấy giá hiện tại để tính ngược một kỳ chưa có lịch sử đơn giá; bot báo chưa thiết lập giá cho kỳ đó.

## Thông báo và dữ liệu cũ

- Mỗi MEMBER đang được xếp phòng nhận thông báo gồm giá cũ, giá mới, đơn vị và ngày áp dụng; MEMBER chưa thuê và ADMIN/LANDLORD không nhận thông báo này.
- Nội dung được lưu cùng giao dịch cập nhật giá. Chỉ gửi Telegram sau khi giao dịch thành công; hủy giao dịch không gửi tin.
- Nếu gửi Telegram thất bại, giá mới và thông báo trong bot vẫn được giữ; các người thuê khác vẫn được gửi. Chưa có cơ chế tự gửi lại Telegram.
- Cột diện tích, tiền cọc và bảng lịch sử đơn giá được bổ sung bằng cập nhật schema khi khởi động. Ràng buộc loại thông báo SQLite được nâng cấp tương thích.
- Phòng cũ chưa có diện tích/tiền cọc hiển thị **Chưa cập nhật**. Giá chung chưa thiết lập hiển thị **Chưa thiết lập**. Không tự đoán thông tin từ mô tả hoặc giá của một phòng bất kỳ.
- Sau cập nhật, chủ trọ điền diện tích/tiền cọc cho phòng cũ và thiết lập hai đơn giá chung trong Điện nước.
