package com.boardinghousebot.bot.command;

import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.state.UserConversationState.Step;

import java.util.Set;

/** Shared entry policy for commands, callbacks and unfinished conversations. */
public final class RoleActionPolicy {
    private RoleActionPolicy() {}

    public static boolean allows(User user, String command, String action, Step step) {
        return allows(user, command, action, step, false);
    }

    public static boolean allows(User user, String command, String action, Step step, boolean textInput) {
        if (user == null || user.getRole() == null || action == null) return false;
        Role role = user.getRole();
        if (command.equals("/notification")) return true; // Always scoped to the recipient.
        if (command.equals("/menu")) {
            if (action.startsWith("ADMIN_") || action.startsWith("/promote")
                    || action.startsWith("/revoke") || action.startsWith("/search_users")) return role == Role.ADMIN;
            if (action.equals("LANDLORD_STATS")) return false; // Retired button in older messages.
            return true;
        }
        if (role == Role.ADMIN) return false;
        boolean creatingMaintenance = step != null && step.name().startsWith("CREATE_MAINTENANCE");
        if (step != null && step != Step.NONE) {
            if (command.equals("/maintenance")) {
                if (creatingMaintenance && role != Role.MEMBER) return false;
                if (!creatingMaintenance && role != Role.LANDLORD) return false;
            } else if (role != Role.LANDLORD) return false;
            // Text input is permitted only for the role owning the active wizard.
            if (textInput && !action.startsWith("/")) return true;
        }
        if (role == Role.LANDLORD) {
            return !action.startsWith("MEMBER_") && !action.equals("MAINTENANCE_ADD")
                    && !action.equals("MAINTENANCE_LIST_MEMBER");
        }
        return switch (command) {
            case "/room" -> Set.of("/room", "MEMBER_ROOM", "MEMBER_AVAILABLE_ROOMS", "/cancel", "❌ Hủy").contains(action)
                    || action.matches("MEMBER_AVAILABLE_ROOMS_[0-9]+");
            case "/contract" -> Set.of("/contract", "MEMBER_CONTRACT", "/cancel").contains(action)
                    || action.startsWith("CONTRACT_DETAIL_") || action.startsWith("/contract_detail_");
            case "/meter" -> Set.of("/meter", "MEMBER_METERS", "MEMBER_METER_ALL", "MEMBER_METER_ELECTRICITY", "MEMBER_METER_WATER", "/cancel").contains(action)
                    || action.matches("MEMBER_CYCLE_DETAIL_[0-9]+") || action.equals("MEMBER_METER_LEGACY") || action.startsWith("METER_DETAIL_") || action.startsWith("/meter_detail_");
            case "/invoice" -> Set.of("/invoice", "MEMBER_INVOICES", "MEMBER_INVOICE_LIST", "INVOICE_LIST_LATEST", "INVOICE_LIST_PAID", "INVOICE_LIST_UNPAID", "/cancel").contains(action)
                    || action.startsWith("INVOICE_DETAIL_") || action.startsWith("/invoice_detail_");
            case "/maintenance" -> Set.of("/maintenance", "MEMBER_MAINTENANCE", "MAINTENANCE_MENU", "MAINTENANCE_ADD", "MAINTENANCE_LIST_MEMBER", "/cancel", "CANCEL_MAINTENANCE").contains(action)
                    || action.startsWith("MAINTENANCE_DETAIL_") || action.startsWith("/maintenance_detail_");
            default -> false;
        };
    }
}
