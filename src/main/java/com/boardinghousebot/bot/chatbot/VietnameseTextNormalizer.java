package com.boardinghousebot.bot.chatbot;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility chuẩn hóa câu hỏi tiếng Việt tự nhiên và trích xuất thực thể thời gian (TargetPeriod).
 */
public class VietnameseTextNormalizer {

    private static final Pattern PATTERN_MONTH_YEAR_FULL = Pattern.compile("(?:thang|t)\\s*(\\d{1,2})\\s*[/\\-]\\s*(\\d{4})");
    private static final Pattern PATTERN_MONTH_YEAR_WORD = Pattern.compile("(?:thang|t)\\s*(\\d{1,2})\\s*nam\\s*(\\d{4})");
    private static final Pattern PATTERN_SLASH_DATE = Pattern.compile("\\b(\\d{1,2})/(\\d{4})\\b");
    private static final Pattern PATTERN_MONTH_ONLY = Pattern.compile("(?:thang|t)\\s*(\\d{1,2})\\b");

    private VietnameseTextNormalizer() {
    }

    /**
     * Loai bo dau tiếng Việt và đưa về chữ thường.
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String lower = input.trim().toLowerCase();
        String unaccented = removeAccents(lower);
        // Replace multiple spaces with single space
        return unaccented.replaceAll("\\s+", " ").trim();
    }

    /**
     * Khử dấu tiếng Việt giữ nguyên cấu trúc chữ cái.
     */
    public static String removeAccents(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String result = pattern.matcher(normalized).replaceAll("");
        return result.replace('đ', 'd').replace('Đ', 'D');
    }

    /**
     * Trích xuất kỳ tra cứu thời gian (TargetPeriod) từ câu hỏi.
     */
    public static TargetPeriod extractTargetPeriod(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return TargetPeriod.latestRecorded();
        }

        String norm = normalize(rawText);

        // 1. Kiểm tra tháng/năm đầy đủ (ví dụ: "tháng 8/2026", "tháng 08-2026")
        Matcher mFull = PATTERN_MONTH_YEAR_FULL.matcher(norm);
        if (mFull.find()) {
            int month = Integer.parseInt(mFull.group(1));
            int year = Integer.parseInt(mFull.group(2));
            if (isValidMonth(month) && isValidYear(year)) {
                return TargetPeriod.explicit(month, year);
            }
        }

        // 2. Kiểm tra "tháng X năm YYYY"
        Matcher mWord = PATTERN_MONTH_YEAR_WORD.matcher(norm);
        if (mWord.find()) {
            int month = Integer.parseInt(mWord.group(1));
            int year = Integer.parseInt(mWord.group(2));
            if (isValidMonth(month) && isValidYear(year)) {
                return TargetPeriod.explicit(month, year);
            }
        }

        // 3. Kiểm tra định dạng "MM/YYYY" (ví dụ: "08/2026")
        Matcher mSlash = PATTERN_SLASH_DATE.matcher(norm);
        if (mSlash.find()) {
            int month = Integer.parseInt(mSlash.group(1));
            int year = Integer.parseInt(mSlash.group(2));
            if (isValidMonth(month) && isValidYear(year)) {
                return TargetPeriod.explicit(month, year);
            }
        }

        // 4. Kiểm tra từ khóa tương đối: "tháng này", "tháng hiện tại", "kỳ này"
        if (norm.contains("thang nay") || norm.contains("thang hien tai") || norm.contains("ky nay") || norm.contains("dot nay")) {
            return TargetPeriod.currentMonth();
        }

        // 5. Kiểm tra từ khóa tương đối: "tháng trước", "tháng vừa rồi", "tháng vừa qua", "kỳ trước"
        if (norm.contains("thang truoc") || norm.contains("thang vua roi") || norm.contains("thang vua qua") || norm.contains("ky truoc")) {
            return TargetPeriod.previousMonth();
        }

        // 6. Kiểm tra "tháng X" (chỉ có tháng, suy ra năm hiện tại)
        Matcher mMonth = PATTERN_MONTH_ONLY.matcher(norm);
        if (mMonth.find()) {
            int month = Integer.parseInt(mMonth.group(1));
            if (isValidMonth(month)) {
                int year = LocalDate.now().getYear();
                return TargetPeriod.explicit(month, year);
            }
        }

        // 7. Mặc định không nói tháng -> Lấy kỳ ghi nhận mới nhất (LATEST_RECORDED)
        return TargetPeriod.latestRecorded();
    }

    private static boolean isValidMonth(int month) {
        return month >= 1 && month <= 12;
    }

    private static boolean isValidYear(int year) {
        return year >= 2000 && year <= 2100;
    }
}
