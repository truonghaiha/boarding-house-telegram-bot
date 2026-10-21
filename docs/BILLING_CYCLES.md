# Điện nước và hóa đơn theo kỳ thuê

## Luồng chính

1. Hợp đồng hiển thị “Thanh toán hóa đơn: ngày N hằng tháng”, lấy N từ ngày bắt đầu hợp đồng.
2. Vào **Điện nước → Ghi số & chốt tiền** hoặc **Hóa đơn → Tạo hóa đơn** để chọn phòng. Hai nút dùng chung luồng ghi số và chốt, không có bước nhập tháng/năm hay phát hành hóa đơn riêng. Nhập số ban đầu tại ngày bắt đầu hợp đồng rồi xác nhận lưu; bước số ban đầu không tạo hóa đơn. Nếu kỳ đã có hóa đơn, lối Tạo hóa đơn hiển thị Xem hóa đơn thay vì tạo trùng.
3. Với kỳ tiếp theo, bot lấy số cuối kỳ trước làm số đầu kỳ. Chủ trọ chỉ nhập số điện và nước mới. Nhập từng phần được lưu thành bản nháp trong cơ sở dữ liệu, có thể tiếp tục sau khi khởi động lại bot.
   Bấm **Hủy** chỉ thoát thao tác và bỏ lựa chọn chốt trả phòng/chốt kỳ chưa xác nhận. Số điện, nước đã nhập vẫn được giữ nguyên; lịch kỳ và tiền phòng trở về theo hợp đồng. Các kỳ đã chốt không thay đổi.
4. Khi đủ dữ liệu và đến hạn, kiểm tra lượng tiêu thụ/đơn giá/thành tiền rồi xác nhận chốt kỳ.
5. Xác nhận chốt theo kỳ hoặc trả phòng sớm sẽ tự tạo hóa đơn và thông báo cho người thuê trong cùng giao dịch. Không cần bấm lập/phát hành hóa đơn thêm.
6. Bấm Ghi nhận thanh toán sẽ hiện ngay phòng, kỳ, tổng thanh toán và hai nút Xác nhận đã nhận đủ tiền/Hủy. Thu đủ toàn bộ số còn nợ trong một lần; không nhập số tiền, phương thức hay ghi chú. Phương thức của khoản mới là Không chỉ định, dữ liệu cũ được giữ nguyên. Hóa đơn trả phòng ghi “Người thuê trả phòng” và ngày trả phòng. Hợp đồng còn hiệu lực trong lúc chờ thanh toán; không mở thêm kỳ hay tạo hóa đơn tháng mới. Khi hóa đơn cuối và toàn bộ hóa đơn cũ của hợp đồng đã được thanh toán đủ, bot kết thúc hợp đồng và đưa các người thuê của phòng ra khỏi phòng. Hóa đơn theo kỳ thông thường không tự kết thúc hợp đồng.

## Quy tắc đã thống nhất

- Thu cuối kỳ: ví dụ kỳ 18/10–18/11, hạn thanh toán 18/11.
- Mốc ngày 29–31 được giới hạn theo ngày cuối tháng nhưng luôn tính từ ngày gốc: 31/1 → 28/2 → 31/3. Có xử lý năm nhuận.
- Chỉ quá hạn từ ngày sau hạn thanh toán, theo giờ Việt Nam.
- Chủ trọ tự bảo đảm ngày hợp đồng và ngày người thuê vào ở phù hợp. Bot không tự đổi ngày hợp đồng theo thao tác xếp phòng.
- Nhập số được lưu nháp; chủ trọ tự bấm **Chốt theo kỳ** khi muốn xác nhận. Ngày kết thúc luôn là ngày cuối kỳ đã lên lịch, kể cả khi bấm chốt sớm hoặc muộn.
- Với số cuối kỳ, sửa/nhập lại chỉ số cập nhật ngày đọc mặc định thành hôm nay. Ngày đọc do bot tự ghi nhận khi nhập số; không có chức năng sửa ngày đọc.
- **Chốt trả phòng sớm** cho phép chốt trước hạn, thu đủ tiền phòng cả kỳ theo hợp đồng, không hỏi nhập lại tiền phòng. Kỳ điện nước kết thúc ở ngày bàn giao; hạn thanh toán vẫn là hạn đã lên của kỳ.
- Nếu trả phòng sau ngày chốt, phải kiểm tra kỳ còn thiếu; không tự lấy một tháng tiền phòng để thanh toán cho nhiều kỳ chưa ghi.
- Giá điện và nước chung cả tòa vẫn ở menu Điện nước. Giá mới áp dụng từ kỳ tiếp theo của từng phòng. Kỳ đang mở giữ đơn giá đã lấy; bản ghi chốt và hóa đơn giữ nguyên số tiền.
- Khi mở kỳ, đơn giá được chọn theo mốc đầu kỳ từ lịch sử giá. Kỳ đầu dùng đơn giá tại ngày bắt đầu hợp đồng. Nếu thiếu lịch sử giá của mốc cũ, bot báo để kiểm tra, không tự lấy giá hiện tại tính ngược.
- Chỉ có một bản nháp cho cùng hợp đồng và mốc đầu kỳ. Có kiểm tra phiên bản khi chốt/phát hành để tránh xác nhận dữ liệu đã bị sửa ở màn hình khác.
- Không cho sửa trực tiếp số đã phát hành hóa đơn hoặc số đầu nguồn đã được dùng mở kỳ sau.
- Thêm người ở cùng không tạo lại kỳ của phòng. Người đại diện và người ở cùng chỉ xem được các kỳ phù hợp với quyền và thời gian cư trú; không xem bản nháp của chủ trọ.

## Số ban đầu và chuyển đổi dữ liệu cũ

- Mốc ban đầu cố định tại ngày bắt đầu hợp đồng, kể cả khi nhập muộn. Không có chức năng sửa ngày bàn giao. Sau khi xác nhận, bot mở kỳ đầu đến ngày tương ứng tháng sau, giới hạn bởi ngày kết thúc hợp đồng; số ban đầu được dùng làm chỉ số đầu kỳ.
- Dữ liệu tháng cũ vẫn được xem ở **Dữ liệu tháng cũ**. Không tự chuyển số cũ thành kỳ ngày cụ thể hoặc sửa hóa đơn/thanh toán cũ.
- Nếu phát hiện hóa đơn tháng cũ cùng hợp đồng và kỳ thanh toán, bot chặn lập thêm để chủ trọ kiểm tra, tránh thu trùng.
- Cấu trúc mới cho phép nhiều kỳ rõ ngày của các đợt thuê khác nhau trong cùng tháng, nhưng mỗi kỳ chỉ được phát hành một hóa đơn. Quy tắc không trùng phòng/tháng của hóa đơn cũ vẫn được giữ riêng.

## Danh sách và quyền truy cập

- Chủ trọ có hai mục **Chưa thanh toán** và **Đã thanh toán**. Người thuê bấm Hóa đơn sẽ thấy ngay nội dung đầy đủ các hóa đơn chưa thanh toán được phép xem, không chọn nhóm hay chọn từng hóa đơn. Chỉ có nút Menu chính. Mỗi mục ghi phòng, kỳ và tổng cộng. Nội dung hóa đơn chỉ hiển thị tổng cộng và trạng thái Đã thanh toán/Chưa thanh toán, bỏ số đã trả, số còn lại, lịch sử và khoảng kỳ lặp. Người thuê chỉ xem các hóa đơn thuộc quyền của mình và không có nút ghi nhận thanh toán.
- Danh sách chỉ số của chủ trọ chỉ lấy các kỳ thuộc hợp đồng đang hiệu lực: chọn phòng → chọn Kỳ này/Kỳ trước/các kỳ cũ kèm khoảng ngày → xem số điện, nước bắt đầu và kết thúc. Không liệt kê các kỳ từ hợp đồng đã kết thúc hoặc mốc ban đầu thành kỳ riêng; màn hình xem chỉ có các nút quay lại.
- Màn hình chi tiết hiển thị ngày đọc thực tế, ngày chốt dự kiến, chỉ số đầu/cuối, lượng tiêu thụ, giá và thành tiền.
- Hóa đơn hiển thị khoảng ngày sử dụng và hạn thanh toán. Ngày/khoản tiền của hóa đơn phát hành không thay đổi khi sửa hợp đồng sau đó.
- Sửa hợp đồng cập nhật lịch và tiền phòng của bản nháp chưa chốt nếu không mâu thuẫn với mốc đã ghi. Trường hợp mâu thuẫn, bot yêu cầu kiểm tra mốc thay vì âm thầm dịch chuyển dữ liệu.
- Thông báo chốt điện nước và phát hành hóa đơn được lưu cùng giao dịch, chỉ gửi Telegram sau khi giao dịch thành công. Lỗi Telegram không hủy dữ liệu đã lưu; nội dung vẫn có trong mục Thông báo.

## Các trường hợp cần đối chiếu thực tế

- **Chốt muộn:** dùng **Chốt theo kỳ** để giữ lịch hợp đồng. Ví dụ chốt ngày 22/11 cho kỳ 20/10–20/11 thì kỳ sau vẫn là 20/11–20/12 (nếu hợp đồng còn hiệu lực). Lượng tiêu thụ được tính bằng số vừa nhập trừ số đầu kỳ, không tự phân bổ lại theo ngày.
- Thay đồng hồ: chỉ số giảm bị chặn để kiểm tra. Chưa tự suy đoán tiêu thụ qua đồng hồ mới.
- Điều chỉnh hóa đơn đã phát hành hoặc hoàn/cấn trừ tiền cọc chưa được tự động thực hiện. Không sửa trực tiếp để mất lịch sử.

## Kịch bản tập demo

- Dùng một phòng riêng cho demo và một hợp đồng bắt đầu vào cùng ngày của tháng trước, kết thúc ở một ngày sau hôm nay.
- Ghi số ban đầu điện 1000, nước 100 tại ngày bắt đầu hợp đồng rồi xác nhận; bot tự lấy ngày từ hợp đồng.
- Mở kỳ tiếp theo, ghi điện 1100, nước 110 với ngày đọc hôm nay. Nếu hôm nay là ngày chốt, xác nhận chốt bình thường; nếu chưa đến hạn, kiểm tra việc lưu nháp hoặc demo thao tác trả phòng sớm.
- Với giá điện 3000 và nước 35000, điện nước là 650000. Tiền phòng hợp đồng 4000000 thì tổng hóa đơn là 4650000, không cộng lại tiền cọc.
- Xác nhận chốt để tự tạo hóa đơn, kiểm tra trên MEMBER, ghi nhận thanh toán đủ 4650000 một lần.
