package com.boardinghousebot.service;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
class UtilityRateNotificationTransactionTest {
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired NotificationRepository notices;
    @Autowired UtilityRateRepository rates;
    @Autowired UtilityRateService prices;
    @Autowired AuditLogRepository audits;
    @Autowired ContractRepository contracts;
    @MockBean BotMessageSender sender;

    @Test void sendsOnlyAfterCommitContinuesAfterTelegramFailureAndRollbackSendsNothing() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        long[] ids = new long[6];
        try {
            tx.executeWithoutResult(status -> {
                var owner = users.save(new User(973001L, "owner", "Owner", Role.LANDLORD)); ids[0] = owner.getId();
                Room room = rooms.save(new Room("RATE-TX", new BigDecimal("1000000"), 3, "")); ids[1] = room.getId();
                var first = new User(973002L, "first", "First", Role.MEMBER); first.setRoom(room); ids[2] = users.save(first).getId();
                var second = new User(973003L, "second", "Second", Role.MEMBER); second.setRoom(room); ids[3] = users.save(second).getId();
                ids[5] = contracts.save(new com.boardinghousebot.domain.contract.Contract(first, room,
                        java.time.LocalDate.now(), java.time.LocalDate.now().plusMonths(6), room.getMonthlyRent(), BigDecimal.ZERO)).getId();
            });
            doThrow(new RuntimeException("Telegram unavailable")).when(sender).sendMessage(eq(973002L), anyString());
            tx.executeWithoutResult(status -> {
                ids[4] = prices.update(973001L, UtilityType.WATER, new BigDecimal("12000")).getId();
                verifyNoInteractions(sender);
            });
            verify(sender).sendMessage(eq(973002L), contains("12000"));
            verify(sender).sendMessage(eq(973003L), contains("12000"));
            assertTrue(notices.findByRecipientTelegramIdOrderByCreatedAtDesc(973002L).stream()
                    .anyMatch(n -> n.getMessage().contains("12000")));
            reset(sender);
            assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
                prices.update(973001L, UtilityType.WATER, new BigDecimal("18000"));
                throw new IllegalStateException("rollback");
            }));
            verifyNoInteractions(sender);
            assertEquals(0, prices.current(UtilityType.WATER).orElseThrow().getUnitPrice().compareTo(new BigDecimal("12000")));
            assertTrue(notices.findByRecipientTelegramIdOrderByCreatedAtDesc(973002L).stream()
                    .noneMatch(n -> n.getMessage().contains("18000")));
        } finally {
            tx.executeWithoutResult(status -> {
                notices.findByRecipientTelegramIdOrderByCreatedAtDesc(973002L).forEach(notices::delete);
                notices.findByRecipientTelegramIdOrderByCreatedAtDesc(973003L).forEach(notices::delete);
                audits.findAll().stream().filter(a -> java.util.Objects.equals(a.getTelegramId(), 973001L)).forEach(audits::delete);
                contracts.findById(ids[5]).ifPresent(contracts::delete);
                users.findById(ids[2]).ifPresent(users::delete);
                users.findById(ids[3]).ifPresent(users::delete);
                users.findById(ids[0]).ifPresent(users::delete);
                rooms.findById(ids[1]).ifPresent(rooms::delete);
                rates.findById(ids[4]).ifPresent(rates::delete);
            });
        }
    }
}
