package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.handler.ContractCommandHandler;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
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
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ContractStateFlowTest {

    @Autowired
    private ContractCommandHandler contractCommandHandler;

    @Autowired
    private ConversationStateService stateService;

    @Autowired
    private com.boardinghousebot.repository.UserRepository userRepository;

    private User landlordUser;
    private User memberUser;
    private BotMessageSender mockMessageSender;

    @BeforeEach
    void setUp() {
        landlordUser = new User();
        landlordUser.setTelegramId(888999111L);
        landlordUser.setRole(Role.LANDLORD);
        landlordUser.setFullName("Test Landlord");
        landlordUser = userRepository.save(landlordUser);

        memberUser = new User();
        memberUser.setTelegramId(12345L);
        memberUser.setRole(Role.MEMBER);
        memberUser.setFullName("Test Member");
        memberUser = userRepository.save(memberUser);

        stateService.clearState(landlordUser.getTelegramId());

        mockMessageSender = new BotMessageSender() {
            @Override
            public void sendMessage(Long chatId, String text) {}
            @Override
            public void sendMessageWithKeyboard(Long chatId, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {}
        };
    }

    @Test
    @DisplayName("Test full contract creation conversation state flow")
    void testContractCreationStateFlow() {
        Long telegramId = landlordUser.getTelegramId();

        // 1. Set state to CREATE_CONTRACT_START_DATE
        UserConversationState state = stateService.getState(telegramId);
        state.setTargetUserTelegramId(memberUser.getTelegramId());
        state.setStep(UserConversationState.Step.CREATE_CONTRACT_START_DATE);
        stateService.setState(telegramId, state);

        // Verify state is stored
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_START_DATE, stateService.getState(telegramId).getStep());

        // 2. Input invalid start date -> step should remain START_DATE
        Update updateInvalidStart = createMessageUpdate("invalid-date");
        contractCommandHandler.handle(updateInvalidStart, landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_START_DATE, stateService.getState(telegramId).getStep());

        // 3. Input valid start date -> step should change to END_DATE
        Update updateValidStart = createMessageUpdate("20/09/2026");
        contractCommandHandler.handle(updateValidStart, landlordUser, mockMessageSender);

        UserConversationState updatedState1 = stateService.getState(telegramId);
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_END_DATE, updatedState1.getStep());
        assertEquals(LocalDate.of(2026, 9, 20), updatedState1.getData().get("startDate"));

        // 4. Input end date before start date -> step should remain END_DATE
        Update updateInvalidEnd = createMessageUpdate("15/09/2026");
        contractCommandHandler.handle(updateInvalidEnd, landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_END_DATE, stateService.getState(telegramId).getStep());

        // 5. Input valid end date -> step should change to RENT
        Update updateValidEnd = createMessageUpdate("20/09/2027");
        contractCommandHandler.handle(updateValidEnd, landlordUser, mockMessageSender);

        UserConversationState updatedState2 = stateService.getState(telegramId);
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_RENT, updatedState2.getStep());
        assertEquals(LocalDate.of(2027, 9, 20), updatedState2.getData().get("endDate"));

        // 6. Input invalid rent <= 0 -> step should remain RENT
        Update updateInvalidRent = createMessageUpdate("0");
        contractCommandHandler.handle(updateInvalidRent, landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_RENT, stateService.getState(telegramId).getStep());

        // 7. Input valid rent -> step should change to DEPOSIT
        Update updateValidRent = createMessageUpdate("1500000");
        contractCommandHandler.handle(updateValidRent, landlordUser, mockMessageSender);

        UserConversationState updatedState3 = stateService.getState(telegramId);
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_DEPOSIT, updatedState3.getStep());
        assertEquals(new BigDecimal("1500000"), updatedState3.getData().get("rent"));

        // 8. Input invalid deposit -> step should remain DEPOSIT
        Update updateInvalidDeposit = createMessageUpdate("abc");
        contractCommandHandler.handle(updateInvalidDeposit, landlordUser, mockMessageSender);
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_DEPOSIT, stateService.getState(telegramId).getStep());

        // 9. Input valid deposit -> step should change to CONFIRM
        Update updateValidDeposit = createMessageUpdate("3000000");
        contractCommandHandler.handle(updateValidDeposit, landlordUser, mockMessageSender);

        UserConversationState updatedState4 = stateService.getState(telegramId);
        assertEquals(UserConversationState.Step.CREATE_CONTRACT_CONFIRM, updatedState4.getStep());
        assertEquals(new BigDecimal("3000000"), updatedState4.getData().get("deposit"));
    }

    @Test
    @DisplayName("Test /cancel in CREATE_CONTRACT wizard")
    void testCancelInCreateContractWizard() {
        Long telegramId = landlordUser.getTelegramId();
        UserConversationState state = stateService.getState(telegramId);
        state.setStep(UserConversationState.Step.CREATE_CONTRACT_RENT);
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

        Update updateCancel = createMessageUpdate("/cancel");
        contractCommandHandler.handle(updateCancel, landlordUser, customSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(telegramId).getStep());
        assertEquals("❌ Đã hủy thao tác tạo hợp đồng.", capturedMessage[0]);
    }

    @Test
    @DisplayName("Test /cancel in UPDATE_CONTRACT wizard")
    void testCancelInUpdateContractWizard() {
        Long telegramId = landlordUser.getTelegramId();
        UserConversationState state = stateService.getState(telegramId);
        state.setStep(UserConversationState.Step.UPDATE_CONTRACT_RENT);
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

        Update updateCancel = createMessageUpdate("/cancel");
        contractCommandHandler.handle(updateCancel, landlordUser, customSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(telegramId).getStep());
        assertEquals("❌ Đã hủy thao tác sửa hợp đồng.", capturedMessage[0]);
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
