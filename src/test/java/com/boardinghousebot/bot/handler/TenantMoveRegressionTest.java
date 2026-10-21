package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.BoardingHouseTelegramBot;
import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.state.UserConversationState.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
public class TenantMoveRegressionTest {

    @Autowired
    private BoardingHouseTelegramBot telegramBot;

    @Autowired
    private TenantCommandHandler tenantCommandHandler;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private ConversationStateService stateService;

    @Mock
    private BotMessageSender mockMessageSender;

    private User landlord;
    private User member;
    private User otherLandlord;
    private Room room101;
    private Room room102;

    private static final Long LANDLORD_ID = 6522259425L;
    private static final Long OTHER_LANDLORD_ID = 7777777777L;
    private static final Long MEMBER_ID = 8167463325L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord1", "Landlord One");
        landlord.setRole(Role.LANDLORD);
        userRepository.save(landlord);

        otherLandlord = userService.getOrCreateUser(OTHER_LANDLORD_ID, "landlord2", "Landlord Two");
        otherLandlord.setRole(Role.LANDLORD);
        userRepository.save(otherLandlord);

        member = userService.getOrCreateUser(MEMBER_ID, "member1", "Nguyen Van Member");

        room101 = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("3000000"), 2, "Phong 101");
        room102 = roomService.createRoom(LANDLORD_ID, "102", new BigDecimal("3500000"), 2, "Phong 102");

        // Assign member to room 101
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room101.getId());
    }

    @Test
    @DisplayName("1. MOVE_TENANT_MEMBER_TELEGRAM_ID regression test: Landlord enters valid Member Telegram ID -> routes correctly & displays target room options")
    void testMoveTenantMemberTelegramId_ValidMember_DisplaysTargetRooms() {
        // Set landlord state to MOVE_TENANT_MEMBER_TELEGRAM_ID
        stateService.setState(LANDLORD_ID, new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID));

        // Create update with member's telegram ID input
        Update update = createMessageUpdate(MEMBER_ID.toString(), LANDLORD_ID);

        // Test BoardingHouseTelegramBot routing & execution without throwing exception
        assertDoesNotThrow(() -> telegramBot.consume(update));

        // Also test handler directly with mockMessageSender to verify output keyboard contents
        stateService.setState(LANDLORD_ID, new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID));
        tenantCommandHandler.handle(update, landlord, mockMessageSender);

        // Verify state advanced to MOVE_TENANT_TARGET_ROOM_SELECT
        UserConversationState nextState = stateService.getState(LANDLORD_ID);
        assertEquals(Step.MOVE_TENANT_TARGET_ROOM_SELECT, nextState.getStep());
        assertEquals(MEMBER_ID, nextState.getData().get("targetUserTelegramId"));
        assertEquals(room101.getId(), nextState.getData().get("oldRoomId"));

        // Verify message with keyboard sent to Landlord containing prompt & target room 102
        verify(mockMessageSender).sendMessageWithKeyboard(
                eq(LANDLORD_ID),
                contains("THÔNG TIN CHUYỂN PHÒNG"),
                argThat(kb -> kb != null && !kb.getKeyboard().isEmpty())
        );
    }

    @Test
    @DisplayName("2. Member does not exist: Returns error message")
    void testMoveTenant_MemberDoesNotExist_ReturnsErrorMessage() {
        stateService.setState(LANDLORD_ID, new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID));

        Long nonExistentId = 9999999999L;
        Update update = createMessageUpdate(nonExistentId.toString(), LANDLORD_ID);

        tenantCommandHandler.handle(update, landlord, mockMessageSender);

        verify(mockMessageSender).sendNotice(eq(LANDLORD_ID), contains("Không tìm thấy người dùng với Telegram ID 9999999999"));
    }

    @Test
    @DisplayName("3. Telegram ID is not a MEMBER: Returns error message")
    void testMoveTenant_TargetIsNotMember_ReturnsErrorMessage() {
        stateService.setState(LANDLORD_ID, new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID));

        // Use otherLandlord's ID instead of MEMBER
        Update update = createMessageUpdate(OTHER_LANDLORD_ID.toString(), LANDLORD_ID);

        tenantCommandHandler.handle(update, landlord, mockMessageSender);

        verify(mockMessageSender).sendNotice(eq(LANDLORD_ID), contains("Chỉ MEMBER mới có thể được chuyển phòng"));
    }

    @Test
    @DisplayName("4. Member has no current room: Returns error message")
    void testMoveTenant_MemberHasNoCurrentRoom_ReturnsErrorMessage() {
        Long unassignedMemberId = 5555555555L;
        userService.getOrCreateUser(unassignedMemberId, "unassigned", "Unassigned Member");

        stateService.setState(LANDLORD_ID, new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID));

        Update update = createMessageUpdate(unassignedMemberId.toString(), LANDLORD_ID);

        tenantCommandHandler.handle(update, landlord, mockMessageSender);

        verify(mockMessageSender).sendNotice(eq(LANDLORD_ID), contains("Người dùng này hiện chưa thuộc phòng nào"));
    }

    @Test
    @DisplayName("5. No available target rooms: Clears state and returns warning message")
    void testMoveTenant_NoAvailableTargetRooms_ReturnsWarning() {
        // Delete room 102 so room 101 is the only room
        roomService.deleteRoom(LANDLORD_ID, room102.getId());

        stateService.setState(LANDLORD_ID, new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID));

        Update update = createMessageUpdate(MEMBER_ID.toString(), LANDLORD_ID);

        tenantCommandHandler.handle(update, landlord, mockMessageSender);

        assertEquals(Step.NONE, stateService.getState(LANDLORD_ID).getStep());
        verify(mockMessageSender).sendMessageWithKeyboard(eq(LANDLORD_ID), contains("Không có phòng khác đủ điều kiện"), any());
    }

    @Test
    @DisplayName("6. Move tenant full flow success: Member is moved to target room 102 in DB")
    void testMoveTenantFullSuccess() {
        // Step 1: Landlord enters member ID
        stateService.setState(LANDLORD_ID, new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID));
        tenantCommandHandler.handle(createMessageUpdate(MEMBER_ID.toString(), LANDLORD_ID), landlord, mockMessageSender);

        assertEquals(Step.MOVE_TENANT_TARGET_ROOM_SELECT, stateService.getState(LANDLORD_ID).getStep());

        // Step 2: Landlord selects room 102 from inline keyboard
        Update selectRoomUpdate = createCallbackUpdate("SELECT_MOVE_TARGET_ROOM_" + room102.getId(), LANDLORD_ID);
        tenantCommandHandler.handle(selectRoomUpdate, landlord, mockMessageSender);

        assertEquals(Step.MOVE_TENANT_CONFIRM, stateService.getState(LANDLORD_ID).getStep());
        verify(mockMessageSender).sendMessageWithKeyboard(eq(LANDLORD_ID), contains("XÁC NHẬN CHUYỂN PHÒNG"), any());

        // Step 3: Landlord confirms move
        Update confirmUpdate = createCallbackUpdate("CONFIRM_MOVE_TENANT", LANDLORD_ID);
        tenantCommandHandler.handle(confirmUpdate, landlord, mockMessageSender);

        // Verify state is cleared
        assertEquals(Step.NONE, stateService.getState(LANDLORD_ID).getStep());

        // Verify member room is now 102 in DB
        User updatedMember = userService.findByTelegramIdWithRoom(MEMBER_ID).orElseThrow();
        assertNotNull(updatedMember.getRoom());
        assertEquals("102", updatedMember.getRoom().getRoomNumber());

        // Verify success message sent
        verify(mockMessageSender).sendResultAndMenu(eq(LANDLORD_ID), contains("Đã chuyển người thuê"), any());
    }

    private Update createMessageUpdate(String text, Long chatId) {
        Message msg = new Message();
        msg.setChat(new Chat(chatId, "private"));
        msg.setText(text);
        org.telegram.telegrambots.meta.api.objects.User tgUser = new org.telegram.telegrambots.meta.api.objects.User(chatId, "User", false);
        msg.setFrom(tgUser);

        Update update = new Update();
        update.setMessage(msg);
        return update;
    }

    private Update createCallbackUpdate(String callbackData, Long chatId) {
        Message msg = new Message();
        msg.setChat(new Chat(chatId, "private"));

        org.telegram.telegrambots.meta.api.objects.User tgUser = new org.telegram.telegrambots.meta.api.objects.User(chatId, "User", false);

        CallbackQuery query = new CallbackQuery();
        query.setId("cb_" + System.currentTimeMillis());
        query.setFrom(tgUser);
        query.setMessage(msg);
        query.setData(callbackData);

        Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }
}
