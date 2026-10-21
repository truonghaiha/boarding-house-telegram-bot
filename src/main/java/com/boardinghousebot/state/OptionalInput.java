package com.boardinghousebot.state;

import java.util.Optional;

/** Only existing optional fields can be skipped; preserve their established defaults. */
public final class OptionalInput {
    private OptionalInput() {}

    public static Optional<String> skipValue(UserConversationState.Step step) {
        return switch (step) {
            case CREATE_ROOM_DESC -> Optional.of("/skip");
            default -> Optional.empty();
        };
    }
}
