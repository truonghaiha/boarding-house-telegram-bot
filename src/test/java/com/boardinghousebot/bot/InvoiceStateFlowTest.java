package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.handler.InvoiceCommandHandler;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.repository.InvoiceRepository;
import com.boardinghousebot.repository.PaymentRepository;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class InvoiceStateFlowTest {

    @Autowired
    private InvoiceCommandHandler invoiceCommandHandler;

    @Autowired
    private ConversationStateService stateService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User landlordUser;
    private Room room103;
    private BotMessageSender mockMessageSender;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_room_period ON invoices(room_id, billing_month, billing_year)");

        paymentRepository.deleteAll();
        invoiceRepository.deleteAll();
        contractRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();

        paymentRepository.flush();
        invoiceRepository.flush();
        contractRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        landlordUser = new User();
        landlordUser.setTelegramId(888777666L);
        landlordUser.setRole(Role.LANDLORD);
        landlordUser.setFullName("Test Landlord");
        landlordUser = userRepository.save(landlordUser);

        room103 = new Room("103", new BigDecimal("3500000"), 2, "Phòng 103");
        room103 = roomRepository.save(room103);

        stateService.clearState(landlordUser.getTelegramId());

        mockMessageSender = new BotMessageSender() {
            @Override
            public void sendMessage(Long chatId, String text) {}
            @Override
            public void sendMessageWithKeyboard(Long chatId, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {}
        };
    }

    @Test
    @DisplayName("Test full invoice creation conversation state flow")
    void testInvoiceCreationStateFlow() {
        Long telegramId=landlordUser.getTelegramId();
        var state=stateService.getState(telegramId);
        state.getData().put("roomId",room103.getId());
        state.setStep(UserConversationState.Step.CREATE_INVOICE_MONTH);
        invoiceCommandHandler.handle(createMessageUpdate("9"),landlordUser,mockMessageSender);
        assertEquals(UserConversationState.Step.NONE,stateService.getState(telegramId).getStep());
        assertEquals(0,invoiceRepository.count());
        invoiceCommandHandler.handle(createMessageUpdate("INVOICE_ADD"),landlordUser,mockMessageSender);
        assertEquals(UserConversationState.Step.NONE,stateService.getState(telegramId).getStep());
        assertEquals(0,invoiceRepository.count());
    }

    @Test
    @DisplayName("Test /cancel in CREATE_INVOICE wizard")
    void testCancelInCreateInvoiceWizard() {
        Long telegramId = landlordUser.getTelegramId();
        UserConversationState state = stateService.getState(telegramId);
        state.setStep(UserConversationState.Step.CREATE_INVOICE_YEAR);
        stateService.setState(telegramId, state);

        final String[] capturedMessage = new String[1];
        BotMessageSender customSender = new BotMessageSender() {
            @Override
            public void sendMessage(Long chatId, String text) {}
            @Override
            public void sendMessageWithKeyboard(Long chatId, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {
                capturedMessage[0] = text;
            }
        };

        invoiceCommandHandler.handle(createMessageUpdate("/cancel"), landlordUser, customSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(telegramId).getStep());
        assertEquals("❌ Đã hủy thao tác tạo hóa đơn.", capturedMessage[0]);
    }

    @Test void paymentFlowPaysWholeInvoiceInOneRecord() {
        var invoice=invoiceRepository.save(new Invoice(room103,null,null,9,2026,
                new BigDecimal("3500000"),new BigDecimal("500000"),new BigDecimal("100000"),
                new BigDecimal("4100000"),LocalDate.of(2026,9,25)));
        invoiceCommandHandler.handle(createMessageUpdate("INVOICE_PAYMENT_INIT_"+invoice.getId()),landlordUser,mockMessageSender);
        assertEquals(UserConversationState.Step.RECORD_PAYMENT_CONFIRM,stateService.getState(landlordUser.getTelegramId()).getStep());
        assertEquals(0,invoice.getTotalAmount().compareTo((BigDecimal)stateService.getState(landlordUser.getTelegramId()).getData().get("amount")));
        invoiceCommandHandler.handle(createMessageUpdate("CONFIRM_RECORD_PAYMENT"),landlordUser,mockMessageSender);
        assertEquals(com.boardinghousebot.domain.invoice.InvoiceStatus.PAID,invoice.getStatus());
        assertEquals(0,invoice.getRemainingAmount().signum());
        var recorded=paymentRepository.findByInvoiceIdOrderByPaymentDateDesc(invoice.getId());
        assertEquals(1,recorded.size());
        assertEquals(com.boardinghousebot.domain.invoice.PaymentMethod.UNSPECIFIED,recorded.get(0).getPaymentMethod());
        assertEquals(0,invoice.getTotalAmount().compareTo(recorded.get(0).getAmount()));
    }

    @Test
    @DisplayName("Test record payment flow & /cancel in RECORD_PAYMENT wizard")
    void testRecordPaymentFlowAndCancel() {
        Long telegramId = landlordUser.getTelegramId();

        Invoice invoice = new Invoice();
        invoice.setRoom(room103);
        invoice.setBillingMonth(9);
        invoice.setBillingYear(2026);
        invoice.setRoomRentAmount(new BigDecimal("3500000"));
        invoice.setElectricityAmount(new BigDecimal("500000"));
        invoice.setWaterAmount(new BigDecimal("100000"));
        invoice.setTotalAmount(new BigDecimal("4100000"));
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setDueDate(LocalDate.of(2026, 9, 25));
        invoice = invoiceRepository.save(invoice);

        UserConversationState state = stateService.getState(telegramId);
        state.getData().put("invoiceId", invoice.getId());
        state.setStep(UserConversationState.Step.RECORD_PAYMENT_AMOUNT);
        stateService.setState(telegramId, state);

        // An old amount-entry session resumes using the full outstanding balance.
        invoiceCommandHandler.handle(createMessageUpdate("abc"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.RECORD_PAYMENT_CONFIRM, stateService.getState(telegramId).getStep());
        assertEquals(new BigDecimal("4100000"),stateService.getState(telegramId).getData().get("amount"));

        // Typed amounts cannot replace the amount supplied by the invoice.
        invoiceCommandHandler.handle(createMessageUpdate("5000000"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.RECORD_PAYMENT_CONFIRM, stateService.getState(telegramId).getStep());

        invoiceCommandHandler.handle(createMessageUpdate("2000000"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.RECORD_PAYMENT_CONFIRM, stateService.getState(telegramId).getStep());
        assertEquals(new BigDecimal("4100000"),stateService.getState(telegramId).getData().get("amount"));

        // Test /cancel in RECORD_PAYMENT wizard
        final String[] capturedMessage = new String[1];
        BotMessageSender customSender = new BotMessageSender() {
            @Override
            public void sendMessage(Long chatId, String text) {}
            @Override
            public void sendMessageWithKeyboard(Long chatId, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {
                capturedMessage[0] = text;
            }
        };

        invoiceCommandHandler.handle(createMessageUpdate("/cancel"), landlordUser, customSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(telegramId).getStep());
        assertEquals("❌ Đã hủy thao tác ghi nhận thanh toán.", capturedMessage[0]);
    }

    private Update createMessageUpdate(String text) {
        Message msg = new Message();
        msg.setChat(new org.telegram.telegrambots.meta.api.objects.chat.Chat(landlordUser.getTelegramId(), "private"));
        msg.setText(text);
        org.telegram.telegrambots.meta.api.objects.User tgUser = new org.telegram.telegrambots.meta.api.objects.User(landlordUser.getTelegramId(), "Landlord", false);
        msg.setFrom(tgUser);

        Update update = new Update();
        update.setMessage(msg);
        return update;
    }
}
