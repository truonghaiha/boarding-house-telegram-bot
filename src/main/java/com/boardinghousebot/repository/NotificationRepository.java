package com.boardinghousebot.repository;

import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("SELECT n FROM Notification n LEFT JOIN FETCH n.recipient r WHERE r.telegramId = :telegramId ORDER BY n.createdAt DESC")
    List<Notification> findByRecipientTelegramIdOrderByCreatedAtDesc(@Param("telegramId") Long telegramId);

    @Query("SELECT n FROM Notification n LEFT JOIN FETCH n.recipient r WHERE r.telegramId = :telegramId AND n.isRead = false ORDER BY n.createdAt DESC")
    List<Notification> findByRecipientTelegramIdAndIsReadFalseOrderByCreatedAtDesc(@Param("telegramId") Long telegramId);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.recipient.telegramId = :telegramId AND n.isRead = false")
    long countByRecipientTelegramIdAndIsReadFalse(@Param("telegramId") Long telegramId);

    boolean existsByReferenceTypeAndReferenceIdAndType(String referenceType, Long referenceId, NotificationType type);

    boolean existsByRecipientTelegramIdAndReferenceTypeAndReferenceIdAndType(Long telegramId, String referenceType, Long referenceId, NotificationType type);
}
