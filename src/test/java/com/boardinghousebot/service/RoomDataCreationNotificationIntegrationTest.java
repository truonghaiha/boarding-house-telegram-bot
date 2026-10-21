package com.boardinghousebot.service;
import static org.mockito.ArgumentMatchers.argThat;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.NotificationRepository;
import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
public class RoomDataCreationNotificationIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserService userService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private MeterReadingService meterReadingService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @MockBean
    private BotMessageSender botMessageSender;

    private User landlordA;
    private User landlordB;
    private User admin;
    private User memberA;
    private User memberB;
    private User memberC;
    private Room room801;
    private Room room802;

    private static final Long LANDLORD_A_ID = 8801L;
    private static final Long LANDLORD_B_ID = 8802L;
    private static final Long ADMIN_ID = 8899L;
    private static final Long MEMBER_A_ID = 8811L;
    private static final Long MEMBER_B_ID = 8812L;
    private static final Long MEMBER_C_ID = 8813L;

    @BeforeEach
    void setUp() {
        landlordA = userService.getOrCreateUser(LANDLORD_A_ID, "landlordA", "Landlord Alpha");
        landlordA.setRole(Role.LANDLORD);
        userRepository.save(landlordA);

        landlordB = userService.getOrCreateUser(LANDLORD_B_ID, "landlordB", "Landlord Beta");
        landlordB.setRole(Role.LANDLORD);
        userRepository.save(landlordB);

        admin = userService.getOrCreateUser(ADMIN_ID, "adminUser", "System Admin");
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);

        memberA = userService.getOrCreateUser(MEMBER_A_ID, "memberA", "Member Alpha");
        memberA.setRole(Role.MEMBER);
        userRepository.save(memberA);

        memberB = userService.getOrCreateUser(MEMBER_B_ID, "memberB", "Member Beta");
        memberB.setRole(Role.MEMBER);
        userRepository.save(memberB);

        memberC = userService.getOrCreateUser(MEMBER_C_ID, "memberC", "Member Gamma");
        memberC.setRole(Role.MEMBER);
        userRepository.save(memberC);

        room801 = roomService.createRoom(LANDLORD_A_ID, "R801", new BigDecimal("3000000"), 3, "Phong 801");
        room802 = roomService.createRoom(LANDLORD_A_ID, "R802", new BigDecimal("4000000"), 3, "Phong 802");

        // Assign Member A & B to Room 801, Member C to Room 802
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_A_ID, room801.getId());
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_B_ID, room801.getId());
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_C_ID, room802.getId());
    }

    @Test
    @DisplayName("TEST 1: CONTRACT Created -> ALL Members in Room receive DB & Telegram notifications, ADMIN & Landlords receive 0")
    void test1_ContractCreated_NotifiesAllRoomMembers() {
        notificationRepository.deleteAll();

        Contract contract = contractService.createContract(
                LANDLORD_A_ID, MEMBER_A_ID,
                LocalDate.now(), LocalDate.now().plusMonths(6),
                new BigDecimal("3000000"), new BigDecimal("3000000")
        );
        assertNotNull(contract);

        // Verify DB Notifications for Member A and Member B
        List<Notification> notifsA = notificationService.getNotificationsForUser(MEMBER_A_ID);
        List<Notification> notifsB = notificationService.getNotificationsForUser(MEMBER_B_ID);
        List<Notification> notifsAdmin = notificationService.getNotificationsForUser(ADMIN_ID);
        List<Notification> notifsLandlordB = notificationService.getNotificationsForUser(LANDLORD_B_ID);

        assertEquals(1, notifsA.size());
        assertEquals(NotificationType.CONTRACT_CREATED, notifsA.get(0).getType());
        assertTrue(notifsA.get(0).getTitle().contains("R801"));

        assertEquals(1, notifsB.size());
        assertEquals(NotificationType.CONTRACT_CREATED, notifsB.get(0).getType());

        assertTrue(notifsAdmin.isEmpty(), "ADMIN must NOT receive contract created notification");
        assertTrue(notifsLandlordB.isEmpty(), "Landlord B must NOT receive contract created notification");

        // Verify Telegram messages sent to Member A & B
        verify(botMessageSender).sendMessage(eq(MEMBER_A_ID), contains("HỢP ĐỒNG MỚI"));
        verify(botMessageSender).sendMessage(eq(MEMBER_B_ID), contains("HỢP ĐỒNG MỚI"));
        verify(botMessageSender, never()).sendMessage(eq(ADMIN_ID), contains("HỢP ĐỒNG MỚI"));
        verify(botMessageSender, never()).sendMessage(eq(LANDLORD_B_ID), contains("HỢP ĐỒNG MỚI"));
    }

    @Test
    void contractEndedNotifiesRoomMembersWithPrivateDetailsOnlyForRepresentative() {
        Contract contract = contractService.createContract(LANDLORD_A_ID, MEMBER_A_ID,
                LocalDate.now(), LocalDate.now().plusMonths(6),
                new BigDecimal("3000000"), new BigDecimal("1500000"));
        notificationRepository.deleteAll();
        org.mockito.Mockito.clearInvocations(botMessageSender);

        contractService.endContract(LANDLORD_A_ID, contract.getId());

        List<Notification> representative = notificationService.getNotificationsForUser(MEMBER_A_ID).stream()
                .filter(n -> n.getType() == NotificationType.CONTRACT_ENDED).toList();
        List<Notification> roommate = notificationService.getNotificationsForUser(MEMBER_B_ID).stream()
                .filter(n -> n.getType() == NotificationType.CONTRACT_ENDED).toList();
        assertTrue(representative.isEmpty());
        assertTrue(roommate.isEmpty());
        assertTrue(notificationService.getNotificationsForUser(MEMBER_A_ID).isEmpty());
        assertTrue(notificationService.getNotificationsForUser(MEMBER_B_ID).isEmpty());
        assertTrue(notificationService.getNotificationsForUser(MEMBER_C_ID).isEmpty());
        assertTrue(notificationService.getNotificationsForUser(LANDLORD_A_ID).isEmpty());
        assertTrue(notificationService.getNotificationsForUser(ADMIN_ID).isEmpty());
        verify(botMessageSender).sendMessage(eq(MEMBER_A_ID), argThat(message ->
                message.contains("HỢP ĐỒNG ĐÃ KẾT THÚC") && message.contains("Tiền cọc theo hợp đồng")));
        verify(botMessageSender).sendMessage(eq(MEMBER_B_ID), argThat(message ->
                message.contains("HỢP ĐỒNG ĐÃ KẾT THÚC") && !message.contains("Tiền cọc") && !message.contains("Tiền thuê")));
        verify(botMessageSender, never()).sendMessage(eq(MEMBER_C_ID), contains("HỢP ĐỒNG ĐÃ KẾT THÚC"));

        org.mockito.Mockito.clearInvocations(botMessageSender);
        assertThrows(com.boardinghousebot.exception.BusinessException.class,
                () -> contractService.endContract(LANDLORD_A_ID, contract.getId()));
        assertEquals(0, notificationService.getNotificationsForUser(MEMBER_A_ID).stream()
                .filter(n -> n.getType() == NotificationType.CONTRACT_ENDED).count());
        org.mockito.Mockito.verifyNoInteractions(botMessageSender);
    }

    @Test
    @DisplayName("TEST 2: ELECTRICITY Created -> ALL Members in Room receive DB & Telegram notifications")
    void test2_ElectricityCreated_NotifiesAllRoomMembers() {
        contractService.createContract(LANDLORD_A_ID, MEMBER_A_ID, LocalDate.now(), LocalDate.now().plusMonths(6),
                room801.getMonthlyRent(), BigDecimal.ZERO);
        notificationRepository.deleteAll();

        MeterReading reading = meterReadingService.createReading(
                LANDLORD_A_ID, room801.getId(), UtilityType.ELECTRICITY,
                LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("3500")
        );
        assertNotNull(reading);

        List<Notification> notifsA = notificationService.getNotificationsForUser(MEMBER_A_ID);
        List<Notification> notifsB = notificationService.getNotificationsForUser(MEMBER_B_ID);

        assertEquals(1, notifsA.size());
        assertEquals(NotificationType.ELECTRICITY_CREATED, notifsA.get(0).getType());
        assertTrue(notifsA.get(0).getTitle().contains("R801"));

        assertEquals(1, notifsB.size());
        assertEquals(NotificationType.ELECTRICITY_CREATED, notifsB.get(0).getType());

        verify(botMessageSender).sendMessage(eq(MEMBER_A_ID), contains("CHỈ SỐ ĐIỆN MỚI"));
        verify(botMessageSender).sendMessage(eq(MEMBER_B_ID), contains("CHỈ SỐ ĐIỆN MỚI"));
    }

    @Test
    @DisplayName("TEST 3: WATER Created -> ALL Members in Room receive DB & Telegram notifications")
    void test3_WaterCreated_NotifiesAllRoomMembers() {
        contractService.createContract(LANDLORD_A_ID, MEMBER_A_ID, LocalDate.now(), LocalDate.now().plusMonths(6),
                room801.getMonthlyRent(), BigDecimal.ZERO);
        notificationRepository.deleteAll();

        MeterReading reading = meterReadingService.createReading(
                LANDLORD_A_ID, room801.getId(), UtilityType.WATER,
                LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("15000")
        );
        assertNotNull(reading);

        List<Notification> notifsA = notificationService.getNotificationsForUser(MEMBER_A_ID);
        List<Notification> notifsB = notificationService.getNotificationsForUser(MEMBER_B_ID);

        assertEquals(1, notifsA.size());
        assertEquals(NotificationType.WATER_CREATED, notifsA.get(0).getType());

        assertEquals(1, notifsB.size());
        assertEquals(NotificationType.WATER_CREATED, notifsB.get(0).getType());

        verify(botMessageSender).sendMessage(eq(MEMBER_A_ID), contains("CHỈ SỐ NƯỚC MỚI"));
        verify(botMessageSender).sendMessage(eq(MEMBER_B_ID), contains("CHỈ SỐ NƯỚC MỚI"));
    }

    @Test
    @DisplayName("TEST 4: INVOICE Created -> ALL Members in Room receive DB & Telegram notifications")
    void test4_InvoiceCreated_NotifiesAllRoomMembers() {
        contractService.createContract(LANDLORD_A_ID, MEMBER_A_ID, LocalDate.now(), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));
        meterReadingService.createReading(LANDLORD_A_ID, room801.getId(), UtilityType.ELECTRICITY, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500"));
        meterReadingService.createReading(LANDLORD_A_ID, room801.getId(), UtilityType.WATER, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000"));

        notificationRepository.deleteAll();

        Invoice invoice = invoiceService.createInvoice(LANDLORD_A_ID, room801.getId(), LocalDate.now().getMonthValue(), LocalDate.now().getYear());
        assertNotNull(invoice);

        List<Notification> notifsA = notificationService.getNotificationsForUser(MEMBER_A_ID);
        List<Notification> notifsB = notificationService.getNotificationsForUser(LANDLORD_A_ID != null ? MEMBER_B_ID : null);

        assertEquals(1, notifsA.size());
        assertEquals(NotificationType.INVOICE_CREATED, notifsA.get(0).getType());
        assertTrue(notifsA.get(0).getTitle().contains("R801"));

        assertEquals(1, notifsB.size());
        assertEquals(NotificationType.INVOICE_CREATED, notifsB.get(0).getType());

        verify(botMessageSender).sendMessage(eq(MEMBER_A_ID), contains("HÓA ĐƠN MỚI"));
        verify(botMessageSender).sendMessage(eq(MEMBER_B_ID), contains("HÓA ĐƠN MỚI"));
    }

    @Test
    @DisplayName("TEST 5: ROOM ISOLATION: Creating Invoice for Room 801 does NOT notify Member C in Room 802")
    void test5_RoomIsolation_MembersInOtherRoomsNotNotified() {
        contractService.createContract(LANDLORD_A_ID, MEMBER_A_ID, LocalDate.now(), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));
        meterReadingService.createReading(LANDLORD_A_ID, room801.getId(), UtilityType.ELECTRICITY, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500"));
        meterReadingService.createReading(LANDLORD_A_ID, room801.getId(), UtilityType.WATER, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000"));

        notificationRepository.deleteAll();

        invoiceService.createInvoice(LANDLORD_A_ID, room801.getId(), LocalDate.now().getMonthValue(), LocalDate.now().getYear());

        List<Notification> notifsC = notificationService.getNotificationsForUser(MEMBER_C_ID);
        assertTrue(notifsC.isEmpty(), "Member C in Room 802 must NOT receive invoice notification for Room 801");
        verify(botMessageSender, never()).sendMessage(eq(MEMBER_C_ID), contains("HÓA ĐƠN MỚI"));
    }

    @Test
    @DisplayName("TEST 6: N-LANDLORD Model: Landlord A creates Invoice -> Members in room receive, Landlord B & ADMIN receive 0")
    void test6_NLandlordModel_RecipientsIsolated() {
        contractService.createContract(LANDLORD_A_ID, MEMBER_A_ID, LocalDate.now(), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));
        meterReadingService.createReading(LANDLORD_A_ID, room801.getId(), UtilityType.ELECTRICITY, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500"));
        meterReadingService.createReading(LANDLORD_A_ID, room801.getId(), UtilityType.WATER, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000"));

        notificationRepository.deleteAll();

        invoiceService.createInvoice(LANDLORD_A_ID, room801.getId(), LocalDate.now().getMonthValue(), LocalDate.now().getYear());

        List<Notification> notifsLandlordB = notificationService.getNotificationsForUser(LANDLORD_B_ID);
        List<Notification> notifsAdmin = notificationService.getNotificationsForUser(ADMIN_ID);

        assertTrue(notifsLandlordB.isEmpty());
        assertTrue(notifsAdmin.isEmpty());
    }

    @Test
    @DisplayName("TEST 7: NO DUPLICATE: Single Invoice creation produces exactly 1 notification per room member")
    void test7_NoDuplicateNotification() {
        contractService.createContract(LANDLORD_A_ID, MEMBER_A_ID, LocalDate.now(), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));
        meterReadingService.createReading(LANDLORD_A_ID, room801.getId(), UtilityType.ELECTRICITY, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500"));
        meterReadingService.createReading(LANDLORD_A_ID, room801.getId(), UtilityType.WATER, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000"));

        notificationRepository.deleteAll();

        Invoice invoice = invoiceService.createInvoice(LANDLORD_A_ID, room801.getId(), LocalDate.now().getMonthValue(), LocalDate.now().getYear());

        List<Notification> notifsA = notificationService.getNotificationsForUser(MEMBER_A_ID);
        List<Notification> notifsB = notificationService.getNotificationsForUser(MEMBER_B_ID);

        assertEquals(1, notifsA.size());
        assertEquals(1, notifsB.size());
        assertEquals(invoice.getId(), notifsA.get(0).getReferenceId());
        assertEquals(invoice.getId(), notifsB.get(0).getReferenceId());
    }
}
