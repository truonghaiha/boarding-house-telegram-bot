package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.AuditLogRepository;
import com.boardinghousebot.service.RoomStayService;
import com.boardinghousebot.repository.MaintenanceRequestRepository;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.service.AuditLogService;
import com.boardinghousebot.service.AuthorizationService;
import com.boardinghousebot.service.MaintenanceService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaintenanceCommandHandlerTest {

    @Mock
    private MaintenanceRequestRepository maintenanceRequestRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoomStayService roomStayService;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BotMessageSender messageSender;

    private AuthorizationService authorizationService;
    private AuditLogService auditLogService;
    private MaintenanceService maintenanceService;
    private ConversationStateService stateService;
    private MaintenanceCommandHandler maintenanceCommandHandler;

    private User member;
    private User landlord;
    private Room room;
    private Contract activeContract;

    @BeforeEach
    void setUp() {
        authorizationService = new AuthorizationService(new com.boardinghousebot.config.TelegramConfig());
        auditLogService = new AuditLogService(auditLogRepository);
        maintenanceService = new MaintenanceService(
                maintenanceRequestRepository,
                userRepository,
                roomStayService,
                authorizationService,
                auditLogService,
                eventPublisher
        );
        stateService = new ConversationStateService();
        maintenanceCommandHandler = new MaintenanceCommandHandler(maintenanceService, stateService);

        member = new User(100L, "member1", "Nguyen Van A", Role.MEMBER);
        landlord = new User(200L, "landlord1", "Tran Van B", Role.LANDLORD);
        room = new Room("101", new BigDecimal("3000000"), 2, "Phong 101");
        member.setRoom(room);

        activeContract = new Contract(member, room, LocalDate.now(), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));
    }

    @Test
    @DisplayName("Cancel command in CREATE_MAINTENANCE step returns correct cancellation message")
    void handleCancel_CreateWizard_ReturnsCreateCancelMessage() {
        stateService.setState(100L, new UserConversationState(UserConversationState.Step.CREATE_MAINTENANCE_TITLE));
        Update update = createMessageUpdate("/cancel", 100L);

        maintenanceCommandHandler.handle(update, member, messageSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(100L).getStep());
        verify(messageSender).sendMessageWithKeyboard(eq(100L), contains("Đã hủy thao tác báo cáo sự cố"), argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(java.util.Collection::stream)
                        .anyMatch(button -> "MEMBER_MAINTENANCE".equals(button.getCallbackData()))
                && keyboard.getKeyboard().stream().flatMap(java.util.Collection::stream)
                        .noneMatch(button -> "MAINTENANCE_LIST_MEMBER".equals(button.getCallbackData()))));
    }

    @Test
    @DisplayName("Cancel command in UPDATE_MAINTENANCE step returns correct cancellation message")
    void handleCancel_UpdateWizard_ReturnsUpdateCancelMessage() {
        stateService.setState(200L, new UserConversationState(UserConversationState.Step.UPDATE_MAINTENANCE_NOTE));
        Update update = createMessageUpdate("/cancel", 200L);

        maintenanceCommandHandler.handle(update, landlord, messageSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(200L).getStep());
        verify(messageSender).sendMessageWithKeyboard(eq(200L), contains("Đã hủy thao tác xử lý sự cố"), any());
    }

    @Test
    @DisplayName("Member complete reporting wizard flow")
    void memberCreateMaintenanceWizard_Success() {
        // Step 1: Start wizard
        Update update1 = new Update();
        var callback = new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
        var menuMessage = new Message();
        menuMessage.setChat(new Chat(100L, "private"));
        callback.setMessage(menuMessage);
        callback.setData("MEMBER_MAINTENANCE");
        update1.setCallbackQuery(callback);
        maintenanceCommandHandler.handle(update1, member, messageSender);
        assertEquals(UserConversationState.Step.CREATE_MAINTENANCE_DESC, stateService.getState(100L).getStep());
        verify(messageSender).sendPrompt(eq(100L), contains("Nhập chi tiết mô tả sự cố:"));
        verify(messageSender, never()).sendMessageWithKeyboard(anyLong(), anyString(), any());

        // One message describes the whole issue and submits the report.
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(member));
        when(roomStayService.getCurrentRoom(100L)).thenReturn(Optional.of(room));
        when(maintenanceRequestRepository.save(any(MaintenanceRequest.class))).thenAnswer(i -> {
            MaintenanceRequest req = i.getArgument(0);
            req.setId(1L);
            return req;
        });

        Update update3 = createMessageUpdate("Không bật được đèn", 100L);
        maintenanceCommandHandler.handle(update3, member, messageSender);

        assertEquals(UserConversationState.Step.NONE, stateService.getState(100L).getStep());
        verify(maintenanceRequestRepository).save(argThat(request ->
                "Không bật được đèn".equals(request.getDescription())
                && "Không bật được đèn".equals(request.getTitle())));
        verify(messageSender, times(1)).sendPrompt(anyLong(), anyString());
        verify(messageSender).sendResultAndMenu(eq(100L), contains("Đã gửi báo cáo sự cố thành công"), argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(java.util.Collection::stream)
                        .anyMatch(button -> "MEMBER_MAINTENANCE".equals(button.getCallbackData()))
                && keyboard.getKeyboard().stream().flatMap(java.util.Collection::stream)
                        .noneMatch(button -> "MAINTENANCE_LIST_MEMBER".equals(button.getCallbackData()))));
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
}
