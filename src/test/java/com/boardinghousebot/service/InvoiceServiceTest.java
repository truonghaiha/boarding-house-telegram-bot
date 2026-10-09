package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditLog;
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
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class InvoiceServiceTest {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private PaymentService paymentService;

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
    private AuditLogRepository auditLogRepository;

    private User landlord;
    private User member101;
    private User member102;
    private Room room101;
    private Room room102;
    private Contract contract101;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        paymentRepository.deleteAll();
        invoiceRepository.deleteAll();
        meterReadingRepository.deleteAll();
        contractRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();

        auditLogRepository.flush();
        paymentRepository.flush();
        invoiceRepository.flush();
        meterReadingRepository.flush();
        contractRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        landlord = new User(999111L, "landlord_inv", "Landlord Inv", Role.LANDLORD);
        landlord = userRepository.save(landlord);

        room101 = new Room("IS101", new BigDecimal("3500000"), 2, "Room IS101");
        room101.setStatus(RoomStatus.OCCUPIED);
        room101 = roomRepository.save(room101);

        room102 = new Room("IS102", new BigDecimal("4000000"), 2, "Room IS102");
        room102.setStatus(RoomStatus.OCCUPIED);
        room102 = roomRepository.save(room102);

        member101 = new User(300001L, "member101", "Member 101", Role.MEMBER);
        member101.setRoom(room101);
        member101 = userRepository.save(member101);

        member102 = new User(300002L, "member102", "Member 102", Role.MEMBER);
        member102.setRoom(room102);
        member102 = userRepository.save(member102);

        contract101 = new Contract(member101, room101, LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(11),
                new BigDecimal("3500000"), new BigDecimal("3500000"));
        contract101 = contractRepository.save(contract101);

        // Meter readings for room 101 for period 9/2099 (future period)
        meterReadingRepository.save(new MeterReading(room101, UtilityType.ELECTRICITY, 9, 2099,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500"))); // total: 150 * 3500 = 525,000
        meterReadingRepository.save(new MeterReading(room101, UtilityType.WATER, 9, 2099,
                new BigDecimal("50"), new BigDecimal("60"), new BigDecimal("15000")));     // total: 10 * 15000 = 150,000
    }

    @Test
    @DisplayName("Landlord creates invoice successfully with financial snapshot")
    void testCreateInvoice_Success() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);

        assertNotNull(invoice.getId());
        assertEquals("IS101", invoice.getRoom().getRoomNumber());
        assertEquals(new BigDecimal("3500000"), invoice.getRoomRentAmount());
        assertEquals(new BigDecimal("525000.00"), invoice.getElectricityAmount().setScale(2));
        assertEquals(new BigDecimal("150000.00"), invoice.getWaterAmount().setScale(2));
        assertEquals(new BigDecimal("4175000.00"), invoice.getTotalAmount().setScale(2));
        assertEquals(new BigDecimal("0.00"), invoice.getPaidAmount().setScale(2));
        assertEquals(InvoiceStatus.UNPAID, invoice.getStatus());

        List<AuditLog> logs = auditLogRepository.findAll();
        assertTrue(logs.stream().anyMatch(l -> l.getAction().contains("CREATE_INVOICE")));
    }

    @Test
    @DisplayName("Reject invoice creation if room has no active contract")
    void testRejectCreateInvoice_NoActiveContract() {
        assertThrows(BusinessException.class, () ->
                invoiceService.createInvoice(landlord.getTelegramId(), room102.getId(), 9, 2099));
    }

    @Test
    @DisplayName("Reject invoice creation if electricity or water meter reading is missing")
    void testRejectCreateInvoice_MissingMeterReading() {
        // Contract active for room 102
        Contract c102 = new Contract(member102, room102, LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(11),
                new BigDecimal("4000000"), new BigDecimal("4000000"));
        contractRepository.save(c102);

        // Only electricity created, missing water
        meterReadingRepository.save(new MeterReading(room102, UtilityType.ELECTRICITY, 9, 2099,
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500")));

        assertThrows(BusinessException.class, () ->
                invoiceService.createInvoice(landlord.getTelegramId(), room102.getId(), 9, 2099));
    }

    @Test
    @DisplayName("Reject duplicate invoice for same room and billing period")
    void testRejectDuplicateInvoice() {
        invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);

        assertThrows(BusinessException.class, () ->
                invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099));
    }

    @Test
    @DisplayName("Verify financial snapshot immutability when meter readings or contract rates change later")
    void testFinancialSnapshotImmutability() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);
        BigDecimal originalTotal = invoice.getTotalAmount();

        // Update contract monthly rent
        contract101.setMonthlyRent(new BigDecimal("5000000"));
        contractRepository.save(contract101);

        // Fetch invoice from database again
        Invoice reloaded = invoiceService.getInvoiceById(invoice.getId());
        assertEquals(0, originalTotal.compareTo(reloaded.getTotalAmount()), "Existing historical invoice snapshot total must remain unchanged");
    }

    @Test
    @DisplayName("Landlord records partial and full payment successfully")
    void testRecordPayment_PartialAndFullStatus() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);
        BigDecimal total = invoice.getTotalAmount();

        // 1. Partial Payment of 2,000,000 VNĐ
        Payment p1 = paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(),
                new BigDecimal("2000000"), PaymentMethod.CASH, "Đợt 1 tiền mặt");

        assertNotNull(p1.getId());
        Invoice afterP1 = invoiceService.getInvoiceById(invoice.getId());
        assertEquals(new BigDecimal("2000000.00"), afterP1.getPaidAmount().setScale(2));
        assertEquals(InvoiceStatus.PARTIALLY_PAID, afterP1.getStatus());

        // 2. Full remaining Payment of 2,175,000 VNĐ
        BigDecimal remaining = afterP1.getRemainingAmount();
        Payment p2 = paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(),
                remaining, PaymentMethod.BANK_TRANSFER, "Đợt 2 chuyển khoản nốt");

        assertNotNull(p2.getId());
        Invoice afterP2 = invoiceService.getInvoiceById(invoice.getId());
        assertEquals(0, total.compareTo(afterP2.getPaidAmount()));
        assertEquals(InvoiceStatus.PAID, afterP2.getStatus());

        List<Payment> history = paymentService.getInvoicePayments(invoice.getId());
        assertEquals(2, history.size());
    }

    @Test
    @DisplayName("Reject payment if amount exceeds remaining balance")
    void testRejectPayment_ExceedsRemainingBalance() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);
        BigDecimal remaining = invoice.getRemainingAmount();

        assertThrows(BusinessException.class, () ->
                paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(),
                        remaining.add(new BigDecimal("1000")), PaymentMethod.CASH, "Quá tiền"));
    }

    @Test
    @DisplayName("Reject payment if amount is less than or equal to zero")
    void testRejectPayment_NonPositiveAmount() {
        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);

        assertThrows(BusinessException.class, () ->
                paymentService.recordPayment(landlord.getTelegramId(), invoice.getId(),
                        BigDecimal.ZERO, PaymentMethod.CASH, "Không âm"));
    }

    @Test
    @DisplayName("Member isolation: Member can only view own room's invoices")
    void testMemberIsolation() {
        invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);

        List<Invoice> member101Invoices = invoiceService.getMemberInvoices(member101.getTelegramId());
        assertEquals(1, member101Invoices.size());
        assertEquals("IS101", member101Invoices.get(0).getRoom().getRoomNumber());

        List<Invoice> member102Invoices = invoiceService.getMemberInvoices(member102.getTelegramId());
        assertTrue(member102Invoices.isEmpty());
    }

    @Test
    @DisplayName("Member cannot create invoice or record payment")
    void testMemberCannotCreateInvoiceOrRecordPayment() {
        assertThrows(BusinessException.class, () ->
                invoiceService.createInvoice(member101.getTelegramId(), room101.getId(), 9, 2099));

        Invoice invoice = invoiceService.createInvoice(landlord.getTelegramId(), room101.getId(), 9, 2099);

        assertThrows(BusinessException.class, () ->
                paymentService.recordPayment(member101.getTelegramId(), invoice.getId(),
                        new BigDecimal("1000000"), PaymentMethod.CASH, "Member hack"));
    }
}
