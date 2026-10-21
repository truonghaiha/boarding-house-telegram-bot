package com.boardinghousebot.service;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.observer.MaintenanceCreatedEvent;
import com.boardinghousebot.observer.MaintenanceStatusChangedEvent;
import com.boardinghousebot.observer.MemberRoomChangedEvent;
import com.boardinghousebot.observer.RoleChangedEvent;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class TelegramNotificationFlowTest {

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private NotificationService notificationService;

    @Autowired private ContractService contracts;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private MaintenanceRequestRepository maintenanceRequestRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockBean
    private BotMessageSender botMessageSender;

    private static final Long ADMIN_ID = 6849481889L;
    private static final Long LANDLORD_ID = 6522259425L;
    private static final Long MEMBER_ID = 8167463325L;

    private User admin;
    private User landlord;
    private User member;
    private Room room101;
    private Room room102;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        notificationRepository.deleteAll();
        maintenanceRequestRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();

        admin = userService.getOrCreateUser(ADMIN_ID, "admin_user", "Admin Master");
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);

        landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord_user", "Chủ Trọ B");
        landlord.setRole(Role.LANDLORD);
        userRepository.save(landlord);

        member = userService.getOrCreateUser(MEMBER_ID, "member_user", "Nguyễn Văn A");

        room101 = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("3500000"), 4, "Phòng 101");
        room102 = roomService.createRoom(LANDLORD_ID, "102", new BigDecimal("4000000"), 4, "Phòng 102");
    }

    @Test
    @DisplayName("1. Promote MEMBER -> LANDLORD saves DB notification and sends immediate Telegram message")
    void testPromoteMemberToLandlordNotification() {
        User promoted = userService.promoteToLandlord(ADMIN_ID, MEMBER_ID);
        assertEquals(Role.LANDLORD, promoted.getRole());

        List<Notification> dbNotifications = notificationService.getNotificationsForUser(MEMBER_ID);
        assertFalse(dbNotifications.isEmpty());
        assertEquals(NotificationType.ROLE_CHANGED, dbNotifications.get(0).getType());

        verify(botMessageSender).sendMessage(eq(MEMBER_ID), contains("Bạn đã được nâng cấp từ MEMBER lên LANDLORD"));
    }

    @Test
    @DisplayName("2. Revoke LANDLORD -> MEMBER saves DB notification and sends immediate Telegram message")
    void testRevokeLandlordNotification() {
        userService.promoteToLandlord(ADMIN_ID, MEMBER_ID);
        reset(botMessageSender);

        User revoked = userService.revokeLandlord(ADMIN_ID, MEMBER_ID);
        assertEquals(Role.MEMBER, revoked.getRole());

        List<Notification> dbNotifications = notificationService.getNotificationsForUser(MEMBER_ID);
        assertEquals(NotificationType.ROLE_CHANGED, dbNotifications.get(0).getType());

        verify(botMessageSender).sendMessage(eq(MEMBER_ID), contains("Vai trò LANDLORD của bạn đã được thu hồi"));
    }

    @Test
    @DisplayName("3. MEMBER creates maintenance request sends DB & Telegram notification to Landlords")
    void testMemberCreateMaintenanceNotification() {
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room101.getId());
        reset(botMessageSender);

        MaintenanceRequest req = maintenanceService.createMaintenanceRequest(MEMBER_ID, "Hỏng quạt trần", "Quạt phát ra tiếng kêu lớn");
        assertNotNull(req);

        List<Notification> landlordNotifications = notificationService.getNotificationsForUser(LANDLORD_ID);
        assertFalse(landlordNotifications.isEmpty());
        assertEquals(NotificationType.MAINTENANCE_CREATED, landlordNotifications.get(0).getType());

        verify(botMessageSender).sendMessage(eq(LANDLORD_ID), contains("BÁO CÁO SỰ CỐ MỚI"));
    }

    @Test
    @DisplayName("4. Maintenance status PENDING -> IN_PROGRESS sends DB & Telegram notification to Member")
    void testMaintenanceStatusPendingToInProgressNotification() {
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room101.getId());
        contracts.createContract(LANDLORD_ID, MEMBER_ID, java.time.LocalDate.now(), java.time.LocalDate.now().plusMonths(6),
                room101.getMonthlyRent(), BigDecimal.ZERO);
        MaintenanceRequest req = maintenanceService.createMaintenanceRequest(MEMBER_ID, "Hỏng điện", "Mất điện toàn phòng");
        reset(botMessageSender);

        MaintenanceRequest updated = maintenanceService.updateMaintenanceStatus(LANDLORD_ID, req.getId(), MaintenanceStatus.IN_PROGRESS, "Đã gọi thợ sửa");
        assertEquals(MaintenanceStatus.IN_PROGRESS, updated.getStatus());

        List<Notification> memberNotifications = notificationService.getNotificationsForUser(MEMBER_ID);
        assertFalse(memberNotifications.isEmpty());
        assertEquals(NotificationType.MAINTENANCE_STATUS_CHANGED, memberNotifications.get(0).getType());

        verify(botMessageSender).sendMessage(eq(MEMBER_ID), contains("PENDING → IN_PROGRESS"));
    }

    @Test
    @DisplayName("5. Maintenance status IN_PROGRESS -> RESOLVED sends DB & Telegram notification to Member")
    void testMaintenanceStatusResolvedNotification() {
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room101.getId());
        contracts.createContract(LANDLORD_ID, MEMBER_ID, java.time.LocalDate.now(), java.time.LocalDate.now().plusMonths(6),
                room101.getMonthlyRent(), BigDecimal.ZERO);
        MaintenanceRequest req = maintenanceService.createMaintenanceRequest(MEMBER_ID, "Hỏng khóa", "Không khóa được cửa");
        maintenanceService.updateMaintenanceStatus(LANDLORD_ID, req.getId(), MaintenanceStatus.IN_PROGRESS, "Đang làm lại chìa");
        reset(botMessageSender);

        MaintenanceRequest resolved = maintenanceService.updateMaintenanceStatus(LANDLORD_ID, req.getId(), MaintenanceStatus.RESOLVED, "Đã hoàn thành");
        assertEquals(MaintenanceStatus.RESOLVED, resolved.getStatus());

        List<Notification> memberNotifications = notificationService.getNotificationsForUser(MEMBER_ID);
        assertFalse(memberNotifications.isEmpty());

        verify(botMessageSender).sendMessage(eq(MEMBER_ID), argThat(text -> text.contains("SỰ CỐ ĐÃ ĐƯỢC XỬ LÝ")
                && text.contains("Trạng thái:\nĐã xử lý") && !text.contains("RESOLVED")));
    }

    @Test
    @DisplayName("6. Assign Member to room sends DB & Telegram notification to Member only")
    void testAssignMemberRoomNotification() {
        User assigned = roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room101.getId());
        assertNotNull(assigned.getRoom());

        List<Notification> memberNotifications = notificationService.getNotificationsForUser(MEMBER_ID);
        assertTrue(memberNotifications.isEmpty());

        verify(botMessageSender).sendMessage(eq(MEMBER_ID), contains("BẠN ĐÃ ĐƯỢC THÊM VÀO PHÒNG"));
        verify(botMessageSender, never()).sendMessage(eq(LANDLORD_ID), anyString());
    }

    @Test
    @DisplayName("7. Remove Member from room sends DB & Telegram notification to Member only")
    void testRemoveMemberRoomNotification() {
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room101.getId());
        reset(botMessageSender);

        User removed = roomService.removeMemberFromRoom(LANDLORD_ID, MEMBER_ID);
        assertNull(removed.getRoom());

        List<Notification> memberNotifications = notificationService.getNotificationsForUser(MEMBER_ID);
        assertTrue(memberNotifications.isEmpty());

        verify(botMessageSender).sendMessage(eq(MEMBER_ID), contains("Bạn đã được xóa khỏi phòng 101"));
        verify(botMessageSender, never()).sendMessage(eq(LANDLORD_ID), anyString());
    }

    @Test
    @DisplayName("8. Move Member to new room sends DB & Telegram notification with old and new room info")
    void testMoveMemberRoomNotification() {
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room101.getId());
        reset(botMessageSender);

        User moved = roomService.moveMemberToRoom(LANDLORD_ID, MEMBER_ID, room102.getId());
        assertEquals("102", moved.getRoom().getRoomNumber());

        List<Notification> memberNotifications = notificationService.getNotificationsForUser(MEMBER_ID);
        assertTrue(memberNotifications.isEmpty());

        verify(botMessageSender).sendMessage(eq(MEMBER_ID), contains("Bạn đã được chuyển từ phòng 101"));
        verify(botMessageSender).sendMessage(eq(MEMBER_ID), contains("sang phòng 102"));
    }

    @Test
    @DisplayName("9. Failed room assignment does not trigger any notification")
    void testFailedRoomAssignNoNotification() {
        assertThrows(BusinessException.class, () ->
                roomService.assignMemberToRoom(LANDLORD_ID, ADMIN_ID, room101.getId()));

        List<Notification> allNotifications = notificationRepository.findAll();
        assertTrue(allNotifications.isEmpty());
        verify(botMessageSender, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    @DisplayName("10. Telegram network failure does not rollback main business transaction or DB notification")
    void testTelegramSendFailureResilience() {
        doThrow(new RuntimeException("Telegram API network timeout"))
                .when(botMessageSender).sendMessage(anyLong(), anyString());

        User assigned = assertDoesNotThrow(() ->
                roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room101.getId())
        );

        assertNotNull(assigned.getRoom());
        assertEquals("101", assigned.getRoom().getRoomNumber());

        List<Notification> memberNotifications = notificationService.getNotificationsForUser(MEMBER_ID);
        assertTrue(memberNotifications.isEmpty());
    }
}
