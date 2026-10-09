package com.boardinghousebot.bot.chatbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class VietnameseTextNormalizerTest {

    @Test
    @DisplayName("Test removal of Vietnamese accents and text normalization")
    void testNormalize() {
        assertEquals("tien dien thang nay bao nhieu?", VietnameseTextNormalizer.normalize("Tiền điện tháng này bao nhiêu?"));
        assertEquals("chi so nuoc thang 8/2026", VietnameseTextNormalizer.normalize("Chỉ số nước tháng 8/2026"));
        assertEquals("thiet bi hu hong d", VietnameseTextNormalizer.normalize("  Thiết bị   hư hỏng Đ "));
    }

    @Test
    @DisplayName("Test extraction of explicit month and year formats")
    void testExtractTargetPeriod_Explicit() {
        TargetPeriod p1 = VietnameseTextNormalizer.extractTargetPeriod("Tiền điện tháng 8/2026 bao nhiêu?");
        assertEquals(TargetPeriod.PeriodType.EXPLICIT, p1.getType());
        assertEquals(8, p1.getMonth());
        assertEquals(2026, p1.getYear());

        TargetPeriod p2 = VietnameseTextNormalizer.extractTargetPeriod("Chỉ số nước 09/2026");
        assertEquals(TargetPeriod.PeriodType.EXPLICIT, p2.getType());
        assertEquals(9, p2.getMonth());
        assertEquals(2026, p2.getYear());

        TargetPeriod p3 = VietnameseTextNormalizer.extractTargetPeriod("Hóa đơn tháng 10 năm 2025");
        assertEquals(TargetPeriod.PeriodType.EXPLICIT, p3.getType());
        assertEquals(10, p3.getMonth());
        assertEquals(2025, p3.getYear());

        TargetPeriod p4 = VietnameseTextNormalizer.extractTargetPeriod("Tiền phòng tháng 5");
        assertEquals(TargetPeriod.PeriodType.EXPLICIT, p4.getType());
        assertEquals(5, p4.getMonth());
        assertEquals(LocalDate.now().getYear(), p4.getYear());
    }

    @Test
    @DisplayName("Test extraction of relative periods: tháng này, tháng trước")
    void testExtractTargetPeriod_Relative() {
        TargetPeriod pCurrent = VietnameseTextNormalizer.extractTargetPeriod("Tiền điện tháng này hết bao nhiêu?");
        assertEquals(TargetPeriod.PeriodType.CURRENT_MONTH, pCurrent.getType());
        assertEquals(LocalDate.now().getMonthValue(), pCurrent.getMonth());
        assertEquals(LocalDate.now().getYear(), pCurrent.getYear());

        TargetPeriod pPrev = VietnameseTextNormalizer.extractTargetPeriod("Chỉ số nước tháng trước");
        assertEquals(TargetPeriod.PeriodType.PREVIOUS_MONTH, pPrev.getType());
        YearMonth expectedPrev = YearMonth.now().minusMonths(1);
        assertEquals(expectedPrev.getMonthValue(), pPrev.getMonth());
        assertEquals(expectedPrev.getYear(), pPrev.getYear());
    }

    @Test
    @DisplayName("Test extraction when no period is specified -> LATEST_RECORDED")
    void testExtractTargetPeriod_Unspecified() {
        TargetPeriod pLatest = VietnameseTextNormalizer.extractTargetPeriod("Tôi còn nợ bao nhiêu tiền?");
        assertEquals(TargetPeriod.PeriodType.LATEST_RECORDED, pLatest.getType());
        assertNull(pLatest.getMonth());
        assertNull(pLatest.getYear());

        TargetPeriod pEmpty = VietnameseTextNormalizer.extractTargetPeriod("");
        assertEquals(TargetPeriod.PeriodType.LATEST_RECORDED, pEmpty.getType());
    }
}
