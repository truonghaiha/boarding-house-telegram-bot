package com.boardinghousebot.domain.invoice;

public enum InvoiceStatus {
    UNPAID("Chưa thanh toán", "🔴"),
    PARTIALLY_PAID("Thanh toán một phần", "🟡"),
    PAID("Đã thanh toán", "🟢"),
    OVERDUE("Quá hạn", "⏰");

    private final String displayName;
    private final String icon;

    InvoiceStatus(String displayName, String icon) {
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
