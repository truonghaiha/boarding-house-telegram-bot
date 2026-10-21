package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.RoleActionPolicy;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.state.UserConversationState.Step;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoleActionPolicyTest {
    User member = new User(1L, "member", "Member", Role.MEMBER);
    User admin = new User(2L, "admin", "Admin", Role.ADMIN);
    User landlord = new User(3L, "landlord", "Landlord", Role.LANDLORD);

    @Test void memberCannotUseManagementCallbacksOrDirectCommands() {
        for (String command : new String[]{"/room", "/tenant", "/contract", "/meter", "/invoice", "/maintenance"}) {
            assertFalse(RoleActionPolicy.allows(member, command, "LANDLORD_MANAGE", Step.NONE));
        }
        assertFalse(RoleActionPolicy.allows(member, "/invoice", "INVOICE_LIST_ALL", Step.NONE));
        assertFalse(RoleActionPolicy.allows(member, "/invoice", "INVOICE_F_ROOM_ALL", Step.NONE));
        assertFalse(RoleActionPolicy.allows(member, "/maintenance", "MAINTENANCE_LIST_ALL", Step.NONE));
        assertFalse(RoleActionPolicy.allows(member, "/menu", "ADMIN_USERS", Step.NONE));
        assertFalse(RoleActionPolicy.allows(member, "/menu", "/search_users a", Step.NONE));
    }
    @Test void adminCannotUseAnyRentalHandler() {
        for (String command : new String[]{"/room", "/tenant", "/contract", "/meter", "/invoice", "/maintenance"}) {
            assertFalse(RoleActionPolicy.allows(admin, command, command, Step.NONE));
        }
        assertTrue(RoleActionPolicy.allows(admin, "/menu", "ADMIN_STATS", Step.NONE));
        assertFalse(RoleActionPolicy.allows(admin, "/menu", "LANDLORD_STATS", Step.NONE));
    }
    @Test void unfinishedWizardCannotSurviveLosingRole() {
        assertFalse(RoleActionPolicy.allows(member, "/invoice", "10000", Step.RECORD_PAYMENT_AMOUNT));
        assertFalse(RoleActionPolicy.allows(member, "/maintenance", "done", Step.UPDATE_MAINTENANCE_NOTE));
        assertFalse(RoleActionPolicy.allows(landlord, "/maintenance", "WATER", Step.CREATE_MAINTENANCE_TITLE));
        assertTrue(RoleActionPolicy.allows(member, "/maintenance", "WATER", Step.CREATE_MAINTENANCE_TITLE, true));
        assertTrue(RoleActionPolicy.allows(member, "/maintenance", "TAP_BROKEN", Step.CREATE_MAINTENANCE_TITLE, true));
        assertFalse(RoleActionPolicy.allows(member, "/maintenance", "MAINTENANCE_LIST_ALL", Step.CREATE_MAINTENANCE_TITLE));
        assertTrue(RoleActionPolicy.allows(member, "/invoice", "INVOICE_DETAIL_1", Step.NONE));
        assertTrue(RoleActionPolicy.allows(member, "/notification", "NOTIFICATION_LIST_ALL", Step.NONE));
    }
}
