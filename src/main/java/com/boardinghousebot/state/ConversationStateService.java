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
        if (state == null || state.getStep() == UserConversationState.Step.NONE) {
            stateMap.remove(telegramId);
        } else {
            stateMap.put(telegramId, state);
        }
    }

    public void clearState(Long telegramId) {
        if (telegramId == null) return;
        stateMap.remove(telegramId);
    }
}
