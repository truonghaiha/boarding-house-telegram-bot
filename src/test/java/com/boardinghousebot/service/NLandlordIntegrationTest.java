package com.boardinghousebot.service;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.PaymentMethod;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
public class NLandlordIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private MeterReadingService meterReadingService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MaintenanceRequestRepository maintenanceRequestRepository;

    @MockBean
    private BotMessageSender botMessageSender;

    private User landlordA;
    private User landlordB;
    private User member;

    @BeforeEach
    void setUp() {
        landlordA = userRepository.save(new User(1001L, "landlordA", "Landlord Alpha", Role.LANDLORD));
        landlordB = userRepository.save(new User(1002L, "landlordB", "Landlord Beta", Role.LANDLORD));
        member = userRepository.save(new User(2001L, "member1", "Nguyen Van Member", Role.MEMBER));
    }

    @Test
    @DisplayName("Landlord A creates room -> Landlord B can view it")
    void testLandlordACreatesRoom_LandlordBCanView() {
        Room createdRoom = roomService.createRoom(landlordA.getTelegramId(), "301", new BigDecimal("4000000"), 3, "Vip Room");
        assertNotNull(createdRoom);

        List<Room> allRoomsForB = roomService.getAllRooms();
        assertTrue(allRoomsForB.stream().anyMatch(r -> r.getRoomNumber().equals("301")));
    }

    @Test
    @DisplayName("Landlord A creates invoice -> Landlord B can view it")
    void testLandlordACreatesInvoice_LandlordBCanView() {
        Room room = roomService.createRoom(landlordA.getTelegramId(), "302", new BigDecimal("3000000"), 2, "Standard");
        roomService.assignMemberToRoom(landlordA.getTelegramId(), member.getTelegramId(), room.getId());
        contractService.createContract(landlordA.getTelegramId(), member.getTelegramId(), java.time.LocalDate.now(), java.time.LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));

        meterReadingService.createReading(landlordA.getTelegramId(), room.getId(), com.boardinghousebot.domain.meter.UtilityType.ELECTRICITY, 9, 2026, new BigDecimal("100"), new BigDecimal("150"), new BigDecimal("3500"));
        meterReadingService.createReading(landlordA.getTelegramId(), room.getId(), com.boardinghousebot.domain.meter.UtilityType.WATER, 9, 2026, new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("15000"));

        Invoice invoice = invoiceService.createInvoice(
                landlordA.getTelegramId(),
                room.getId(),
                9,
                2026
        );

        assertNotNull(invoice);

        List<Invoice> allInvoicesForB = invoiceService.getAllInvoices();
        assertTrue(allInvoicesForB.stream().anyMatch(i -> i.getId().equals(invoice.getId())));
    }

    @Test
    @DisplayName("Landlord B records payment -> Landlord A can view it")
    void testLandlordBRecordsPayment_LandlordACanView() {
        User member2 = userRepository.save(new User(2002L, "member2", "Nguyen Van Member 2", Role.MEMBER));
        Room room = roomService.createRoom(landlordA.getTelegramId(), "303", new BigDecimal("3000000"), 2, "Standard");
        roomService.assignMemberToRoom(landlordA.getTelegramId(), member2.getTelegramId(), room.getId());
        contractService.createContract(landlordA.getTelegramId(), member2.getTelegramId(), java.time.LocalDate.now(), java.time.LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));

        meterReadingService.createReading(landlordA.getTelegramId(), room.getId(), com.boardinghousebot.domain.meter.UtilityType.ELECTRICITY, 9, 2026, new BigDecimal("100"), new BigDecimal("150"), new BigDecimal("3500"));
        meterReadingService.createReading(landlordA.getTelegramId(), room.getId(), com.boardinghousebot.domain.meter.UtilityType.WATER, 9, 2026, new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("15000"));

        Invoice invoice = invoiceService.createInvoice(
                landlordA.getTelegramId(),
                room.getId(),
                9,
                2026
        );

        Payment payment = paymentService.recordPayment(landlordB.getTelegramId(), invoice.getId(), new BigDecimal("1000000"), PaymentMethod.CASH, "Landlord B collected partial payment");
        assertNotNull(payment);

        List<Payment> paymentsForInvoice = paymentService.getInvoicePayments(invoice.getId());
        assertTrue(paymentsForInvoice.stream().anyMatch(p -> p.getId().equals(payment.getId())));

        Invoice updatedInvoice = invoiceService.getInvoiceById(invoice.getId());
        assertEquals(0, new BigDecimal("1000000").compareTo(updatedInvoice.getPaidAmount()));
    }

    @Test
    @DisplayName("TEST 4: Member reports maintenance -> BOTH Landlord A and Landlord B receive DB Notification")
    void testMemberReportsMaintenance_BothLandlordsReceiveDbNotification() {
        Room room = roomService.createRoom(landlordA.getTelegramId(), "304", new BigDecimal("3000000"), 2, "Standard");
        roomService.assignMemberToRoom(landlordA.getTelegramId(), member.getTelegramId(), room.getId());

        MaintenanceRequest request = maintenanceService.createMaintenanceRequest(
                member.getTelegramId(),
                "Hỏng quạt trần",
                "Quạt trần phòng 304 bị hỏng tụ điện"
        );
        assertNotNull(request);

        // Verify DB Notifications created for both Landlord A and Landlord B
        List<Notification> notifsA = notificationService.getNotificationsForUser(landlordA.getTelegramId());
        List<Notification> notifsB = notificationService.getNotificationsForUser(landlordB.getTelegramId());

        assertTrue(notifsA.stream().anyMatch(n -> n.getType() == NotificationType.MAINTENANCE_CREATED && n.getMessage().contains("304")));
        assertTrue(notifsB.stream().anyMatch(n -> n.getType() == NotificationType.MAINTENANCE_CREATED && n.getMessage().contains("304")));
    }

    @Test
    @DisplayName("TEST 5: Member reports maintenance -> TelegramNotificationService sends message to BOTH Landlord A and Landlord B")
    void testMemberReportsMaintenance_BothLandlordsReceiveTelegramMessage() {
        Room room = roomService.createRoom(landlordA.getTelegramId(), "305", new BigDecimal("3000000"), 2, "Standard");
        roomService.assignMemberToRoom(landlordA.getTelegramId(), member.getTelegramId(), room.getId());

        MaintenanceRequest request = maintenanceService.createMaintenanceRequest(
                member.getTelegramId(),
                "Hỏng điều hòa",
                "Điều hòa không mát"
        );
        assertNotNull(request);

        // Verify Telegram messages sent to both Landlord A and Landlord B
        verify(botMessageSender).sendMessage(eq(landlordA.getTelegramId()), contains("BÁO CÁO SỰ CỐ MỚI"));
        verify(botMessageSender).sendMessage(eq(landlordB.getTelegramId()), contains("BÁO CÁO SỰ CỐ MỚI"));
    }

    @Test
    @DisplayName("TEST 6: Notification isolation: Landlord A only sees notifications of Landlord A, Landlord B only sees Landlord B")
    void testNotificationIsolation() {
        notificationService.createNotification(landlordA.getTelegramId(), NotificationType.ROLE_CHANGED, "Notif A", "Msg for A");
        notificationService.createNotification(landlordB.getTelegramId(), NotificationType.ROLE_CHANGED, "Notif B", "Msg for B");

        List<Notification> notifsA = notificationService.getNotificationsForUser(landlordA.getTelegramId());
        List<Notification> notifsB = notificationService.getNotificationsForUser(landlordB.getTelegramId());

        assertEquals(1, notifsA.size());
        assertEquals("Notif A", notifsA.get(0).getTitle());

        assertEquals(1, notifsB.size());
        assertEquals("Notif B", notifsB.get(0).getTitle());
    }

    @Test
    @DisplayName("TEST 7: Member reports maintenance -> BOTH Landlords receive notification, ADMIN receives NO DB or Telegram notification")
    void testMemberReportsMaintenance_AdminDoesNotReceiveNotification() {
        User admin = userRepository.save(new User(9999L, "adminUser", "System Admin", Role.ADMIN));

        Room room = roomService.createRoom(landlordA.getTelegramId(), "306", new BigDecimal("3000000"), 2, "Standard");
        roomService.assignMemberToRoom(landlordA.getTelegramId(), member.getTelegramId(), room.getId());

        MaintenanceRequest request = maintenanceService.createMaintenanceRequest(
                member.getTelegramId(),
                "Hỏng khóa cửa",
                "Khóa cửa phòng 306 bị kẹt"
        );
        assertNotNull(request);

        // Verify DB Notifications created for Landlord A and Landlord B, but NOT for Admin
        List<Notification> notifsA = notificationService.getNotificationsForUser(landlordA.getTelegramId());
        List<Notification> notifsB = notificationService.getNotificationsForUser(landlordB.getTelegramId());
        List<Notification> notifsAdmin = notificationService.getNotificationsForUser(admin.getTelegramId());

        assertFalse(notifsA.isEmpty(), "Landlord A must receive DB notification");
        assertFalse(notifsB.isEmpty(), "Landlord B must receive DB notification");
        assertTrue(notifsAdmin.isEmpty(), "ADMIN must NOT receive maintenance DB notification");

        // Verify Telegram message sent to Landlord A and Landlord B, but NEVER to Admin
        verify(botMessageSender).sendMessage(eq(landlordA.getTelegramId()), contains("BÁO CÁO SỰ CỐ MỚI"));
        verify(botMessageSender).sendMessage(eq(landlordB.getTelegramId()), contains("BÁO CÁO SỰ CỐ MỚI"));
        verify(botMessageSender, never()).sendMessage(eq(admin.getTelegramId()), contains("BÁO CÁO SỰ CỐ MỚI"));
    }
}
