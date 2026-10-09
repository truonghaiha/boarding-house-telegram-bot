package com.boardinghousebot.domain.invoice;

public enum PaymentMethod {
    CASH("Tiền mặt", "💵"),
    BANK_TRANSFER("Chuyển khoản", "💳");

    private final String displayName;
    private final String icon;

    PaymentMethod(String displayName, String icon) {
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
