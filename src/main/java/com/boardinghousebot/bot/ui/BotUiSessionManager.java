package com.boardinghousebot.bot.ui;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Request-scoped UI adapter. Event senders never go through this navigation layer. */
public final class BotUiSessionManager {
    private static final Logger log = LoggerFactory.getLogger(BotUiSessionManager.class);
    private static final int TEXT_PAGE_SIZE = 3500;
    private static final int BUTTON_PAGE_SIZE = 40;
    private static final int MAX_HISTORY = 30;
    private final Supplier<TelegramClient> client;
    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();

    public BotUiSessionManager(Supplier<TelegramClient> client) { this.client = client; }

    public Request open(Update update, User user, BotMessageSender events, ConversationStateService states) {
        Long chatId = update.hasCallbackQuery() ? update.getCallbackQuery().getMessage().getChatId() : update.getMessage().getChatId();
        Session session = sessions.computeIfAbsent(new Key(chatId, user.getTelegramId()), key -> new Session());
        return new Request(update, user, chatId, session, events, states);
    }

    private record Key(Long chatId, Long userId) {}
    public record Destination(String action, Map<String, Object> filters) {}
    private record Screen(String action, Map<String, Object> filters, List<String> textPages,
                          InlineKeyboardMarkup keyboard, String parseMode, Role role) {}
    private record VisitedScreen(Screen screen, int page) {}
    private record ViewContext(Screen screen, int page, List<VisitedScreen> history) {}
    private static final class Session {
        boolean waitingForInput;
        Screen screen;
        int page;
        String pendingMenu;
        final Map<Integer, ViewContext> views = new HashMap<>();
        final Deque<VisitedScreen> history = new ArrayDeque<>();
    }

    public final class Request implements BotMessageSender, AutoCloseable {
        private final Update update;
        private final User user;
        private final Long chatId;
        private final Session session;
        private final BotMessageSender events;
        private final ConversationStateService states;
        private String action;
        private boolean replaying;
        private boolean hadNotice;
        private int replayPage;
        private String returnMenu = "MAIN_MENU";

        private Request(Update update, User user, Long chatId, Session session, BotMessageSender events, ConversationStateService states) {
            this.update = update;
            this.user = user;
            this.chatId = chatId;
            this.session = session;
            this.events = events;
            this.states = states;
            action = update.hasCallbackQuery() ? update.getCallbackQuery().getData() : update.getMessage().getText();
        }

        /** Old menus remain usable. Restore only navigation context; handlers check current rights. */
        public boolean acceptsCallback() {
            if (update.hasCallbackQuery() && (navigationAction(action) || "UI_BACK".equals(action) || action.startsWith("UI_PAGE_"))) {
                ViewContext view = session.views.get(update.getCallbackQuery().getMessage().getMessageId());
                if (view != null) {
                    session.screen = view.screen();
                    session.page = view.page();
                    session.history.clear();
                    session.history.addAll(view.history());
                    states.restoreNavigationFilters(user.getTelegramId(), view.screen().filters());
                }
            }
            return true;
        }

        public void newSession() {
            session.screen = null;
            session.history.clear();
            session.pendingMenu = null;
            session.waitingForInput = false;
        }

        public void action(String value) { action = value; }
        public String pendingMenu() { return session.pendingMenu; }

        public void returnMenu(String command) {
            returnMenu = switch (command) {
                case "/room" -> user.getRole() == Role.MEMBER ? "MEMBER_ROOM" : "LANDLORD_ROOMS";
                case "/tenant" -> "LANDLORD_TENANTS";
                case "/contract" -> user.getRole() == Role.MEMBER ? "MEMBER_CONTRACT" : "LANDLORD_CONTRACTS";
                case "/invoice" -> user.getRole() == Role.MEMBER ? "MEMBER_INVOICES" : "LANDLORD_INVOICES";
                case "/meter" -> user.getRole() == Role.MEMBER ? "MEMBER_METERS" : "LANDLORD_METERS";
                case "/maintenance" -> user.getRole() == Role.MEMBER ? "MEMBER_MAINTENANCE" : "LANDLORD_MAINTENANCE";
                case "/notification" -> "NOTIFICATION_MENU";
                default -> user.getRole() == Role.ADMIN ? "ADMIN_USERS" : "MAIN_MENU";
            };
        }

        public Destination back() {
            VisitedScreen previous = session.history.peekLast();
            if (previous == null) return new Destination("MAIN_MENU", Map.of());
            replaying = true;
            replayPage = previous.page();
            return new Destination(previous.screen().action(), previous.screen().filters());
        }

        /** Paging uses an immutable view snapshot and never re-executes a business action. */
        public boolean page(String callback) {
            if (session.screen == null || session.screen.role() != user.getRole()) return false;
            try {
                int page = Integer.parseInt(callback.substring("UI_PAGE_".length()));
                render(session.screen, page);
            } catch (NumberFormatException e) {
                sendNotice(chatId, "Trang không hợp lệ.");
            }
            return true;
        }

        public String screenAction() { return session.screen == null || session.screen.action() == null ? returnMenu : session.screen.action(); }

        @Override
        public void sendMessage(Long targetChatId, String text) {
            if (Objects.equals(targetChatId, chatId) && wizardActive()) {
                sendPrompt(targetChatId, text);
            } else {
                events.sendMessage(targetChatId, text);
            }
        }

        @Override
        public void sendNotice(Long targetChatId, String text) {
            hadNotice = true;
            sendMessage(targetChatId, text);
        }

        @Override
        public void sendPrompt(Long targetChatId, String text) {
            sendPrompt(targetChatId, text, TelegramKeyboardUtil.getCancelKeyboard());
        }

        @Override
        public void sendPrompt(Long targetChatId, String text, InlineKeyboardMarkup keyboard) {
            if (Objects.equals(targetChatId, chatId)) {
                session.waitingForInput = true;
                session.pendingMenu = returnMenu;
            }
            // Prompts keep navigation context and the pending return menu intact.
            events.sendMessageWithKeyboard(targetChatId, text, wizardKeyboard(keyboard));
        }

        @Override
        public void sendResultAndMenu(Long targetChatId, String result, InlineKeyboardMarkup keyboard) {
            events.sendMessage(targetChatId, result);
            action = menuAction();
            sendMessageWithKeyboard(targetChatId, "📌 Chọn thao tác tiếp theo:", keyboard);
        }

        @Override
        public void sendMessageWithKeyboard(Long targetChatId, String text, InlineKeyboardMarkup keyboard) {
            show(targetChatId, text, keyboard, "Markdown");
        }

        @Override
        public void sendOrEditMessageWithKeyboard(Long targetChatId, Integer ignoredMessageId, String text, InlineKeyboardMarkup keyboard) {
            show(targetChatId, text, keyboard, null);
        }

        private void show(Long targetChatId, String text, InlineKeyboardMarkup keyboard, String parseMode) {
            if (!Objects.equals(targetChatId, chatId)) {
                events.sendMessageWithKeyboard(targetChatId, text, keyboard);
                return;
            }
            if (text == null) return;
            Screen previous = session.screen;
            Deque<VisitedScreen> oldHistory = new ArrayDeque<>(session.history);
            int targetPage = replaying ? replayPage : 0;
            String safeAction = navigationAction(action) ? action
                    : action != null && (action.startsWith("CANCEL_") || action.equals("/cancel")) ? returnMenu : null;
            Map<String, Object> filters = states.navigationFilters(user.getTelegramId());
            Screen screen = new Screen(safeAction, filters, textPages(text), copyKeyboard(keyboard), parseMode, user.getRole());
            // Return transitions truncate the trail, rather than growing it on every back press.
            if (replaying) {
                session.history.pollLast();
                replaying = false;
            } else if (previous != null && !sameControls(previous, screen)) {
                // Updating a list/filter stays at the same level. Returning to an ancestor's
                // controls replaces that view instead of treating the return as another child.
                VisitedScreen ancestor = matchingAncestor(screen);
                if (ancestor != null) {
                    while (!session.history.isEmpty()) {
                        if (session.history.removeLast() == ancestor) break;
                    }
                } else if (previous.action() != null) {
                    session.history.addLast(new VisitedScreen(previous, session.page));
                    while (session.history.size() > MAX_HISTORY) session.history.removeFirst();
                }
            }
            if ("MAIN_MENU".equals(action) || "/menu".equals(action) || action != null && action.startsWith("/start")) {
                session.history.clear();
            }
            render(screen, targetPage);
            if (session.screen == previous) {
                session.history.clear();
                session.history.addAll(oldHistory);
            }
        }

        private void render(Screen screen, int requestedPage) {
            int pages = Math.max(screen.textPages().size(), buttonPageCount(screen.keyboard()));
            int page = Math.max(0, Math.min(requestedPage, pages - 1));
            String text = screen.textPages().get(Math.min(page, screen.textPages().size() - 1));
            if (pages > 1) text += "\n\nTrang " + (page + 1) + "/" + pages;
            InlineKeyboardMarkup keyboard = pageKeyboard(screen, page, pages);
            Integer sentMessageId = null;
            if (client.get() == null) {
                log.info("[SIMULATED UI SEND to {}]: {}", chatId, text);
            } else {
                try {
                    var sent = client.get().execute(SendMessage.builder().chatId(chatId.toString()).text(text)
                            .parseMode(screen.parseMode()).replyMarkup(keyboard).build());
                    if (sent != null) sentMessageId = sent.getMessageId();
                } catch (TelegramApiException e) {
                    String error = Optional.ofNullable(e.getMessage()).orElse("").toLowerCase(Locale.ROOT);
                    if (screen.parseMode() != null && (error.contains("can't parse entities") || error.contains("cannot parse entities"))) {
                        render(new Screen(screen.action(), screen.filters(), screen.textPages(), screen.keyboard(), null, screen.role()), page);
                    } else {
                        log.warn("Cannot send UI to {}: {}", chatId, e.getMessage());
                        sendNotice(chatId, "⚠️ Không thể gửi màn hình lúc này. Vui lòng thử lại hoặc gửi /start.");
                    }
                    return;
                }
            }
            session.screen = screen;
            session.page = page;
            session.waitingForInput = wizardActive();
            session.pendingMenu = session.waitingForInput ? returnMenu : null;
            if (sentMessageId != null) {
                // Keep the existing Back/Page context associated with the message that was clicked.
                // This is navigation metadata only: no keyboard is retired or removed.
                session.views.put(sentMessageId, new ViewContext(screen, page, List.copyOf(session.history)));
            }
        }

        private InlineKeyboardMarkup pageKeyboard(Screen screen, int page, int pages) {
            List<InlineKeyboardRow> choices = new ArrayList<>();
            List<InlineKeyboardRow> navigation = new ArrayList<>();
            for (InlineKeyboardRow row : screen.keyboard().getKeyboard()) {
                boolean back = row.stream().anyMatch(button -> isBackButton(button) || isCancelButton(button)
                        || "MAIN_MENU".equals(button.getCallbackData()));
                (back ? navigation : choices).add(new InlineKeyboardRow(row));
            }
            // Short control menus (filters, payment actions, ...) remain usable on every text page.
            int buttonPage = Math.min(page, Math.max(0, (choices.size() - 1) / BUTTON_PAGE_SIZE));
            int start = buttonPage * BUTTON_PAGE_SIZE;
            List<InlineKeyboardRow> rows = new ArrayList<>(choices.subList(start, Math.min(start + BUTTON_PAGE_SIZE, choices.size())));
            if (pages > 1) {
                InlineKeyboardRow row = new InlineKeyboardRow();
                if (page > 0) row.add(TelegramKeyboardUtil.createButton("⬅️ Trang trước", "UI_PAGE_" + (page - 1)));
                if (page + 1 < pages) row.add(TelegramKeyboardUtil.createButton("Trang sau ➡️", "UI_PAGE_" + (page + 1)));
                rows.add(row);
            }
            String screenAction = screen.action();
            boolean hasParentScreen = !session.history.isEmpty() && !isRootScreen(session.history.peekLast().screen());
            boolean contextualBack = (screenAction != null && (screenAction.contains("DETAIL_") || screenAction.contains("detail_")
                    || screenAction.contains("FILTER_SELECT_") || screenAction.startsWith("ROOM_TENANTS_"))) && hasParentScreen;
            if (contextualBack) {
                boolean contractDetail = screenAction.startsWith("CONTRACT_DETAIL_") || screenAction.startsWith("/contract_detail_");
                for (InlineKeyboardRow row : navigation) {
                    for (int i = 0; i < row.size(); i++) {
                        InlineKeyboardButton button = row.get(i);
                        if ((isBackButton(button) || !contractDetail && button.getText().startsWith("📋 Danh sách")) && !"MAIN_MENU".equals(button.getCallbackData())) {
                            row.set(i, TelegramKeyboardUtil.createButton(button.getText(), "UI_BACK"));
                        }
                    }
                }
            }
            rows.addAll(navigation);
            if (wizardActive()) return wizardKeyboard(new InlineKeyboardMarkup(rows));
            boolean invoiceGroups="INVOICE_LIST_LATEST".equals(screenAction) || "INVOICE_LIST_ALL".equals(screenAction)
                    || "INVOICE_LIST_UNPAID".equals(screenAction) || "INVOICE_LIST_PAID".equals(screenAction)
                    || "MEMBER_INVOICES".equals(screenAction) || "MEMBER_INVOICE_LIST".equals(screenAction);
            if (hasParentScreen && !invoiceGroups && rows.stream().flatMap(List::stream)
                    .noneMatch(button -> (isBackButton(button) || button.getText().startsWith("⬅️")
                            && button.getCallbackData()!=null && !button.getCallbackData().startsWith("UI_PAGE_"))
                            && !"MAIN_MENU".equals(button.getCallbackData()))) {
                rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔙 Quay lại", "UI_BACK")));
            }
            boolean root = screen.action() != null && (screen.action().startsWith("/start") || screen.action().equals("/menu") || screen.action().equals("MAIN_MENU"));
            if (!root && rows.stream().flatMap(List::stream).noneMatch(button -> "MAIN_MENU".equals(button.getCallbackData()))) {
                rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔙 Menu chính", "MAIN_MENU")));
            }
            return new InlineKeyboardMarkup(rows);
        }

        private boolean wizardActive() {
            return states.getState(user.getTelegramId()).getStep()
                    != com.boardinghousebot.state.UserConversationState.Step.NONE;
        }

        /** Keep step choices, skip and confirmation controls; cancellation is the only exit. */
        private InlineKeyboardMarkup wizardKeyboard(InlineKeyboardMarkup keyboard) {
            List<InlineKeyboardRow> rows = new ArrayList<>();
            for (InlineKeyboardRow row : copyKeyboard(keyboard).getKeyboard()) {
                InlineKeyboardRow controls = new InlineKeyboardRow();
                for (InlineKeyboardButton button : row) {
                    if (!isBackButton(button) && !"MAIN_MENU".equals(button.getCallbackData())) {
                        controls.add(button);
                    }
                }
                if (!controls.isEmpty()) rows.add(controls);
            }
            if (rows.stream().flatMap(List::stream).noneMatch(BotUiSessionManager::isCancelButton)) {
                rows.addAll(TelegramKeyboardUtil.getCancelKeyboard().getKeyboard());
            }
            return new InlineKeyboardMarkup(rows);
        }

        private String menuAction() {
            return returnMenu;
        }

        private VisitedScreen matchingAncestor(Screen screen) {
            var iterator = session.history.descendingIterator();
            while (iterator.hasNext()) {
                VisitedScreen ancestor = iterator.next();
                if (sameControls(ancestor.screen(), screen)) return ancestor;
            }
            return null;
        }

        @Override
        public void close() {
            if (hadNotice && session.waitingForInput && states.getState(user.getTelegramId()).getStep()
                    == com.boardinghousebot.state.UserConversationState.Step.NONE) {
                // Some business failures end a wizard. Restore safe navigation, never its action buttons.
                if (session.screen != null && session.screen.action() != null && session.screen.role() == user.getRole()) {
                    render(session.screen, session.page);
                } else {
                    var iterator = session.history.descendingIterator();
                    while (iterator.hasNext()) {
                        VisitedScreen previous = iterator.next();
                        if (previous.screen().role() == user.getRole()) {
                            render(previous.screen(), previous.page());
                            break;
                        }
                    }
                }
            }
            if (!update.hasCallbackQuery() || update.getCallbackQuery().getId() == null || client.get() == null) return;
            try {
                client.get().execute(AnswerCallbackQuery.builder().callbackQueryId(update.getCallbackQuery().getId())
                        .build());
            } catch (TelegramApiException e) {
                log.debug("Cannot answer callback: {}", e.getMessage());
            }
        }
    }

    private static boolean isBackButton(InlineKeyboardButton button) {
        String label = button.getText() == null ? "" : button.getText();
        String callback = button.getCallbackData() == null ? "" : button.getCallbackData();
        return label.startsWith("🔙") || callback.equals("UI_BACK");
    }

    private static boolean isCancelButton(InlineKeyboardButton button) {
        String callback = button.getCallbackData();
        return "/cancel".equals(callback) || callback != null && callback.startsWith("CANCEL_");
    }

    private static boolean sameControls(Screen left, Screen right) {
        // Filtering/paging within a floor stays at the same navigation level, even for an empty list.
        if (left.role() == right.role() && left.action() != null && right.action() != null
                && left.action().startsWith("ROOM_FLOOR_") && right.action().startsWith("ROOM_FLOOR_")) return true;
        return left.role() == right.role() && controlActions(left.keyboard()).equals(controlActions(right.keyboard()));
    }

    private static Set<String> controlActions(InlineKeyboardMarkup keyboard) {
        Set<String> actions = new HashSet<>();
        keyboard.getKeyboard().stream().flatMap(List::stream).forEach(button -> {
            String callback = button.getCallbackData();
            if (callback != null && !isBackButton(button) && !"MAIN_MENU".equals(callback)
                    && !callback.startsWith("UI_PAGE_")) {
                // Different room IDs, periods or native page numbers do not create a deeper screen.
                actions.add(callback.replaceAll("_[0-9]+(?=_|$)", "_#"));
            }
        });
        return actions;
    }

    private static boolean isRootScreen(Screen screen) {
        String action = screen.action();
        if (action != null && (action.startsWith("/start") || action.equals("/menu") || action.equals("MAIN_MENU"))) return true;
        Set<String> actions = controlActions(screen.keyboard());
        return actions.equals(controlActions(TelegramKeyboardUtil.getRoleMenu(screen.role())))
                || screen.role() == Role.MEMBER && actions.equals(controlActions(TelegramKeyboardUtil.getUnassignedMemberMenu()));
    }

    private static boolean navigationAction(String action) {
        if (action == null) return false;
        return Set.of("MAIN_MENU", "/menu", "/start", "/help", "/room", "/tenant", "/contract", "/invoice", "/meter", "/maintenance", "/notification", "ACCOUNT_INFO", "ADMIN_USERS", "ADMIN_LIST_USERS",
                "ADMIN_STATS", "ADMIN_AUDIT", "MEMBER_CHECK_STATUS", "MEMBER_CONTACT_INFO",
                "MEMBER_ROOM", "MEMBER_CONTRACT", "MEMBER_INVOICES", "MEMBER_INVOICE_LIST", "MEMBER_METERS",
                "MEMBER_MAINTENANCE", "LANDLORD_ROOMS", "LANDLORD_TENANTS", "LANDLORD_CONTRACTS", "LANDLORD_INVOICES",
                "LANDLORD_METERS", "LANDLORD_MAINTENANCE", "LANDLORD_NOTIFICATIONS", "MEMBER_NOTIFICATIONS",
                "NOTIFICATION_MENU", "MAINTENANCE_MENU", "INVOICE_MENU", "CONTRACT_MENU").contains(action)
                || action.matches("/?[a-zA-Z]+(?:_LIST_.*|_DETAIL_.*|_detail_.*)")
                || action.startsWith("ROOM_FLOOR") || action.startsWith("ROOM_TENANTS_")
                || action.startsWith("METER_CYCLE_LIST_") || action.startsWith("MEMBER_CYCLE_DETAIL_") || action.startsWith("METER_CYCLE_DETAIL_")
                || action.startsWith("MEMBER_AVAILABLE_ROOMS") || action.startsWith("MEMBER_METER_") || action.startsWith("/room_detail_")
                || action.startsWith("INVOICE_F_") || action.startsWith("METER_F_")
                || action.startsWith("/search_users ") || action.startsWith("/start")
                || action.contains("FILTER_SELECT_") || action.endsWith("FILTER_CLEAR");
    }

    private static InlineKeyboardMarkup copyKeyboard(InlineKeyboardMarkup keyboard) {
        if (keyboard == null || keyboard.getKeyboard() == null) return new InlineKeyboardMarkup(List.of());
        return new InlineKeyboardMarkup(keyboard.getKeyboard().stream().map(InlineKeyboardRow::new).toList());
    }

    private static int buttonPageCount(InlineKeyboardMarkup keyboard) {
        long rows = keyboard.getKeyboard().stream().filter(row -> row.stream().noneMatch(BotUiSessionManager::isBackButton)).count();
        return Math.max(1, (int) ((rows + BUTTON_PAGE_SIZE - 1) / BUTTON_PAGE_SIZE));
    }

    private static List<String> textPages(String text) {
        List<String> pages = new ArrayList<>();
        while (text.length() > TEXT_PAGE_SIZE) {
            int split = text.lastIndexOf("\n\n", TEXT_PAGE_SIZE);
            if (split < TEXT_PAGE_SIZE / 2) split = text.lastIndexOf('\n', TEXT_PAGE_SIZE);
            if (split < TEXT_PAGE_SIZE / 2) split = TEXT_PAGE_SIZE;
            if (Character.isHighSurrogate(text.charAt(split - 1))) split--;
            pages.add(text.substring(0, split));
            text = text.substring(split).stripLeading();
        }
        pages.add(text);
        return List.copyOf(pages);
    }
}
