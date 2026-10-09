package com.boardinghousebot.observer;

import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;

public class RoleChangedEvent {

    private final User user;
    private final Role oldRole;
    private final Role newRole;

    public RoleChangedEvent(User user, Role oldRole, Role newRole) {
        this.user = user;
        this.oldRole = oldRole;
        this.newRole = newRole;
    }

    public User getUser() {
        return user;
    }

    public Role getOldRole() {
        return oldRole;
    }

    public Role getNewRole() {
        return newRole;
    }
}
