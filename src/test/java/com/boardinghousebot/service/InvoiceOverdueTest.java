package com.boardinghousebot.service;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.handler.InvoiceCommandHandler;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.invoice.PaymentMethod;
import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.repository.InvoiceRepository;
import com.boardinghousebot.repository.MeterReadingRepository;
import com.boardinghousebot.repository.PaymentRepository;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class InvoiceOverdueTest {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private InvoiceCommandHandler invoiceCommandHandler;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MeterReadingRepository meterReadingRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User landlord;
    private User member101;
    private Room room101;
    private Contract contract101;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_room_period ON invoices(room_id, billing_month, billing_year)");

        paymentRepository.deleteAll();
        invoiceRepository.deleteAll();
        meterReadingRepository.deleteAll();
        contractRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();

        paymentRepository.flush();
        invoiceRepository.flush();
        meterReadingRepository.flush();
        contractRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        landlord = new User(999111L, "landlord_overdue", "Landlord Overdue", Role.LANDLORD);
        landlord = userRepository.save(landlord);

        room101 = new Room("OD101", new BigDecimal("3500000"), 2, "Room OD101");
        room101.setStatus(RoomStatus.OCCUPIED);
        room101 = roomRepository.save(room101);

        member101 = new User(300001L, "member101_od", "Member 101 OD", Role.MEMBER);
        member101.setRoom(room101);
        member101 = userRepository.save(member101);

        contract101 = new Contract(member101, room101, LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(6),
                new BigDecimal("3500000"), new BigDecimal("3500000"));
        contract101 = contractRepository.save(contract101);

        // Meter readings for future cycle (e.g. year 2099) so not overdue by default
        meterReadingRepository.save(new MeterReading(room101, UtilityType.ELECTRICITY, 9, 2099,
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500")));
        meterReadingRepository.save(new MeterReading(room101, UtilityType.WATER, 9, 2099,
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000")));

        // Meter readings for past cycle (e.g. month 8, year 2026) overdue by default
        meterReadingRepository.save(new MeterReading(room101, UtilityType.ELECTRICITY, 8, 2026,
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500")));
        meterReadingRepository.save(new MeterReading(room101, UtilityType.WATER, 8, 2026,
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000")));

        // Meter readings for period 9/2026
        meterReadingRepository.save(new MeterReading(room101, UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500")));
        meterReadingRepository.save(new MeterReading(room101, UtilityType.WATER, 9, 2026,
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000")));
    }

    @Test
    @DisplayName("Test 1: Invoice chưa đến hạn + chưa thanh toán -> UNPAID")
    void test1_FutureInvoiceUnpaid() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);
        assertEquals(InvoiceStatus.UNPAID, invoice.getStatus());
    }

    @Test
    @DisplayName("Test 2: Invoice chưa đến hạn + thanh toán một phần -> PARTIALLY_PAID")
    void test2_FutureInvoicePartiallyPaid() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);
        paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(), new BigDecimal("1000000"), PaymentMethod.CASH, "Partial payment");
        
        Invoice reloaded = invoiceService.getInvoiceById(invoice.getId());
        assertEquals(InvoiceStatus.PARTIALLY_PAID, reloaded.getStatus());
    }

    @Test
    @DisplayName("Test 3: Invoice quá hạn + chưa thanh toán -> OVERDUE")
    void test3_PastInvoiceUnpaid() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 8, 2026);
        assertEquals(InvoiceStatus.OVERDUE, invoice.getStatus());
    }

    @Test
    @DisplayName("Test 4: Invoice quá hạn + thanh toán một phần -> OVERDUE")
    void test4_PastInvoicePartiallyPaid() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 8, 2026);
        paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(), new BigDecimal("1000000"), PaymentMethod.CASH, "Partial payment for overdue");
        
        Invoice reloaded = invoiceService.getInvoiceById(invoice.getId());
        assertEquals(InvoiceStatus.OVERDUE, reloaded.getStatus());
    }

    @Test
    @DisplayName("Test 5: Invoice quá hạn + thanh toán đủ -> PAID")
    void test5_PastInvoiceFullyPaid() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 8, 2026);
        paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(), invoice.getTotalAmount(), PaymentMethod.BANK_TRANSFER, "Full payment");
        
        Invoice reloaded = invoiceService.getInvoiceById(invoice.getId());
        assertEquals(InvoiceStatus.PAID, reloaded.getStatus());
    }

    @Test
    @DisplayName("Test 6: Kiểm tra dueDate được lưu đúng ngày 15 của billing month/year")
    void test6_DueDateIsFifteenth() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2026);
        assertEquals(LocalDate.of(2026, 9, 15), invoice.getDueDate());
    }

    @Test
    @DisplayName("Test 7: Kiểm tra dueDate được hiển thị trong preview, detail, list, member view")
    void test7_DueDateDisplayInUI() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 8, 2026);

        List<String> capturedMessages = new ArrayList<>();
        BotMessageSender mockSender = new BotMessageSender() {
            @Override
            public void sendMessage(Long chatId, String text) {
                capturedMessages.add(text);
            }
            @Override
            public void sendMessageWithKeyboard(Long chatId, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {
                capturedMessages.add(text);
            }
        };

        // 1. Preview
        UserConversationState state = new UserConversationState();
        state.getData().put("roomId", room101.getId());
        state.getData().put("month", 8);
        state.getData().put("year", 2026);
        state.setStep(UserConversationState.Step.CREATE_INVOICE_CONFIRM);

        invoiceCommandHandler.handle(createMessageUpdate("/invoice"), landlord, mockSender);
        
        // 2. Member view
        invoiceCommandHandler.handle(createCallbackUpdate("MEMBER_INVOICES"), member101, mockSender);
        assertTrue(capturedMessages.stream().anyMatch(msg -> msg.contains("Hạn thanh toán: 15/08/2026")));

        // 3. Invoice list
        capturedMessages.clear();
        invoiceCommandHandler.handle(createCallbackUpdate("INVOICE_LIST_LATEST"), landlord, mockSender);
        assertTrue(capturedMessages.stream().anyMatch(msg -> msg.contains("Hạn: 15/08/2026")));

        // 4. Detail view
        capturedMessages.clear();
        invoiceCommandHandler.handle(createCallbackUpdate("INVOICE_DETAIL_" + invoice.getId()), landlord, mockSender);
        assertTrue(capturedMessages.stream().anyMatch(msg -> msg.contains("Hạn thanh toán: 15/08/2026")));
    }

    @Test
    @DisplayName("Test 8: Filter OVERDUE trả về đúng invoice")
    void test8_FilterOverdueInvoices() {
        Invoice overdueInv = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 8, 2026);
        
        List<Invoice> overdueList = invoiceService.getFilteredInvoices(null, InvoiceStatus.OVERDUE, 8, 2026);
        assertEquals(1, overdueList.size());
        assertEquals(overdueInv.getId(), overdueList.get(0).getId());

        List<Invoice> unpaidList = invoiceService.getFilteredInvoices(null, InvoiceStatus.UNPAID, 8, 2026);
        assertTrue(unpaidList.isEmpty(), "Overdue invoice should not be returned when filtering for UNPAID");
    }

    @Test
    @DisplayName("Test 9: Payment từ OVERDUE nhưng chưa trả đủ -> vẫn OVERDUE")
    void test9_PaymentFromOverdueNotFullyPaid() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 8, 2026);
        assertEquals(InvoiceStatus.OVERDUE, invoice.getStatus());

        paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(), new BigDecimal("2000000"), PaymentMethod.CASH, "Partial payment");
        Invoice reloaded = invoiceService.getInvoiceById(invoice.getId());
        
        assertEquals(InvoiceStatus.OVERDUE, reloaded.getStatus());
        assertTrue(reloaded.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    @DisplayName("Test 10: Payment phần còn lại của OVERDUE -> PAID")
    void test10_PaymentRemainingOfOverdue() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 8, 2026);
        paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(), new BigDecimal("2000000"), PaymentMethod.CASH, "Partial 1");

        Invoice afterP1 = invoiceService.getInvoiceById(invoice.getId());
        assertEquals(InvoiceStatus.OVERDUE, afterP1.getStatus());

        paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(), afterP1.getRemainingAmount(), PaymentMethod.BANK_TRANSFER, "Partial 2");
        Invoice afterP2 = invoiceService.getInvoiceById(invoice.getId());

        assertEquals(InvoiceStatus.PAID, afterP2.getStatus());
        assertEquals(new BigDecimal("0.00"), afterP2.getRemainingAmount().setScale(2));
    }

    private Update createCallbackUpdate(String callbackData) {
        org.telegram.telegrambots.meta.api.objects.CallbackQuery cb = new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
        cb.setData(callbackData);
        Message msg = new Message();
        msg.setChat(new org.telegram.telegrambots.meta.api.objects.chat.Chat(landlord.getTelegramId(), "private"));
        cb.setMessage(msg);
        cb.setFrom(new org.telegram.telegrambots.meta.api.objects.User(landlord.getTelegramId(), "Landlord", false));

        Update update = new Update();
        update.setCallbackQuery(cb);
        return update;
    }

    private Update createMessageUpdate(String text) {
        Message msg = new Message();
        msg.setChat(new org.telegram.telegrambots.meta.api.objects.chat.Chat(landlord.getTelegramId(), "private"));
        msg.setText(text);
        org.telegram.telegrambots.meta.api.objects.User tgUser = new org.telegram.telegrambots.meta.api.objects.User(landlord.getTelegramId(), "Landlord", false);
        msg.setFrom(tgUser);

        Update update = new Update();
        update.setMessage(msg);
        return update;
    }
}
