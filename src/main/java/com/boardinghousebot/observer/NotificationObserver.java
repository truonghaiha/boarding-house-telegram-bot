package com.boardinghousebot.observer;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.service.NotificationService;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Component
public class NotificationObserver {

        private static final Logger log = LoggerFactory.getLogger(NotificationObserver.class);
        private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,###");

        private final NotificationService notificationService;
        private final TelegramNotificationService telegramNotificationService;
        private final UserRepository userRepository;
        private final RoomService roomService;
        private final com.boardinghousebot.service.RoomStayService roomStayService;
        private final com.boardinghousebot.repository.InvoiceRepository invoiceRepository;

        public NotificationObserver(NotificationService notificationService,
                        TelegramNotificationService telegramNotificationService,
                        UserRepository userRepository,
                        RoomService roomService, com.boardinghousebot.service.RoomStayService roomStayService,
                        com.boardinghousebot.repository.InvoiceRepository invoiceRepository) {
                this.notificationService = notificationService;
                this.telegramNotificationService = telegramNotificationService;
                this.userRepository = userRepository;
                this.roomService = roomService;
                this.roomStayService = roomStayService;
                this.invoiceRepository = invoiceRepository;
        }

        @EventListener
        public void handleRoleChanged(RoleChangedEvent event) {
                User user = event.getUser();
                Role newRole = event.getNewRole();
                log.info("Received RoleChangedEvent for User ID={}, NewRole={}", user.getTelegramId(), newRole);

                String title = "Thay đổi vai trò tài khoản";
                String dbMessage;
                String telegramMessage;

                if (newRole == Role.LANDLORD) {
                        dbMessage = "Bạn đã được nâng cấp từ MEMBER lên LANDLORD.";
                        telegramMessage = """
                                        🔔 VAI TRÒ TÀI KHOẢN ĐÃ THAY ĐỔI

                                        Bạn đã được nâng cấp từ MEMBER lên LANDLORD.

                                        Bạn hiện có quyền sử dụng các chức năng quản lý nhà trọ.

                                        Gõ /menu để xem các chức năng mới.
                                        """.trim();
                } else {
                        dbMessage = "Vai trò LANDLORD của bạn đã được thu hồi.";
                        telegramMessage = """
                                        🔔 VAI TRÒ TÀI KHOẢN ĐÃ THAY ĐỔI

                                        Vai trò LANDLORD của bạn đã được thu hồi.

                                        Tài khoản của bạn hiện trở lại vai trò MEMBER.

                                        Gõ /menu để xem các chức năng hiện có.
                                        """.trim();
                }

                notificationService.createNotification(
                                user.getTelegramId(),
                                NotificationType.ROLE_CHANGED,
                                title,
                                dbMessage,
                                "USER",
                                user.getId());

                telegramNotificationService.sendNotification(user.getTelegramId(), telegramMessage);
        }

        @EventListener
        public void handleMaintenanceCreated(MaintenanceCreatedEvent event) {
                MaintenanceRequest req = event.getRequest();
                log.info("Received MaintenanceCreatedEvent for Maintenance ID={}", req.getId());

                String title = String.format("Yêu cầu bảo trì mới - Phòng %s", req.getRoom().getRoomNumber());
                String dbMsg = String.format("🏠 Phòng: %s\n📝 Mô tả: %s\n👤 Người báo: %s",
                                req.getRoom().getRoomNumber(),
                                req.getDescription() != null && !req.getDescription().isBlank() ? req.getDescription()
                                                : req.getTitle(),
                                req.getMember().getFullName() != null ? req.getMember().getFullName()
                                                : "User " + req.getMember().getTelegramId());

                String createdTime = req.getCreatedAt() != null ? req.getCreatedAt().format(DATE_TIME_FORMATTER)
                                : "N/A";
                String descriptionContent = (req.getDescription() != null && !req.getDescription().isBlank())
                                ? req.getDescription()
                                : req.getTitle();
                String telegramMsg = String.format("""
                                🔧 BÁO CÁO SỰ CỐ MỚI

                                Phòng: %s
                                Người báo: %s

                                Mô tả sự cố:
                                %s

                                Thời gian: %s

                                Trạng thái: Cần xử lý
                                """,
                                req.getRoom().getRoomNumber(),
                                req.getMember().getFullName() != null ? req.getMember().getFullName()
                                                : "User " + req.getMember().getTelegramId(),
                                descriptionContent,
                                createdTime).trim();

                List<User> landlords = userRepository.findAll().stream()
                                .filter(u -> u.getRole() == Role.LANDLORD)
                                .toList();

                for (User landlord : landlords) {
                        notificationService.createNotification(
                                        landlord.getTelegramId(),
                                        NotificationType.MAINTENANCE_CREATED,
                                        title,
                                        dbMsg,
                                        "MAINTENANCE",
                                        req.getId());
                        telegramNotificationService.sendNotification(landlord.getTelegramId(), telegramMsg);
                }
        }

        @EventListener
        public void handleMaintenanceStatusChanged(MaintenanceStatusChangedEvent event) {
                MaintenanceRequest req = event.getRequest();
                MaintenanceStatus oldStatus = event.getPreviousStatus();
                MaintenanceStatus newStatus = event.getNewStatus();
                log.info("Received MaintenanceStatusChangedEvent for Maintenance ID={}, OldStatus={}, NewStatus={}",
                                req.getId(), oldStatus, newStatus);

                String title = newStatus == MaintenanceStatus.RESOLVED
                                ? String.format("Sự cố phòng %s đã được xử lý", req.getRoom().getRoomNumber())
                                : String.format("Cập nhật bảo trì phòng %s", req.getRoom().getRoomNumber());

                StringBuilder dbSb = new StringBuilder();
                dbSb.append(String.format("🏠 Phòng: %s\n📌 Tiêu đề: %s\n⏳ Trạng thái: %s %s\n",
                                req.getRoom().getRoomNumber(), req.getTitle(), newStatus.getIcon(),
                                newStatus.getDisplayName()));

                notificationService.createNotification(
                                req.getMember().getTelegramId(),
                                NotificationType.MAINTENANCE_STATUS_CHANGED,
                                title,
                                dbSb.toString().trim(),
                                "MAINTENANCE",
                                req.getId());

                String telegramMsg;

                if (newStatus == MaintenanceStatus.RESOLVED) {
                        telegramMsg = String.format("""
                                        🔧 SỰ CỐ ĐÃ ĐƯỢC XỬ LÝ

                                        Phòng: %s

                                        Sự cố:
                                        %s

                                        Trạng thái:
                                        Đã xử lý

                                        """,
                                        req.getRoom().getRoomNumber(),
                                        req.getTitle()).trim();
                } else {
                        telegramMsg = String.format("""
                                        🔧 CẬP NHẬT SỰ CỐ

                                        Phòng: %s

                                        Sự cố:
                                        %s

                                        Trạng thái:
                                        %s → %s

                                        """,
                                        req.getRoom().getRoomNumber(),
                                        req.getTitle(),
                                        oldStatus.name(),
                                        newStatus.name()).trim();
                }

                telegramNotificationService.sendNotification(req.getMember().getTelegramId(), telegramMsg);
        }

        @EventListener
        public void handleMemberRoomChanged(MemberRoomChangedEvent event) {
                User member = event.getMember();
                MemberRoomChangedEvent.ChangeType type = event.getChangeType();
                Room oldRoom = event.getOldRoom();
                Room newRoom = event.getNewRoom();

                log.info("Received MemberRoomChangedEvent for User ID={}, ChangeType={}", member.getTelegramId(), type);

                if (type == MemberRoomChangedEvent.ChangeType.ASSIGNED && newRoom != null) {
                        int occupants = roomService.getCurrentOccupantCount(newRoom.getId());
                        String rentFormatted = CURRENCY_FORMAT.format(newRoom.getMonthlyRent());

                        String dbTitle = "Bạn đã được thêm vào phòng";
                        String dbMsg = "Bạn đã được thêm vào phòng " + newRoom.getRoomNumber() + ".";
                        notificationService.createNotification(member.getTelegramId(), NotificationType.ROOM_CHANGED,
                                        dbTitle, dbMsg, "ROOM", newRoom.getId());

                        String telegramMsg = String.format("""
                                        🏠 BẠN ĐÃ ĐƯỢC THÊM VÀO PHÒNG

                                        Bạn đã được thêm vào phòng %s.

                                        🏠 Phòng: %s
                                        💰 Tiền phòng: %s VNĐ/tháng
                                        👥 Số người hiện tại: %d/%d

                                        Gõ /menu để xem thông tin phòng.
                                        """,
                                        newRoom.getRoomNumber(),
                                        newRoom.getRoomNumber(),
                                        rentFormatted,
                                        occupants,
                                        newRoom.getMaxOccupants()).trim();

                        telegramNotificationService.sendNotification(member.getTelegramId(), telegramMsg);

                } else if (type == MemberRoomChangedEvent.ChangeType.REMOVED && oldRoom != null) {
                        String dbTitle = "Cập nhật phòng";
                        String dbMsg = "Bạn đã được xóa khỏi phòng " + oldRoom.getRoomNumber() + ".";
                        notificationService.createNotification(member.getTelegramId(), NotificationType.ROOM_CHANGED,
                                        dbTitle, dbMsg, "ROOM", oldRoom.getId());

                        String telegramMsg = String.format("""
                                        🏠 CẬP NHẬT PHÒNG

                                        Bạn đã được xóa khỏi phòng %s.

                                        Bạn hiện không thuộc phòng nào.

                                        Gõ /menu để xem thông tin tài khoản.
                                        """,
                                        oldRoom.getRoomNumber()).trim();

                        telegramNotificationService.sendNotification(member.getTelegramId(), telegramMsg);

                } else if (type == MemberRoomChangedEvent.ChangeType.MOVED && newRoom != null && oldRoom != null) {
                        int occupants = roomService.getCurrentOccupantCount(newRoom.getId());
                        String rentFormatted = CURRENCY_FORMAT.format(newRoom.getMonthlyRent());

                        String dbTitle = "Bạn đã được chuyển phòng";
                        String dbMsg = "Bạn đã được chuyển từ phòng " + oldRoom.getRoomNumber() + " sang phòng "
                                        + newRoom.getRoomNumber() + ".";
                        notificationService.createNotification(member.getTelegramId(), NotificationType.ROOM_CHANGED,
                                        dbTitle, dbMsg, "ROOM", newRoom.getId());

                        String telegramMsg = String.format("""
                                        🏠 BẠN ĐÃ ĐƯỢC CHUYỂN PHÒNG

                                        Bạn đã được chuyển từ phòng %s
                                        sang phòng %s.

                                        🏠 Phòng mới: %s
                                        💰 Tiền phòng: %s VNĐ/tháng
                                        👥 Số người hiện tại: %d/%d

                                        Gõ /menu để xem thông tin phòng mới.
                                        """,
                                        oldRoom.getRoomNumber(),
                                        newRoom.getRoomNumber(),
                                        newRoom.getRoomNumber(),
                                        rentFormatted,
                                        occupants,
                                        newRoom.getMaxOccupants()).trim();

                        telegramNotificationService.sendNotification(member.getTelegramId(), telegramMsg);
                }
        }

        @EventListener
        public void handleContractCreated(ContractCreatedEvent event) {
                Contract contract = event.getContract();
                Room room = contract.getRoom();
                if (room == null) return;

                log.info("Received ContractCreatedEvent for Contract ID={}, Room={}", contract.getId(), room.getRoomNumber());

                List<User> members = userRepository.findByRoomId(room.getId()).stream()
                                .filter(u -> u.getRole() == Role.MEMBER)
                                .toList();

                if (members.isEmpty()) return;

                String title = String.format("Hợp đồng mới - Phòng %s", room.getRoomNumber());
                String startDateStr = contract.getStartDate() != null ? contract.getStartDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "N/A";
                String rentFormatted = contract.getMonthlyRent() != null ? CURRENCY_FORMAT.format(contract.getMonthlyRent()) : "0";
                String statusStr = contract.getStatus() != null ? contract.getStatus().name() : "ACTIVE";

                String dbMsg = String.format("🏠 Phòng: %s\n📅 Ngày bắt đầu: %s\n💰 Tiền phòng: %s VNĐ\n⏳ Trạng thái: %s",
                                room.getRoomNumber(), startDateStr, rentFormatted, statusStr);

                String telegramMsg = String.format("""
                                📄 HỢP ĐỒNG MỚI

                                Phòng: %s

                                Hợp đồng mới đã được tạo cho phòng của bạn.

                                📅 Ngày bắt đầu: %s
                                💰 Tiền phòng: %s VNĐ

                                Trạng thái: %s

                                Vui lòng kiểm tra thông tin hợp đồng trong mục:
                                📄 Hợp đồng
                                """,
                                room.getRoomNumber(), startDateStr, rentFormatted, statusStr).trim();

                for (User member : members) {
                        notificationService.createNotification(
                                        member.getTelegramId(),
                                        NotificationType.CONTRACT_CREATED,
                                        title,
                                        dbMsg,
                                        "CONTRACT",
                                        contract.getId());
                        telegramNotificationService.sendNotification(member.getTelegramId(), telegramMsg);
                }
        }

        @EventListener
        public void handleContractEnded(ContractEndedEvent event) {
                Contract contract = event.contract();
                Room room = contract.getRoom();
                if (room == null) return;

                List<User> members = userRepository.findByRoomId(room.getId()).stream()
                                .filter(user -> user.getRole() == Role.MEMBER)
                                .toList();
                String title = "Hợp đồng đã kết thúc - Phòng " + room.getRoomNumber();
                for (User member : members) {
                        boolean representative = contract.getMember() != null
                                        && member.getTelegramId().equals(contract.getMember().getTelegramId());
                        String message = "📄 HỢP ĐỒNG ĐÃ KẾT THÚC\n\n"
                                        + "🏠 Phòng: " + room.getRoomNumber() + "\n"
                                        + "Chủ trọ đã kết thúc hợp đồng của phòng bạn.\n\n";
                        if (representative) {
                                DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");
                                message += "Bạn là người đại diện đứng tên hợp đồng.\n"
                                                + "📅 Thời hạn đã thỏa thuận: " + contract.getStartDate().format(dateFormat)
                                                + " → " + contract.getEndDate().format(dateFormat) + "\n"
                                                + "💰 Tiền thuê: " + CURRENCY_FORMAT.format(contract.getMonthlyRent()) + " VNĐ/tháng\n"
                                                + "💵 Tiền cọc theo hợp đồng: " + CURRENCY_FORMAT.format(contract.getDeposit()) + " VNĐ\n\n"
                                                + "Vui lòng liên hệ chủ trọ để trao đổi việc xử lý tiền cọc và bàn giao phòng.";
                        } else {
                                message += "Vui lòng liên hệ người đại diện đứng tên hợp đồng hoặc chủ trọ để được hướng dẫn các bước tiếp theo.";
                        }
                        message += "\n\nThông tin liên hệ chủ trọ có trong /menu → 📞 Thông tin liên hệ chủ trọ.";
                        notificationService.createNotification(member.getTelegramId(), NotificationType.CONTRACT_ENDED,
                                        title, message, "CONTRACT", contract.getId());
                        telegramNotificationService.sendNotification(member.getTelegramId(), message);
                }
        }

        @EventListener
        public void handleMeterReadingCreated(MeterReadingCreatedEvent event) {
                MeterReading reading = event.getReading();
                Room room = reading.getRoom();
                if (room == null) return;

                log.info("Received MeterReadingCreatedEvent for Reading ID={}, Type={}, Room={}", reading.getId(), reading.getUtilityType(), room.getRoomNumber());

                List<User> members = userRepository.findByRole(Role.MEMBER).stream()
                                .filter(u -> u.getRole() == Role.MEMBER)
                                .filter(u -> roomStayService.canReadPeriod(u, room.getId(), reading.getBillingMonth(), reading.getBillingYear(), reading.getCreatedAt()))
                                .toList();

                if (members.isEmpty()) return;

                boolean isElectricity = reading.getUtilityType() == UtilityType.ELECTRICITY;
                NotificationType notifType = isElectricity ? NotificationType.ELECTRICITY_CREATED : NotificationType.WATER_CREATED;
                String title = String.format("%s - Phòng %s", isElectricity ? "Chỉ số điện mới" : "Chỉ số nước mới", room.getRoomNumber());
                String unit = isElectricity ? "kWh" : "m³";
                String header = isElectricity ? "⚡ CHỈ SỐ ĐIỆN MỚI" : "💧 CHỈ SỐ NƯỚC MỚI";

                String prevStr = formatNumber(reading.getPreviousReading());
                String currStr = formatNumber(reading.getCurrentReading());
                String consStr = formatNumber(reading.getConsumption());
                String priceStr = formatNumber(reading.getUnitPrice());
                String totalStr = formatNumber(reading.getTotalCharge());

                String dbMsg = String.format("🏠 Phòng: %s (Kỳ %02d/%d)\nChỉ số cũ: %s | Chỉ số mới: %s\nTiêu thụ: %s %s | Đơn giá: %s VNĐ/%s\nThành tiền: %s VNĐ",
                                room.getRoomNumber(), reading.getBillingMonth(), reading.getBillingYear(),
                                prevStr, currStr, consStr, unit, priceStr, unit, totalStr);

                String telegramMsg = String.format("""
                                %s

                                Phòng: %s
                                Kỳ: %02d/%d

                                Chỉ số cũ: %s
                                Chỉ số mới: %s
                                Tiêu thụ: %s %s

                                Đơn giá: %s VNĐ/%s
                                Thành tiền: %s VNĐ

                                Vui lòng kiểm tra mục:
                                ⚡ Điện nước
                                """,
                                header,
                                room.getRoomNumber(), reading.getBillingMonth(), reading.getBillingYear(),
                                prevStr, currStr, consStr, unit, priceStr, unit, totalStr).trim();

                for (User member : members) {
                        notificationService.createNotification(
                                        member.getTelegramId(),
                                        notifType,
                                        title,
                                        dbMsg,
                                        "METER_READING",
                                        reading.getId());
                        telegramNotificationService.sendNotification(member.getTelegramId(), telegramMsg);
                }
        }

        @EventListener
        public void handleInvoiceCreated(InvoiceCreatedEvent event) {
                Invoice invoice = event.getInvoice();
                Room room = invoice.getRoom();
                if (room == null) return;

                log.info("Received InvoiceCreatedEvent for Invoice ID={}, Room={}", invoice.getId(), room.getRoomNumber());

                List<User> members = userRepository.findByRole(Role.MEMBER).stream()
                                .filter(u -> u.getRole() == Role.MEMBER)
                                .filter(u -> roomStayService.canReadInvoice(u, invoice))
                                .toList();

                if (members.isEmpty()) return;

                String title = String.format("Hóa đơn mới - Phòng %s", room.getRoomNumber());
                String rentStr = formatNumber(invoice.getRoomRentAmount());
                String elecStr = formatNumber(invoice.getElectricityAmount());
                String waterStr = formatNumber(invoice.getWaterAmount());
                String totalStr = formatNumber(invoice.getTotalAmount());
                String dueDateStr = invoice.getDueDate() != null ? invoice.getDueDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "N/A";

                String dbMsg = String.format("🏠 Tiền phòng: %s VNĐ\n⚡ Tiền điện: %s VNĐ\n💧 Tiền nước: %s VNĐ\n💵 Tổng cộng: %s VNĐ\n📅 Hạn thanh toán: %s",
                                rentStr, elecStr, waterStr, totalStr, dueDateStr);

                String telegramMsg = String.format("""
                                💰 HÓA ĐƠN MỚI

                                Phòng: %s
                                Kỳ: %02d/%d

                                🏠 Tiền phòng: %s VNĐ
                                ⚡ Tiền điện: %s VNĐ
                                💧 Tiền nước: %s VNĐ

                                💵 Tổng cộng: %s VNĐ

                                📅 Hạn thanh toán: %s

                                Vui lòng kiểm tra mục:
                                💰 Hóa đơn
                                """,
                                room.getRoomNumber(), invoice.getBillingMonth(), invoice.getBillingYear(),
                                rentStr, elecStr, waterStr, totalStr, dueDateStr).trim();

                for (User member : members) {
                        notificationService.createNotification(
                                        member.getTelegramId(),
                                        NotificationType.INVOICE_CREATED,
                                        title,
                                        dbMsg,
                                        "INVOICE",
                                        invoice.getId());
                        telegramNotificationService.sendNotification(member.getTelegramId(), telegramMsg);
                }
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        public void handlePaymentRecorded(PaymentRecordedEvent event) {
                Payment payment = event.getPayment();
                if (payment == null) return;

                List<Long> recipientIds = event.getRecipientTelegramIds();
                if (recipientIds == null || recipientIds.isEmpty()) return;
                var authorizedInvoice = invoiceRepository.findByIdWithDetails(event.getInvoice().getId());
                if (authorizedInvoice.isEmpty()) return;
                recipientIds = recipientIds.stream().filter(id -> userRepository.findByTelegramId(id)
                        .filter(u -> u.getRole() == Role.MEMBER)
                        .filter(u -> roomStayService.canReadInvoice(u, authorizedInvoice.get())).isPresent()).toList();

                String roomNum = event.getRoomNumber() != null ? event.getRoomNumber()
                                : (event.getRoom() != null ? event.getRoom().getRoomNumber() : "N/A");
                log.info("Received PaymentRecordedEvent for Payment ID={}, Invoice ID={}, Room={}",
                                payment.getId(),
                                event.getInvoice() != null ? event.getInvoice().getId() : null,
                                roomNum);

                String title = String.format("Thanh toán hóa đơn - Phòng %s", roomNum);

                String payAmountStr = formatNumber(event.getPaymentAmount());
                String totalStr = formatNumber(event.getTotalAmount());
                String paidStr = formatNumber(event.getPaidAmount());
                String remainingStr = formatNumber(event.getRemainingAmount());
                String statusIcon = event.getInvoiceStatus() != null ? event.getInvoiceStatus().getIcon() : "🟢";
                String statusName = event.getInvoiceStatus() != null ? event.getInvoiceStatus().getDisplayName() : "Đã thanh toán";
                Integer month = event.getBillingMonth();
                Integer year = event.getBillingYear();

                String paymentTimeStr = payment.getPaymentDate() != null
                                ? payment.getPaymentDate().format(DATE_TIME_FORMATTER)
                                : (payment.getCreatedAt() != null ? payment.getCreatedAt().format(DATE_TIME_FORMATTER) : java.time.LocalDateTime.now().format(DATE_TIME_FORMATTER));

                String dbMsg = String.format("🏠 Phòng: %s (Kỳ %02d/%d)\n💰 Số tiền thanh toán: %s VNĐ\n💵 Tổng hóa đơn: %s VNĐ\n💳 Đã thanh toán: %s VNĐ\n📌 Còn lại: %s VNĐ\n%s Trạng thái: %s",
                                roomNum, month, year,
                                payAmountStr, totalStr, paidStr, remainingStr, statusIcon, statusName);

                String telegramMsg = String.format("""
                                💳 THANH TOÁN HÓA ĐƠN

                                🏠 Phòng: %s
                                📅 Kỳ: %02d/%d

                                💰 Số tiền thanh toán: %s VNĐ
                                💵 Tổng hóa đơn: %s VNĐ
                                💳 Đã thanh toán: %s VNĐ
                                📌 Còn lại: %s VNĐ

                                %s Trạng thái: %s

                                🕒 %s
                                """,
                                roomNum, month, year,
                                payAmountStr, totalStr, paidStr, remainingStr, statusIcon, statusName, paymentTimeStr).trim();

                for (Long telegramId : recipientIds) {
                        if (notificationService.hasNotificationBeenSentToUser(telegramId, "PAYMENT", payment.getId(), NotificationType.PAYMENT_RECORDED)) {
                                log.info("Notification for Payment ID={} has already been sent to user Telegram ID={}. Skipping duplicate.", payment.getId(), telegramId);
                                continue;
                        }

                        notificationService.createNotification(
                                        telegramId,
                                        NotificationType.PAYMENT_RECORDED,
                                        title,
                                        dbMsg,
                                        "PAYMENT",
                                        payment.getId());
                        telegramNotificationService.sendNotification(telegramId, telegramMsg);
                }
        }

        private String formatNumber(BigDecimal val) {
                if (val == null) return "0";
                if (val.stripTrailingZeros().scale() <= 0) {
                        return String.format(Locale.US, "%,d", val.longValue());
                }
                return String.format(Locale.US, "%,.2f", val.doubleValue());
        }
}
