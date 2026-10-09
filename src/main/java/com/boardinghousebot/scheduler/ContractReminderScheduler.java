package com.boardinghousebot.scheduler;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ContractReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContractReminderScheduler.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ContractRepository contractRepository;
    private final NotificationService notificationService;
    private final com.boardinghousebot.service.TelegramNotificationService telegramNotificationService;

    public ContractReminderScheduler(ContractRepository contractRepository,
                                     NotificationService notificationService,
                                     com.boardinghousebot.service.TelegramNotificationService telegramNotificationService) {
        this.contractRepository = contractRepository;
        this.notificationService = notificationService;
        this.telegramNotificationService = telegramNotificationService;
    }

    @Scheduled(cron = "0 30 8 * * ?") // Daily at 08:30 AM
    @Transactional
    public void processContractReminders() {
        log.info("Starting scheduled job: processContractReminders");
        checkExpiringContracts();
        log.info("Finished scheduled job: processContractReminders");
    }

    @Transactional
    public void checkExpiringContracts() {
        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.plusDays(7);
        List<Contract> expiringContracts = contractRepository.findExpiringContracts(today, targetDate);

        for (Contract contract : expiringContracts) {
            if (contract.getMember() == null || contract.getMember().getTelegramId() == null) {
                continue;
            }

            Long memberTelegramId = contract.getMember().getTelegramId();
            boolean alreadySent = notificationService.hasNotificationBeenSent(
                    "CONTRACT", contract.getId(), NotificationType.CONTRACT_EXPIRING);

            if (!alreadySent) {
                String title = "Hợp đồng sắp hết hạn";
                String content = String.format(
                        "Hợp đồng thuê phòng %s của bạn sẽ hết hạn vào ngày %s. Vui lòng liên hệ chủ nhà để thực hiện gia hạn hoặc thanh lý hợp đồng.",
                        contract.getRoom() != null ? contract.getRoom().getRoomNumber() : "N/A",
                        contract.getEndDate() != null ? contract.getEndDate().format(DATE_FORMATTER) : "N/A"
                );

                notificationService.createNotification(
                        memberTelegramId, NotificationType.CONTRACT_EXPIRING, title, content,
                        "CONTRACT", contract.getId()
                );
                telegramNotificationService.sendNotification(memberTelegramId, title + "\n\n" + content);
                log.info("Sent contract expiring notification for Contract ID={} to Telegram ID={}", contract.getId(), memberTelegramId);
            }
        }
    }
}
