package com.boardinghousebot.bot;

import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest
@Transactional
class TelegramBotCallbackTest {

    @Autowired
    private BoardingHouseTelegramBot telegramBot;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("consume handles CallbackQuery update and triggers early acknowledgement safely")
    void testConsumeCallbackQuery_ShouldNotThrowException() {
        org.telegram.telegrambots.meta.api.objects.User tgUser = new org.telegram.telegrambots.meta.api.objects.User(777123L, "TestUser", false);
        tgUser.setFirstName("Test");
        tgUser.setLastName("User");

        Message msg = new Message();
        msg.setChat(new org.telegram.telegrambots.meta.api.objects.chat.Chat(777123L, "private"));

        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setId("cb_123456789");
        callbackQuery.setFrom(tgUser);
        callbackQuery.setMessage(msg);
        callbackQuery.setData("ACCOUNT_INFO");

        Update update = new Update();
        update.setCallbackQuery(callbackQuery);

        assertDoesNotThrow(() -> telegramBot.consume(update));
    }
}
