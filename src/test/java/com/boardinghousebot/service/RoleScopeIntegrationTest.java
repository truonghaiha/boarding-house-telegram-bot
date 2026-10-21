package com.boardinghousebot.service;

import com.boardinghousebot.config.RoomStayBackfill;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.domain.invoice.*;
import com.boardinghousebot.domain.meter.*;
import com.boardinghousebot.domain.room.*;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import com.boardinghousebot.state.UserConversationState.Step;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class RoleScopeIntegrationTest {
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired ContractRepository contracts;
    @Autowired InvoiceRepository invoices;
    @Autowired MeterReadingRepository meters;
    @Autowired RoomStayRepository stays;
    @Autowired RoomService roomService;
    @Autowired RoomStayService stayService;
    @Autowired UserService userService;
    @Autowired ContractService contractService;
    @Autowired InvoiceService invoiceService;
    @Autowired MeterReadingService meterService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository payments;
    @Autowired MaintenanceService maintenanceService;
    @Autowired StatisticsService statistics;
    @Autowired RoomStayBackfill backfill;
    @Autowired NotificationService notifications;
    @Autowired com.boardinghousebot.config.TelegramConfig telegramConfig;
    String previousAdminId;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    User admin, landlord, tenant, peer, outsider;
    Room first, second;
    final BigDecimal rent = new BigDecimal("3000000");

    @BeforeEach void setup() {
        previousAdminId = telegramConfig.getAdminId();
        telegramConfig.setAdminId("99000001");
        admin = users.save(new User(99000001L, "scope_admin", "Scope Admin", Role.ADMIN));
        landlord = users.save(new User(99000002L, "scope_owner", "Scope Owner", Role.LANDLORD));
        tenant = users.save(new User(99000003L, "scope_tenant", "Scope Tenant", Role.MEMBER));
        peer = users.save(new User(99000004L, "scope_peer", "Scope Peer", Role.MEMBER));
        outsider = users.save(new User(99000005L, "scope_outsider", "Scope Outsider", Role.MEMBER));
        first = roomService.createRoom(landlord.getTelegramId(), "SCOPE-A", rent, 3, "");
        second = roomService.createRoom(landlord.getTelegramId(), "SCOPE-B", rent, 3, "");
        roomService.assignMemberToRoom(landlord.getTelegramId(), tenant.getTelegramId(), first.getId());
        roomService.assignMemberToRoom(landlord.getTelegramId(), peer.getTelegramId(), first.getId());
        roomService.assignMemberToRoom(landlord.getTelegramId(), outsider.getTelegramId(), second.getId());
    }
    @AfterEach void restoreAdminConfiguration() {
        telegramConfig.setAdminId(previousAdminId);
    }
    Invoice invoice(Room room, User holder, YearMonth period, LocalDateTime created) {
        Invoice invoice = new Invoice(room, holder, null, period.getMonthValue(), period.getYear(), rent,
                BigDecimal.ZERO, BigDecimal.ZERO, rent, period.atEndOfMonth());
        invoice.setCreatedAt(created);
        return invoices.saveAndFlush(invoice);
    }
    Contract contract() {
        return contractService.createContract(landlord.getTelegramId(), tenant.getTelegramId(), LocalDate.now(), LocalDate.now().plusMonths(6), rent, rent);
    }

    @Test void adminCannotPerformRentalWrites() {
        assertThrows(BusinessException.class, () -> roomService.createRoom(admin.getTelegramId(), "DENIED", rent, 2, ""));
        assertThrows(BusinessException.class, () -> roomService.assignMemberToRoom(admin.getTelegramId(), peer.getTelegramId(), second.getId()));
        assertThrows(BusinessException.class, () -> contractService.createContract(admin.getTelegramId(), tenant.getTelegramId(), LocalDate.now(), LocalDate.now().plusMonths(6), rent, rent));
        assertThrows(BusinessException.class, () -> meterService.createReading(admin.getTelegramId(), first.getId(), UtilityType.WATER, 1, 2026, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
        assertThrows(BusinessException.class, () -> invoiceService.createInvoice(admin.getTelegramId(), first.getId(), 1, 2026));
        Invoice bill = invoice(first, tenant, YearMonth.now(), LocalDateTime.now());
        assertThrows(BusinessException.class, () -> paymentService.recordPayment(admin.getTelegramId(), bill.getId(), BigDecimal.ONE, PaymentMethod.CASH, ""));
        var issue = maintenanceService.createMaintenanceRequest(tenant.getTelegramId(), "Water", "Broken tap");
        assertThrows(BusinessException.class, () -> maintenanceService.updateMaintenanceStatus(admin.getTelegramId(), issue.getId(), com.boardinghousebot.domain.maintenance.MaintenanceStatus.RESOLVED, ""));
    }
    @Test void coTenantsCanReadRoomContractButOtherRecordsRemainScoped() {
        Contract lease = contract();
        Invoice bill = invoice(first, tenant, YearMonth.now(), LocalDateTime.now());
        var issue = maintenanceService.createMaintenanceRequest(tenant.getTelegramId(), "Water", "Broken tap");
        assertEquals(lease.getId(), contractService.getContractForUser(peer, lease.getId()).getId());
        assertThrows(BusinessException.class, () -> contractService.getContractForUser(outsider, lease.getId()));
        assertThrows(BusinessException.class, () -> contractService.getContractForUser(admin, lease.getId()));
        assertThrows(BusinessException.class, () -> invoiceService.getInvoiceForUser(outsider, bill.getId()));
        assertThrows(BusinessException.class, () -> maintenanceService.getMaintenanceForUser(peer, issue.getId()));
        assertEquals(lease.getId(), contractService.getContractForUser(tenant, lease.getId()).getId());
        assertEquals(bill.getId(), invoiceService.getInvoiceForUser(peer, bill.getId()).getId());
        assertEquals(issue.getId(), maintenanceService.getMaintenanceForUser(tenant, issue.getId()).getId());
        assertThrows(BusinessException.class, () -> invoiceService.getInvoiceForUser(admin, bill.getId()));
    }
    @Test void allConfirmedResidentsSeeSameCurrentContractButCannotModifyIt() {
        Contract lease = contract();
        assertEquals(lease.getId(), contractService.getCurrentRoomActiveContract(tenant.getTelegramId()).orElseThrow().getId());
        assertEquals(lease.getId(), contractService.getCurrentRoomActiveContract(peer.getTelegramId()).orElseThrow().getId());
        assertTrue(contractService.getMemberActiveContract(peer.getTelegramId()).isEmpty());
        assertTrue(contractService.getCurrentRoomActiveContract(outsider.getTelegramId()).isEmpty());
        assertThrows(BusinessException.class, () -> contractService.endContract(peer.getTelegramId(), lease.getId()));
    }
    @Test void movingOrLeavingRevokesCoTenantAccessEvenWithAnOldContractId() {
        Contract lease = contract();
        roomService.moveMemberToRoom(landlord.getTelegramId(), peer.getTelegramId(), second.getId());
        assertTrue(contractService.getCurrentRoomActiveContract(peer.getTelegramId()).isEmpty());
        assertThrows(BusinessException.class, () -> contractService.getContractForUser(peer, lease.getId()));
        roomService.moveMemberToRoom(landlord.getTelegramId(), peer.getTelegramId(), first.getId());
        assertEquals(lease.getId(), contractService.getContractForUser(peer, lease.getId()).getId());
        roomService.removeMemberFromRoom(landlord.getTelegramId(), peer.getTelegramId());
        assertTrue(contractService.getCurrentRoomActiveContract(peer.getTelegramId()).isEmpty());
        assertThrows(BusinessException.class, () -> contractService.getContractForUser(peer, lease.getId()));
    }
    @Test void unconfirmedAssignmentDoesNotGrantRoomContractAccess() {
        Contract lease = contract();
        outsider.setRoom(first);
        users.saveAndFlush(outsider); // Existing stay still belongs to the other room.
        assertTrue(contractService.getCurrentRoomActiveContract(outsider.getTelegramId()).isEmpty());
        assertThrows(BusinessException.class, () -> contractService.getContractForUser(outsider, lease.getId()));
    }
    @Test void endedContractRemainsVisibleOnlyToItsRepresentativeAndLandlord() {
        Contract lease = contract();
        contractService.endContract(landlord.getTelegramId(), lease.getId());
        assertTrue(contractService.getCurrentRoomActiveContract(peer.getTelegramId()).isEmpty());
        assertThrows(BusinessException.class, () -> contractService.getContractForUser(peer, lease.getId()));
        assertEquals(lease.getId(), contractService.getContractForUser(tenant, lease.getId()).getId());
        assertEquals(lease.getId(), contractService.getContractForUser(landlord, lease.getId()).getId());
    }
    @Test void newTenantCannotReadOrReceiveOldInvoice() {
        LocalDateTime earlier = LocalDateTime.now().minusDays(1);
        Invoice sameMonth = invoice(first, tenant, YearMonth.now(), earlier);
        Invoice previousMonth = invoice(first, tenant, YearMonth.now().minusMonths(1), LocalDateTime.now());
        assertTrue(invoiceService.getMemberInvoices(peer.getTelegramId()).isEmpty());
        assertThrows(BusinessException.class, () -> invoiceService.getInvoiceForUser(peer, sameMonth.getId()));
        assertThrows(BusinessException.class, () -> invoiceService.getInvoiceForUser(peer, previousMonth.getId()));
        assertFalse(stayService.canReadInvoice(peer, sameMonth));
        assertFalse(stayService.canReadInvoice(peer, previousMonth));
        events.publishEvent(new com.boardinghousebot.observer.InvoiceCreatedEvent(sameMonth));
        events.publishEvent(new com.boardinghousebot.observer.InvoiceCreatedEvent(previousMonth));
        assertTrue(notifications.getNotificationsForUser(peer.getTelegramId()).stream()
                .noneMatch(n -> n.getType() == com.boardinghousebot.domain.notification.NotificationType.INVOICE_CREATED));
    }
    @Test void historicalAccessSurvivesMoveAndReentryDoesNotGrantGap() {
        stays.deleteAll(stays.findByMemberTelegramIdAndRoomId(peer.getTelegramId(), first.getId()));
        LocalDateTime oldStart = LocalDateTime.now().minusMonths(4).withDayOfMonth(1);
        RoomStay oldStay = new RoomStay(peer, first, oldStart);
        oldStay.leave(oldStart.plusMonths(1));
        stays.save(oldStay);
        stayService.enter(peer, first, LocalDateTime.now());
        Invoice oldBill = invoice(first, tenant, YearMonth.from(oldStart), oldStart.plusDays(5));
        Invoice gapBill = invoice(first, tenant, YearMonth.from(oldStart.plusMonths(2)), oldStart.plusMonths(2).plusDays(5));
        assertEquals(oldBill.getId(), invoiceService.getInvoiceForUser(peer, oldBill.getId()).getId());
        assertThrows(BusinessException.class, () -> invoiceService.getInvoiceForUser(peer, gapBill.getId()));
        roomService.moveMemberToRoom(landlord.getTelegramId(), peer.getTelegramId(), second.getId());
        assertEquals(oldBill.getId(), invoiceService.getInvoiceForUser(peer, oldBill.getId()).getId());
        assertEquals(1, stays.findByMemberTelegramIdAndLeftAtIsNull(peer.getTelegramId()).size());
    }
    @Test void currentResidenceControlsMemberMenuWhileHistoricalReadsRemainAvailable() {
        assertTrue(stayService.getCurrentResidence(peer.getTelegramId()).isRenting());
        assertEquals("SCOPE-A", stayService.getCurrentResidence(peer.getTelegramId()).room().getRoomNumber());

        roomService.removeMemberFromRoom(landlord.getTelegramId(), peer.getTelegramId());

        assertFalse(stayService.getCurrentResidence(peer.getTelegramId()).isRenting());
        assertFalse(stayService.allowsMemberAction(peer, "/room", "MEMBER_ROOM", Step.NONE));
        assertFalse(stayService.allowsMemberAction(peer, "/maintenance", "MAINTENANCE_ADD", Step.NONE));
        assertTrue(stayService.allowsMemberAction(peer, "/meter", "MEMBER_METERS", Step.NONE));
    }
    @Test void inconsistentContractAssignmentIsNotConfirmedAsCurrentResidence() {
        contract();
        tenant.setRoom(null);
        users.saveAndFlush(tenant);

        var residence = stayService.getCurrentResidence(tenant.getTelegramId());
        assertFalse(residence.isRenting());
        assertTrue(residence.unconfirmed());
    }
    @Test void meterListsAndDetailsEnforceSamePeriodScope() {
        YearMonth now = YearMonth.now();
        MeterReading current = meterService.createReading(landlord.getTelegramId(), first.getId(), UtilityType.WATER, now.getMonthValue(), now.getYear(), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
        YearMonth old = now.minusMonths(1);
        MeterReading historic = meterService.createReading(landlord.getTelegramId(), first.getId(), UtilityType.WATER, old.getMonthValue(), old.getYear(), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
        assertEquals(1, meterService.getMemberRoomReadings(peer.getTelegramId()).size());
        assertEquals(current.getId(), meterService.getReadingForUser(peer, current.getId()).getId());
        assertThrows(BusinessException.class, () -> meterService.getReadingForUser(peer, historic.getId()));
        assertThrows(BusinessException.class, () -> meterService.getReadingForUser(outsider, current.getId()));
        assertThrows(BusinessException.class, () -> meterService.getMemberRoomReadings(landlord.getTelegramId()));
    }
    @Test void promotingAssignedOrContractedTenantIsDeniedUntilRentalEnds() {
        assertThrows(BusinessException.class, () -> userService.promoteToLandlord(admin.getTelegramId(), peer.getTelegramId()));
        Contract lease = contract();
        assertThrows(BusinessException.class, () -> roomService.removeMemberFromRoom(landlord.getTelegramId(), tenant.getTelegramId()));
        // Even an inconsistent old record without a room must not bypass the active lease.
        tenant.setRoom(null); users.save(tenant);
        assertThrows(BusinessException.class, () -> userService.promoteToLandlord(admin.getTelegramId(), tenant.getTelegramId()));
        contractService.endContract(landlord.getTelegramId(), lease.getId());
        assertEquals(Role.LANDLORD, userService.promoteToLandlord(admin.getTelegramId(), tenant.getTelegramId()).getRole());
        assertEquals(Role.MEMBER, userService.revokeLandlord(admin.getTelegramId(), tenant.getTelegramId()).getRole());
        assertNull(tenant.getRoom());
    }
    @Test void backfillIsIdempotentAndDoesNotInventLegacyPeerHistory() {
        stays.deleteAll(stays.findByMemberTelegramIdAndRoomId(peer.getTelegramId(), first.getId()));
        backfill.run(null);
        long count = stays.count();
        backfill.run(null);
        assertEquals(count, stays.count());
        var interval = stays.findByMemberTelegramIdAndRoomId(peer.getTelegramId(), first.getId()).get(0);
        assertTrue(interval.getJoinedAt().isAfter(LocalDateTime.now().minusMinutes(1)));
    }
    @Test void statisticsAndUserSearchAreRoleSpecific() {
        assertNotNull(statistics.systemStats(admin));
        assertThrows(BusinessException.class, () -> statistics.systemStats(landlord));
        assertThrows(BusinessException.class, () -> statistics.systemStats(peer));
        assertEquals(peer.getId(), userService.searchUsersAsAdmin(admin, "99000004").get(0).getId());
        assertEquals(peer.getId(), userService.searchUsersAsAdmin(admin, "@scope_peer").get(0).getId());
        assertThrows(BusinessException.class, () -> userService.searchUsersAsAdmin(landlord, "scope_peer"));
    }
}
