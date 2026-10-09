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
        Long telegramId = landlordUser.getTelegramId();

        // 1. Set state to CREATE_INVOICE_MONTH
        UserConversationState state = stateService.getState(telegramId);
        state.getData().put("roomId", room103.getId());
        state.setStep(UserConversationState.Step.CREATE_INVOICE_MONTH);
        stateService.setState(telegramId, state);

        // 2. Input invalid month -> step remains CREATE_INVOICE_MONTH
        invoiceCommandHandler.handle(createMessageUpdate("15"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_INVOICE_MONTH, stateService.getState(telegramId).getStep());

        // 3. Input valid month (9) -> step changes to CREATE_INVOICE_YEAR
        invoiceCommandHandler.handle(createMessageUpdate("9"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_INVOICE_YEAR, stateService.getState(telegramId).getStep());

        // 4. Input invalid year -> step remains CREATE_INVOICE_YEAR
        invoiceCommandHandler.handle(createMessageUpdate("invalid"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_INVOICE_YEAR, stateService.getState(telegramId).getStep());

        // 5. Input valid year (2026) -> step changes to CREATE_INVOICE_CONFIRM
        invoiceCommandHandler.handle(createMessageUpdate("2026"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_INVOICE_CONFIRM, stateService.getState(telegramId).getStep());
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

        // Invalid input (non-numeric) -> step remains RECORD_PAYMENT_AMOUNT
        invoiceCommandHandler.handle(createMessageUpdate("abc"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.RECORD_PAYMENT_AMOUNT, stateService.getState(telegramId).getStep());

        // Overpayment input -> step remains RECORD_PAYMENT_AMOUNT
        invoiceCommandHandler.handle(createMessageUpdate("5000000"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.RECORD_PAYMENT_AMOUNT, stateService.getState(telegramId).getStep());

        // Valid partial amount -> step changes to RECORD_PAYMENT_METHOD
        invoiceCommandHandler.handle(createMessageUpdate("2000000"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.RECORD_PAYMENT_METHOD, stateService.getState(telegramId).getStep());

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
