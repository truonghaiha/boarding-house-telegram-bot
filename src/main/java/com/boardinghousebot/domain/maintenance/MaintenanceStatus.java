package com.boardinghousebot.domain.maintenance;

public enum MaintenanceStatus {
    PENDING("Cần xử lý", "⏳"),
    IN_PROGRESS("Cần xử lý", "⏳"), // Compatibility with existing records only.
    RESOLVED("Đã xử lý", "✅");

    private final String displayName;
    private final String icon;

    MaintenanceStatus(String displayName, String icon) {
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
