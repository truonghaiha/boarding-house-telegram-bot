package com.boardinghousebot.bot.chatbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class ChatbotIntentResolverTest {

    @Test
    @DisplayName("1. Test combined query: 'Tháng 8 hết bao nhiêu tiền điện nước?'")
    void testCombinedUtilityQuery() {
        QueryContext ctx = ChatbotIntentResolver.resolve("Tháng 8 hết bao nhiêu tiền điện nước?");
        assertEquals(ChatbotIntent.UTILITY_COMBINED_COST, ctx.getIntent());
        assertEquals(TargetPeriod.PeriodType.EXPLICIT, ctx.getTargetPeriod().getType());
        assertEquals(8, ctx.getTargetPeriod().getMonth());

        QueryContext ctx2 = ChatbotIntentResolver.resolve("Tiền điện và nước tháng này");
        assertEquals(ChatbotIntent.UTILITY_COMBINED_COST, ctx2.getIntent());
        assertEquals(TargetPeriod.PeriodType.CURRENT_MONTH, ctx2.getTargetPeriod().getType());
    }

    @Test
    @DisplayName("2. Test Electricity reading vs cost queries")
    void testElectricityQueries() {
        QueryContext readingCtx = ChatbotIntentResolver.resolve("Chỉ số điện tháng 9/2026 bao nhiêu?");
        assertEquals(ChatbotIntent.ELECTRICITY_READING, readingCtx.getIntent());
        assertEquals(9, readingCtx.getTargetPeriod().getMonth());

        QueryContext costCtx = ChatbotIntentResolver.resolve("Tiền điện tháng 9/2026 hết bao nhiêu?");
        assertEquals(ChatbotIntent.ELECTRICITY_COST, costCtx.getIntent());
        assertEquals(9, costCtx.getTargetPeriod().getMonth());
    }

    @Test
    @DisplayName("3. Test Water reading vs cost queries")
    void testWaterQueries() {
        QueryContext readingCtx = ChatbotIntentResolver.resolve("Số khối nước tháng trước?");
        assertEquals(ChatbotIntent.WATER_READING, readingCtx.getIntent());
        assertEquals(TargetPeriod.PeriodType.PREVIOUS_MONTH, readingCtx.getTargetPeriod().getType());

        QueryContext costCtx = ChatbotIntentResolver.resolve("Tiền nước tháng trước bao nhiêu?");
        assertEquals(ChatbotIntent.WATER_COST, costCtx.getIntent());
        assertEquals(TargetPeriod.PeriodType.PREVIOUS_MONTH, costCtx.getTargetPeriod().getType());
    }

    @Test
    @DisplayName("4. Test distinguish listed room rent vs room info")
    void testRoomQueries() {
        QueryContext rentCtx = ChatbotIntentResolver.resolve("Giá thuê phòng hiện tại là bao nhiêu?");
        assertEquals(ChatbotIntent.ROOM_RENT, rentCtx.getIntent());
        assertEquals(TargetPeriod.PeriodType.LATEST_RECORDED, rentCtx.getTargetPeriod().getType());

        QueryContext infoCtx = ChatbotIntentResolver.resolve("Cho tôi xem thông tin phòng và bạn cùng phòng");
        assertEquals(ChatbotIntent.ROOM_INFO, infoCtx.getIntent());
    }

    @Test
    @DisplayName("5. Test Debt, Total Paid, and Payment History queries")
    void testDebtAndPaymentQueries() {
        QueryContext debtCtx = ChatbotIntentResolver.resolve("Tôi còn nợ bao nhiêu tiền?");
        assertEquals(ChatbotIntent.DEBT_INFO, debtCtx.getIntent());
        assertEquals(TargetPeriod.PeriodType.LATEST_RECORDED, debtCtx.getTargetPeriod().getType());

        QueryContext totalPaidCtx = ChatbotIntentResolver.resolve("Tổng số tiền đã trả là bao nhiêu?");
        assertEquals(ChatbotIntent.TOTAL_PAID, totalPaidCtx.getIntent());

        QueryContext historyCtx = ChatbotIntentResolver.resolve("Cho tôi xem lịch sử thanh toán các đợt");
        assertEquals(ChatbotIntent.PAYMENT_HISTORY, historyCtx.getIntent());
    }

    @Test
    @DisplayName("6. Test Contract and Invoice queries")
    void testContractAndInvoiceQueries() {
        QueryContext contractCtx = ChatbotIntentResolver.resolve("Xem thông tin hợp đồng và tiền cọc");
        assertEquals(ChatbotIntent.CONTRACT_INFO, contractCtx.getIntent());

        QueryContext invoiceCtx = ChatbotIntentResolver.resolve("Cho tôi xem hóa đơn tháng 8/2026");
        assertEquals(ChatbotIntent.INVOICE_INFO, invoiceCtx.getIntent());
        assertEquals(8, invoiceCtx.getTargetPeriod().getMonth());
        assertEquals(2026, invoiceCtx.getTargetPeriod().getYear());
    }

    @Test
    @DisplayName("7. Test distinction between 'tháng này', 'tháng trước', and unspecified period (LATEST_RECORDED)")
    void testPeriodDistinction() {
        QueryContext currentCtx = ChatbotIntentResolver.resolve("Tiền điện tháng này");
        assertEquals(TargetPeriod.PeriodType.CURRENT_MONTH, currentCtx.getTargetPeriod().getType());
        assertEquals(LocalDate.now().getMonthValue(), currentCtx.getTargetPeriod().getMonth());

        QueryContext prevCtx = ChatbotIntentResolver.resolve("Tiền điện tháng trước");
        assertEquals(TargetPeriod.PeriodType.PREVIOUS_MONTH, prevCtx.getTargetPeriod().getType());
        assertEquals(YearMonth.now().minusMonths(1).getMonthValue(), prevCtx.getTargetPeriod().getMonth());

        QueryContext latestCtx = ChatbotIntentResolver.resolve("Tiền điện hết bao nhiêu?");
        assertEquals(TargetPeriod.PeriodType.LATEST_RECORDED, latestCtx.getTargetPeriod().getType());
        assertNull(latestCtx.getTargetPeriod().getMonth());
        assertNull(latestCtx.getTargetPeriod().getYear());
    }

    @Test
    @DisplayName("8. Test ambiguous / unknown query fallback")
    void testUnknownQuery() {
        QueryContext unknownCtx = ChatbotIntentResolver.resolve("Xin chào bot");
        assertEquals(ChatbotIntent.UNKNOWN, unknownCtx.getIntent());

        QueryContext emptyCtx = ChatbotIntentResolver.resolve(" ");
        assertEquals(ChatbotIntent.UNKNOWN, emptyCtx.getIntent());
    }
}
