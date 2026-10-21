package com.boardinghousebot.service;

import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.meter.*;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.repository.*;
import com.boardinghousebot.util.BillingDates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:sqlite:file:auto-invoice-rollback?mode=memory&cache=shared")
class AutoInvoiceTransactionTest {
    @MockBean com.boardinghousebot.bot.command.BotMessageSender sender;
    @SpyBean InvoiceRepository invoices;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserRepository users;
    @Autowired RoomService rooms;
    @Autowired ContractService contracts;
    @Autowired UtilityCycleService service;
    @Autowired UtilityCycleRepository cycles;
    @Autowired UtilityRateRepository rates;

    @Test void invoiceFailureRollsBackTheCloseAndDoesNotNotifyTenant() {
        var tx=new TransactionTemplate(transactions);
        Long id=tx.execute(status -> {
            var today=BillingDates.today();
            var owner=users.save(new User(992001L,"owner","Owner",Role.LANDLORD));
            var member=users.save(new User(992002L,"member","Member",Role.MEMBER));
            var room=rooms.createRoom(owner.getTelegramId(),"ATOMIC101",new BigDecimal("4000000"),2,"");
            rooms.assignMemberToRoom(owner.getTelegramId(),member.getTelegramId(),room.getId());
            contracts.createContract(owner.getTelegramId(),member.getTelegramId(),today.minusMonths(1),today.plusMonths(1),new BigDecimal("4000000"),BigDecimal.ZERO);
            rates.save(new UtilityRate(UtilityType.ELECTRICITY,new BigDecimal("3000"),today.minusMonths(2)));
            rates.save(new UtilityRate(UtilityType.WATER,new BigDecimal("35000"),today.minusMonths(2)));
            var c=service.open(owner.getTelegramId(),room.getId());
            service.reading(owner.getTelegramId(),c.getId(),true,new BigDecimal("1000"));
            c=service.reading(owner.getTelegramId(),c.getId(),false,new BigDecimal("100"));
            service.finalizeCycle(owner.getTelegramId(),c.getId(),c.getVersion());
            c=service.open(owner.getTelegramId(),room.getId());
            service.reading(owner.getTelegramId(),c.getId(),true,new BigDecimal("1100"));
            c=service.reading(owner.getTelegramId(),c.getId(),false,new BigDecimal("110"));
            return c.getId();
        });
        clearInvocations(sender);
        doThrow(new IllegalStateException("Simulated invoice write failure")).when(invoices).saveAndFlush(any(Invoice.class));
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->tx.execute(status -> {
            var c=service.get(992001L,id);
            return service.confirmAndInvoice(992001L,id,c.getVersion(),true);
        }));
        assertEquals("Simulated invoice write failure",failure.getMostSpecificCause().getMessage());
        tx.executeWithoutResult(status -> {
            var c=cycles.findById(id).orElseThrow();
            assertFalse(c.isFinalized());
            assertNull(c.getFinalizedAt());
            assertEquals(0,new BigDecimal("1100").compareTo(c.getElectricity()));
            assertTrue(invoices.findByUtilityCycleId(id).isEmpty());
        });
        verifyNoInteractions(sender);
    }
}
