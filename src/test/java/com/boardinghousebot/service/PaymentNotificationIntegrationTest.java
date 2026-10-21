package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.invoice.PaymentMethod;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.observer.PaymentRecordedEvent;
import com.boardinghousebot.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PaymentNotificationIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private TelegramConfig telegramConfig;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired private ContractRepository contractRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private User landlordA;
    private User landlordB;
    private User admin;
    private User member1;
    private User member2;
    private User otherRoomMember;

    private Room room101;
    private Room room102;
    private Invoice invoice101;
    private Long rentalContractId;

    @org.junit.jupiter.api.AfterEach
    void removeRentalFixture() {
        if (rentalContractId != null) contractRepository.deleteById(rentalContractId);
    }

    private static final Long LANDLORD_A_ID = 888111222L;
    private static final Long LANDLORD_B_ID = 888333444L;
    private static final Long ADMIN_ID = 999111999L;
    private static final Long MEMBER1_ID = 100001L;
    private static final Long MEMBER2_ID = 100002L;
    private static final Long OTHER_MEMBER_ID = 100003L;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        paymentRepository.deleteAll();
        invoiceRepository.deleteAll();
        contractRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();

        notificationRepository.flush();
        paymentRepository.flush();
        invoiceRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        telegramConfig.setAdminId(ADMIN_ID.toString());

        admin = userService.getOrCreateUser(ADMIN_ID, "admin_user", "Admin System");
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);

        landlordA = userService.getOrCreateUser(LANDLORD_A_ID, "landlord_a", "Chủ Nhà A");
        landlordA.setRole(Role.LANDLORD);
        userRepository.save(landlordA);

        landlordB = userService.getOrCreateUser(LANDLORD_B_ID, "landlord_b", "Chủ Nhà B");
        landlordB.setRole(Role.LANDLORD);
        userRepository.save(landlordB);

        member1 = userService.getOrCreateUser(MEMBER1_ID, "member_1", "Nguyễn Văn A");
        member2 = userService.getOrCreateUser(MEMBER2_ID, "member_2", "Trần Thị B");
        otherRoomMember = userService.getOrCreateUser(OTHER_MEMBER_ID, "member_other", "Lê Văn C");

        room101 = roomService.createRoom(LANDLORD_A_ID, "PY101", new BigDecimal("3000000"), 2, "Phòng 101");
        room102 = roomService.createRoom(LANDLORD_A_ID, "PY102", new BigDecimal("4000000"), 2, "Phòng 102");

        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER1_ID, room101.getId());
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER2_ID, room101.getId());
        roomService.assignMemberToRoom(LANDLORD_A_ID, OTHER_MEMBER_ID, room102.getId());
        rentalContractId = contractRepository.save(new com.boardinghousebot.domain.contract.Contract(member1, room101,
                LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), BigDecimal.ZERO)).getId();

        invoice101 = new Invoice(
                room101,
                member1,
                null,
                LocalDate.now().getMonthValue(),
                LocalDate.now().getYear(),
                new BigDecimal("3000000"),
                new BigDecimal("350000"),
                new BigDecimal("150000"),
                new BigDecimal("3500000"),
                LocalDate.now().plusDays(10)
        );
        invoice101 = invoiceRepository.save(invoice101);

        notificationRepository.deleteAll();
        notificationRepository.flush();
    }

    @Test
    @DisplayName("TEST 1-5: LANDLORD records Partial & Full Payment -> ALL Room Members receive DB Notification with correct amounts & status")
    void testRecordPayment_NotificationsSentToRoomMembers() {
        // 1. Partial Payment: 1,000,000 VNĐ out of 3,500,000 VNĐ
        Payment partialPayment = paymentService.recordPayment(
                LANDLORD_A_ID,
                invoice101.getId(),
                new BigDecimal("1000000"),
                PaymentMethod.BANK_TRANSFER,
                "Thanh toán đợt 1"
        );

        assertNotNull(partialPayment.getId());

        // TEST 2 & 3: Member 1 & Member 2 must receive DB notification for PAYMENT_RECORDED
        List<Notification> m1Notifs = notificationService.getNotificationsForUser(MEMBER1_ID);
        List<Notification> m2Notifs = notificationService.getNotificationsForUser(MEMBER2_ID);

        assertEquals(1, m1Notifs.size(), "Member 1 in room 101 must receive 1 DB notification");
        assertEquals(1, m2Notifs.size(), "Member 2 in room 101 must receive 1 DB notification");

        // TEST 4: Partial payment status & amounts check
        Notification n1 = m1Notifs.get(0);
        assertEquals(NotificationType.PAYMENT_RECORDED, n1.getType());
        assertEquals("PAYMENT", n1.getReferenceType());
        assertEquals(partialPayment.getId(), n1.getReferenceId());
        assertTrue(n1.getTitle().contains("PY101"));
        assertTrue(n1.getMessage().contains("1,000,000")); // Payment amount
        assertTrue(n1.getMessage().contains("3,500,000")); // Total
        assertTrue(n1.getMessage().contains("2,500,000")); // Remaining
        assertTrue(n1.getMessage().contains("Thanh toán một phần")); // Status name

        // 2. Full Payment: remaining 2,500,000 VNĐ
        Payment fullPayment = paymentService.recordPayment(
                LANDLORD_A_ID,
                invoice101.getId(),
                new BigDecimal("2500000"),
                PaymentMethod.CASH,
                "Thanh toán nốt đợt 2"
        );

        assertNotNull(fullPayment.getId());

        // TEST 5: Full payment status & remaining = 0 check
        List<Notification> m1NotifsPostFull = notificationService.getNotificationsForUser(MEMBER1_ID);
        assertEquals(2, m1NotifsPostFull.size());

        Notification latestNotif = m1NotifsPostFull.get(0);
        assertEquals(NotificationType.PAYMENT_RECORDED, latestNotif.getType());
        assertEquals(fullPayment.getId(), latestNotif.getReferenceId());
        assertTrue(latestNotif.getMessage().contains("2,500,000")); // Payment amount
        assertTrue(latestNotif.getMessage().contains("3,500,000")); // Paid amount
        assertTrue(latestNotif.getMessage().contains("0")); // Remaining amount
        assertTrue(latestNotif.getMessage().contains("Đã thanh toán")); // Status name
    }

    @Test
    @DisplayName("TEST 6-8: Room isolation & Non-recipients (Other room member, LANDLORDs, ADMIN receive ZERO payment notifications)")
    void testPaymentNotificationTargetIsolation() {
        paymentService.recordPayment(
                LANDLORD_A_ID,
                invoice101.getId(),
                new BigDecimal("1500000"),
                PaymentMethod.BANK_TRANSFER,
                "Cọc tiền"
        );

        // TEST 6: Other room member (Room 102) -> 0 notifications
        List<Notification> otherNotifs = notificationService.getNotificationsForUser(OTHER_MEMBER_ID);
        assertTrue(otherNotifs.isEmpty(), "Member in other room (102) must NOT receive payment notification for room 101");

        // TEST 7: LANDLORD A & LANDLORD B -> 0 notifications
        List<Notification> landlordANotifs = notificationService.getNotificationsForUser(LANDLORD_A_ID);
        List<Notification> landlordBNotifs = notificationService.getNotificationsForUser(LANDLORD_B_ID);
        assertTrue(landlordANotifs.isEmpty(), "Landlord A must NOT receive payment notification");
        assertTrue(landlordBNotifs.isEmpty(), "Landlord B must NOT receive payment notification");

        // TEST 8: ADMIN -> 0 notifications
        List<Notification> adminNotifs = notificationService.getNotificationsForUser(ADMIN_ID);
        assertTrue(adminNotifs.isEmpty(), "ADMIN must NOT receive payment notification");
    }

    @Test
    @DisplayName("CASE 2: Failed/Rollbacked Payment transaction -> NO notification created or sent")
    void testFailedPayment_NoNotificationCreated() {
        assertThrows(RuntimeException.class, () -> {
            paymentService.recordPayment(
                    LANDLORD_A_ID,
                    999999L, // Non-existent invoice ID -> throws BusinessException & transaction rolls back
                    new BigDecimal("1000000"),
                    PaymentMethod.CASH,
                    "Thanh toán lỗi"
            );
        });

        assertTrue(notificationService.getNotificationsForUser(MEMBER1_ID).isEmpty());
        assertTrue(notificationService.getNotificationsForUser(MEMBER2_ID).isEmpty());
    }

    @Test
    @DisplayName("CASE 4: Event PaymentRecordedEvent được xử lý lại -> mỗi MEMBER chỉ có đúng 1 notification")
    void testNoDuplicateNotificationOnReprocessedEvent() {
        Payment payment = paymentService.recordPayment(
                LANDLORD_A_ID,
                invoice101.getId(),
                new BigDecimal("1000000"),
                PaymentMethod.CASH,
                "Thanh toán đợt 1"
        );

        List<Notification> initialM1 = notificationService.getNotificationsForUser(MEMBER1_ID);
        List<Notification> initialM2 = notificationService.getNotificationsForUser(MEMBER2_ID);
        assertEquals(1, initialM1.size());
        assertEquals(1, initialM2.size());

        // Manually publish event again
        eventPublisher.publishEvent(new PaymentRecordedEvent(payment));

        List<Notification> postReprocessM1 = notificationService.getNotificationsForUser(MEMBER1_ID);
        List<Notification> postReprocessM2 = notificationService.getNotificationsForUser(MEMBER2_ID);
        assertEquals(1, postReprocessM1.size(), "Reprocessing event must NOT create duplicate notification for Member 1");
        assertEquals(1, postReprocessM2.size(), "Reprocessing event must NOT create duplicate notification for Member 2");
    }

    @Test
    @DisplayName("TEST 10: N-LANDLORD - Landlord B records payment -> Members in Room receive notification, Landlords/Admin receive 0")
    void testNLandlordPaymentNotification() {
        Payment payment = paymentService.recordPayment(
                LANDLORD_B_ID, // Landlord B records payment for shared house
                invoice101.getId(),
                new BigDecimal("3500000"),
                PaymentMethod.BANK_TRANSFER,
                "Thanh toán bởi Landlord B"
        );

        assertNotNull(payment.getId());

        List<Notification> memberNotifs = notificationService.getNotificationsForUser(MEMBER1_ID);
        assertEquals(1, memberNotifs.size(), "Member 1 must receive notification regardless of which Landlord recorded it");

        assertTrue(notificationService.getNotificationsForUser(LANDLORD_A_ID).isEmpty());
        assertTrue(notificationService.getNotificationsForUser(LANDLORD_B_ID).isEmpty());
        assertTrue(notificationService.getNotificationsForUser(ADMIN_ID).isEmpty());
    }
}
