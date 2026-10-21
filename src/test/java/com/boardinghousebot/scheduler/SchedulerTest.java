package com.boardinghousebot.scheduler;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.repository.InvoiceRepository;
import com.boardinghousebot.repository.NotificationRepository;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.service.NotificationService;
import com.boardinghousebot.service.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BotMessageSender botMessageSender;

    private TelegramNotificationService telegramNotificationService;
    private NotificationService notificationService;
    private InvoiceReminderScheduler invoiceReminderScheduler;
    private ContractReminderScheduler contractReminderScheduler;

    private User member;
    private Room room;
    private Contract contract;

    @BeforeEach
    void setUp() {
        telegramNotificationService = new TelegramNotificationService(botMessageSender);
        notificationService = new NotificationService(notificationRepository, userRepository, contractRepository);
        invoiceReminderScheduler = new InvoiceReminderScheduler(invoiceRepository, notificationService, telegramNotificationService);
        contractReminderScheduler = new ContractReminderScheduler(contractRepository, notificationService, telegramNotificationService);

        member = new User(100L, "member1", "Nguyen Van A", Role.MEMBER);
        room = new Room("101", new BigDecimal("3000000"), 2, "Standard");
        room.setId(1L); member.setRoom(room);
        lenient().when(contractRepository.existsByRoomIdAndStatus(1L, com.boardinghousebot.domain.contract.ContractStatus.ACTIVE)).thenReturn(true);
        contract = new Contract(member, room, LocalDate.now(), LocalDate.now().plusDays(7), new BigDecimal("3000000"), new BigDecimal("3000000"));
    }

    @Test
    @DisplayName("InvoiceReminderScheduler creates due reminder if not sent already")
    void checkUpcomingDueInvoices_SendsNotification() {
        LocalDate targetDueDate = LocalDate.now().plusDays(3);
        Invoice invoice = new Invoice(room, member, contract, 9, 2026, new BigDecimal("3000000"), new BigDecimal("1000000"), new BigDecimal("500000"), new BigDecimal("4500000"), targetDueDate);

        when(invoiceRepository.findInvoicesDueOn(targetDueDate)).thenReturn(List.of(invoice));
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(member));
        when(notificationRepository.existsByReferenceTypeAndReferenceIdAndType("INVOICE", invoice.getId(), NotificationType.INVOICE_DUE_REMINDER)).thenReturn(false);

        invoiceReminderScheduler.checkUpcomingDueInvoices();

        verify(notificationRepository).save(argThat(n ->
                n.getRecipient().getTelegramId().equals(100L) &&
                n.getType() == NotificationType.INVOICE_DUE_REMINDER &&
                n.getTitle().contains("Hạn thanh toán")
        ));
    }

    @Test
    @DisplayName("InvoiceReminderScheduler updates OVERDUE status and sends notification")
    void checkOverdueInvoices_UpdatesStatusAndSendsNotification() {
        LocalDate pastDate = LocalDate.now().minusDays(2);
        Invoice invoice = new Invoice(room, member, contract, 8, 2026, new BigDecimal("3000000"), new BigDecimal("1000000"), new BigDecimal("500000"), new BigDecimal("4500000"), pastDate);
        invoice.setStatus(InvoiceStatus.UNPAID);

        when(invoiceRepository.findOverdueInvoices(LocalDate.now())).thenReturn(List.of(invoice));
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(member));
        when(notificationRepository.existsByReferenceTypeAndReferenceIdAndType("INVOICE", invoice.getId(), NotificationType.INVOICE_OVERDUE)).thenReturn(false);

        invoiceReminderScheduler.checkOverdueInvoices();

        assertEquals(InvoiceStatus.OVERDUE, invoice.getStatus());
        verify(invoiceRepository).save(invoice);
        verify(notificationRepository).save(argThat(n ->
                n.getRecipient().getTelegramId().equals(100L) &&
                n.getType() == NotificationType.INVOICE_OVERDUE &&
                n.getTitle().contains("quá hạn")
        ));
    }

    @Test
    @DisplayName("InvoiceReminderScheduler skips sending notification if already sent (deduplication)")
    void checkOverdueInvoices_Deduplication_SkipsNotification() {
        LocalDate pastDate = LocalDate.now().minusDays(2);
        Invoice invoice = new Invoice(room, member, contract, 8, 2026, new BigDecimal("3000000"), new BigDecimal("1000000"), new BigDecimal("500000"), new BigDecimal("4500000"), pastDate);

        when(invoiceRepository.findOverdueInvoices(LocalDate.now())).thenReturn(List.of(invoice));
        when(notificationRepository.existsByReferenceTypeAndReferenceIdAndType("INVOICE", invoice.getId(), NotificationType.INVOICE_OVERDUE)).thenReturn(true);

        invoiceReminderScheduler.checkOverdueInvoices();

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("ContractReminderScheduler sends expiring notification if not sent already")
    void checkExpiringContracts_SendsNotification() {
        when(contractRepository.findExpiringContracts(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(contract));
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(member));
        when(notificationRepository.existsByReferenceTypeAndReferenceIdAndType("CONTRACT", contract.getId(), NotificationType.CONTRACT_EXPIRING)).thenReturn(false);

        contractReminderScheduler.checkExpiringContracts();

        verify(notificationRepository).save(argThat(n ->
                n.getRecipient().getTelegramId().equals(100L) &&
                n.getType() == NotificationType.CONTRACT_EXPIRING &&
                n.getTitle().contains("Hợp đồng sắp hết hạn")
        ));
    }
}
