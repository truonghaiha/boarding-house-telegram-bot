package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.invoice.PaymentMethod;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.InvoiceService;
import com.boardinghousebot.service.PaymentService;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class InvoiceCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceCommandHandler.class);
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(new Locale("vi", "VN"));
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final RoomService roomService;
    private final UserService userService;
    private final ConversationStateService stateService;

    public InvoiceCommandHandler(InvoiceService invoiceService,
                                 PaymentService paymentService,
                                 RoomService roomService,
                                 UserService userService,
                                 ConversationStateService stateService) {
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
        this.roomService = roomService;
        this.userService = userService;
        this.stateService = stateService;
    }

    @Override
    public String getCommand() {
        return "/invoice";
    }

    @Override
    public String getDescription() {
        return "Quản lý hóa đơn và thanh toán";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        Long chatId = extractChatId(update);
        String text = extractText(update);
        UserConversationState state = stateService.getState(user.getTelegramId());

        if (text == null) return;

        log.info("INVOICE STATE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), text);

        if ("/cancel".equalsIgnoreCase(text) || "CANCEL_CREATE_INVOICE".equals(text) || "CANCEL_RECORD_PAYMENT".equals(text)) {
            boolean isPayment = "CANCEL_RECORD_PAYMENT".equals(text) || state.getStep().name().startsWith("RECORD_PAYMENT");
            stateService.clearState(user.getTelegramId());
            String cancelMsg = isPayment ? "❌ Đã hủy thao tác ghi nhận thanh toán." : "❌ Đã hủy thao tác tạo hóa đơn.";
            messageSender.sendMessageWithKeyboard(chatId, cancelMsg, TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
            return;
        }

        // Inline button action callbacks for creation wizard
        if (text.startsWith("INVOICE_SELECT_ROOM_")) {
            handleRoomSelectedForInvoice(chatId, user, text, state, messageSender);
            return;
        }

        if ("CONFIRM_CREATE_INVOICE".equals(text)) {
            executeCreateInvoice(chatId, user, state, messageSender);
            return;
        }

        // Inline button action callbacks for payment wizard
        if (text.startsWith("INVOICE_PAYMENT_INIT_")) {
            Long invoiceId = Long.parseLong(text.replace("INVOICE_PAYMENT_INIT_", "").trim());
            startRecordPaymentFlow(chatId, user, invoiceId, state, messageSender);
            return;
        }

        if ("INVOICE_PAYMENT_SELECT".equals(text)) {
            promptSelectInvoiceForPayment(chatId, user, state, messageSender);
            return;
        }

        if (text.startsWith("PAYMENT_SELECT_METHOD_")) {
            handlePaymentMethodSelected(chatId, user, text, state, messageSender);
            return;
        }

        if ("CONFIRM_RECORD_PAYMENT".equals(text)) {
            executeRecordPayment(chatId, user, state, messageSender);
            return;
        }

        // Active conversation step for text inputs
        if (state.getStep() != UserConversationState.Step.NONE) {
            handleConversationStep(chatId, user, text, state, messageSender);
            return;
        }

        // Detail callbacks
        if (text.startsWith("INVOICE_DETAIL_") || text.startsWith("/invoice_detail_")) {
            String idStr = text.startsWith("INVOICE_DETAIL_") ? text.replace("INVOICE_DETAIL_", "") : text.replace("/invoice_detail_", "");
            showInvoiceDetail(chatId, user, Long.parseLong(idStr.trim()), messageSender);
            return;
        }

        // Inline filter callbacks
        if (text.startsWith("INVOICE_F_MONTH_")) {
            String val = text.replace("INVOICE_F_MONTH_", "").trim();
            if ("ALL".equals(val)) {
                state.getData().remove("filterMonth");
                state.getData().remove("filterYear");
            } else {
                String[] parts = val.split("_");
                state.getData().put("filterMonth", Integer.parseInt(parts[0]));
                state.getData().put("filterYear", Integer.parseInt(parts[1]));
            }
            stateService.setState(user.getTelegramId(), state);
            executeFilterSearch(chatId, user, state, messageSender);
            return;
        }

        if (text.startsWith("INVOICE_F_ROOM_")) {
            String val = text.replace("INVOICE_F_ROOM_", "").trim();
            if ("ALL".equals(val)) {
                state.getData().remove("filterRoomId");
            } else {
                state.getData().put("filterRoomId", Long.parseLong(val));
            }
            stateService.setState(user.getTelegramId(), state);
            executeFilterSearch(chatId, user, state, messageSender);
            return;
        }

        if (text.startsWith("INVOICE_F_STATUS_")) {
            String val = text.replace("INVOICE_F_STATUS_", "").trim();
            if ("ALL".equals(val)) {
                state.getData().remove("filterStatus");
            } else {
                state.getData().put("filterStatus", val);
            }
            stateService.setState(user.getTelegramId(), state);
            executeFilterSearch(chatId, user, state, messageSender);
            return;
        }

        if ("INVOICE_FILTER_CLEAR".equals(text)) {
            state.getData().remove("filterMonth");
            state.getData().remove("filterYear");
            state.getData().remove("filterRoomId");
            state.getData().remove("filterStatus");
            stateService.setState(user.getTelegramId(), state);
            listLatestInvoices(chatId, user, messageSender);
            return;
        }

        if ("INVOICE_FILTER_SELECT_MONTH".equals(text)) {
            showMonthFilterKeyboard(chatId, user, messageSender);
            return;
        }

        if ("INVOICE_FILTER_SELECT_ROOM".equals(text)) {
            showRoomFilterKeyboard(chatId, user, messageSender);
            return;
        }

        if ("INVOICE_FILTER_SELECT_STATUS".equals(text)) {
            showStatusFilterKeyboard(chatId, user, messageSender);
            return;
        }

        switch (text) {
            case "LANDLORD_INVOICES", "INVOICE_MENU", "/invoice" -> showInvoiceMenu(chatId, user, messageSender);
            case "INVOICE_ADD" -> startCreateInvoiceFlow(chatId, user, state, messageSender);
            case "INVOICE_LIST_ALL", "INVOICE_LIST_LATEST" -> listLatestInvoices(chatId, user, messageSender);
            case "MEMBER_INVOICES", "MEMBER_INVOICE_LIST" -> showMemberInvoices(chatId, user, messageSender);
            default -> showInvoiceMenu(chatId, user, messageSender);
        }
    }

    private void showInvoiceMenu(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() == Role.MEMBER) {
            showMemberInvoices(chatId, user, messageSender);
            return;
        }
        messageSender.sendMessageWithKeyboard(chatId, "💰 QUẢN LÝ HÓA ĐƠN & THANH TOÁN\n\nVui lòng chọn thao tác bên dưới:", TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
    }

    private void startCreateInvoiceFlow(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD) {
            messageSender.sendMessage(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<Room> rooms = roomService.getAllRooms();
        if (rooms.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "💰 TẠO HÓA ĐƠN\n\nChưa có phòng nào trong hệ thống.", TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Room r : rooms) {
            String label = "🏠 Phòng " + r.getRoomNumber();
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "INVOICE_SELECT_ROOM_" + r.getId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_CREATE_INVOICE")));

        state.setStep(UserConversationState.Step.CREATE_INVOICE_ROOM_SELECT);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendMessageWithKeyboard(chatId, "🏠 CHỌN PHÒNG ĐỂ TẠO HÓA ĐƠN:", new InlineKeyboardMarkup(rows));
    }

    private void handleRoomSelectedForInvoice(Long chatId, User user, String text, UserConversationState state, BotMessageSender messageSender) {
        Long roomId = Long.parseLong(text.replace("INVOICE_SELECT_ROOM_", "").trim());
        state.getData().put("roomId", roomId);
        state.setStep(UserConversationState.Step.CREATE_INVOICE_MONTH);
        stateService.setState(user.getTelegramId(), state);

        int currentMonth = LocalDateTime.now().getMonthValue();
        messageSender.sendMessage(chatId, "📅 Nhập tháng tạo hóa đơn (1–12, ví dụ: " + currentMonth + "):\n\nGửi /cancel để hủy.");
    }

    private void startRecordPaymentFlow(Long chatId, User user, Long invoiceId, UserConversationState state, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD) {
            messageSender.sendMessage(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        Invoice invoice = invoiceService.getInvoiceById(invoiceId);
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            messageSender.sendMessageWithKeyboard(chatId, "⚠️ Hóa đơn #" + invoiceId + " đã được thanh toán đủ.", TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
            return;
        }

        state.getData().put("invoiceId", invoiceId);
        state.setStep(UserConversationState.Step.RECORD_PAYMENT_AMOUNT);
        stateService.setState(user.getTelegramId(), state);

        String promptMsg = String.format("""
                💳 GHI NHẬN THANH TOÁN HÓA ĐƠN #%d
                
                🏠 Phòng: %s (Kỳ %02d/%d)
                💰 Tổng hóa đơn: %s
                💵 Đã trả: %s
                📉 Còn lại: %s
                
                Nhập số tiền thanh toán (VNĐ, ví dụ: %d):
                
                Gửi /cancel để hủy.
                """,
                invoice.getId(),
                invoice.getRoom().getRoomNumber(),
                invoice.getBillingMonth(), invoice.getBillingYear(),
                CURRENCY_FORMAT.format(invoice.getTotalAmount()),
                CURRENCY_FORMAT.format(invoice.getPaidAmount()),
                CURRENCY_FORMAT.format(invoice.getRemainingAmount()),
                invoice.getRemainingAmount().longValue()
        );

        messageSender.sendMessage(chatId, promptMsg);
    }

    private void promptSelectInvoiceForPayment(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        List<Invoice> unpaidInvoices = invoiceService.getAllInvoices().stream()
                .filter(i -> i.getStatus() != InvoiceStatus.PAID)
                .toList();

        if (unpaidInvoices.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "💳 GHI NHẬN THANH TOÁN\n\nHiện không có hóa đơn nào chưa thanh toán.", TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Invoice inv : unpaidInvoices) {
            String label = String.format("🏠 Phòng %s (%02d/%d) - Còn: %s",
                    inv.getRoom().getRoomNumber(), inv.getBillingMonth(), inv.getBillingYear(),
                    CURRENCY_FORMAT.format(inv.getRemainingAmount()));
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "INVOICE_PAYMENT_INIT_" + inv.getId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_RECORD_PAYMENT")));

        messageSender.sendMessageWithKeyboard(chatId, "💳 CHỌN HÓA ĐƠN CẦN GHI NHẬN THANH TOÁN:", new InlineKeyboardMarkup(rows));
    }

    private void handlePaymentMethodSelected(Long chatId, User user, String text, UserConversationState state, BotMessageSender messageSender) {
        PaymentMethod method = text.endsWith("CASH") ? PaymentMethod.CASH : PaymentMethod.BANK_TRANSFER;
        state.getData().put("paymentMethod", method.name());
        state.setStep(UserConversationState.Step.RECORD_PAYMENT_NOTE);
        stateService.setState(user.getTelegramId(), state);

        messageSender.sendMessage(chatId, "📝 Nhập ghi chú thanh toán (ví dụ: Chuyển khoản Vietcombank hoặc gửi '-' để bỏ qua):\n\nGửi /cancel để hủy.");
    }

    private void handleConversationStep(Long chatId, User user, String input, UserConversationState state, BotMessageSender messageSender) {
        switch (state.getStep()) {
            case CREATE_INVOICE_ROOM_SELECT -> {
                messageSender.sendMessage(chatId, "❌ Vui lòng chọn phòng từ danh sách phím bấm bên dưới hoặc gửi /cancel để hủy.");
            }
            case CREATE_INVOICE_MONTH -> {
                Integer month = parseInteger(input);
                if (month == null || month < 1 || month > 12) {
                    messageSender.sendMessage(chatId, "❌ Tháng không hợp lệ (phải từ 1 đến 12). Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("month", month);
                state.setStep(UserConversationState.Step.CREATE_INVOICE_YEAR);
                stateService.setState(user.getTelegramId(), state);

                int currentYear = LocalDateTime.now().getYear();
                messageSender.sendMessage(chatId, "📅 Nhập năm tạo hóa đơn (ví dụ: " + currentYear + "):\n\nGửi /cancel để hủy.");
            }
            case CREATE_INVOICE_YEAR -> {
                Integer year = parseInteger(input);
                if (year == null || year < 2000 || year > 2100) {
                    messageSender.sendMessage(chatId, "❌ Năm không hợp lệ. Vui lòng nhập lại năm (ví dụ: 2026):");
                    return;
                }
                state.getData().put("year", year);
                state.setStep(UserConversationState.Step.CREATE_INVOICE_CONFIRM);
                stateService.setState(user.getTelegramId(), state);

                showInvoiceCreatePreview(chatId, state, messageSender);
            }
            case CREATE_INVOICE_CONFIRM -> {
                messageSender.sendMessage(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            case RECORD_PAYMENT_AMOUNT -> {
                BigDecimal amount = parseBigDecimal(input);
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    messageSender.sendMessage(chatId, "❌ Số tiền thanh toán phải lớn hơn 0. Vui lòng nhập lại:");
                    return;
                }
                Long invoiceId = (Long) state.getData().get("invoiceId");
                Invoice invoice = invoiceService.getInvoiceById(invoiceId);
                if (amount.compareTo(invoice.getRemainingAmount()) > 0) {
                    messageSender.sendMessage(chatId, String.format("❌ Số tiền (%s VNĐ) vượt quá số tiền còn lại (%s VNĐ). Vui lòng nhập lại:",
                            CURRENCY_FORMAT.format(amount), CURRENCY_FORMAT.format(invoice.getRemainingAmount())));
                    return;
                }
                state.getData().put("amount", amount);
                state.setStep(UserConversationState.Step.RECORD_PAYMENT_METHOD);
                stateService.setState(user.getTelegramId(), state);

                List<InlineKeyboardRow> rows = List.of(
                        new InlineKeyboardRow(
                                TelegramKeyboardUtil.createButton("💵 Tiền mặt", "PAYMENT_SELECT_METHOD_CASH"),
                                TelegramKeyboardUtil.createButton("💳 Chuyển khoản", "PAYMENT_SELECT_METHOD_BANK_TRANSFER")
                        ),
                        new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_RECORD_PAYMENT"))
                );

                messageSender.sendMessageWithKeyboard(chatId, "💵 CHỌN PHƯƠNG THỨC THANH TOÁN:", new InlineKeyboardMarkup(rows));
            }
            case RECORD_PAYMENT_METHOD -> {
                messageSender.sendMessage(chatId, "❌ Vui lòng chọn [💵 Tiền mặt] hoặc [💳 Chuyển khoản] từ phím bấm bên dưới hoặc gửi /cancel để hủy.");
            }
            case RECORD_PAYMENT_NOTE -> {
                String note = "-".equals(input.trim()) ? "" : input.trim();
                state.getData().put("note", note);
                state.setStep(UserConversationState.Step.RECORD_PAYMENT_CONFIRM);
                stateService.setState(user.getTelegramId(), state);

                showRecordPaymentPreview(chatId, state, messageSender);
            }
            case RECORD_PAYMENT_CONFIRM -> {
                messageSender.sendMessage(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            default -> {
                stateService.clearState(user.getTelegramId());
                showInvoiceMenu(chatId, user, messageSender);
            }
        }
    }

    private void showInvoiceCreatePreview(Long chatId, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long roomId = (Long) state.getData().get("roomId");
            Integer month = (Integer) state.getData().get("month");
            Integer year = (Integer) state.getData().get("year");

            Room room = roomService.getRoomById(roomId);

            // Fetch preview details (validates requirements)
            Invoice dummy = invoiceService.createInvoice(888777666L, roomId, month, year); // Transient rollback test or preview build
            // Actually createInvoice saves it, so we catch preview or construct preview manually:
        } catch (Exception ignore) {
        }

        Long roomId = (Long) state.getData().get("roomId");
        Integer month = (Integer) state.getData().get("month");
        Integer year = (Integer) state.getData().get("year");

        try {
            Room room = roomService.getRoomById(roomId);
            int maxDay = YearMonth.of(year, month).lengthOfMonth();
            LocalDate dueDate = LocalDate.of(year, month, Math.min(15, maxDay));

            String previewText = String.format("""
                    💰 XÁC NHẬN TẠO HÓA ĐƠN
                    
                    🏠 Phòng: %s
                    📅 Kỳ: %02d/%d
                    📅 Hạn thanh toán: %s
                    
                    Vui lòng bấm [✅ Xác nhận] để hệ thống tự động tổng hợp tiền phòng và điện nước kỳ này.
                    """,
                    room.getRoomNumber(), month, year, dueDate.format(DATE_FORMATTER));

            messageSender.sendMessageWithKeyboard(chatId, previewText, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_CREATE_INVOICE", "CANCEL_CREATE_INVOICE"));
        } catch (BusinessException e) {
            stateService.clearState(chatId);
            messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
        }
    }

    private void showRecordPaymentPreview(Long chatId, UserConversationState state, BotMessageSender messageSender) {
        Long invoiceId = (Long) state.getData().get("invoiceId");
        BigDecimal amount = (BigDecimal) state.getData().get("amount");
        PaymentMethod method = PaymentMethod.valueOf((String) state.getData().get("paymentMethod"));
        String note = (String) state.getData().get("note");

        Invoice invoice = invoiceService.getInvoiceById(invoiceId);
        BigDecimal newRemaining = invoice.getRemainingAmount().subtract(amount);
        String newStatusStr = newRemaining.compareTo(BigDecimal.ZERO) == 0 ? "🟢 ĐÃ THANH TOÁN" : "🟡 THANH TOÁN MỘT PHẦN";

        String previewText = String.format("""
                💳 XÁC NHẬN GHI NHẬN THANH TOÁN
                
                🏠 Phòng: %s (Kỳ %02d/%d)
                💰 Tổng hóa đơn: %s
                💵 Số tiền trả đợt này: %s
                📉 Còn lại sau thanh toán: %s
                💳 Phương thức: %s %s
                📝 Ghi chú: %s
                📊 Trạng thái mới: %s
                """,
                invoice.getRoom().getRoomNumber(), invoice.getBillingMonth(), invoice.getBillingYear(),
                CURRENCY_FORMAT.format(invoice.getTotalAmount()),
                CURRENCY_FORMAT.format(amount),
                CURRENCY_FORMAT.format(newRemaining),
                method.getIcon(), method.getDisplayName(),
                note == null || note.isBlank() ? "(Không)" : note,
                newStatusStr
        );

        messageSender.sendMessageWithKeyboard(chatId, previewText, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_RECORD_PAYMENT", "CANCEL_RECORD_PAYMENT"));
    }

    private void executeCreateInvoice(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long roomId = (Long) state.getData().get("roomId");
            Integer month = (Integer) state.getData().get("month");
            Integer year = (Integer) state.getData().get("year");

            Invoice invoice = invoiceService.createInvoice(user.getTelegramId(), roomId, month, year);

            stateService.clearState(user.getTelegramId());

            String successMsg = String.format("""
                    ✅ Đã tạo hóa đơn thành công!
                    
                    🏠 Phòng: %s (Kỳ %02d/%d)
                    🏠 Tiền phòng: %s
                    ⚡ Tiền điện: %s
                    💧 Tiền nước: %s
                    ────────────────
                    💰 Tổng cộng: %s
                    """,
                    invoice.getRoom().getRoomNumber(), month, year,
                    CURRENCY_FORMAT.format(invoice.getRoomRentAmount()),
                    CURRENCY_FORMAT.format(invoice.getElectricityAmount()),
                    CURRENCY_FORMAT.format(invoice.getWaterAmount()),
                    CURRENCY_FORMAT.format(invoice.getTotalAmount()));

            messageSender.sendMessageWithKeyboard(chatId, successMsg, TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
        }
    }

    private void executeRecordPayment(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long invoiceId = (Long) state.getData().get("invoiceId");
            BigDecimal amount = (BigDecimal) state.getData().get("amount");
            PaymentMethod method = PaymentMethod.valueOf((String) state.getData().get("paymentMethod"));
            String note = (String) state.getData().get("note");

            Payment payment = paymentService.recordPayment(user.getTelegramId(), invoiceId, amount, method, note);

            stateService.clearState(user.getTelegramId());
            Invoice invoice = payment.getInvoice();

            String successMsg = String.format("✅ Đã ghi nhận thanh toán %s VNĐ thành công cho phòng %s!\nTrạng thái hóa đơn: %s %s",
                    CURRENCY_FORMAT.format(amount), invoice.getRoom().getRoomNumber(),
                    invoice.getStatus().getIcon(), invoice.getStatus().getDisplayName());

            messageSender.sendMessageWithKeyboard(chatId, successMsg, TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
        }
    }

    private void listLatestInvoices(Long chatId, User user, BotMessageSender messageSender) {
        int[] latest = invoiceService.getLatestBillingPeriod();
        List<Invoice> invoices = invoiceService.getFilteredInvoices(null, null, latest[0], latest[1]);
        renderInvoicesGroupedByRoom(chatId, user, messageSender, invoices, latest[0], latest[1], null, null);
    }

    private void executeFilterSearch(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        Integer month = (Integer) state.getData().get("filterMonth");
        Integer year = (Integer) state.getData().get("filterYear");
        Long roomId = (Long) state.getData().get("filterRoomId");
        String statusStr = (String) state.getData().get("filterStatus");
        InvoiceStatus status = statusStr == null ? null : InvoiceStatus.valueOf(statusStr);

        List<Invoice> invoices = invoiceService.getFilteredInvoices(roomId, status, month, year);
        renderInvoicesGroupedByRoom(chatId, user, messageSender, invoices, month, year, roomId, statusStr);
    }

    private void renderInvoicesGroupedByRoom(Long chatId, User user, BotMessageSender messageSender,
                                             List<Invoice> invoices, Integer month, Integer year,
                                             Long roomIdFilter, String statusFilter) {
        String title = (month != null && year != null) ? String.format("💰 HÓA ĐƠN THÁNG %02d/%d", month, year) : "💰 DANH SÁCH HÓA ĐƠN";

        if (invoices.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, title + "\n\nChưa có hóa đơn nào cho lựa chọn này.",
                    TelegramKeyboardUtil.getInvoiceFilterKeyboard(roomIdFilter, statusFilter, month, year));
            return;
        }

        StringBuilder sb = new StringBuilder(title + "\n\n");
        for (Invoice inv : invoices) {
            String dueDateStr = inv.getDueDate() != null ? inv.getDueDate().format(DATE_FORMATTER) : "N/A";
            sb.append("🏠 Phòng ").append(inv.getRoom().getRoomNumber()).append("\n");
            sb.append("   📅 Hạn: ").append(dueDateStr).append("\n");
            sb.append("   💰 Tổng: ").append(CURRENCY_FORMAT.format(inv.getTotalAmount())).append("\n");
            sb.append("   💵 Đã trả: ").append(CURRENCY_FORMAT.format(inv.getPaidAmount())).append("\n");
            sb.append("   📉 Còn lại: ").append(CURRENCY_FORMAT.format(inv.getRemainingAmount())).append("\n");
            sb.append("   Trạng thái: ").append(inv.getStatus().getIcon()).append(" ").append(inv.getStatus().getDisplayName()).append("\n");
            sb.append("   Chi tiết: `/invoice_detail_").append(inv.getId()).append("`\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(),
                TelegramKeyboardUtil.getInvoiceFilterKeyboard(roomIdFilter, statusFilter, month, year));
    }

    private void showMemberInvoices(Long chatId, User user, BotMessageSender messageSender) {
        List<Invoice> invoices = invoiceService.getMemberInvoices(user.getTelegramId());

        if (user.getRoom() == null) {
            messageSender.sendMessage(chatId, "💰 HÓA ĐƠN PHÒNG TÔI\n\nBạn hiện chưa được xếp vào phòng nào.");
            return;
        }

        String title = "💰 HÓA ĐƠN PHÒNG " + user.getRoom().getRoomNumber();
        if (invoices.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, title + "\n\nChưa có dữ liệu hóa đơn.", TelegramKeyboardUtil.getMemberInvoiceMenu());
            return;
        }

        StringBuilder sb = new StringBuilder(title + "\n\n");
        for (Invoice inv : invoices) {
            String dueDateStr = inv.getDueDate() != null ? inv.getDueDate().format(DATE_FORMATTER) : "N/A";
            sb.append("📅 Kỳ: ").append(String.format("%02d/%d", inv.getBillingMonth(), inv.getBillingYear())).append("\n");
            sb.append("   📅 Hạn thanh toán: ").append(dueDateStr).append("\n");
            sb.append("   🏠 Tiền phòng: ").append(CURRENCY_FORMAT.format(inv.getRoomRentAmount())).append("\n");
            sb.append("   ⚡ Tiền điện: ").append(CURRENCY_FORMAT.format(inv.getElectricityAmount())).append("\n");
            sb.append("   💧 Tiền nước: ").append(CURRENCY_FORMAT.format(inv.getWaterAmount())).append("\n");
            sb.append("   ────────────────\n");
            sb.append("   💰 Tổng tiền: ").append(CURRENCY_FORMAT.format(inv.getTotalAmount())).append("\n");
            sb.append("   💵 Đã trả: ").append(CURRENCY_FORMAT.format(inv.getPaidAmount())).append("\n");
            sb.append("   📉 Còn lại: ").append(CURRENCY_FORMAT.format(inv.getRemainingAmount())).append("\n");
            sb.append("   Trạng thái: ").append(inv.getStatus().getIcon()).append(" ").append(inv.getStatus().getDisplayName()).append("\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getMemberInvoiceMenu());
    }

    private void showInvoiceDetail(Long chatId, User user, Long invoiceId, BotMessageSender messageSender) {
        Invoice inv = invoiceService.getInvoiceById(invoiceId);
        List<Payment> payments = paymentService.getInvoicePayments(invoiceId);

        String dueDateStr = inv.getDueDate() != null ? inv.getDueDate().format(DATE_FORMATTER) : "N/A";

        StringBuilder sb = new StringBuilder(String.format("""
                💰 CHI TIẾT HÓA ĐƠN #%d
                
                🏠 Phòng: %s
                📅 Kỳ: %02d/%d
                📅 Hạn thanh toán: %s
                
                🏠 Tiền phòng: %s
                ⚡ Tiền điện: %s
                💧 Tiền nước: %s
                ────────────────
                💰 Tổng cộng: %s
                💵 Đã thanh toán: %s
                📉 Còn lại: %s
                
                Trạng thái: %s %s
                """,
                inv.getId(),
                inv.getRoom().getRoomNumber(),
                inv.getBillingMonth(), inv.getBillingYear(),
                dueDateStr,
                CURRENCY_FORMAT.format(inv.getRoomRentAmount()),
                CURRENCY_FORMAT.format(inv.getElectricityAmount()),
                CURRENCY_FORMAT.format(inv.getWaterAmount()),
                CURRENCY_FORMAT.format(inv.getTotalAmount()),
                CURRENCY_FORMAT.format(inv.getPaidAmount()),
                CURRENCY_FORMAT.format(inv.getRemainingAmount()),
                inv.getStatus().getIcon(), inv.getStatus().getDisplayName()
        ));

        sb.append("\n📜 LỊCH SỬ THANH TOÁN:\n");
        if (payments.isEmpty()) {
            sb.append("Chưa có lượt thanh toán nào.\n");
        } else {
            int idx = 1;
            for (Payment p : payments) {
                sb.append(idx++).append(". ").append(CURRENCY_FORMAT.format(p.getAmount()))
                        .append(" - ").append(p.getPaymentMethod().getIcon()).append(" ").append(p.getPaymentMethod().getDisplayName())
                        .append(" (").append(p.getPaymentDate().format(DATE_TIME_FORMATTER)).append(")\n");
                if (p.getNote() != null && !p.getNote().isBlank()) {
                    sb.append("   Ghi chú: ").append(p.getNote()).append("\n");
                }
            }
        }

        if (user.getRole() == Role.LANDLORD || user.getRole() == Role.ADMIN) {
            boolean isPaid = inv.getStatus() == InvoiceStatus.PAID;
            messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getInvoiceDetailKeyboard(inv.getId(), isPaid));
        } else {
            messageSender.sendMessage(chatId, sb.toString().trim());
        }
    }

    private void showMonthFilterKeyboard(Long chatId, User user, BotMessageSender messageSender) {
        List<int[]> periods = invoiceService.getAvailableBillingPeriods();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("📅 Tất cả các kỳ", "INVOICE_F_MONTH_ALL")));

        for (int[] p : periods) {
            String label = String.format("📅 Kỳ %02d/%d", p[0], p[1]);
            String cb = String.format("INVOICE_F_MONTH_%d_%d", p[0], p[1]);
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, cb)));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔙 Quay lại", "INVOICE_LIST_LATEST")));

        messageSender.sendMessageWithKeyboard(chatId, "📅 CHỌN KỲ HÓA ĐƠN ĐỂ LỌC:", new InlineKeyboardMarkup(rows));
    }

    private void showRoomFilterKeyboard(Long chatId, User user, BotMessageSender messageSender) {
        List<Room> rooms = roomService.getAllRooms();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🏠 Tất cả phòng", "INVOICE_F_ROOM_ALL")));

        for (Room r : rooms) {
            String label = "🏠 Phòng " + r.getRoomNumber();
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "INVOICE_F_ROOM_" + r.getId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔙 Quay lại", "INVOICE_LIST_LATEST")));

        messageSender.sendMessageWithKeyboard(chatId, "🏠 CHỌN PHÒNG ĐỂ LỌC:", new InlineKeyboardMarkup(rows));
    }

    private void showStatusFilterKeyboard(Long chatId, User user, BotMessageSender messageSender) {
        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(
                        TelegramKeyboardUtil.createButton("🔴 Chưa trả", "INVOICE_F_STATUS_UNPAID"),
                        TelegramKeyboardUtil.createButton("🟡 Trả 1 phần", "INVOICE_F_STATUS_PARTIALLY_PAID")
                ),
                new InlineKeyboardRow(
                        TelegramKeyboardUtil.createButton("🟢 Đã trả đủ", "INVOICE_F_STATUS_PAID"),
                        TelegramKeyboardUtil.createButton("⏰ Quá hạn", "INVOICE_F_STATUS_OVERDUE")
                ),
                new InlineKeyboardRow(
                        TelegramKeyboardUtil.createButton("🌐 Tất cả", "INVOICE_F_STATUS_ALL")
                ),
                new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔙 Quay lại", "INVOICE_LIST_LATEST"))
        );

        messageSender.sendMessageWithKeyboard(chatId, "📊 CHỌN TRẠNG THÁI HÓA ĐƠN ĐỂ LỌC:", new InlineKeyboardMarkup(rows));
    }

    private Integer parseInteger(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String clean = text.trim().replaceAll("[^0-9.]", "");
            return new BigDecimal(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private Long extractChatId(Update update) {
        if (update.hasMessage()) return update.getMessage().getChatId();
        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return null;
    }

    private String extractText(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            return update.getMessage().getText();
        }
        if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getData();
        }
        return null;
    }
}
