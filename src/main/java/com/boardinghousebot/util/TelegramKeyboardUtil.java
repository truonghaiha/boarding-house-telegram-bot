package com.boardinghousebot.util;

import com.boardinghousebot.domain.user.Role;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

public class TelegramKeyboardUtil {

    public static InlineKeyboardMarkup getRoleMenu(Role role) {
        if (role == null) {
            return getMemberMenu();
        }
        return switch (role) {
            case ADMIN -> getAdminMenu();
            case LANDLORD -> getLandlordMenu();
            case MEMBER -> getMemberMenu();
        };
    }

    public static InlineKeyboardMarkup getAdminMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("👥 Quản lý người dùng", "ADMIN_USERS"),
                createButton("📊 Thống kê", "ADMIN_STATS")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("📋 Nhật ký hệ thống", "ADMIN_AUDIT"),
                createButton("👤 Tài khoản", "ACCOUNT_INFO")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("🏠 Quản lý phòng", "LANDLORD_ROOMS"),
                createButton("👥 Người thuê", "LANDLORD_TENANTS")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("📄 Hợp đồng", "LANDLORD_CONTRACTS"),
                createButton("⚡ Điện nước", "LANDLORD_METERS")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("💰 Hóa đơn", "LANDLORD_INVOICES"),
                createButton("🔧 Bảo trì", "LANDLORD_MAINTENANCE")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔔 Thông báo", "LANDLORD_NOTIFICATIONS"),
                createButton("📊 Thống kê", "LANDLORD_STATS")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("👤 Tài khoản", "ACCOUNT_INFO")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMemberMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("🏠 Phòng của tôi", "MEMBER_ROOM"),
                createButton("📄 Hợp đồng", "MEMBER_CONTRACT")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("⚡ Điện nước", "MEMBER_METERS"),
                createButton("💰 Hóa đơn", "MEMBER_INVOICES")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔧 Báo sự cố", "MEMBER_MAINTENANCE"),
                createButton("🔔 Thông báo", "MEMBER_NOTIFICATIONS")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("👤 Tài khoản", "ACCOUNT_INFO")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getAdminUserManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("📋 Danh sách người dùng", "ADMIN_LIST_USERS"),
                createButton("🔎 Tìm kiếm", "ADMIN_SEARCH_USERS")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("⬆️ Cấp quyền LANDLORD", "ADMIN_PROMOTE_INPUT"),
                createButton("⬇️ Thu hồi LANDLORD", "ADMIN_REVOKE_INPUT")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordRoomManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("➕ Thêm phòng", "ROOM_ADD"),
                createButton("📋 Danh sách phòng", "ROOM_LIST_ALL")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🟢 Phòng còn chỗ", "ROOM_LIST_AVAILABLE"),
                createButton("🔴 Phòng đang ở", "ROOM_LIST_OCCUPIED")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getRoomDetailKeyboard(Long roomId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("👥 Người thuê", "ROOM_TENANTS_" + roomId),
                createButton("➕ Thêm người thuê", "ROOM_ASSIGN_" + roomId)
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🚚 Chuyển phòng", "ROOM_MOVE_" + roomId),
                createButton("❌ Xóa phòng", "ROOM_DELETE_" + roomId)
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Danh sách phòng", "ROOM_LIST_ALL")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordTenantManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("➕ Thêm người thuê", "TENANT_ADD_INPUT"),
                createButton("➖ Xóa người thuê", "TENANT_REMOVE_INPUT")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔄 Chuyển phòng", "TENANT_MOVE_INPUT"),
                createButton("📋 Danh sách người thuê", "TENANT_LIST_ALL")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordContractManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("➕ Tạo hợp đồng", "CONTRACT_ADD"),
                createButton("📋 Danh sách hợp đồng", "CONTRACT_LIST_ALL")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🟢 Đang hiệu lực", "CONTRACT_LIST_ACTIVE"),
                createButton("⚫ Đã kết thúc", "CONTRACT_LIST_ENDED")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("⏰ Sắp hết hạn", "CONTRACT_LIST_EXPIRING"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getContractDetailKeyboard(Long contractId, boolean isEnded) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (!isEnded) {
            rows.add(new InlineKeyboardRow(
                    createButton("✏️ Sửa hợp đồng", "CONTRACT_EDIT_" + contractId),
                    createButton("⛔ Kết thúc hợp đồng", "CONTRACT_END_" + contractId)
            ));
        }
        rows.add(new InlineKeyboardRow(
                createButton("📋 Danh sách hợp đồng", "CONTRACT_LIST_ALL"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordMeterManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("➕ Ghi chỉ số", "METER_ADD"),
                createButton("📋 Danh sách chỉ số", "METER_LIST_LATEST")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMeterFilterKeyboard(Long roomIdFilter, String utilityTypeFilter, Integer monthFilter, Integer yearFilter) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("📅 Chọn tháng", "METER_FILTER_SELECT_MONTH"),
                createButton("🏠 Chọn phòng", "METER_FILTER_SELECT_ROOM")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("⚡ Lọc loại", "METER_FILTER_SELECT_TYPE"),
                createButton("🔄 Làm mới", "METER_FILTER_CLEAR")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMeterDetailKeyboard(Long readingId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("✏️ Sửa", "METER_EDIT_" + readingId),
                createButton("🗑️ Xóa", "METER_DELETE_" + readingId)
        ));
        rows.add(new InlineKeyboardRow(
                createButton("📋 Danh sách điện nước", "METER_LIST_ALL"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMemberMeterMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("⚡ Điện", "MEMBER_METER_ELECTRICITY"),
                createButton("💧 Nước", "MEMBER_METER_WATER")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("📋 Xem tất cả", "MEMBER_METER_ALL"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordInvoiceManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("➕ Tạo hóa đơn", "INVOICE_ADD"),
                createButton("📋 Danh sách hóa đơn", "INVOICE_LIST_LATEST")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("💳 Ghi nhận thanh toán", "INVOICE_PAYMENT_SELECT"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMemberInvoiceMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getInvoiceFilterKeyboard(Long roomIdFilter, String statusFilter, Integer monthFilter, Integer yearFilter) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("📅 Chọn tháng", "INVOICE_FILTER_SELECT_MONTH"),
                createButton("🏠 Chọn phòng", "INVOICE_FILTER_SELECT_ROOM")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("📊 Trạng thái", "INVOICE_FILTER_SELECT_STATUS"),
                createButton("🔄 Làm mới", "INVOICE_FILTER_CLEAR")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getInvoiceDetailKeyboard(Long invoiceId, boolean isPaid) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (!isPaid) {
            rows.add(new InlineKeyboardRow(
                    createButton("💳 Ghi nhận thanh toán", "INVOICE_PAYMENT_INIT_" + invoiceId)
            ));
        }
        rows.add(new InlineKeyboardRow(
                createButton("📋 Danh sách hóa đơn", "INVOICE_LIST_LATEST"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordMaintenanceManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("📋 Tất cả sự cố", "MAINTENANCE_LIST_ALL"),
                createButton("⏳ Chờ xử lý", "MAINTENANCE_LIST_PENDING")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("⚙️ Đang xử lý", "MAINTENANCE_LIST_IN_PROGRESS"),
                createButton("✅ Đã hoàn thành", "MAINTENANCE_LIST_RESOLVED")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMaintenanceDetailKeyboard(Long requestId, com.boardinghousebot.domain.maintenance.MaintenanceStatus status) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (status == com.boardinghousebot.domain.maintenance.MaintenanceStatus.PENDING) {
            rows.add(new InlineKeyboardRow(
                    createButton("⚙️ Đang xử lý", "MAINTENANCE_UPDATE_IN_PROGRESS_" + requestId),
                    createButton("✅ Hoàn thành", "MAINTENANCE_UPDATE_RESOLVED_" + requestId)
            ));
        } else if (status == com.boardinghousebot.domain.maintenance.MaintenanceStatus.IN_PROGRESS) {
            rows.add(new InlineKeyboardRow(
                    createButton("✅ Hoàn thành", "MAINTENANCE_UPDATE_RESOLVED_" + requestId)
            ));
        }

        rows.add(new InlineKeyboardRow(
                createButton("📋 Danh sách sự cố", "MAINTENANCE_LIST_ALL"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMemberMaintenanceMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("➕ Báo sự cố mới", "MAINTENANCE_ADD"),
                createButton("📋 Lịch sử sự cố", "MAINTENANCE_LIST_MEMBER")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getNotificationMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("📋 Tất cả thông báo", "NOTIFICATION_LIST_ALL"),
                createButton("📩 Chưa đọc", "NOTIFICATION_LIST_UNREAD")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("✅ Đọc tất cả", "NOTIFICATION_MARK_ALL_READ"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getConfirmKeyboard(String confirmData, String cancelData) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("✅ Xác nhận", confirmData),
                createButton("❌ Hủy", cancelData)
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardButton createButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton(text);
        button.setCallbackData(callbackData);
        return button;
    }
}
