package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
public class MemberRoomDisplayRegressionTest {

    @Autowired
    private RoomCommandHandler roomCommandHandler;

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
    private User member1;
    private User member2;
    private Room room101;

    private static final Long LANDLORD_ID = 999111L;
    private static final Long MEMBER1_ID = 111222L;
    private static final Long MEMBER2_ID = 333444L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord", "Landlord User");
        landlord.setRole(Role.LANDLORD);
        userRepository.save(landlord);

        member1 = userService.getOrCreateUser(MEMBER1_ID, "member1", "Nguyen Van Member A");
        member2 = userService.getOrCreateUser(MEMBER2_ID, "member2", "Tran Thi Member B");

        room101 = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("3000000"), 2, "Phong 101 test");
    }

    @Test
    @DisplayName("Member living alone in room: Displays total occupants as 1/2 and co-tenants as '- Không có'")
    void testShowMemberRoom_LivingAlone_DisplaysNoCoTenants() {
        // Assign member1 to room101
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER1_ID, room101.getId());

        Update update = createCallbackUpdate("MEMBER_ROOM", MEMBER1_ID);

        // Member1 views room details
        roomCommandHandler.handle(update, member1, mockMessageSender);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockMessageSender).sendMessage(eq(MEMBER1_ID), textCaptor.capture());

        String messageText = textCaptor.getValue();
        assertNotNull(messageText);

        // Total occupants must be 1/2
        assertTrue(messageText.contains("👥 Số người ở: 1/2 người"));

        // Co-tenants section must show "- Không có"
        assertTrue(messageText.contains("- Không có"));

        // Must NOT list member1 (Nguyen Van Member A) under co-tenants
        assertFalse(messageText.contains("- Nguyen Van Member A"));
    }

    @Test
    @DisplayName("Member living with others: Displays total occupants as 2/2 and co-tenants listing ONLY other member")
    void testShowMemberRoom_LivingWithOthers_DisplaysOnlyOtherMember() {
        // Assign both member1 and member2 to room101
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER1_ID, room101.getId());
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER2_ID, room101.getId());

        Update update = createCallbackUpdate("MEMBER_ROOM", MEMBER1_ID);

        // Member1 views room details
        roomCommandHandler.handle(update, member1, mockMessageSender);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockMessageSender).sendMessage(eq(MEMBER1_ID), textCaptor.capture());

        String messageText = textCaptor.getValue();
        assertNotNull(messageText);

        // Total occupants must be 2/2
        assertTrue(messageText.contains("👥 Số người ở: 2/2 người"));

        // Co-tenants section must list member2 (Tran Thi Member B)
        assertTrue(messageText.contains("- Tran Thi Member B"));

        // Must NOT list member1 (Nguyen Van Member A) under co-tenants
        assertFalse(messageText.contains("- Nguyen Van Member A"));
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
