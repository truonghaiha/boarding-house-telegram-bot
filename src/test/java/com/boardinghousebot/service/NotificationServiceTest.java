package com.boardinghousebot.service;

import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.NotificationRepository;
import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.boardinghousebot.repository.ContractRepository contractRepository;

    @InjectMocks
    private NotificationService notificationService;

    private User recipient;
    private Notification notification;

    @BeforeEach
    void setUp() {
        recipient = new User(100L, "user1", "Nguyen Van A", Role.MEMBER);
        notification = new Notification(recipient, NotificationType.INVOICE_CREATED, "Tiêu đề", "Nội dung", "INVOICE", 10L);
    }

    @Test
    @DisplayName("createNotification saves notification correctly")
    void createNotification_Success() {
        var room = new com.boardinghousebot.domain.room.Room("101", java.math.BigDecimal.TEN, 2, "");
        room.setId(1L); recipient.setRoom(room);
        when(contractRepository.existsByRoomIdAndStatus(1L, com.boardinghousebot.domain.contract.ContractStatus.ACTIVE)).thenReturn(true);
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(recipient));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        Notification created = notificationService.createNotification(100L, NotificationType.MAINTENANCE_CREATED, "Thứ hai", "Nội dung 2", "MAINTENANCE", 5L);

        assertNotNull(created);
        assertEquals(100L, created.getRecipient().getTelegramId());
        assertEquals("Thứ hai", created.getTitle());
        assertFalse(created.isRead());
    }

    @Test void notificationsAreNotStoredWithoutActiveRental() {
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(recipient));
        notificationService.createNotification(100L, NotificationType.ROOM_CHANGED, "Phòng", "Trước hợp đồng");
        var room = new com.boardinghousebot.domain.room.Room("101", java.math.BigDecimal.TEN, 2, "");
        room.setId(1L); recipient.setRoom(room);
        notificationService.createNotification(100L, NotificationType.CONTRACT_ENDED, "Kết thúc", "Sau hợp đồng", "CONTRACT", 1L);
        verify(notificationRepository, never()).save(any());
    }

    @Test void landlordNotificationsAreStoredWithoutRentalContract() {
        recipient.setRole(Role.LANDLORD);
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(recipient));
        notificationService.createNotification(100L, NotificationType.MAINTENANCE_CREATED, "Sự cố", "Nội dung");
        verify(notificationRepository).save(any());
        verifyNoInteractions(contractRepository);
    }

    @Test
    @DisplayName("markAsRead marks notification as read when owned by user")
    void markAsRead_Success() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        notificationService.markAsRead(1L, 100L);

        assertTrue(notification.isRead());
    }

    @Test
    @DisplayName("markAsRead throws exception when user does not match recipient")
    void markAsRead_UnauthorizedUser_ThrowsException() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notification));

        assertThrows(BusinessException.class, () -> notificationService.markAsRead(1L, 999L));
    }

    @Test
    @DisplayName("hasNotificationBeenSent checks deduplication via repository")
    void hasNotificationBeenSent_ChecksRepository() {
        when(notificationRepository.existsByReferenceTypeAndReferenceIdAndType("INVOICE", 10L, NotificationType.INVOICE_DUE_REMINDER)).thenReturn(true);

        assertTrue(notificationService.hasNotificationBeenSent("INVOICE", 10L, NotificationType.INVOICE_DUE_REMINDER));
        assertFalse(notificationService.hasNotificationBeenSent("INVOICE", 10L, NotificationType.INVOICE_OVERDUE));
    }
}
