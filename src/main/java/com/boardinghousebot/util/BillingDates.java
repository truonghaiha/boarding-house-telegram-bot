package com.boardinghousebot.util;
import java.time.*;
public final class BillingDates {
    private BillingDates() {}
    public static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")); }
    public static LocalDate next(LocalDate anchor, LocalDate after) {
        if (anchor == null || after == null || after.isBefore(anchor)) throw new IllegalArgumentException("Invalid billing anchor");
        long months = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(after));
        LocalDate next = anchor.plusMonths(months);
        return next.isAfter(after) ? next : anchor.plusMonths(months + 1);
    }
    public static LocalDate due(LocalDate anchor, int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        return ym.atDay(Math.min(anchor.getDayOfMonth(), ym.lengthOfMonth()));
    }
}
