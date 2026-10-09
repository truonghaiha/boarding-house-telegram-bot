package com.boardinghousebot.service;

import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.NotificationRepository;
import com.boardinghousebot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Notification createNotification(Long recipientTelegramId, NotificationType type, String title, String message) {
        User recipient = userRepository.findByTelegramId(recipientTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng nhận thông báo."));
        Notification notification = new Notification(recipient, type, title, message);
        Notification saved = notificationRepository.save(notification);
        log.info("Notification created for user {}: {}", recipientTelegramId, title);
        return saved;
    }

    @Transactional
    public Notification createNotification(Long recipientTelegramId, NotificationType type, String title, String message, String referenceType, Long referenceId) {
        User recipient = userRepository.findByTelegramId(recipientTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng nhận thông báo."));
        Notification notification = new Notification(recipient, type, title, message, referenceType, referenceId);
        Notification saved = notificationRepository.save(notification);
        log.info("Notification created for user {}: {} (ref: {}={})", recipientTelegramId, title, referenceType, referenceId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Notification> getNotificationsForUser(Long telegramId) {
        return notificationRepository.findByRecipientTelegramIdOrderByCreatedAtDesc(telegramId);
    }

    @Transactional(readOnly = true)
    public List<Notification> getUnreadNotificationsForUser(Long telegramId) {
        return notificationRepository.findByRecipientTelegramIdAndIsReadFalseOrderByCreatedAtDesc(telegramId);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long telegramId) {
        return notificationRepository.countByRecipientTelegramIdAndIsReadFalse(telegramId);
    }

    @Transactional
    public void markAsRead(Long notificationId, Long telegramId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông báo"));

        if (!notification.getRecipient().getTelegramId().equals(telegramId)) {
            throw new BusinessException("Bạn không có quyền đánh dấu thông báo này");
        }

        notification.setRead(true);
        notificationRepository.save(notification);
    }

    @Transactional
    public void markAllAsRead(Long telegramId) {
        List<Notification> unreadList = notificationRepository.findByRecipientTelegramIdAndIsReadFalseOrderByCreatedAtDesc(telegramId);
        for (Notification notification : unreadList) {
            notification.setRead(true);
        }
        notificationRepository.saveAll(unreadList);
    }

    @Transactional(readOnly = true)
    public boolean hasNotificationBeenSent(String referenceType, Long referenceId, NotificationType type) {
        return notificationRepository.existsByReferenceTypeAndReferenceIdAndType(referenceType, referenceId, type);
    }

    @Transactional(readOnly = true)
    public boolean hasNotificationBeenSentToUser(Long recipientTelegramId, String referenceType, Long referenceId, NotificationType type) {
        return notificationRepository.existsByRecipientTelegramIdAndReferenceTypeAndReferenceIdAndType(recipientTelegramId, referenceType, referenceId, type);
    }
}
