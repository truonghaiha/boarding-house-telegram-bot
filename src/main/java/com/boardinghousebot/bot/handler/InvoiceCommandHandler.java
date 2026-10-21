package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.invoice.PaymentMethod;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.InvoiceService;
import com.boardinghousebot.service.PaymentService;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class InvoiceCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceCommandHandler.class);
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(new Locale("vi", "VN"));
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @org.springframework.beans.factory.annotation.Autowired
    private UtilityCycleFlow cycleFlow;

    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final ConversationStateService stateService;

    public InvoiceCommandHandler(InvoiceService invoiceService,
                                 PaymentService paymentService,
                                 ConversationStateService stateService) {
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
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
        String action = update.hasMessage() ? update.getMessage().getText() : update.getCallbackQuery().getData();
        Long actionChatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        var actionStep = stateService.getState(user.getTelegramId()).getStep();
        if (!com.boardinghousebot.bot.command.RoleActionPolicy.allows(user, getCommand(), action, actionStep, update.hasMessage())) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendNotice(actionChatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

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

        if (cycleFlow != null && cycleFlow.invoice(chatId,user,text,state,messageSender)) return;

        if(text.startsWith("INVOICE_SELECT_ROOM_") || "CONFIRM_CREATE_INVOICE".equals(text)) {
            openInvoiceCreation(chatId,user,state,messageSender);
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

        switch (text) {
            case "LANDLORD_INVOICES", "INVOICE_MENU", "/invoice" -> showInvoiceMenu(chatId, user, messageSender);
            case "INVOICE_ADD" -> openInvoiceCreation(chatId, user, state, messageSender);
            case "INVOICE_LIST_ALL", "INVOICE_LIST_LATEST" -> showInvoiceGroups(chatId, user, messageSender);
            case "INVOICE_LIST_UNPAID" -> showInvoiceGroup(chatId, user, false, messageSender);
            case "INVOICE_LIST_PAID" -> showInvoiceGroup(chatId, user, true, messageSender);
            case "MEMBER_INVOICES", "MEMBER_INVOICE_LIST" -> showInvoiceGroups(chatId, user, messageSender);
            default -> showInvoiceMenu(chatId, user, messageSender);
        }
    }

    private void showInvoiceMenu(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() == Role.MEMBER) {
            showInvoiceGroups(chatId, user, messageSender);
            return;
        }
        messageSender.sendMessageWithKeyboard(chatId, "💰 QUẢN LÝ HÓA ĐƠN & THANH TOÁN\n\nVui lòng chọn thao tác bên dưới:", TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
    }

    private void openInvoiceCreation(Long chatId, User user, UserConversationState state, BotMessageSender sender) {
        stateService.clearState(user.getTelegramId());
        if(cycleFlow!=null) cycleFlow.invoice(chatId,user,"INVOICE_ADD",state,sender);
        else showInvoiceMenu(chatId,user,sender);
    }

    private void startRecordPaymentFlow(Long chatId, User user, Long invoiceId, UserConversationState state, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD) {
            messageSender.sendNotice(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        Invoice invoice = invoiceService.getInvoiceById(invoiceId);
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            messageSender.sendMessageWithKeyboard(chatId, "⚠️ Hóa đơn #" + invoiceId + " đã được thanh toán đủ.", TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
            return;
        }

        state.getData().clear();
        state.getData().put("invoiceId",invoiceId);
        state.getData().put("amount",invoice.getRemainingAmount());
        state.setStep(UserConversationState.Step.RECORD_PAYMENT_CONFIRM);
        stateService.setState(user.getTelegramId(),state);
        messageSender.sendMessageWithKeyboard(chatId,"💳 XÁC NHẬN THANH TOÁN HÓA ĐƠN #"+invoice.getId()
                +"\n\nPhòng: "+invoice.getRoom().getRoomNumber()
                +"\nKỳ: "+invoice.getPeriodDisplay()
                +"\nTổng thanh toán: "+CURRENCY_FORMAT.format(invoice.getRemainingAmount()),
                new InlineKeyboardMarkup(List.of(
                        new InlineKeyboardRow(TelegramKeyboardUtil.createButton("✅ Xác nhận đã nhận đủ tiền","CONFIRM_RECORD_PAYMENT")),
                        new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy","CANCEL_RECORD_PAYMENT")))));
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

        state.setStep(UserConversationState.Step.RECORD_PAYMENT_INVOICE_SELECT);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendMessageWithKeyboard(chatId, "💳 CHỌN HÓA ĐƠN CẦN GHI NHẬN THANH TOÁN:", new InlineKeyboardMarkup(rows));
    }

    private void handleConversationStep(Long chatId, User user, String input, UserConversationState state, BotMessageSender messageSender) {
        switch (state.getStep()) {
            case CREATE_INVOICE_ROOM_SELECT, CREATE_INVOICE_MONTH, CREATE_INVOICE_YEAR, CREATE_INVOICE_CONFIRM ->
                    openInvoiceCreation(chatId,user,state,messageSender);
            case RECORD_PAYMENT_INVOICE_SELECT -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng chọn hóa đơn từ danh sách phím bấm bên dưới.");
            }
            case RECORD_PAYMENT_AMOUNT, RECORD_PAYMENT_METHOD, RECORD_PAYMENT_NOTE -> startRecordPaymentFlow(chatId,user,
                    (Long)state.getData().get("invoiceId"),state,messageSender);
            case RECORD_PAYMENT_CONFIRM -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            default -> {
                stateService.clearState(user.getTelegramId());
                showInvoiceMenu(chatId, user, messageSender);
            }
        }
    }

    private void executeRecordPayment(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        try {
            if(state.getStep()!=UserConversationState.Step.RECORD_PAYMENT_CONFIRM)
                throw new BusinessException("Lựa chọn đã hết hiệu lực. Vui lòng mở lại thanh toán hóa đơn.");
            Long invoiceId = (Long) state.getData().get("invoiceId");
            BigDecimal amount = (BigDecimal) state.getData().get("amount");

            Invoice current=invoiceService.getInvoiceById(invoiceId);
            if(amount==null || amount.compareTo(current.getRemainingAmount())!=0)
                throw new BusinessException("Số tiền còn nợ đã thay đổi. Vui lòng mở lại thanh toán hóa đơn.");
            Payment payment = paymentService.recordFullPayment(user.getTelegramId(), invoiceId, PaymentMethod.UNSPECIFIED, "");

            stateService.clearState(user.getTelegramId());
            Invoice invoice = payment.getInvoice();

            String successMsg = String.format("✅ Đã ghi nhận thanh toán %s VNĐ thành công cho phòng %s!\nTrạng thái hóa đơn: %s %s",
                    CURRENCY_FORMAT.format(amount), invoice.getRoom().getRoomNumber(),
                    invoice.getStatus().getIcon(), invoice.getStatus().getDisplayName());

            messageSender.sendResultAndMenu(chatId, successMsg, TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void showInvoiceGroups(Long chatId, User user, BotMessageSender sender) {
        stateService.clearState(user.getTelegramId());
        if(user.getRole()==Role.MEMBER) { showInvoiceGroup(chatId,user,false,sender); return; }
        sender.sendMessageWithKeyboard(chatId,"💰 DANH SÁCH HÓA ĐƠN",new InlineKeyboardMarkup(List.of(
                new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔴 Chưa thanh toán","INVOICE_LIST_UNPAID")),
                new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🟢 Đã thanh toán","INVOICE_LIST_PAID")),
                new InlineKeyboardRow(TelegramKeyboardUtil.createButton("⬅️ Menu chính","MAIN_MENU")))));
    }

    private void showInvoiceGroup(Long chatId, User user, boolean paid, BotMessageSender sender) {
        if(user.getRole()==Role.MEMBER) {
            String text=invoiceService.getMemberInvoices(user.getTelegramId()).stream()
                    .filter(invoice -> invoice.getRemainingAmount().signum()>0)
                    .map(this::invoiceText).collect(java.util.stream.Collectors.joining("\n\n────────────────\n\n"));
            sender.sendMessageWithKeyboard(chatId,text.isEmpty()?"Không có hóa đơn chưa thanh toán.":text,
                    TelegramKeyboardUtil.getMemberInvoiceMenu());
            return;
        }
        var rows=new ArrayList<InlineKeyboardRow>();
        var visible=invoiceService.getAllInvoices();
        for(var invoice:visible) {
            if((invoice.getRemainingAmount().signum()<=0)!=paid) continue;
            String label="Phòng "+invoice.getRoom().getRoomNumber()+" · "+invoice.getPeriodDisplay();
            label+=" · Tổng cộng: "+CURRENCY_FORMAT.format(invoice.getTotalAmount());
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label,"INVOICE_DETAIL_"+invoice.getId())));
        }
        String title=paid?"🟢 HÓA ĐƠN ĐÃ THANH TOÁN":"🔴 HÓA ĐƠN CHƯA THANH TOÁN";
        String text=title+"\n\n"+(rows.isEmpty()?(paid?"Chưa có hóa đơn đã thanh toán.":"Không có hóa đơn chưa thanh toán."):"Chọn hóa đơn để xem chi tiết.");
        if(user.getRole()==Role.LANDLORD) rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("⬅️ Danh sách hóa đơn","INVOICE_LIST_LATEST")));
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("⬅️ Menu chính","MAIN_MENU")));
        sender.sendMessageWithKeyboard(chatId,text,new InlineKeyboardMarkup(rows));
    }

    private String invoiceText(Invoice inv) {
        boolean isPaid=inv.getRemainingAmount().signum()<=0;

        String dueDateStr = inv.getDueDate() != null ? inv.getDueDate().format(DATE_FORMATTER) : "N/A";

        StringBuilder sb = new StringBuilder(String.format("""
                💰 CHI TIẾT HÓA ĐƠN #%d

                🏠 Phòng: %s
                📅 Kỳ: %s
                📅 Hạn thanh toán: %s

                🏠 Tiền phòng: %s
                ⚡ Tiền điện: %s
                💧 Tiền nước: %s
                ────────────────
                💰 Tổng cộng: %s

                Trạng thái: %s %s
                """,
                inv.getId(),
                inv.getRoom().getRoomNumber(),
                inv.getPeriodDisplay(),
                dueDateStr,
                CURRENCY_FORMAT.format(inv.getRoomRentAmount()),
                CURRENCY_FORMAT.format(inv.getElectricityAmount()),
                CURRENCY_FORMAT.format(inv.getWaterAmount()),
                CURRENCY_FORMAT.format(inv.getTotalAmount()),
                isPaid?"🟢":"🔴", isPaid?"Đã thanh toán":"Chưa thanh toán"
        ));

        if(inv.getNote()!=null && !inv.getNote().isBlank()) sb.append("\n📝 Ghi chú: ").append(inv.getNote()).append("\n");
        if(inv.getCheckoutDate()!=null) sb.append("Ngày trả phòng: ").append(inv.getCheckoutDate().format(DATE_FORMATTER)).append("\n");
        return sb.toString().trim();
    }

    private void showInvoiceDetail(Long chatId, User user, Long invoiceId, BotMessageSender messageSender) {
        Invoice inv = invoiceService.getInvoiceForUser(user, invoiceId);
        boolean isPaid=inv.getRemainingAmount().signum()<=0;
        String text=invoiceText(inv);
        if (user.getRole() == Role.LANDLORD) {
            messageSender.sendMessageWithKeyboard(chatId, text, TelegramKeyboardUtil.getInvoiceDetailKeyboard(inv.getId(), isPaid));
        } else {
            messageSender.sendMessageWithKeyboard(chatId,text,TelegramKeyboardUtil.getMemberInvoiceMenu());
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
