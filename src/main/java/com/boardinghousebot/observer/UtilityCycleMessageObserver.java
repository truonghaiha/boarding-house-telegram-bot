package com.boardinghousebot.observer;
import com.boardinghousebot.service.TelegramNotificationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
@Component
public class UtilityCycleMessageObserver {
    private final TelegramNotificationService telegram;
    public UtilityCycleMessageObserver(TelegramNotificationService telegram) { this.telegram=telegram; }
    @TransactionalEventListener
    public void send(UtilityCycleMessage event) { telegram.sendNotification(event.telegramId(),event.message()); }
}
