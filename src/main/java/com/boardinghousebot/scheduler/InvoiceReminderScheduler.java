package com.boardinghousebot.scheduler;

import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.repository.InvoiceRepository;
import com.boardinghousebot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Component
public class InvoiceReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceReminderScheduler.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final InvoiceRepository invoiceRepository;
    private final NotificationService notificationService;
    private final com.boardinghousebot.service.TelegramNotificationService telegramNotificationService;

    public InvoiceReminderScheduler(InvoiceRepository invoiceRepository,
                                    NotificationService notificationService,
                                    com.boardinghousebot.service.TelegramNotificationService telegramNotificationService) {
        this.invoiceRepository = invoiceRepository;
        this.notificationService = notificationService;
        this.telegramNotificationService = telegramNotificationService;
    }

    @Scheduled(cron = "0 0 8 * * ?", zone="Asia/Ho_Chi_Minh") // Daily at 08:00 AM
    @Transactional
    public void processInvoiceReminders() {
        log.info("Starting scheduled job: processInvoiceReminders");
        checkUpcomingDueInvoices();
        checkOverdueInvoices();
        log.info("Finished scheduled job: processInvoiceReminders");
    }

    @Transactional
    public void checkUpcomingDueInvoices() {
        LocalDate targetDueDate = com.boardinghousebot.util.BillingDates.today().plusDays(3);
        List<Invoice> upcomingInvoices = invoiceRepository.findInvoicesDueOn(targetDueDate);

        for (Invoice invoice : upcomingInvoices) {
            if (invoice.getMember() == null || invoice.getMember().getTelegramId() == null) {
                continue;
            }

            Long memberTelegramId = invoice.getMember().getTelegramId();
            boolean alreadySent = notificationService.hasNotificationBeenSent(
                    "INVOICE", invoice.getId(), NotificationType.INVOICE_DUE_REMINDER);

            if (!alreadySent) {
                BigDecimal remaining = invoice.getRemainingAmount();
                String title = "Hạn thanh toán hóa đơn sắp tới";
                String content = String.format(
                        "Hóa đơn #%d của phòng %s (Kỳ %02d/%d) sẽ hết hạn thanh toán vào ngày %s. Số tiền còn lại: %s VNĐ. Vui lòng thanh toán sớm.",
                        invoice.getId(),
                        invoice.getRoom() != null ? invoice.getRoom().getRoomNumber() : "N/A",
                        invoice.getBillingMonth(),
                        invoice.getBillingYear(),
                        invoice.getDueDate() != null ? invoice.getDueDate().format(DATE_FORMATTER) : "N/A",
                        formatNumber(remaining)
                );

                notificationService.createNotification(
                        memberTelegramId, NotificationType.INVOICE_DUE_REMINDER, title, content,
                        "INVOICE", invoice.getId()
                );
                telegramNotificationService.sendNotification(memberTelegramId, title + "\n\n" + content);
                log.info("Sent due reminder for Invoice ID={} to Telegram ID={}", invoice.getId(), memberTelegramId);
            }
        }
    }

    @Transactional
    public void checkOverdueInvoices() {
        LocalDate today = com.boardinghousebot.util.BillingDates.today();
        List<Invoice> overdueInvoices = invoiceRepository.findOverdueInvoices(today);

        for (Invoice invoice : overdueInvoices) {
            if (invoice.getStatus() != InvoiceStatus.OVERDUE) {
                invoice.setStatus(InvoiceStatus.OVERDUE);
                invoiceRepository.save(invoice);
            }

            if (invoice.getMember() == null || invoice.getMember().getTelegramId() == null) {
                continue;
            }

            Long memberTelegramId = invoice.getMember().getTelegramId();
            boolean alreadySent = notificationService.hasNotificationBeenSent(
                    "INVOICE", invoice.getId(), NotificationType.INVOICE_OVERDUE);

            if (!alreadySent) {
                BigDecimal remaining = invoice.getRemainingAmount();
                String title = "Hóa đơn đã quá hạn thanh toán";
                String content = String.format(
                        "Hóa đơn #%d của phòng %s (Kỳ %02d/%d) đã quá hạn thanh toán từ ngày %s. Số tiền còn lại: %s VNĐ. Vui lòng thanh toán ngay.",
                        invoice.getId(),
                        invoice.getRoom() != null ? invoice.getRoom().getRoomNumber() : "N/A",
                        invoice.getBillingMonth(),
                        invoice.getBillingYear(),
                        invoice.getDueDate() != null ? invoice.getDueDate().format(DATE_FORMATTER) : "N/A",
                        formatNumber(remaining)
                );

                notificationService.createNotification(
                        memberTelegramId, NotificationType.INVOICE_OVERDUE, title, content,
                        "INVOICE", invoice.getId()
                );
                telegramNotificationService.sendNotification(memberTelegramId, title + "\n\n" + content);
                log.info("Sent overdue notification for Invoice ID={} to Telegram ID={}", invoice.getId(), memberTelegramId);
            }
        }
    }

    private String formatNumber(BigDecimal val) {
        if (val == null) return "0";
        if (val.stripTrailingZeros().scale() <= 0) {
            return String.format(Locale.US, "%,d", val.longValue());
        }
        return String.format(Locale.US, "%,.2f", val.doubleValue());
    }
}
