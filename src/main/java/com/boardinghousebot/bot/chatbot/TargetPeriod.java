package com.boardinghousebot.bot.chatbot;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Đại diện cho Kỳ tra cứu thời gian (Target Period) được trích xuất từ câu hỏi của MEMBER.
 */
public class TargetPeriod {

    public enum PeriodType {
        EXPLICIT,         // Tháng/năm cụ thể (ví dụ: "tháng 8", "08/2026")
        CURRENT_MONTH,    // "tháng này", "tháng hiện tại"
        PREVIOUS_MONTH,   // "tháng trước", "tháng vừa rồi"
        LATEST_RECORDED   // Không chỉ định tháng -> Lấy kỳ ghi nhận mới nhất trong DB
    }

    private final PeriodType type;
    private final Integer month;
    private final Integer year;

    public TargetPeriod(PeriodType type, Integer month, Integer year) {
        this.type = type != null ? type : PeriodType.LATEST_RECORDED;
        this.month = month;
        this.year = year;
    }

    public static TargetPeriod explicit(int month, int year) {
        return new TargetPeriod(PeriodType.EXPLICIT, month, year);
    }

    public static TargetPeriod currentMonth() {
        LocalDate now = LocalDate.now();
        return new TargetPeriod(PeriodType.CURRENT_MONTH, now.getMonthValue(), now.getYear());
    }

    public static TargetPeriod previousMonth() {
        YearMonth prev = YearMonth.now().minusMonths(1);
        return new TargetPeriod(PeriodType.PREVIOUS_MONTH, prev.getMonthValue(), prev.getYear());
    }

    public static TargetPeriod latestRecorded() {
        return new TargetPeriod(PeriodType.LATEST_RECORDED, null, null);
    }

    public PeriodType getType() {
        return type;
    }

    public Integer getMonth() {
        if (type == PeriodType.CURRENT_MONTH) {
            return LocalDate.now().getMonthValue();
        }
        if (type == PeriodType.PREVIOUS_MONTH) {
            return YearMonth.now().minusMonths(1).getMonthValue();
        }
        return month;
    }

    public Integer getYear() {
        if (type == PeriodType.CURRENT_MONTH) {
            return LocalDate.now().getYear();
        }
        if (type == PeriodType.PREVIOUS_MONTH) {
            return YearMonth.now().minusMonths(1).getYear();
        }
        return year;
    }

    public boolean isExplicitOrRelative() {
        return type == PeriodType.EXPLICIT || type == PeriodType.CURRENT_MONTH || type == PeriodType.PREVIOUS_MONTH;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TargetPeriod that = (TargetPeriod) o;
        return type == that.type && Objects.equals(getMonth(), that.getMonth()) && Objects.equals(getYear(), that.getYear());
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, getMonth(), getYear());
    }

    @Override
    public String toString() {
        if (type == PeriodType.LATEST_RECORDED) {
            return "TargetPeriod{LATEST_RECORDED}";
        }
        return String.format("TargetPeriod{%s: %02d/%d}", type, getMonth(), getYear());
    }
}
