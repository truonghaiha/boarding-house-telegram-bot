package com.boardinghousebot.domain.meter;

public enum UtilityType {
    ELECTRICITY("Điện", "kWh", "⚡"),
    WATER("Nước", "m³", "💧");

    private final String displayName;
    private final String unit;
    private final String icon;

    UtilityType(String displayName, String unit, String icon) {
        this.displayName = displayName;
        this.unit = unit;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getUnit() {
        return unit;
    }

    public String getIcon() {
        return icon;
    }
}
