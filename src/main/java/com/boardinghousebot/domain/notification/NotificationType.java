package com.boardinghousebot.domain.notification;

public enum NotificationType {
    MAINTENANCE_CREATED("Yêu cầu bảo trì mới", "🔧"),
    MAINTENANCE_STATUS_CHANGED("Cập nhật bảo trì", "🔄"),
    ROLE_CHANGED("Thay đổi vai trò", "🔔"),
    ROOM_CHANGED("Cập nhật phòng", "🏠"),
    INVOICE_CREATED("Hóa đơn mới", "💰"),
    INVOICE_DUE_REMINDER("Nhắc nhở thanh toán", "⚠️"),
    INVOICE_OVERDUE("Hóa đơn quá hạn", "⏰"),
    CONTRACT_EXPIRING("Hợp đồng sắp hết hạn", "📜"),
    CONTRACT_CREATED("Hợp đồng mới", "📄"),
    CONTRACT_ENDED("Hợp đồng đã kết thúc", "📄"),
    ELECTRICITY_CREATED("Chỉ số điện mới", "⚡"),
    WATER_CREATED("Chỉ số nước mới", "💧"),
    UTILITY_CYCLE_FINALIZED("Chốt điện nước", "⚡"),
    UTILITY_RATE_CHANGED("Thay đổi giá điện nước", "🔔"),
    PAYMENT_RECORDED("Thanh toán hóa đơn", "💳");

    private final String displayName;
    private final String icon;

    NotificationType(String displayName, String icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIcon() {
        return icon;
    }
}
