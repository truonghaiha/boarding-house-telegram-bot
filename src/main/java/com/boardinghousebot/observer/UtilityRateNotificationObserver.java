package com.boardinghousebot.observer;
import com.boardinghousebot.service.TelegramNotificationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
@Component
public class UtilityRateNotificationObserver {
    private final TelegramNotificationService telegram;
    public UtilityRateNotificationObserver(TelegramNotificationService telegram) { this.telegram = telegram; }
    @TransactionalEventListener
    public void onChanged(UtilityRateChangedEvent event) {
        telegram.sendNotification(event.telegramId(), event.message());
    }
}
