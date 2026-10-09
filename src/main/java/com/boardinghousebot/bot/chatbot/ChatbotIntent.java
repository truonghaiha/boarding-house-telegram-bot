package com.boardinghousebot.bot.chatbot;

/**
 * Các Ý định (Intent) tra cứu của Chatbot hỗ trợ người thuê (MEMBER).
 */
public enum ChatbotIntent {
    ROOM_INFO("Thông tin phòng trọ và bạn cùng phòng"),
    ROOM_RENT("Giá thuê phòng niêm yết hiện tại"),
    ELECTRICITY_READING("Chỉ số điện (chỉ số cũ, chỉ số mới, tiêu thụ)"),
    ELECTRICITY_COST("Tiền điện và đơn giá"),
    WATER_READING("Chỉ số nước (chỉ số cũ, chỉ số mới, tiêu thụ)"),
    WATER_COST("Tiền nước và đơn giá"),
    UTILITY_COMBINED_COST("Tiền điện nước kết hợp"),
    INVOICE_INFO("Hóa đơn tổng hợp của phòng"),
    PAYMENT_HISTORY("Lịch sử các đợt thanh toán"),
    TOTAL_PAID("Tổng số tiền đã thanh toán"),
    DEBT_INFO("Số tiền còn nợ / dư nợ chưa trả"),
    CONTRACT_INFO("Thông tin hợp đồng thuê và tiền cọc"),
    UNKNOWN("Không nhận diện được intent / Cần người dùng làm rõ");

    private final String description;

    ChatbotIntent(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
