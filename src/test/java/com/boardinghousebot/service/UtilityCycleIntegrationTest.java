package com.boardinghousebot.service;
import com.boardinghousebot.domain.meter.*;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.*;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.repository.*;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.util.BillingDates;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @Transactional
class UtilityCycleIntegrationTest {
    @org.springframework.boot.test.mock.mockito.MockBean com.boardinghousebot.bot.command.BotMessageSender sender;
    @Autowired UtilityCycleService service;
    @Autowired UserRepository users;
    @Autowired RoomService rooms;
    @Autowired ContractService contracts;
    @Autowired UtilityRateRepository rates;
    @Autowired UtilityCycleRepository cycles;
    @Autowired InvoiceRepository invoices;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService payments;
    @Autowired NotificationService notices;
    User owner,member,other;
    Contract contract;
    LocalDate today=BillingDates.today();
    BigDecimal n(String n) { return new BigDecimal(n); }
    @BeforeEach void setup() {
        owner=users.save(new User(981001L,"owner","Owner",Role.LANDLORD));
        member=users.save(new User(981002L,"member","Member",Role.MEMBER));
        other=users.save(new User(981003L,"other","Other",Role.MEMBER));
        var room=rooms.createRoom(owner.getTelegramId(),"CYCLE101",n("4000000"),3,"");
        rooms.assignMemberToRoom(owner.getTelegramId(),member.getTelegramId(),room.getId());
        contract=contracts.createContract(owner.getTelegramId(),member.getTelegramId(),today.minusMonths(1),today.plusMonths(6),n("4000000"),n("2000000"));
        rates.save(new UtilityRate(UtilityType.ELECTRICITY,n("3000"),today.minusMonths(2)));
        rates.save(new UtilityRate(UtilityType.WATER,n("35000"),today.minusMonths(2)));
        org.mockito.Mockito.clearInvocations(sender);
    }
    @AfterEach void noDeliveryBeforeTransactionCommit() { org.mockito.Mockito.verifyNoInteractions(sender); }
    UtilityCycle baseline() {
        var c=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertTrue(c.isBaseline());
        c=service.reading(owner.getTelegramId(),c.getId(),true,n("1000"));
        c=service.reading(owner.getTelegramId(),c.getId(),false,n("100"));
        return service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
    }
    UtilityCycle complete() {
        baseline();
        var c=service.open(owner.getTelegramId(),contract.getRoom().getId());
        c=service.reading(owner.getTelegramId(),c.getId(),true,n("1100"));
        return service.reading(owner.getTelegramId(),c.getId(),false,n("110"));
    }
    // Simulate a draft recorded on an earlier day; users cannot edit its date.
    UtilityCycle recordedOn(UtilityCycle c, LocalDate day) {
        c.setReadingDate(day);
        return cycles.saveAndFlush(c);
    }
    @Test void upcomingInvoiceRoomsIncludesThreeDayBoundaryAndOverdueDrafts() {
        baseline();
        var draft=service.open(owner.getTelegramId(),contract.getRoom().getId());
        draft.setPlannedEnd(today.plusDays(4));
        cycles.saveAndFlush(draft);
        assertTrue(service.upcomingInvoiceRooms(owner.getTelegramId()).isEmpty());
        for(var due:java.util.List.of(today.plusDays(3),today,today.minusDays(2))) {
            draft.setPlannedEnd(due);
            cycles.saveAndFlush(draft);
            var rooms=service.upcomingInvoiceRooms(owner.getTelegramId());
            assertEquals(1,rooms.size());
            assertEquals(due,rooms.get(0).dueDate());
            assertEquals(draft.getId(),rooms.get(0).cycleId());
        }
        contract.setStatus(com.boardinghousebot.domain.contract.ContractStatus.ENDED);
        assertTrue(service.upcomingInvoiceRooms(owner.getTelegramId()).isEmpty());
    }
    @Test void upcomingInvoiceRoomsProjectsUnopenedPeriodWithoutCreatingDraft() {
        baseline();
        long count=cycles.count();
        var due=service.upcomingInvoiceRooms(owner.getTelegramId());
        assertEquals(1,due.size());
        assertEquals(BillingDates.next(contract.getStartDate(),contract.getStartDate()),due.get(0).dueDate());
        assertNull(due.get(0).cycleId());
        assertEquals(count,cycles.count());
    }
    @Test void upcomingInvoiceRoomsExcludesBilledPeriodAndMembers() {
        var c=complete();
        assertEquals(1,service.upcomingInvoiceRooms(owner.getTelegramId()).size());
        service.confirmAndInvoice(owner.getTelegramId(),c.getId(),c.getVersion(),true);
        assertTrue(service.upcomingInvoiceRooms(owner.getTelegramId()).isEmpty());
        assertThrows(BusinessException.class,()->service.upcomingInvoiceRooms(member.getTelegramId()));
    }
    @Test void cancellingClosingPreservesReadingsAndResetsCheckout() {
        var draft=service.open(owner.getTelegramId(),contract.getRoom().getId());
        service.reading(owner.getTelegramId(),draft.getId(),true,n("1000"));
        service.reading(owner.getTelegramId(),draft.getId(),false,n("100"));
        assertThrows(BusinessException.class,()->service.cancelDraft(member.getTelegramId(),draft.getId()));
        service.cancelDraft(owner.getTelegramId(),draft.getId());
        assertTrue(cycles.findById(draft.getId()).isPresent());
        var reopened=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertEquals(draft.getId(),reopened.getId());
        assertEquals(0,n("1000").compareTo(reopened.getElectricity()));
        assertEquals(0,n("100").compareTo(reopened.getWater()));
        var confirmed=baseline();
        service.cancelDraft(owner.getTelegramId(),confirmed.getId());
        assertTrue(cycles.findById(confirmed.getId()).orElseThrow().isFinalized());
        var next=service.open(owner.getTelegramId(),contract.getRoom().getId());
        service.reading(owner.getTelegramId(),next.getId(),true,n("1100"));
        service.reading(owner.getTelegramId(),next.getId(),false,n("110"));
        service.adjust(owner.getTelegramId(),next.getId(),null,true);
        assertTrue(next.isCheckout()); assertTrue(next.isAdjusted());
        service.cancelDraft(owner.getTelegramId(),next.getId());
        assertTrue(cycles.findById(next.getId()).isPresent());
        var nextAgain=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertEquals(next.getId(),nextAgain.getId());
        assertEquals(0,n("1000").compareTo(nextAgain.getPreviousElectricity()));
        assertEquals(0,n("1100").compareTo(nextAgain.getElectricity()));
        assertEquals(0,n("110").compareTo(nextAgain.getWater()));
        assertFalse(nextAgain.isCheckout()); assertFalse(nextAgain.isAdjusted());
        assertFalse(nextAgain.isFinalized());
        assertEquals(nextAgain.getPlannedEnd(),nextAgain.getPeriodEnd());
        assertEquals(0,contract.getMonthlyRent().compareTo(nextAgain.getRent()));
    }
    @Test void newInvoiceNotifiesEveryCurrentResidentAndExcludesPeopleInOtherRooms() {
        var c=complete();
        rooms.assignMemberToRoom(owner.getTelegramId(),other.getTelegramId(),contract.getRoom().getId());
        var outsider=users.save(new User(981004L,"outsider","Other room",Role.MEMBER));
        var otherRoom=rooms.createRoom(owner.getTelegramId(),"CYCLE102",n("3000000"),2,"");
        rooms.assignMemberToRoom(owner.getTelegramId(),outsider.getTelegramId(),otherRoom.getId());
        org.mockito.Mockito.clearInvocations(sender);
        service.confirmAndInvoice(owner.getTelegramId(),c.getId(),c.getVersion(),true);
        var invoice=invoices.findByUtilityCycleId(c.getId()).orElseThrow();
        for(var resident:java.util.List.of(member,other)) {
            var sent=notices.getNotificationsForUser(resident.getTelegramId()).stream()
                    .filter(n -> n.getType()==com.boardinghousebot.domain.notification.NotificationType.INVOICE_CREATED
                            && invoice.getId().equals(n.getReferenceId())).toList();
            assertEquals(1,sent.size());
            assertTrue(sent.get(0).getMessage().contains("💰 HÓA ĐƠN MỚI"));
            assertTrue(sent.get(0).getMessage().contains("4650000"));
        }
        assertTrue(notices.getNotificationsForUser(outsider.getTelegramId()).stream()
                .noneMatch(n -> n.getType()==com.boardinghousebot.domain.notification.NotificationType.INVOICE_CREATED));
    }
    @Test void confirmingNormalPeriodCreatesOneInvoiceAndPaymentKeepsContractActive() {
        var c=complete();
        c=service.confirmAndInvoice(owner.getTelegramId(),c.getId(),c.getVersion(),true);
        var bill=invoices.findByUtilityCycleId(c.getId()).orElseThrow();
        assertNull(bill.getCheckoutDate()); assertNull(bill.getNote());
        assertEquals(InvoiceStatus.UNPAID,bill.getStatus());
        final var id=c.getId(); final var version=c.getVersion();
        long count=invoices.count();
        assertThrows(BusinessException.class,()->service.confirmAndInvoice(owner.getTelegramId(),id,version,true));
        assertEquals(count,invoices.count());
        payments.recordFullPayment(owner.getTelegramId(),bill.getId(),PaymentMethod.CASH,"");
        assertEquals(0,bill.getRemainingAmount().signum());
        assertThrows(BusinessException.class,()->payments.recordFullPayment(owner.getTelegramId(),bill.getId(),PaymentMethod.CASH,""));
        assertEquals(com.boardinghousebot.domain.contract.ContractStatus.ACTIVE,contract.getStatus());
        assertNotNull(member.getRoom());
        var next=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertEquals(c.getPeriodEnd(),next.getPeriodStart());
        assertEquals(0,c.getElectricity().compareTo(next.getPreviousElectricity()));
    }
    @Test void checkoutAutomaticallyInvoicesAndEndsContractOnlyAfterAllDebtIsPaid() {
        var first=complete();
        service.confirmAndInvoice(owner.getTelegramId(),first.getId(),first.getVersion(),true);
        var older=invoices.findByUtilityCycleId(first.getId()).orElseThrow();
        rooms.assignMemberToRoom(owner.getTelegramId(),other.getTelegramId(),contract.getRoom().getId());
        var c=service.open(owner.getTelegramId(),contract.getRoom().getId());
        c=service.reading(owner.getTelegramId(),c.getId(),true,n("1150"));
        c=service.reading(owner.getTelegramId(),c.getId(),false,n("115"));
        c=service.adjust(owner.getTelegramId(),c.getId(),null,true);
        c=service.confirmAndInvoice(owner.getTelegramId(),c.getId(),c.getVersion(),false);
        var last=invoices.findByUtilityCycleId(c.getId()).orElseThrow();
        assertEquals("Người thuê trả phòng",last.getNote());
        assertEquals(today,last.getCheckoutDate());
        assertThrows(BusinessException.class,()->service.open(owner.getTelegramId(),contract.getRoom().getId()));
        payments.recordFullPayment(owner.getTelegramId(),last.getId(),PaymentMethod.CASH,"");
        assertEquals(com.boardinghousebot.domain.contract.ContractStatus.ACTIVE,contract.getStatus());
        assertNotNull(member.getRoom());
        payments.recordPayment(owner.getTelegramId(),older.getId(),n("1000000"),PaymentMethod.CASH,"");
        assertEquals(com.boardinghousebot.domain.contract.ContractStatus.ACTIVE,contract.getStatus());
        payments.recordFullPayment(owner.getTelegramId(),older.getId(),PaymentMethod.CASH,"");
        assertEquals(com.boardinghousebot.domain.contract.ContractStatus.ENDED,contract.getStatus());
        assertNull(member.getRoom()); assertNull(other.getRoom());
        assertEquals(com.boardinghousebot.domain.room.RoomStatus.AVAILABLE,contract.getRoom().getStatus());
        assertEquals(0,last.getRemainingAmount().signum());
        // Existing residence/contract event handlers send their own notifications.
        org.mockito.Mockito.clearInvocations(sender);
    }
    @Test void baselineDoesNotBillAndNextCycleAutomaticallyCarriesReadings() {
        var first=baseline(); assertTrue(invoices.findByUtilityCycleId(first.getId()).isEmpty());
        var c=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertEquals(contract.getStartDate(),c.getPeriodStart()); assertEquals(today,c.getPeriodEnd());
        assertEquals(0,c.getPreviousElectricity().compareTo(n("1000")));
        assertEquals(0,c.getPreviousWater().compareTo(n("100")));
        assertEquals(c.getId(),service.open(owner.getTelegramId(),contract.getRoom().getId()).getId());
    }
    @Test void draftResumesAndPreviewDoesNotPersistInvoiceOrNotify() {
        var c=complete(); long count=invoices.count();
        long noticeCount=notices.getNotificationsForUser(member.getTelegramId()).size();
        var estimate=service.estimate(owner.getTelegramId(),c.getId());
        assertEquals(0,estimate.electricityAmount().compareTo(n("300000")));
        assertEquals(0,estimate.waterAmount().compareTo(n("350000")));
        assertEquals(count,invoices.count());
        assertEquals(noticeCount,notices.getNotificationsForUser(member.getTelegramId()).size());
        assertThrows(BusinessException.class,()->service.preview(owner.getTelegramId(),c.getId()));
    }
    @Test void finalizedCyclePublishesExactlyOnceAndLocksReadingsAndSupportsPayments() {
        var c=complete(); c=service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
        long before=invoices.count(); var preview=service.preview(owner.getTelegramId(),c.getId());
        assertEquals(before,invoices.count()); assertEquals(today,preview.getDueDate());
        assertEquals(0,n("4650000").compareTo(preview.getTotalAmount()));
        var invoice=service.publish(owner.getTelegramId(),c.getId(),c.getVersion());
        assertEquals(InvoiceStatus.UNPAID,invoice.getStatus()); assertEquals(today,invoice.getPeriodEnd());
        final var id=c.getId(); final var version=c.getVersion();
        assertThrows(BusinessException.class,()->service.publish(owner.getTelegramId(),id,version));
        assertThrows(BusinessException.class,()->service.reading(owner.getTelegramId(),id,true,n("1200")));
        payments.recordPayment(owner.getTelegramId(),invoice.getId(),n("2000000"),PaymentMethod.CASH,"");
        assertEquals(0,invoiceService.getInvoiceById(invoice.getId()).getRemainingAmount().compareTo(n("2650000")));
        payments.recordPayment(owner.getTelegramId(),invoice.getId(),n("2650000"),PaymentMethod.CASH,"");
        assertEquals(InvoiceStatus.PAID,invoiceService.getInvoiceById(invoice.getId()).getStatus());
    }
    @Test void scheduledCloseKeepsContractPeriodsWhenOwnerClosesLate() {
        var start=contract.getStartDate().minusDays(2);
        contracts.updateContract(owner.getTelegramId(),contract.getId(),start,contract.getEndDate(),contract.getMonthlyRent(),contract.getDeposit());
        var draft=complete();
        var scheduledEnd=draft.getPlannedEnd();
        assertTrue(draft.getReadingDate().isAfter(scheduledEnd));
        var closed=service.finalizePeriod(owner.getTelegramId(),draft.getId(),draft.getVersion());
        assertEquals(scheduledEnd,closed.getPeriodEnd());
        assertEquals(today,closed.getReadingDate());
        assertEquals(scheduledEnd,service.preview(owner.getTelegramId(),closed.getId()).getDueDate());
        var next=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertEquals(scheduledEnd,next.getPeriodStart());
        assertEquals(BillingDates.next(start,scheduledEnd),next.getPeriodEnd());
        assertEquals(0,n("1100").compareTo(next.getPreviousElectricity()));
    }
    @Test void earlyReadIsDraftUnlessExplicitlyCheckingOutWithFullPeriodRent() {
        var c=recordedOn(complete(),today.minusDays(2));
        final var early=c;
        assertThrows(BusinessException.class,()->service.finalizeCycle(owner.getTelegramId(),early.getId(),early.getVersion()));
        c=service.adjust(owner.getTelegramId(),c.getId(),n("3500000"),true);
        c=service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
        assertEquals(today.minusDays(2),c.getPeriodEnd());
        assertEquals(today,service.preview(owner.getTelegramId(),c.getId()).getDueDate());
        assertEquals(0,service.preview(owner.getTelegramId(),c.getId()).getRoomRentAmount().compareTo(n("4000000")));
        assertThrows(BusinessException.class,()->service.open(owner.getTelegramId(),contract.getRoom().getId()));
    }
    @Test void newPeriodNeverRewritesPriorRatesOrDueDate() {
        var c=complete(); c=service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
        var invoice=service.publish(owner.getTelegramId(),c.getId(),c.getVersion());
        rates.save(new UtilityRate(UtilityType.WATER,n("50000"),today));
        var next=service.open(owner.getTelegramId(),contract.getRoom().getId());
        next=service.reading(owner.getTelegramId(),next.getId(),true,n("1200"));
        next=service.reading(owner.getTelegramId(),next.getId(),false,n("120"));
        assertEquals(0,service.estimate(owner.getTelegramId(),next.getId()).waterPrice().compareTo(n("50000")));
        assertEquals(0,invoice.getWaterAmount().compareTo(n("350000")));
        assertEquals(today,invoice.getDueDate());
    }
    @Test void validatesScopeDatesMissingReadingsAndVersion() {
        var c=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertThrows(BusinessException.class,()->service.get(other.getTelegramId(),c.getId()));
        assertThrows(BusinessException.class,()->service.reading(member.getTelegramId(),c.getId(),true,n("1")));
        assertThrows(BusinessException.class,()->service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion()));
        Long previous=c.getVersion(); service.reading(owner.getTelegramId(),c.getId(),true,n("1"));
        service.reading(owner.getTelegramId(),c.getId(),false,n("1"));
        assertThrows(BusinessException.class,()->service.finalizeCycle(owner.getTelegramId(),c.getId(),previous));
    }
    @Test void decreasingMeterIsRejectedAndFinalizedHistoryCannotBeEditedAfterNextOpens() {
        var first=baseline(); var next=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertThrows(BusinessException.class,()->service.reading(owner.getTelegramId(),next.getId(),true,n("999")));
        assertThrows(BusinessException.class,()->service.reading(owner.getTelegramId(),first.getId(),true,n("900")));
    }
    @Test void newRatesDoNotAffectAlreadyOpenedCycleEvenWhenUpdatedOnSameDay() {
        var c=complete();
        rates.save(new UtilityRate(UtilityType.WATER,n("99000"),c.getPeriodStart()));
        var closed=service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
        assertEquals(0,closed.getWaterPrice().compareTo(n("35000")));
    }
    @Test void changingContractDatesUpdatesOnlyUnfinalizedSchedule() {
        var c=complete();
        var newStart=contract.getStartDate().minusDays(1);
        contracts.updateContract(owner.getTelegramId(),contract.getId(),newStart,contract.getEndDate(),n("4200000"),contract.getDeposit());
        var reloaded=service.get(owner.getTelegramId(),c.getId());
        assertEquals(com.boardinghousebot.util.BillingDates.next(newStart,c.getPeriodStart()),reloaded.getPlannedEnd());
        assertEquals(0,reloaded.getRent().compareTo(n("4200000")));
    }
    @Test void memberJoiningAfterPeriodEndCannotSeePreviousTenantsConsumption() {
        var c=complete(); c=service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
        rooms.assignMemberToRoom(owner.getTelegramId(),other.getTelegramId(),contract.getRoom().getId());
        org.mockito.Mockito.clearInvocations(sender);
        Long id=c.getId();
        assertThrows(BusinessException.class,()->service.get(other.getTelegramId(),id));
        assertTrue(service.list(other.getTelegramId()).isEmpty());
    }

    @Test void earlyNormalAdjustmentCannotBypassFullRentCheckoutRule() {
        var c=recordedOn(complete(),today.minusDays(2));
        final var id=c.getId();
        assertThrows(BusinessException.class,()->service.adjust(owner.getTelegramId(),id,BigDecimal.ZERO,false));
        c=service.adjust(owner.getTelegramId(),id,BigDecimal.ZERO,true);
        assertEquals(0,c.getRent().compareTo(contract.getMonthlyRent()));
    }
    @Test void lateReadingMustBeExplicitlyReconciledAndDoesNotShiftAnchorDay() {
        var c=complete();
        var earlierStart=contract.getStartDate().minusDays(2);
        contracts.updateContract(owner.getTelegramId(),contract.getId(),earlierStart,contract.getEndDate(),contract.getMonthlyRent(),contract.getDeposit());
        c=service.get(owner.getTelegramId(),c.getId());
        final var late=c;
        assertThrows(BusinessException.class,()->service.finalizeCycle(owner.getTelegramId(),late.getId(),late.getVersion()));
        c=service.adjust(owner.getTelegramId(),c.getId(),contract.getMonthlyRent(),false);
        c=service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
        assertEquals(today,c.getPeriodEnd());
        assertEquals(today.minusDays(2),service.preview(owner.getTelegramId(),c.getId()).getDueDate());
        var next=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertEquals(today,next.getPeriodStart());
        assertEquals(BillingDates.next(earlierStart,today),next.getPlannedEnd());
    }
    @Test void baselineAlwaysUsesContractStartEvenWhenEnteredLater() {
        var c=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertEquals(contract.getStartDate(),c.getReadingDate());
        c=service.reading(owner.getTelegramId(),c.getId(),true,n("1000"));
        c=service.reading(owner.getTelegramId(),c.getId(),false,n("100"));
        assertEquals(contract.getStartDate(),c.getReadingDate());
        c=service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
        assertEquals(contract.getStartDate(),c.getPeriodStart());
        var next=service.open(owner.getTelegramId(),contract.getRoom().getId());
        assertEquals(contract.getStartDate(),next.getPeriodStart());
        assertEquals(contract.getStartDate().plusMonths(1),next.getPeriodEnd());
        assertEquals(0,n("1000").compareTo(next.getPreviousElectricity()));
        assertTrue(invoices.findByUtilityCycleId(c.getId()).isEmpty());
    }

}
