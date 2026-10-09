package com.boardinghousebot.repository;

import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class DatabaseNotificationConstraintTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Verify database supports saving all NotificationTypes (including CONTRACT_CREATED, ELECTRICITY_CREATED, WATER_CREATED)")
    void testSaveAllNotificationTypesSuccess() {
        Long telegramId = 9998887771L;
        User testUser = new User();
        testUser.setTelegramId(telegramId);
        testUser.setFullName("Test User DB Constraint");
        testUser.setRole(Role.MEMBER);
        userRepository.save(testUser);

        // Test all notification types
        for (NotificationType type : NotificationType.values()) {
            Notification notif = new Notification(
                    testUser,
                    type,
                    type.getDisplayName() + " - Phòng 101",
                    "Nội dung kiểm tra " + type.name(),
                    "TEST_REF",
                    100L
            );
            
            assertDoesNotThrow(() -> {
                notificationRepository.save(notif);
                notificationRepository.flush();
            }, "Database must support saving NotificationType: " + type.name());
        }

        List<Notification> saved = notificationRepository.findByRecipientTelegramIdOrderByCreatedAtDesc(telegramId);
        assertEquals(NotificationType.values().length, saved.size(), "All notification types must be retrievable from DB");
    }

    @Test
    @DisplayName("Verify individual insertion for mandatory new types: CONTRACT_CREATED, ELECTRICITY_CREATED, WATER_CREATED")
    void testMandatoryNewNotificationTypesIndividual() {
        Long telegramId = 9998887772L;
        User testUser = new User();
        testUser.setTelegramId(telegramId);
        testUser.setFullName("Test User Individual");
        testUser.setRole(Role.MEMBER);
        userRepository.save(testUser);

        // 1. CONTRACT_CREATED
        Notification contractNotif = notificationRepository.save(
                new Notification(testUser, NotificationType.CONTRACT_CREATED, "Hợp đồng mới - Phòng 101", "Chi tiết hợp đồng", "CONTRACT", 1L)
        );
        assertNotNull(contractNotif.getId());

        // 2. ELECTRICITY_CREATED
        Notification elecNotif = notificationRepository.save(
                new Notification(testUser, NotificationType.ELECTRICITY_CREATED, "Chỉ số điện mới - Phòng 101", "Chi tiết điện", "METER_READING", 2L)
        );
        assertNotNull(elecNotif.getId());

        // 3. WATER_CREATED
        Notification waterNotif = notificationRepository.save(
                new Notification(testUser, NotificationType.WATER_CREATED, "Chỉ số nước mới - Phòng 101", "Chi tiết nước", "METER_READING", 3L)
        );
        assertNotNull(waterNotif.getId());

        // 4. INVOICE_CREATED
        Notification invoiceNotif = notificationRepository.save(
                new Notification(testUser, NotificationType.INVOICE_CREATED, "Hóa đơn mới - Phòng 101", "Chi tiết hóa đơn", "INVOICE", 4L)
        );
        assertNotNull(invoiceNotif.getId());

        // Flush and read back
        notificationRepository.flush();
        List<Notification> userNotifs = notificationRepository.findByRecipientTelegramIdOrderByCreatedAtDesc(telegramId);
        assertEquals(4, userNotifs.size());
    }
}
