package com.boardinghousebot.observer;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.*;
import com.boardinghousebot.service.ContractService;
import com.boardinghousebot.service.MaintenanceService;
import com.boardinghousebot.service.NotificationService;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.UserService;
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
class NotificationObserverIntegrationTest {

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private TelegramConfig telegramConfig;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MaintenanceRequestRepository maintenanceRequestRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    private User adminLandlord;
    private User member;
    private Room room;

    private static final Long LANDLORD_TELEGRAM_ID = 888999000L;
    private static final Long MEMBER_TELEGRAM_ID = 111222333L;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        maintenanceRequestRepository.deleteAll();
        contractRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();

        notificationRepository.flush();
        maintenanceRequestRepository.flush();
        contractRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        telegramConfig.setAdminId(LANDLORD_TELEGRAM_ID.toString());

        adminLandlord = userService.getOrCreateUser(LANDLORD_TELEGRAM_ID, "landlord_admin", "Chủ Trọ Admin");
        adminLandlord.setRole(Role.LANDLORD);
        userRepository.save(adminLandlord);
        member = userService.getOrCreateUser(MEMBER_TELEGRAM_ID, "member_test", "Người Thuê A");

        room = roomService.createRoom(LANDLORD_TELEGRAM_ID, "201", new BigDecimal("3500000"), 2, "Phong 201");
        roomService.assignMemberToRoom(LANDLORD_TELEGRAM_ID, MEMBER_TELEGRAM_ID, room.getId());

        contractService.createContract(
                LANDLORD_TELEGRAM_ID,
                MEMBER_TELEGRAM_ID,
                LocalDate.now(),
                LocalDate.now().plusMonths(6),
                new BigDecimal("3500000"),
                new BigDecimal("3500000")
        );
    }

    @Test
    @DisplayName("End-to-end Observer Flow: MEMBER creates maintenance -> LANDLORD receives notification in DB")
    void testMaintenanceCreated_ObserverCreatesNotificationForLandlord() {
        // 1. MEMBER creates maintenance request
        MaintenanceRequest req = maintenanceService.createMaintenanceRequest(
                MEMBER_TELEGRAM_ID,
                "Hỏng công tắc đèn",
                "Công tắc bị ngắt mạch không bật được"
        );

        assertNotNull(req.getId());

        // 2. Verify LANDLORD receives notification in DB via NotificationObserver
        List<Notification> landlordNotifications = notificationService.getNotificationsForUser(LANDLORD_TELEGRAM_ID);
        assertFalse(landlordNotifications.isEmpty(), "LANDLORD must receive notification when MEMBER reports an issue");

        Notification notif = landlordNotifications.get(0);
        assertEquals(NotificationType.MAINTENANCE_CREATED, notif.getType());
        assertTrue(notif.getTitle().contains("201"));
        assertTrue(notif.getMessage().contains("Hỏng công tắc đèn"));
        assertEquals("MAINTENANCE", notif.getReferenceType());
        assertEquals(req.getId(), notif.getReferenceId());
    }

    @Test
    @DisplayName("End-to-end Observer Flow: LANDLORD updates maintenance status -> MEMBER receives notification in DB")
    void testMaintenanceStatusChanged_ObserverCreatesNotificationForMember() {
        MaintenanceRequest req = maintenanceService.createMaintenanceRequest(
                MEMBER_TELEGRAM_ID,
                "Hỏng vòi nước",
                "Vòi nước bồn rửa bị rò rỉ"
        );

        // Clear previous landlord notifications
        notificationRepository.deleteAll();

        // 2. LANDLORD updates status to IN_PROGRESS
        MaintenanceRequest updated = maintenanceService.updateMaintenanceStatus(
                LANDLORD_TELEGRAM_ID,
                req.getId(),
                MaintenanceStatus.IN_PROGRESS,
                "Đã hẹn thợ 15h"
        );

        assertEquals(MaintenanceStatus.IN_PROGRESS, updated.getStatus());

        // 3. Verify MEMBER receives notification in DB via NotificationObserver
        List<Notification> memberNotifications = notificationService.getNotificationsForUser(MEMBER_TELEGRAM_ID);
        assertFalse(memberNotifications.isEmpty(), "MEMBER must receive notification when LANDLORD updates status");

        Notification notif = memberNotifications.get(0);
        assertEquals(NotificationType.MAINTENANCE_STATUS_CHANGED, notif.getType());
        assertTrue(notif.getMessage().contains("Đã hẹn thợ 15h"));
        assertEquals("MAINTENANCE", notif.getReferenceType());
        assertEquals(req.getId(), notif.getReferenceId());
    }
}
