package com.boardinghousebot.service;

import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class NotificationRentalLifecycleTest {
    @MockBean com.boardinghousebot.bot.command.BotMessageSender sender;
    @Autowired UserRepository users;
    @Autowired RoomService rooms;
    @Autowired ContractService contracts;
    @Autowired NotificationService notifications;
    @Autowired NotificationRepository records;

    @Test void inboxExistsOnlyDuringRentalAndRestartsForNextContract() {
        var owner=users.save(new User(771001L,"owner","Owner",Role.LANDLORD));
        var tenant=users.save(new User(771002L,"tenant","Tenant",Role.MEMBER));
        var roommate=users.save(new User(771003L,"peer","Peer",Role.MEMBER));
        var outside=users.save(new User(771004L,"outside","Outside",Role.MEMBER));
        var room=rooms.createRoom(owner.getTelegramId(),"NOTICE101",BigDecimal.TEN,3,"");
        var otherRoom=rooms.createRoom(owner.getTelegramId(),"NOTICE102",BigDecimal.TEN,2,"");
        rooms.assignMemberToRoom(owner.getTelegramId(),tenant.getTelegramId(),room.getId());
        rooms.assignMemberToRoom(owner.getTelegramId(),roommate.getTelegramId(),room.getId());
        rooms.assignMemberToRoom(owner.getTelegramId(),outside.getTelegramId(),otherRoom.getId());
        assertTrue(notifications.getNotificationsForUser(tenant.getTelegramId()).isEmpty());
        assertTrue(notifications.getNotificationsForUser(roommate.getTelegramId()).isEmpty());
        records.save(new com.boardinghousebot.domain.notification.Notification(tenant,NotificationType.ROOM_CHANGED,"Cũ","Trước hợp đồng"));
        notifications.clearInactiveTenantNotifications();
        assertTrue(records.findByRecipientTelegramIdOrderByCreatedAtDesc(tenant.getTelegramId()).isEmpty());
        var today=LocalDate.now();
        contracts.createContract(owner.getTelegramId(),outside.getTelegramId(),today,today.plusMonths(1),BigDecimal.TEN,BigDecimal.ZERO);
        var lease=contracts.createContract(owner.getTelegramId(),tenant.getTelegramId(),today,today.plusMonths(1),BigDecimal.TEN,BigDecimal.ZERO);
        for(var resident:java.util.List.of(tenant,roommate)) {
            assertEquals(NotificationType.CONTRACT_CREATED,notifications.getNotificationsForUser(resident.getTelegramId()).get(0).getType());
            var note=notifications.createNotification(resident.getTelegramId(),NotificationType.INVOICE_CREATED,"Hóa đơn","Nội dung");
            notifications.markAsRead(note.getId(),resident.getTelegramId());
            assertEquals(2,notifications.getNotificationsForUser(resident.getTelegramId()).size());
        }
        notifications.createNotification(owner.getTelegramId(),NotificationType.MAINTENANCE_CREATED,"Sự cố","Nội dung");
        int otherCount=notifications.getNotificationsForUser(outside.getTelegramId()).size();
        contracts.endContract(owner.getTelegramId(),lease.getId());
        for(var resident:java.util.List.of(tenant,roommate)) {
            assertTrue(notifications.getNotificationsForUser(resident.getTelegramId()).isEmpty());
            assertEquals(0,notifications.getUnreadCount(resident.getTelegramId()));
            // A delayed payment/reminder event must not recreate the inbox after checkout.
            notifications.createNotification(resident.getTelegramId(),NotificationType.PAYMENT_RECORDED,"Thanh toán","Muộn");
            assertTrue(notifications.getNotificationsForUser(resident.getTelegramId()).isEmpty());
        }
        assertEquals(otherCount,notifications.getNotificationsForUser(outside.getTelegramId()).size());
        assertEquals(1,notifications.getNotificationsForUser(owner.getTelegramId()).size());
        assertNotNull(contracts.getContractById(lease.getId()));
        assertTrue(records.findByRecipientTelegramIdOrderByCreatedAtDesc(tenant.getTelegramId()).isEmpty());
        rooms.assignMemberToRoom(owner.getTelegramId(),tenant.getTelegramId(),room.getId());
        assertTrue(notifications.getNotificationsForUser(tenant.getTelegramId()).isEmpty());
        contracts.createContract(owner.getTelegramId(),tenant.getTelegramId(),today,today.plusMonths(2),BigDecimal.TEN,BigDecimal.ZERO);
        assertEquals(1,notifications.getNotificationsForUser(tenant.getTelegramId()).size());
        assertEquals(NotificationType.CONTRACT_CREATED,notifications.getNotificationsForUser(tenant.getTelegramId()).get(0).getType());
    }
}
