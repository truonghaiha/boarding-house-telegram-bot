package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.service.MaintenanceService;
import com.boardinghousebot.service.NotificationService;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
public class NotificationUiFormattingRegressionTest {

    @Autowired
    private NotificationCommandHandler notificationCommandHandler;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserRepository userRepository;

    @Mock
    private BotMessageSender mockMessageSender;

    private User landlordA;
    private User landlordB;
    private User member;
    private Room room102;

    private static final Long LANDLORD_A_ID = 888001L;
    private static final Long LANDLORD_B_ID = 888002L;
    private static final Long MEMBER_ID = 999001L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        landlordA = userService.getOrCreateUser(LANDLORD_A_ID, "landlordA", "Landlord A");
        landlordA.setRole(Role.LANDLORD);
        userRepository.save(landlordA);

        landlordB = userService.getOrCreateUser(LANDLORD_B_ID, "landlordB", "Landlord B");
        landlordB.setRole(Role.LANDLORD);
        userRepository.save(landlordB);

        member = userService.getOrCreateUser(MEMBER_ID, "member", "HD");
        member.setRole(Role.MEMBER);
        userRepository.save(member);

        room102 = roomService.createRoom(LANDLORD_A_ID, "102", new BigDecimal("3000000"), 2, "Phong 102");
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room102.getId());
    }

    @Test
    @DisplayName("1 & 8. Maintenance notification: Created for N-LANDLORD without duplicate 🔧 🔧 and formatted cleanly")
    void testMaintenanceNotification_FormattingAndNLandlord() {
        // Member creates maintenance request
        MaintenanceRequest req = maintenanceService.createMaintenanceRequest(
                MEMBER_ID,
                "Điều hòa hỏng",
                "Điều hòa bật không mát"
        );
        assertNotNull(req);

        // Verify N-LANDLORD DB notifications created for both Landlord A and Landlord B
        List<Notification> notifsA = notificationService.getNotificationsForUser(LANDLORD_A_ID);
        List<Notification> notifsB = notificationService.getNotificationsForUser(LANDLORD_B_ID);
        assertFalse(notifsA.isEmpty());
        assertFalse(notifsB.isEmpty());

        // Verify title does NOT contain leading emoji (so renderer doesn't duplicate)
        Notification notifA = notifsA.get(0);
        assertFalse(notifA.getTitle().startsWith("🔧"));
        assertEquals("Yêu cầu bảo trì mới - Phòng 102", notifA.getTitle());

        // Render via NotificationCommandHandler
        Update update = createCallbackUpdate("/notification", LANDLORD_A_ID);
        notificationCommandHandler.handle(update, landlordA, mockMessageSender);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockMessageSender).sendMessageWithKeyboard(eq(LANDLORD_A_ID), textCaptor.capture(), any());

        String renderedText = textCaptor.getValue();
        assertNotNull(renderedText);

        // Check NO duplicate icons "🔧 🔧"
        assertFalse(renderedText.contains("🔧 🔧"), "Should NOT contain duplicate icon '🔧 🔧'");
        assertTrue(renderedText.contains("🔧 Yêu cầu bảo trì mới - Phòng 102 (🔴 MỚI)"));

        // Check NO leading indentation spaces on lines
        assertFalse(renderedText.contains("\n   🏠"), "Should not have leading spaces before 🏠");
        assertFalse(renderedText.contains("\n   🕒"), "Should not have leading spaces before 🕒");
        assertTrue(renderedText.contains("\n🏠 Phòng: 102"));
        assertTrue(renderedText.contains("\n🕒 "));

        // Check incident description displays correctly
        assertTrue(renderedText.contains("📌 Tiêu đề: Điều hòa hỏng"));
        assertTrue(renderedText.contains("📝 Mô tả: Điều hòa bật không mát"));
        assertTrue(renderedText.contains("👤 Người báo: HD"));
    }

    @Test
    @DisplayName("2 & 3. Role notification: Renders without duplicate 🔔 🔔 and has exactly one icon at the start")
    void testRoleNotification_NoDuplicateIcon() {
        notificationService.createNotification(LANDLORD_A_ID, NotificationType.ROLE_CHANGED, "Thay đổi vai trò tài khoản", "Bạn đã được nâng cấp từ MEMBER lên LANDLORD.");

        Update update = createCallbackUpdate("/notification", LANDLORD_A_ID);
        notificationCommandHandler.handle(update, landlordA, mockMessageSender);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockMessageSender).sendMessageWithKeyboard(eq(LANDLORD_A_ID), textCaptor.capture(), any());

        String renderedText = textCaptor.getValue();
        assertNotNull(renderedText);

        assertFalse(renderedText.contains("🔔 🔔"), "Should NOT contain duplicate icon '🔔 🔔'");
        assertTrue(renderedText.contains("🔔 Thay đổi vai trò tài khoản (🔴 MỚI)"));
        assertTrue(renderedText.contains("\nBạn đã được nâng cấp từ MEMBER lên LANDLORD."));
        assertFalse(renderedText.contains("\n   Bạn đã được"));
    }

    @Test
    @DisplayName("6 & 7. Notification list displays correct unread count & enforces recipient isolation")
    void testNotificationList_UnreadCountAndIsolation() {
        notificationService.createNotification(LANDLORD_A_ID, NotificationType.ROLE_CHANGED, "Notif A1", "Msg A1");
        notificationService.createNotification(LANDLORD_A_ID, NotificationType.ROLE_CHANGED, "Notif A2", "Msg A2");
        notificationService.createNotification(LANDLORD_B_ID, NotificationType.ROLE_CHANGED, "Notif B1", "Msg B1");

        // Verify unread count for Landlord A
        assertEquals(2, notificationService.getUnreadCount(LANDLORD_A_ID));
        assertEquals(1, notificationService.getUnreadCount(LANDLORD_B_ID));

        // Test Landlord A list rendering
        Update updateA = createCallbackUpdate("/notification", LANDLORD_A_ID);
        notificationCommandHandler.handle(updateA, landlordA, mockMessageSender);

        ArgumentCaptor<String> textCaptorA = ArgumentCaptor.forClass(String.class);
        verify(mockMessageSender).sendMessageWithKeyboard(eq(LANDLORD_A_ID), textCaptorA.capture(), any());

        String textA = textCaptorA.getValue();
        assertTrue(textA.contains("📩 Chưa đọc: 2 thông báo"));
        assertTrue(textA.contains("Notif A1"));
        assertTrue(textA.contains("Notif A2"));
        assertFalse(textA.contains("Notif B1"));
    }

    private Update createCallbackUpdate(String callbackData, Long chatId) {
        Message msg = new Message();
        msg.setChat(new Chat(chatId, "private"));

        org.telegram.telegrambots.meta.api.objects.User tgUser = new org.telegram.telegrambots.meta.api.objects.User(chatId, "User", false);

        CallbackQuery query = new CallbackQuery();
        query.setId("cb_" + System.currentTimeMillis());
        query.setFrom(tgUser);
        query.setMessage(msg);
        query.setData(callbackData);

        Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }
}
