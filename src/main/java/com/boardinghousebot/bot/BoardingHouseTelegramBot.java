package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
public class BoardingHouseTelegramBot implements LongPollingSingleThreadUpdateConsumer, BotMessageSender {

    private static final Logger log = LoggerFactory.getLogger(BoardingHouseTelegramBot.class);

    private final TelegramConfig telegramConfig;
    private final UserService userService;
    private final ConversationStateService stateService;
    private final Map<String, CommandHandler> commandHandlerMap = new HashMap<>();
    private TelegramClient telegramClient;
    private TelegramBotsLongPollingApplication botsApplication;

    public BoardingHouseTelegramBot(TelegramConfig telegramConfig,
                                  UserService userService,
                                  ConversationStateService stateService,
                                  List<CommandHandler> commandHandlers) {
        this.telegramConfig = telegramConfig;
        this.userService = userService;
        this.stateService = stateService;
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
        try {
            if (update.hasCallbackQuery() && update.getCallbackQuery().getId() != null) {
                answerCallbackQuery(update.getCallbackQuery().getId());
            }

            Long telegramId = extractTelegramId(update);
            if (telegramId == null) {
                return;
            }

            String username = extractUsername(update);
            String fullName = extractFullName(update);

            User user = userService.getOrCreateUser(telegramId, username, fullName);

            String commandText = extractCommandOrAction(update);
            CommandHandler handler = findHandler(commandText, telegramId);

            if (handler != null) {
                handler.handle(update, user, this);
            } else {
                CommandHandler menuHandler = commandHandlerMap.get("/menu");
                if (menuHandler != null) {
                    menuHandler.handle(update, user, this);
                }
            }
        } catch (BusinessException e) {
            log.warn("Business exception during update handling: {}", e.getMessage());
            Long chatId = extractChatId(update);
            if (chatId != null) {
                sendMessage(chatId, "❌ " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error handling update", e);
            Long chatId = extractChatId(update);
            if (chatId != null) {
                sendMessage(chatId, "❌ Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.");
            }
        }
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

    private CommandHandler findHandler(String commandText, Long telegramId) {
        if (telegramId != null && stateService != null) {
            UserConversationState state = stateService.getState(telegramId);
            if (state.getStep() != UserConversationState.Step.NONE) {
                log.info("STATE ROUTING: telegramId={}, step={}, input={}", telegramId, state.getStep(), commandText);
                String stepName = state.getStep().name();
                if (stepName.startsWith("CREATE_CONTRACT") || stepName.startsWith("UPDATE_CONTRACT")) {
                    return commandHandlerMap.get("/contract");
                } else if (stepName.startsWith("CREATE_METER") || stepName.startsWith("UPDATE_METER")) {
                    return commandHandlerMap.get("/meter");
                } else if (stepName.startsWith("CREATE_INVOICE") || stepName.startsWith("RECORD_PAYMENT")) {
                    return commandHandlerMap.get("/invoice");
                } else if (stepName.startsWith("CREATE_MAINTENANCE") || stepName.startsWith("UPDATE_MAINTENANCE")) {
                    return commandHandlerMap.get("/maintenance");
                } else if (stepName.startsWith("ASSIGN_TENANT") || stepName.startsWith("MOVE_TENANT")) {
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
                || commandText.startsWith("LANDLORD_INVOICES") || commandText.equals("MEMBER_INVOICES")
                || commandText.startsWith("INVOICE_") || commandText.startsWith("PAYMENT_")
                || commandText.startsWith("CONFIRM_CREATE_INVOICE") || commandText.startsWith("CANCEL_CREATE_INVOICE")
                || commandText.startsWith("CONFIRM_RECORD_PAYMENT") || commandText.startsWith("CANCEL_RECORD_PAYMENT")) {
            return commandHandlerMap.get("/invoice");
        }
        if (commandText.startsWith("/meter") || commandText.startsWith("/meter_detail_")
                || commandText.startsWith("LANDLORD_METERS") || commandText.startsWith("MEMBER_METER")
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
        if (commandText.startsWith("/room") || commandText.startsWith("/room_detail_") || commandText.startsWith("LANDLORD_ROOMS")
                || commandText.startsWith("ROOM_") || commandText.equals("MEMBER_ROOM")
                || commandText.equals("CONFIRM_CREATE_ROOM") || commandText.equals("CANCEL_CREATE_ROOM")) {
            return commandHandlerMap.get("/room");
        }
        if (commandText.startsWith("/tenant") || commandText.startsWith("/assign") || commandText.startsWith("/remove_tenant")
                || commandText.startsWith("/move") || commandText.startsWith("LANDLORD_TENANTS")
                || commandText.startsWith("TENANT_") || commandText.startsWith("REMOVE_TENANT_")) {
            return commandHandlerMap.get("/tenant");
        }
        if (commandText.startsWith("/menu") || commandText.startsWith("/promote") || commandText.startsWith("/revoke")) {
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

    private Long extractChatId(Update update) {
        if (update.hasMessage()) return update.getMessage().getChatId();
        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return null;
    }

    private String extractCommandOrAction(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            return update.getMessage().getText();
        }
        if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getData();
        }
        return null;
    }
}
