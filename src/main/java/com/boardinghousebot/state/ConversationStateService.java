package com.boardinghousebot.state;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationStateService {

    private final Map<Long, UserConversationState> stateMap = new ConcurrentHashMap<>();

    public UserConversationState getState(Long telegramId) {
        if (telegramId == null) {
            return new UserConversationState(UserConversationState.Step.NONE);
        }
        return stateMap.computeIfAbsent(telegramId, k -> new UserConversationState(UserConversationState.Step.NONE));
    }

    public void setState(Long telegramId, UserConversationState state) {
        if (telegramId == null) return;
        if (state == null || (state.getStep() == UserConversationState.Step.NONE && state.getData().isEmpty())) {
            stateMap.remove(telegramId);
        } else {
            stateMap.put(telegramId, state);
        }
    }

    public void clearState(Long telegramId) {
        if (telegramId == null) return;
        stateMap.remove(telegramId);
    }

    /** Only view filters are restored on Back; wizard fields and steps are never replayed. */
    public Map<String, Object> navigationFilters(Long telegramId) {
        Map<String, Object> filters = new java.util.HashMap<>();
        getState(telegramId).getData().forEach((key, value) -> {
            if (key.startsWith("invoiceFilter") || key.startsWith("meterFilter")) filters.put(key, value);
        });
        return Map.copyOf(filters);
    }

    public void restoreNavigationFilters(Long telegramId, Map<String, Object> filters) {
        UserConversationState state = getState(telegramId);
        state.getData().keySet().removeIf(key -> key.startsWith("invoiceFilter") || key.startsWith("meterFilter"));
        state.getData().putAll(filters);
        setState(telegramId, state);
    }
}
