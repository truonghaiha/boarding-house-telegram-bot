package com.boardinghousebot.config;

import com.boardinghousebot.service.UserService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Reconcile persisted roles before the bot starts accepting Telegram updates. */
@Component
public class AdminRoleSynchronization {
    private final UserService userService;

    public AdminRoleSynchronization(UserService userService) {
        this.userService = userService;
    }

    @PostConstruct
    public void initialize() {
        userService.synchronizeConfiguredAdmin();
    }
}
