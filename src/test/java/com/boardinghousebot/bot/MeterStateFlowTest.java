package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.handler.MeterCommandHandler;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class MeterStateFlowTest {

    @Autowired
    private MeterCommandHandler meterCommandHandler;

    @Autowired
    private ConversationStateService stateService;

    @Autowired
    private com.boardinghousebot.repository.ContractRepository contractRepository;

    @Autowired
    private com.boardinghousebot.repository.MeterReadingRepository meterReadingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired private com.boardinghousebot.repository.UtilityRateRepository utilityRates;

    private User landlordUser;
    private Room room103;
    private BotMessageSender mockMessageSender;

    @BeforeEach
    void setUp() {
        contractRepository.deleteAll();
        meterReadingRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();
        contractRepository.flush();
        meterReadingRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        landlordUser = new User();
        landlordUser.setTelegramId(888777666L);
        landlordUser.setRole(Role.LANDLORD);
        landlordUser.setFullName("Test Landlord");
        landlordUser = userRepository.save(landlordUser);

        room103 = new Room("SF903", new BigDecimal("3500000"), 4, "Phòng SF903");
        room103 = roomRepository.save(room103);

        stateService.clearState(landlordUser.getTelegramId());

        mockMessageSender = new BotMessageSender() {
            @Override
            public void sendMessage(Long chatId, String text) {}
            @Override
            public void sendMessageWithKeyboard(Long chatId, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {}
        };
    }

    @Test
    @DisplayName("Test full meter creation conversation state flow")
    void testMeterCreationStateFlow() {
        Long telegramId = landlordUser.getTelegramId();

        // 1. Select room & type
        UserConversationState state = stateService.getState(telegramId);
        state.getData().put("roomId", room103.getId());
        state.getData().put("utilityType", "ELECTRICITY");
        state.setStep(UserConversationState.Step.CREATE_METER_MONTH);
        stateService.setState(telegramId, state);

        // 2. Input invalid month -> step should remain CREATE_METER_MONTH
        meterCommandHandler.handle(createMessageUpdate("13"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_METER_MONTH, stateService.getState(telegramId).getStep());

        // 3. Input valid month (9) -> step should change to CREATE_METER_YEAR
        meterCommandHandler.handle(createMessageUpdate("9"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_METER_YEAR, stateService.getState(telegramId).getStep());

        // 4. Input valid year (2026) -> step should change to CREATE_METER_PREVIOUS
        meterCommandHandler.handle(createMessageUpdate("2026"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_METER_PREVIOUS, stateService.getState(telegramId).getStep());

        // 5. Input valid previous (1200) -> step should change to CREATE_METER_CURRENT
        meterCommandHandler.handle(createMessageUpdate("1200"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_METER_CURRENT, stateService.getState(telegramId).getStep());

        // 6. Input current reading less than previous (1100) -> step should remain CREATE_METER_CURRENT
        meterCommandHandler.handle(createMessageUpdate("1100"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_METER_CURRENT, stateService.getState(telegramId).getStep());

        utilityRates.save(new com.boardinghousebot.domain.meter.UtilityRate(
                com.boardinghousebot.domain.meter.UtilityType.ELECTRICITY, new BigDecimal("3500"),
                java.time.LocalDate.of(2026, 9, 1)));
        // Common rate is selected automatically, with no unit-price input.
        meterCommandHandler.handle(createMessageUpdate("1350"), landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_METER_CONFIRM, stateService.getState(telegramId).getStep());
        assertEquals(0, new BigDecimal("3500").compareTo(
                (BigDecimal) stateService.getState(telegramId).getData().get("unitPrice")));
    }

    @Test
    @DisplayName("Test /cancel in CREATE_METER wizard")
    void testCancelInCreateMeterWizard() {
        Long telegramId = landlordUser.getTelegramId();
        UserConversationState state = stateService.getState(telegramId);
        state.setStep(UserConversationState.Step.CREATE_METER_CURRENT);
        stateService.setState(telegramId, state);

        final String[] capturedMessage = new String[1];
        BotMessageSender customSender = new BotMessageSender() {
            @Override
            public void sendMessage(Long chatId, String text) {}
            @Override
            public void sendMessageWithKeyboard(Long chatId, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {
                capturedMessage[0] = text;
            }
        };

        meterCommandHandler.handle(createMessageUpdate("/cancel"), landlordUser, customSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(telegramId).getStep());
        assertEquals("❌ Đã hủy thao tác ghi chỉ số điện nước.", capturedMessage[0]);
    }

    @Test
    @DisplayName("Test /cancel in UPDATE_METER wizard")
    void testCancelInUpdateMeterWizard() {
        Long telegramId = landlordUser.getTelegramId();
        UserConversationState state = stateService.getState(telegramId);
        state.setStep(UserConversationState.Step.UPDATE_METER_CURRENT);
        stateService.setState(telegramId, state);

        final String[] capturedMessage = new String[1];
        BotMessageSender customSender = new BotMessageSender() {
            @Override
            public void sendMessage(Long chatId, String text) {}
            @Override
            public void sendMessageWithKeyboard(Long chatId, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {
                capturedMessage[0] = text;
            }
        };

        meterCommandHandler.handle(createMessageUpdate("/cancel"), landlordUser, customSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(telegramId).getStep());
        assertEquals("❌ Đã hủy thao tác sửa chỉ số điện nước.", capturedMessage[0]);
    }

    private Update createMessageUpdate(String text) {
        Message msg = new Message();
        msg.setChat(new org.telegram.telegrambots.meta.api.objects.chat.Chat(landlordUser.getTelegramId(), "private"));
        msg.setText(text);
        org.telegram.telegrambots.meta.api.objects.User tgUser = new org.telegram.telegrambots.meta.api.objects.User(landlordUser.getTelegramId(), "Landlord", false);
        msg.setFrom(tgUser);

        Update update = new Update();
        update.setMessage(msg);
        return update;
    }
}
