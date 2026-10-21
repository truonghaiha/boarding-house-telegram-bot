package com.boardinghousebot.bot;

import static com.boardinghousebot.util.TelegramUpdateUtil.extractChatId;
import static com.boardinghousebot.util.TelegramUpdateUtil.extractText;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.service.RoomStayService;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.bot.handler.StartHandler;
import com.boardinghousebot.bot.ui.BotUiSessionManager;
import org.springframework.dao.DataAccessException;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@DependsOn("adminRoleSynchronization")
public class BoardingHouseTelegramBot implements LongPollingSingleThreadUpdateConsumer, BotMessageSender {

    private static final Logger log = LoggerFactory.getLogger(BoardingHouseTelegramBot.class);

    private final TelegramConfig telegramConfig;
    private final UserService userService;
    private final ConversationStateService stateService;
    private final RoomStayService roomStayService;
    private final Map<String, CommandHandler> commandHandlerMap = new HashMap<>();
    private TelegramClient telegramClient;
    private TelegramBotsLongPollingApplication botsApplication;
    private final BotUiSessionManager uiSessions = new BotUiSessionManager(() -> telegramClient);

    public BoardingHouseTelegramBot(TelegramConfig telegramConfig,
                                  UserService userService,
                                  ConversationStateService stateService,
                                  RoomStayService roomStayService,
                                  List<CommandHandler> commandHandlers) {
        this.telegramConfig = telegramConfig;
        this.userService = userService;
        this.stateService = stateService;
        this.roomStayService = roomStayService;
        for (CommandHandler handler : commandHandlers) {
            this.commandHandlerMap.put(handler.getCommand(), handler);
        }
    }

    @PostConstruct
    public void init() {
        String token = telegramConfig.getBotToken();
        if (token != null && !token.isBlank()) {
            this.telegramClient = new OkHttpTelegramClient(token);
            try {
                this.botsApplication = new TelegramBotsLongPollingApplication();
                this.botsApplication.registerBot(token, this);
                telegramConfig.logConfigurationStatus();
                log.info("Telegram Bot successfully registered and listening via Long Polling.");

                registerBotCommands();
            } catch (Exception e) {
                log.error("Failed to start Telegram Bot Long Polling: {}", e.getMessage());
            }
        } else {
            log.warn("Telegram Bot Token is not configured. Bot execution disabled.");
        }
    }

    private void registerBotCommands() {
        if (telegramClient == null) {
            return;
        }
        try {
            List<BotCommand> commands = List.of(
                    new BotCommand("start", "🚀 Bắt đầu sử dụng bot"),
                    new BotCommand("menu", "📋 Hiển thị menu chính"),
                    new BotCommand("help", "❓ Hướng dẫn sử dụng")
            );
            SetMyCommands setMyCommands = new SetMyCommands(commands);
            telegramClient.execute(setMyCommands);
            log.info("Telegram Bot commands registered successfully: /start, /help, /menu.");
        } catch (Exception e) {
            log.error("Failed to register Telegram Bot commands: {}", e.getMessage());
        }
    }

    @Override
    public void consume(Update update) {
        BotUiSessionManager.Request ui = null;
        try {
            Long telegramId = extractTelegramId(update);
            if (telegramId == null) {
                return;
            }

            String username = extractUsername(update);
            String fullName = extractFullName(update);

            User user = userService.getOrCreateUser(telegramId, username, fullName);
            ui = uiSessions.open(update, user, this, stateService);
            if (!ui.acceptsCallback()) return;

            String commandText = extractText(update);
            if (update.hasCallbackQuery() && "/cancel".equals(commandText)) {
                // Reuse text cancellation, including handlers that only accept message input.
                update = navigationUpdate(update, commandText);
            }
            String typedMenuCommand = typedMenuCommand(update);
            if (update.hasCallbackQuery() && commandText != null && commandText.startsWith("SKIP_INPUT_")) {
                var step = stateService.getState(telegramId).getStep();
                var skipValue = com.boardinghousebot.state.OptionalInput.skipValue(step);
                if (!commandText.equals("SKIP_INPUT_" + step.name()) || skipValue.isEmpty()) {
                    ui.sendNotice(extractChatId(update), "❌ Không thể bỏ qua bước này. Nút Bỏ qua đã hết hiệu lực hoặc bước hiện tại là bắt buộc.");
                    return;
                }
                // Process through the existing handler and its current role checks, just like typed input.
                update = navigationUpdate(update, "/skip");
                commandText = skipValue.get();
                update.getMessage().setText(commandText);
                ui.action(commandText);
            }
            if (typedMenuCommand != null) {
                // Explicit commands reset navigation; every screen is sent as a new message.
                ui.newSession();
                commandText = typedMenuCommand;
                update = navigationUpdate(update, commandText);
                ui.action(commandText);
            }
            if ("UI_BACK".equals(commandText)) {
                var destination = ui.back();
                stateService.clearState(telegramId);
                stateService.restoreNavigationFilters(telegramId, destination.filters());
                commandText = destination.action();
                update = navigationUpdate(update, commandText);
                ui.action(commandText);
            }
            if (commandText != null && commandText.startsWith("UI_PAGE_")) {
                String sourceAction = ui.screenAction();
                CommandHandler source = findHandler(sourceAction, null);
                boolean privilegedAdminView = sourceAction.startsWith("ADMIN_") || sourceAction.startsWith("/search_users");
                boolean configuredAdmin = !privilegedAdminView
                        || new com.boardinghousebot.service.AuthorizationService(telegramConfig).hasRole(user, Role.ADMIN);
                if (configuredAdmin && source != null && com.boardinghousebot.bot.command.RoleActionPolicy.allows(user, source.getCommand(), sourceAction,
                        UserConversationState.Step.NONE) && roomStayService.allowsMemberAction(user, source.getCommand(), sourceAction,
                        UserConversationState.Step.NONE)) {
                    if (!ui.page(commandText)) ui.sendNotice(extractChatId(update), "Màn hình đã hết hiệu lực. Vui lòng gửi /start.");
                } else ui.sendNotice(extractChatId(update), "Bạn không có quyền xem màn hình này.");
                return;
            }
            if (user.getRole() == Role.MEMBER && ("ACCOUNT_INFO".equals(commandText)
                    || "MEMBER_CHECK_STATUS".equals(commandText) || "MEMBER_CONTACT_INFO".equals(commandText))) {
                stateService.clearState(telegramId);
            }
            if ("MAIN_MENU".equals(commandText) || "/menu".equals(commandText)
                    || "/start".equals(commandText) || "/help".equals(commandText)) {
                stateService.clearState(telegramId);
            }
            if ("/cancel".equals(commandText) && stateService.getState(telegramId).getStep() == UserConversationState.Step.NONE) {
                commandText = ui.pendingMenu() != null ? ui.pendingMenu() : "/menu";
                update = navigationUpdate(update, commandText);
                ui.action(commandText);
            }
            if (stateService.getState(telegramId).getStep() == UserConversationState.Step.ADMIN_SEARCH_USERS
                    && update.hasMessage() && commandText != null && !commandText.startsWith("/")) {
                ui.action("/search_users " + commandText);
            }
            CommandHandler handler = findHandler(commandText, telegramId);

            if (handler != null) {
                ui.returnMenu(handler.getCommand());
                if (!roomStayService.allowsMemberAction(user, handler.getCommand(), commandText,
                        stateService.getState(telegramId).getStep())) {
                    stateService.clearState(telegramId);
                    if (commandHandlerMap.get("/start") instanceof StartHandler startHandler) {
                        startHandler.showMemberMenu(update, user, ui, false);
                    }
                    return;
                }
                handler.handle(update, user, ui);
            } else {
                CommandHandler menuHandler = commandHandlerMap.get("/menu");
                if (menuHandler != null) {
                    ui.returnMenu(menuHandler.getCommand());
                    menuHandler.handle(update, user, ui);
                }
            }
        } catch (DataAccessException e) {
            log.error("Database unavailable during update handling", e);
            Long chatId = extractChatId(update);
            if (chatId != null) sendMessage(chatId, StartHandler.STATUS_ERROR);
        } catch (BusinessException e) {
            log.warn("Business exception during update handling: {}", e.getMessage());
            Long chatId = extractChatId(update);
            if (chatId != null) {
                if (ui != null) ui.sendNotice(chatId, "❌ " + e.getMessage());
                else sendMessage(chatId, "❌ " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error handling update", e);
            Long chatId = extractChatId(update);
            if (chatId != null) {
                sendMessage(chatId, "❌ Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.");
            }
        } finally {
            if (ui != null) ui.close();
            else if (update.hasCallbackQuery()) answerCallbackQuery(update.getCallbackQuery().getId());
        }
    }

    private String typedMenuCommand(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return null;
        String command = update.getMessage().getText().trim().split("\\s+", 2)[0].split("@", 2)[0];
        return "/start".equals(command) || "/menu".equals(command) ? command : null;
    }

    private Update navigationUpdate(Update source, String action) {
        Update replay = new Update();
        if (action.startsWith("/")) {
            var message = new org.telegram.telegrambots.meta.api.objects.message.Message();
            message.setChat(new org.telegram.telegrambots.meta.api.objects.chat.Chat(extractChatId(source), "private"));
            message.setFrom(source.hasMessage() ? source.getMessage().getFrom() : source.getCallbackQuery().getFrom());
            message.setText(action);
            replay.setMessage(message);
        } else {
            var callback = new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
            callback.setData(action);
            callback.setMessage(source.hasMessage() ? source.getMessage() : source.getCallbackQuery().getMessage());
            callback.setFrom(source.hasMessage() ? source.getMessage().getFrom() : source.getCallbackQuery().getFrom());
            replay.setCallbackQuery(callback);
        }
        return replay;
    }

    private void answerCallbackQuery(String callbackQueryId) {
        if (telegramClient == null || callbackQueryId == null) {
            log.info("[SIMULATED ANSWER CALLBACK QUERY]: id={}", callbackQueryId);
            return;
        }

        AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .build();

        try {
            telegramClient.execute(answer);
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback query: {}", e.getMessage());
        }
    }

    @Override
    public void sendMessage(Long chatId, String text) {
        if (telegramClient == null || chatId == null || text == null) {
            log.info("[SIMULATED SEND to {}]: {}", chatId, text);
            return;
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("parse entities")) {
                try {
                    message.setParseMode(null);
                    telegramClient.execute(message);
                    return;
                } catch (TelegramApiException fallbackError) {
                    log.error("Failed to send plain-text telegram message to {}: {}", chatId, fallbackError.getMessage());
                    return;
                }
            }
            log.error("Failed to send telegram message to {}: {}", chatId, e.getMessage());
        }
    }

    @Override
    public void sendMessageWithKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        if (telegramClient == null || chatId == null || text == null) {
            log.info("[SIMULATED SEND WITH KEYBOARD to {}]: {}", chatId, text);
            return;
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboard)
                .parseMode("Markdown")
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send telegram message with keyboard to {}: {}", chatId, e.getMessage());
        }
    }

    @Override
    public void sendOrEditMessageWithKeyboard(Long chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        if (telegramClient == null || chatId == null || text == null) {
            log.info("[SIMULATED MENU SEND to {}]: {}", chatId, text);
            return;
        }
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId.toString()).text(text).replyMarkup(keyboard).build());
        } catch (TelegramApiException e) {
            log.error("Failed to send menu for {}: {}", chatId, e.getMessage());
        }
    }

    private CommandHandler findHandler(String commandText, Long telegramId) {
        if ("MAIN_MENU".equals(commandText) || "/menu".equals(commandText) || "/help".equals(commandText)) {
            return commandHandlerMap.get("/help".equals(commandText) ? "/help" : "/menu");
        }
        if (commandText != null && commandText.startsWith("/start")) {
            stateService.clearState(telegramId);
            return commandHandlerMap.get("/start");
        }
        if ("MEMBER_CHECK_STATUS".equals(commandText) || "MEMBER_CONTACT_INFO".equals(commandText)) {
            return commandHandlerMap.get("/menu");
        }
        if (commandText != null && commandText.startsWith("INVOICE_CYCLE_")) return commandHandlerMap.get("/invoice");
        if (commandText != null && (commandText.startsWith("METER_CYCLE_") || commandText.startsWith("MEMBER_CYCLE_"))) return commandHandlerMap.get("/meter");
        if (telegramId != null && stateService != null) {
            UserConversationState state = stateService.getState(telegramId);
            if (state.getStep() != UserConversationState.Step.NONE) {
                log.debug("STATE ROUTING: telegramId={}, step={}, input={}", telegramId, state.getStep(), commandText);
                String stepName = state.getStep().name();
                if (stepName.equals("ADMIN_SEARCH_USERS") || stepName.startsWith("ADMIN_ROLE_CHANGE")) {
                    return commandHandlerMap.get("/menu");
                } else if (stepName.startsWith("CREATE_CONTRACT") || stepName.startsWith("UPDATE_CONTRACT")) {
                    return commandHandlerMap.get("/contract");
                } else if (stepName.startsWith("CREATE_METER") || stepName.startsWith("UPDATE_METER") || stepName.startsWith("UPDATE_UTILITY")) {
                    return commandHandlerMap.get("/meter");
                } else if (stepName.startsWith("CREATE_INVOICE") || stepName.startsWith("RECORD_PAYMENT")) {
                    return commandHandlerMap.get("/invoice");
                } else if (stepName.startsWith("CREATE_MAINTENANCE") || stepName.startsWith("UPDATE_MAINTENANCE")) {
                    return commandHandlerMap.get("/maintenance");
                } else if (stepName.startsWith("ASSIGN_TENANT") || stepName.startsWith("MOVE_TENANT") || stepName.startsWith("EDIT_TENANT") || stepName.startsWith("REMOVE_TENANT")) {
                    return commandHandlerMap.get("/tenant");
                } else if (stepName.startsWith("CREATE_ROOM")) {
                    return commandHandlerMap.get("/room");
                } else {
                    return commandHandlerMap.get("/room");
                }
            }
        }

        if (commandText == null) return null;
        if (commandText.startsWith("/start")) return commandHandlerMap.get("/start");
        if (commandText.startsWith("/help")) return commandHandlerMap.get("/help");
        if (commandText.startsWith("/maintenance") || commandText.startsWith("/maintenance_detail_")
                || commandText.startsWith("LANDLORD_MAINTENANCE") || commandText.startsWith("MEMBER_MAINTENANCE")
                || commandText.startsWith("MAINTENANCE_") || commandText.startsWith("CANCEL_MAINTENANCE")) {
            return commandHandlerMap.get("/maintenance");
        }
        if (commandText.startsWith("/notification")
                || commandText.startsWith("LANDLORD_NOTIFICATIONS") || commandText.startsWith("MEMBER_NOTIFICATIONS")
                || commandText.startsWith("NOTIFICATION_")) {
            return commandHandlerMap.get("/notification");
        }
        if (commandText.startsWith("/invoice") || commandText.startsWith("/invoice_detail_")
                || commandText.startsWith("LANDLORD_INVOICES") || commandText.startsWith("MEMBER_INVOICE")
                || commandText.startsWith("INVOICE_") || commandText.startsWith("PAYMENT_")
                || commandText.startsWith("CONFIRM_CREATE_INVOICE") || commandText.startsWith("CANCEL_CREATE_INVOICE")
                || commandText.startsWith("CONFIRM_RECORD_PAYMENT") || commandText.startsWith("CANCEL_RECORD_PAYMENT")) {
            return commandHandlerMap.get("/invoice");
        }
        if (commandText.startsWith("/meter") || commandText.startsWith("/meter_detail_")
                || commandText.startsWith("LANDLORD_METERS") || (commandText.startsWith("MEMBER_METER") || commandText.startsWith("MEMBER_CYCLE_"))
                || commandText.startsWith("METER_") || commandText.startsWith("CONFIRM_CREATE_METER")
                || commandText.startsWith("CANCEL_CREATE_METER") || commandText.startsWith("CONFIRM_UPDATE_METER")
                || commandText.startsWith("CANCEL_UPDATE_METER") || commandText.startsWith("CONFIRM_DELETE_METER_")) {
            return commandHandlerMap.get("/meter");
        }
        if (commandText.startsWith("/contract") || commandText.startsWith("/contract_detail_")
                || commandText.startsWith("LANDLORD_CONTRACTS") || commandText.equals("MEMBER_CONTRACT")
                || commandText.startsWith("CONTRACT_") || commandText.startsWith("CONFIRM_CREATE_CONTRACT")
                || commandText.startsWith("CANCEL_CREATE_CONTRACT") || commandText.startsWith("CONFIRM_UPDATE_CONTRACT")
                || commandText.startsWith("CANCEL_UPDATE_CONTRACT") || commandText.startsWith("CONFIRM_END_CONTRACT_")) {
            return commandHandlerMap.get("/contract");
        }
        if (commandText.startsWith("ROOM_TENANTS_") || commandText.startsWith("ROOM_ASSIGN_")
                || commandText.startsWith("ROOM_MOVE_")) {
            return commandHandlerMap.get("/tenant");
        }
        if (commandText.startsWith("/room") || commandText.startsWith("/room_detail_") || commandText.startsWith("LANDLORD_ROOMS")
                || commandText.startsWith("ROOM_") || (commandText.equals("MEMBER_ROOM") || commandText.startsWith("MEMBER_AVAILABLE_ROOMS"))
                || commandText.equals("CONFIRM_CREATE_ROOM") || commandText.equals("CANCEL_CREATE_ROOM")
                || commandText.equals("CANCEL_ROOM_REMOVE_TENANT")) {
            return commandHandlerMap.get("/room");
        }
        if (commandText.startsWith("/tenant") || commandText.startsWith("/assign") || commandText.startsWith("/remove_tenant")
                || commandText.startsWith("/move") || commandText.startsWith("LANDLORD_TENANTS")
                || commandText.startsWith("TENANT_") || commandText.startsWith("REMOVE_TENANT_")
                || commandText.startsWith("SELECT_MOVE_TARGET_ROOM_") || commandText.equals("CONFIRM_MOVE_TENANT")
                || commandText.equals("CANCEL_MOVE_TENANT")) {
            return commandHandlerMap.get("/tenant");
        }
        if (commandText.startsWith("/menu") || commandText.startsWith("/promote") || commandText.startsWith("/revoke") || commandText.startsWith("/search_users")) {
            return commandHandlerMap.get("/menu");
        }
        return null;
    }

    private Long extractTelegramId(Update update) {
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            return update.getMessage().getFrom().getId();
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
            return update.getCallbackQuery().getFrom().getId();
        }
        return null;
    }

    private String extractUsername(Update update) {
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            return update.getMessage().getFrom().getUserName();
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
            return update.getCallbackQuery().getFrom().getUserName();
        }
        return null;
    }

    private String extractFullName(Update update) {
        org.telegram.telegrambots.meta.api.objects.User from = null;
        if (update.hasMessage()) from = update.getMessage().getFrom();
        else if (update.hasCallbackQuery()) from = update.getCallbackQuery().getFrom();

        if (from != null) {
            String firstName = from.getFirstName() != null ? from.getFirstName() : "";
            String lastName = from.getLastName() != null ? from.getLastName() : "";
            String full = (firstName + " " + lastName).trim();
            return full.isEmpty() ? from.getUserName() : full;
        }
        return "Telegram User";
    }

}
