package com.boardinghousebot.util;

import com.boardinghousebot.domain.user.Role;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

public class TelegramKeyboardUtil {

    public static InlineKeyboardMarkup getCancelKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                new InlineKeyboardRow(createButton("❌ Hủy", "/cancel"))));
    }

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
        rows.add(new InlineKeyboardRow(createButton("🔔 Thông báo cá nhân", "NOTIFICATION_LIST_ALL")));

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
                createButton("👤 Tài khoản", "ACCOUNT_INFO")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMemberMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(createButton("🏘️ Phòng còn chỗ", "MEMBER_AVAILABLE_ROOMS")));

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
                createButton("👤 Tài khoản", "ACCOUNT_INFO"),
                createButton("📞 Thông tin liên hệ chủ trọ", "MEMBER_CONTACT_INFO")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getUnassignedMemberMenu() {
        return new InlineKeyboardMarkup(List.of(
                new InlineKeyboardRow(createButton("🏘️ Phòng còn chỗ", "MEMBER_AVAILABLE_ROOMS")),
                new InlineKeyboardRow(
                        createButton("👤 Tài khoản", "ACCOUNT_INFO"),
                        createButton("🔄 Kiểm tra trạng thái", "MEMBER_CHECK_STATUS")
                ),
                new InlineKeyboardRow(
                        createButton("📞 Thông tin liên hệ chủ trọ", "MEMBER_CONTACT_INFO")
                )
        ));
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

    public static InlineKeyboardMarkup getAdminRoleChangePromptKeyboard() {
        return getCancelKeyboard();
    }

    public static InlineKeyboardMarkup getLandlordRoomManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("➕ Thêm phòng", "ROOM_ADD"),
                createButton("📋 Danh sách phòng", "ROOM_LIST_ALL")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getRoomListKeyboard(List<com.boardinghousebot.domain.room.Room> rooms) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (var room : rooms) {
            rows.add(new InlineKeyboardRow(
                    createButton("🏠 Chi tiết phòng " + room.getRoomNumber(), "ROOM_DETAIL_" + room.getId())
            ));
        }
        rows.addAll(getLandlordRoomManagementMenu().getKeyboard());
        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getRoomDetailKeyboard(Long roomId) {
        return getRoomDetailKeyboard(roomId, "ROOM_LIST_ALL");
    }

    public static InlineKeyboardMarkup getFloorListKeyboard(java.util.Map<String, Integer> floorCounts,
                                                           int page, int totalPages) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        floorCounts.forEach((floor, count) -> rows.add(new InlineKeyboardRow(
                createButton("🏢 " + RoomFloorUtil.label(floor) + " (" + count + " phòng)",
                        "ROOM_FLOOR_" + floor + "_0"))));
        addRoomPageButtons(rows, "ROOM_FLOORS_", page, totalPages);
        rows.add(new InlineKeyboardRow(createButton("🔙 Quản lý phòng", "LANDLORD_ROOMS")));
        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getFloorRoomsKeyboard(List<com.boardinghousebot.domain.room.Room> rooms,
            String floor, int page, int totalPages, int floorListPage) {
        return getFloorRoomsKeyboard(rooms, floor, page, totalPages, floorListPage, "ALL");
    }

    public static InlineKeyboardMarkup getFloorRoomsKeyboard(List<com.boardinghousebot.domain.room.Room> rooms,
            String floor, int page, int totalPages, int floorListPage, String filter) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                createButton(("ALL".equals(filter) ? "✓ " : "") + "📋 Tất cả", "ROOM_FLOOR_" + floor + "_0_ALL"),
                createButton(("AVAILABLE".equals(filter) ? "✓ " : "") + "🟢 Còn chỗ", "ROOM_FLOOR_" + floor + "_0_AVAILABLE"),
                createButton(("FULL".equals(filter) ? "✓ " : "") + "🔴 Đã đầy", "ROOM_FLOOR_" + floor + "_0_FULL")));
        String suffix = "ALL".equals(filter) ? "" : "_" + filter;
        for (var room : rooms) {
            if (rows.size() == 1 || rows.get(rows.size() - 1).size() == 2) {
                rows.add(new InlineKeyboardRow());
            }
            rows.get(rows.size() - 1).add(createButton("🏠 Phòng " + room.getRoomNumber(),
                    "ROOM_DETAIL_" + room.getId() + "_F_" + floor + "_" + page + suffix));
        }
        addRoomPageButtons(rows, "ROOM_FLOOR_" + floor + "_", page, totalPages, suffix);
        rows.add(new InlineKeyboardRow(createButton("🔙 Danh sách tầng", "ROOM_FLOORS_" + floorListPage)));
        return new InlineKeyboardMarkup(rows);
    }

    private static void addRoomPageButtons(List<InlineKeyboardRow> rows, String prefix, int page, int totalPages) {
        addRoomPageButtons(rows, prefix, page, totalPages, "");
    }

    private static void addRoomPageButtons(List<InlineKeyboardRow> rows, String prefix, int page, int totalPages, String suffix) {
        InlineKeyboardRow navigation = new InlineKeyboardRow();
        if (page > 0) navigation.add(createButton("⬅️ Trang trước", prefix + (page - 1) + suffix));
        if (page + 1 < totalPages) navigation.add(createButton("Trang sau ➡️", prefix + (page + 1) + suffix));
        if (!navigation.isEmpty()) rows.add(navigation);
    }

    public static InlineKeyboardMarkup getRoomDetailKeyboard(Long roomId, String backCallback) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("👥 Người thuê", "ROOM_TENANTS_" + roomId),
                createButton("🚚 Chuyển phòng", "ROOM_MOVE_" + roomId)
        ));
        rows.add(new InlineKeyboardRow(
                createButton("➕ Thêm người thuê", "ROOM_ASSIGN_" + roomId),
                createButton("➖ Xóa người thuê", "ROOM_REMOVE_TENANTS_" + roomId)
        ));
        rows.add(new InlineKeyboardRow(createButton("💰 Sửa giá phòng", "ROOM_EDIT_RENT_" + roomId),
                createButton("✏️ Sửa mô tả", "ROOM_EDIT_DESC_" + roomId)));
        rows.add(new InlineKeyboardRow(createButton("📐 Sửa diện tích", "ROOM_EDIT_AREA_" + roomId),
                createButton("💵 Sửa tiền cọc", "ROOM_EDIT_DEPOSIT_" + roomId)));
        rows.add(new InlineKeyboardRow(createButton("❌ Xóa phòng", "ROOM_DELETE_" + roomId)));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Danh sách phòng", backCallback)
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
        rows.add(new InlineKeyboardRow(createButton("✏️ Sửa thông tin người thuê", "TENANT_EDIT_INPUT")));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordContractManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("➕ Tạo hợp đồng", "CONTRACT_ADD")
        ));
        rows.add(new InlineKeyboardRow(createButton("📋 Danh sách tất cả hợp đồng", "CONTRACT_LIST_ALL")));
        rows.add(new InlineKeyboardRow(
                createButton("🟢 Đang hiệu lực", "CONTRACT_LIST_ACTIVE"),
                createButton("⚫ Đã kết thúc", "CONTRACT_LIST_ENDED")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("⏰ Sắp hết hạn", "CONTRACT_LIST_EXPIRING")
        ));
        rows.add(new InlineKeyboardRow(createButton("⬅️ Menu chính", "MAIN_MENU")));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getContractListKeyboard(List<com.boardinghousebot.domain.contract.Contract> contracts) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (var contract : contracts) {
            if (rows.isEmpty() || rows.get(rows.size() - 1).size() == 2) rows.add(new InlineKeyboardRow());
            rows.get(rows.size() - 1).add(createButton("📄 HĐ #" + contract.getId() + " – P" + contract.getRoom().getRoomNumber(),
                    "CONTRACT_DETAIL_" + contract.getId()));
        }
        rows.addAll(getBackKeyboard("LANDLORD_CONTRACTS").getKeyboard());
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
                createButton("🔙 Quay lại", "CONTRACT_LIST_ALL"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordMeterManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(createButton("⚡ Giá điện cả tòa", "METER_RATE_ELECTRICITY"),
                createButton("💧 Giá nước cả tòa", "METER_RATE_WATER")));

        rows.add(new InlineKeyboardRow(
                createButton("✏️ Ghi số & chốt tiền", "METER_ADD"),
                createButton("📋 Danh sách chỉ số", "METER_LIST_LATEST")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMeterFilterKeyboard() {
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
                createButton("📅 Phòng sắp đến kỳ", "INVOICE_LIST_DUE"),
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

    public static InlineKeyboardMarkup getInvoiceDetailKeyboard(Long invoiceId, boolean isPaid) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (!isPaid) {
            rows.add(new InlineKeyboardRow(
                    createButton("💳 Ghi nhận thanh toán", "INVOICE_PAYMENT_INIT_" + invoiceId)
            ));
        }
        rows.add(new InlineKeyboardRow(
                createButton("📋 Danh sách hóa đơn", isPaid ? "INVOICE_LIST_PAID" : "INVOICE_LIST_UNPAID"),
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getLandlordMaintenanceManagementMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("📋 Cần xử lý", "MAINTENANCE_LIST_ALL"),
                createButton("✅ Đã xử lý", "MAINTENANCE_LIST_RESOLVED")
        ));
        rows.add(new InlineKeyboardRow(
                createButton("🔙 Menu chính", "MAIN_MENU")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getMaintenanceDetailKeyboard(Long requestId, com.boardinghousebot.domain.maintenance.MaintenanceStatus status) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (status != com.boardinghousebot.domain.maintenance.MaintenanceStatus.RESOLVED) {
            rows.add(new InlineKeyboardRow(
                    createButton("✅ Hoàn thành", "MAINTENANCE_UPDATE_RESOLVED_" + requestId)
            ));
        }

        rows.add(new InlineKeyboardRow(
                createButton("⬅️ Danh sách sự cố", status == com.boardinghousebot.domain.maintenance.MaintenanceStatus.RESOLVED
                        ? "MAINTENANCE_LIST_RESOLVED" : "MAINTENANCE_LIST_ALL")
        ));

        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getNotificationMenu() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(
                createButton("📩 Chưa đọc", "NOTIFICATION_LIST_UNREAD"),
                createButton("✅ Đã đọc", "NOTIFICATION_LIST_READ")
        ));
        rows.add(new InlineKeyboardRow(
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

    public static InlineKeyboardMarkup getBackKeyboard(String callback) {
        return new InlineKeyboardMarkup(List.of(
                new InlineKeyboardRow(createButton("🔙 Quay lại", callback)),
                new InlineKeyboardRow(createButton("🔙 Menu chính", "MAIN_MENU"))));
    }

    public static <T> InlineKeyboardMarkup getDetailListKeyboard(List<T> items, java.util.function.Function<T, String> label,
            java.util.function.Function<T, String> callback, InlineKeyboardMarkup menu) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (T item : items) {
            if (rows.isEmpty() || rows.get(rows.size() - 1).size() == 2) rows.add(new InlineKeyboardRow());
            rows.get(rows.size() - 1).add(createButton(label.apply(item), callback.apply(item)));
        }
        rows.addAll(menu.getKeyboard());
        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup getOptionalInputKeyboard(com.boardinghousebot.state.UserConversationState.Step step) {
        if (com.boardinghousebot.state.OptionalInput.skipValue(step).isEmpty())
            throw new IllegalArgumentException("This input step is required.");
        return new InlineKeyboardMarkup(List.of(new InlineKeyboardRow(
                createButton("⏭️ Bỏ qua", "SKIP_INPUT_" + step.name()), createButton("❌ Hủy", "/cancel"))));
    }

    public static InlineKeyboardButton createButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton(text);
        button.setCallbackData(callbackData);
        return button;
    }
}
