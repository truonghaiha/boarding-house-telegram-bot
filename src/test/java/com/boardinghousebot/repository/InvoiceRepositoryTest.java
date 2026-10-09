package com.boardinghousebot.repository;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class InvoiceRepositoryTest {

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
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Room room101;
    private User member;
    private Contract contract;

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

        room101 = new Room("IV101", new BigDecimal("3500000"), 2, "Mô tả IV101");
        room101.setStatus(RoomStatus.OCCUPIED);
        room101 = roomRepository.save(room101);

        member = new User(100099L, "invmember", "Invoice Member", Role.MEMBER);
        member.setRoom(room101);
        member = userRepository.save(member);

        contract = new Contract(member, room101, LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(11),
                new BigDecimal("3500000"), new BigDecimal("3500000"));
        contract = contractRepository.save(contract);
    }

    @Test
    @DisplayName("Save and find Invoice by id with details")
    void testSaveAndFindByIdWithDetails() {
        Invoice invoice = new Invoice(room101, member, contract, 9, 2026,
                new BigDecimal("3500000"), new BigDecimal("140000"), new BigDecimal("105000"),
                new BigDecimal("3745000"), LocalDate.of(2026, 9, 15));
        Invoice saved = invoiceRepository.save(invoice);

        Optional<Invoice> foundOpt = invoiceRepository.findByIdWithDetails(saved.getId());
        assertTrue(foundOpt.isPresent());

        Invoice found = foundOpt.get();
        assertEquals("IV101", found.getRoom().getRoomNumber());
        assertEquals("invmember", found.getMember().getUsername());
        assertEquals(new BigDecimal("3745000.00"), found.getTotalAmount().setScale(2));
        assertEquals(InvoiceStatus.UNPAID, found.getStatus());
    }

    @Test
    @DisplayName("Enforce unique constraint on (room_id, billing_month, billing_year)")
    void testUniqueConstraint() {
        Invoice inv1 = new Invoice(room101, member, contract, 9, 2026,
                new BigDecimal("3500000"), new BigDecimal("140000"), new BigDecimal("105000"),
                new BigDecimal("3745000"), LocalDate.of(2026, 9, 15));
        invoiceRepository.saveAndFlush(inv1);

        Invoice duplicate = new Invoice(room101, member, contract, 9, 2026,
                new BigDecimal("3500000"), new BigDecimal("140000"), new BigDecimal("105000"),
                new BigDecimal("3745000"), LocalDate.of(2026, 9, 15));

        assertThrows(org.springframework.dao.DataAccessException.class, () -> invoiceRepository.saveAndFlush(duplicate));
    }

    @Test
    @DisplayName("Filter invoices by room, status, month, and year")
    void testFilterInvoicesWithDetails() {
        Invoice inv1 = new Invoice(room101, member, contract, 8, 2026,
                new BigDecimal("3500000"), new BigDecimal("100000"), new BigDecimal("80000"),
                new BigDecimal("3680000"), LocalDate.of(2026, 8, 15));
        inv1.setStatus(InvoiceStatus.PAID);
        inv1.setPaidAmount(new BigDecimal("3680000"));

        Invoice inv2 = new Invoice(room101, member, contract, 9, 2026,
                new BigDecimal("3500000"), new BigDecimal("140000"), new BigDecimal("105000"),
                new BigDecimal("3745000"), LocalDate.of(2026, 9, 15));
        inv2.setStatus(InvoiceStatus.UNPAID);

        invoiceRepository.save(inv1);
        invoiceRepository.save(inv2);

        List<Invoice> month8 = invoiceRepository.filterInvoicesWithDetails(null, null, 8, 2026);
        assertEquals(1, month8.size());
        assertEquals(8, month8.get(0).getBillingMonth());

        List<Invoice> unpaidList = invoiceRepository.filterInvoicesWithDetails(null, InvoiceStatus.UNPAID, null, null);
        assertEquals(1, unpaidList.size());
        assertEquals(InvoiceStatus.UNPAID, unpaidList.get(0).getStatus());

        List<Object[]> periods = invoiceRepository.findDistinctBillingPeriods();
        assertTrue(periods.size() >= 2);
    }
}
