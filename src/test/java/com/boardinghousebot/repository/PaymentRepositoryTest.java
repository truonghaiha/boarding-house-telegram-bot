package com.boardinghousebot.repository;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.invoice.PaymentMethod;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContractRepository contractRepository;

    private Invoice invoice;

    @BeforeEach
    void setUp() {
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

        Room room = new Room("PY101", new BigDecimal("3500000"), 2, "Mô tả PY101");
        room.setStatus(RoomStatus.OCCUPIED);
        room = roomRepository.save(room);

        User member = new User(200099L, "pymember", "Payment Member", Role.MEMBER);
        member.setRoom(room);
        member = userRepository.save(member);

        Contract contract = new Contract(member, room, LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(11),
                new BigDecimal("3500000"), new BigDecimal("3500000"));
        contract = contractRepository.save(contract);

        invoice = new Invoice(room, member, contract, 9, 2026,
                new BigDecimal("3500000"), new BigDecimal("140000"), new BigDecimal("105000"),
                new BigDecimal("3745000"), LocalDate.of(2026, 9, 15));
        invoice = invoiceRepository.save(invoice);
    }

    @Test
    @DisplayName("Save and find payments by invoice ID ordered by payment date desc")
    void testSaveAndFindByInvoiceId() {
        Payment p1 = new Payment(invoice, new BigDecimal("1000000"), PaymentMethod.CASH, "Tiền mặt đợt 1");
        Payment p2 = new Payment(invoice, new BigDecimal("2745000"), PaymentMethod.BANK_TRANSFER, "Chuyển khoản nốt");

        paymentRepository.save(p1);
        paymentRepository.save(p2);

        List<Payment> payments = paymentRepository.findByInvoiceIdOrderByPaymentDateDesc(invoice.getId());
        assertEquals(2, payments.size());
        assertEquals(invoice.getId(), payments.get(0).getInvoice().getId());
    }
}
