package com.boardinghousebot.service;

import com.boardinghousebot.bot.command.RoleActionPolicy;
import com.boardinghousebot.domain.meter.*;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.*;
import com.boardinghousebot.state.UserConversationState.Step;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AvailabilityAndUtilityRateIntegrationTest {
    @Autowired UtilityRateService prices;
    @Autowired UtilityRateRepository rates;
    @Autowired RoomService rooms;
    @Autowired RoomStayService stays;
    @Autowired UserRepository users;
    @Autowired NotificationService notifications;
    @Autowired MeterReadingService meters;
    @Autowired ContractService contracts;
    @Autowired InvoiceService invoices;
    User landlord, member, newcomer;
    Room room;
    @BeforeEach void setup() {
        landlord = users.save(new User(972001L, "rate-owner", "Owner", Role.LANDLORD));
        member = users.save(new User(972002L, "rate-tenant", "Tenant", Role.MEMBER));
        newcomer = users.save(new User(972003L, "rate-new", "New", Role.MEMBER));
        room = rooms.createRoom(landlord.getTelegramId(), "RATE-101", new BigDecimal("3000000"),
                2, "Room", new BigDecimal("25.5"), new BigDecimal("1500000"));
        rooms.assignMemberToRoom(landlord.getTelegramId(), member.getTelegramId(), room.getId());
    }
    @Test void partiallyOccupiedRoomIsAvailableAndCountsTrackMovesAndCheckout() {
        assertTrue(rooms.getAvailableRooms().stream().anyMatch(r -> r.getId().equals(room.getId())));
        rooms.assignMemberToRoom(landlord.getTelegramId(), newcomer.getTelegramId(), room.getId());
        assertFalse(rooms.getAvailableRooms().stream().anyMatch(r -> r.getId().equals(room.getId())));
        Room other = rooms.createRoom(landlord.getTelegramId(), "RATE-102", new BigDecimal("2000000"), 2, "",
                new BigDecimal("18"), BigDecimal.ZERO);
        rooms.moveMemberToRoom(landlord.getTelegramId(), newcomer.getTelegramId(), other.getId());
        assertEquals(1, rooms.getCurrentOccupantCount(room.getId()));
        assertEquals(1, rooms.getCurrentOccupantCount(other.getId()));
        rooms.removeMemberFromRoom(landlord.getTelegramId(), member.getTelegramId());
        assertEquals(0, rooms.getCurrentOccupantCount(room.getId()));
    }
    @Test void newcomerCanBrowseButCannotAccessTenantDetailsOrModifyRates() {
        assertTrue(RoleActionPolicy.allows(newcomer, "/room", "MEMBER_AVAILABLE_ROOMS", Step.NONE));
        assertTrue(stays.allowsMemberAction(newcomer, "/room", "MEMBER_AVAILABLE_ROOMS_1", Step.NONE));
        assertFalse(RoleActionPolicy.allows(newcomer, "/room", "ROOM_DETAIL_1", Step.NONE));
        assertFalse(RoleActionPolicy.allows(newcomer, "/meter", "METER_RATE_WATER", Step.NONE));
        assertThrows(BusinessException.class, () -> prices.update(newcomer.getTelegramId(), UtilityType.WATER, new BigDecimal("10000")));
    }
    @Test void roomDepositIsIndependentAndDoesNotRewriteSignedContract() {
        var contract = contracts.createContract(landlord.getTelegramId(), member.getTelegramId(),
                UtilityRateService.today().minusDays(1), UtilityRateService.today().plusMonths(6),
                room.getMonthlyRent(), room.getRequiredDeposit());
        rooms.updateRoomDetails(landlord.getTelegramId(), room.getId(), new BigDecimal("28"), new BigDecimal("2000000"));
        assertEquals(0, room.getRequiredDeposit().compareTo(new BigDecimal("2000000")));
        assertEquals(0, contract.getDeposit().compareTo(new BigDecimal("1500000")));
        assertThrows(BusinessException.class, () -> rooms.updateRoomDetails(landlord.getTelegramId(), room.getId(), BigDecimal.ZERO, null));
        assertThrows(BusinessException.class, () -> rooms.updateRoomDetails(landlord.getTelegramId(), room.getId(), null, new BigDecimal("-1")));
    }
    @Test void changingRateNotifiesOnlyCurrentMembersWithOldNewAndEffectiveDate() {
        contracts.createContract(landlord.getTelegramId(), member.getTelegramId(), UtilityRateService.today(),
                UtilityRateService.today().plusMonths(6), room.getMonthlyRent(), room.getRequiredDeposit());
        rates.save(new UtilityRate(UtilityType.WATER, new BigDecimal("10000"), UtilityRateService.today().minusMonths(2)));
        long before = notifications.getNotificationsForUser(member.getTelegramId()).stream()
                .filter(n -> n.getType() == NotificationType.UTILITY_RATE_CHANGED).count();
        prices.update(landlord.getTelegramId(), UtilityType.WATER, new BigDecimal("15000"));
        var notice = notifications.getNotificationsForUser(member.getTelegramId()).stream()
                .filter(n -> n.getType() == NotificationType.UTILITY_RATE_CHANGED).findFirst().orElseThrow();
        assertTrue(notice.getMessage().contains("10000"));
        assertTrue(notice.getMessage().contains("15000"));
        assertTrue(notice.getMessage().contains("m³"));
        assertTrue(notice.getMessage().contains(UtilityRateService.today().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
        assertTrue(notifications.getNotificationsForUser(newcomer.getTelegramId()).stream()
                .noneMatch(n -> n.getType() == NotificationType.UTILITY_RATE_CHANGED));
        prices.update(landlord.getTelegramId(), UtilityType.WATER, new BigDecimal("15000.00"));
        assertEquals(before + 1, notifications.getNotificationsForUser(member.getTelegramId()).stream()
                .filter(n -> n.getType() == NotificationType.UTILITY_RATE_CHANGED).count());
    }
    @Test void ratesAreHistoricalAndExistingReadingsAndInvoicesKeepAmounts() {
        LocalDate now = UtilityRateService.today();
        rates.save(new UtilityRate(UtilityType.ELECTRICITY, new BigDecimal("3000"), now.minusMonths(2)));
        rates.save(new UtilityRate(UtilityType.WATER, new BigDecimal("10000"), now.minusMonths(2)));
        contracts.createContract(landlord.getTelegramId(), member.getTelegramId(), now.minusMonths(2), now.plusMonths(6),
                room.getMonthlyRent(), room.getRequiredDeposit());
        var electricity = meters.createReading(landlord.getTelegramId(), room.getId(), UtilityType.ELECTRICITY,
                now.getMonthValue(), now.getYear(), BigDecimal.ZERO, new BigDecimal("10"));
        var water = meters.createReading(landlord.getTelegramId(), room.getId(), UtilityType.WATER,
                now.getMonthValue(), now.getYear(), BigDecimal.ZERO, new BigDecimal("2.5"));
        assertEquals(0, water.getTotalCharge().compareTo(new BigDecimal("25000")));
        var invoice = invoices.createInvoice(landlord.getTelegramId(), room.getId(), now.getMonthValue(), now.getYear());
        BigDecimal total = invoice.getTotalAmount();
        prices.update(landlord.getTelegramId(), UtilityType.ELECTRICITY, new BigDecimal("4000"));
        prices.update(landlord.getTelegramId(), UtilityType.WATER, new BigDecimal("15000"));
        assertEquals(0, electricity.getUnitPrice().compareTo(new BigDecimal("3000")));
        assertEquals(0, invoices.getInvoiceById(invoice.getId()).getTotalAmount().compareTo(total));
        assertEquals(0, prices.forPeriod(UtilityType.WATER, now.getMonthValue(), now.getYear()).compareTo(new BigDecimal("15000")));
        LocalDate previous = now.minusMonths(1);
        assertEquals(0, prices.forPeriod(UtilityType.WATER, previous.getMonthValue(), previous.getYear()).compareTo(new BigDecimal("10000")));
        var historical = meters.createReading(landlord.getTelegramId(), room.getId(), UtilityType.WATER,
                previous.getMonthValue(), previous.getYear(), BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("99999"));
        assertEquals(0, historical.getUnitPrice().compareTo(new BigDecimal("10000")));
    }
    @Test void missingRateAndInvalidPriceAreRejected() {
        assertThrows(BusinessException.class, () -> prices.forPeriod(UtilityType.WATER, 1, 2001));
        assertThrows(BusinessException.class, () -> prices.update(landlord.getTelegramId(), UtilityType.WATER, BigDecimal.ZERO));
        assertThrows(BusinessException.class, () -> prices.update(landlord.getTelegramId(), UtilityType.WATER, new BigDecimal("-1")));
    }
}
