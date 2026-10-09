package com.boardinghousebot.bot.chatbot;

/**
 * Engine nhận diện Ý định (Intent) và trích xuất Kỳ tra cứu (TargetPeriod) từ câu hỏi của MEMBER.
 */
public class ChatbotIntentResolver {

    private ChatbotIntentResolver() {
    }

    /**
     * Phân tích câu hỏi tự nhiên và trả về QueryContext chứa Intent + TargetPeriod.
     */
    public static QueryContext resolve(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new QueryContext(rawQuery, "", ChatbotIntent.UNKNOWN, TargetPeriod.latestRecorded());
        }

        String norm = VietnameseTextNormalizer.normalize(rawQuery);
        TargetPeriod period = VietnameseTextNormalizer.extractTargetPeriod(rawQuery);
        ChatbotIntent intent = detectIntent(norm);

        return new QueryContext(rawQuery, norm, intent, period);
    }

    /**
     * Nhận diện Intent dựa trên từ khóa và pattern trong văn bản đã chuẩn hóa.
     */
    public static ChatbotIntent detectIntent(String norm) {
        if (norm == null || norm.isBlank()) {
            return ChatbotIntent.UNKNOWN;
        }

        boolean containsDien = norm.contains("dien") || norm.contains("kwh");
        boolean containsNuoc = norm.contains("nuoc") || norm.contains("m3") || norm.contains("khoi");

        // 1. Kiểm tra câu hỏi kết hợp Điện Nước (UTILITY_COMBINED_COST)
        if (norm.contains("dien nuoc") || norm.contains("dien va nuoc")
                || (containsDien && containsNuoc)
                || (norm.contains("tien dien") && norm.contains("tien nuoc"))) {
            return ChatbotIntent.UTILITY_COMBINED_COST;
        }

        boolean isPriceQuery = norm.contains("tien") || norm.contains("don gia") || norm.contains("cuoc") || norm.contains("gia") || norm.contains("het bao nhieu");
        boolean isReadingQuery = norm.contains("chi so") || norm.contains("so dien") || norm.contains("so nuoc")
                || norm.contains("so khoi") || norm.contains("khoi nuoc") || norm.contains("so kwh")
                || norm.contains("chi so cu") || norm.contains("chi so moi") || norm.contains("tieu thu");

        // 2. Tra cứu Điện (Chỉ số vs Tiền điện)
        if (containsDien) {
            if (isReadingQuery && !isPriceQuery) {
                return ChatbotIntent.ELECTRICITY_READING;
            }
            if (isPriceQuery) {
                return ChatbotIntent.ELECTRICITY_COST;
            }
            if (isReadingQuery) {
                return ChatbotIntent.ELECTRICITY_READING;
            }
            return ChatbotIntent.ELECTRICITY_COST;
        }

        // 3. Tra cứu Nước (Chỉ số vs Tiền nước)
        if (containsNuoc) {
            if (isReadingQuery && !isPriceQuery) {
                return ChatbotIntent.WATER_READING;
            }
            if (isPriceQuery) {
                return ChatbotIntent.WATER_COST;
            }
            if (isReadingQuery) {
                return ChatbotIntent.WATER_READING;
            }
            return ChatbotIntent.WATER_COST;
        }

        // 4. Dư nợ / Số tiền còn nợ (DEBT_INFO)
        if (norm.contains("con no") || norm.contains("no bao nhieu") || norm.contains("thieu bao nhieu")
                || norm.contains("con thieu") || norm.contains("chua thanh toan") || norm.contains("chua tra")
                || norm.contains("du no") || norm.contains("no hien tai")) {
            return ChatbotIntent.DEBT_INFO;
        }

        // 5. Lịch sử thanh toán & Số tiền đã thanh toán
        if (norm.contains("tong so tien da tra") || norm.contains("da thanh toan bao nhieu") || norm.contains("da tra bao nhieu") || norm.contains("tong tien da dong")) {
            return ChatbotIntent.TOTAL_PAID;
        }
        if (norm.contains("lich su") || norm.contains("cac dot") || norm.contains("da thanh toan") || norm.contains("da tra") || norm.contains("chuyen khoan")) {
            return ChatbotIntent.PAYMENT_HISTORY;
        }

        // 6. Hóa đơn (INVOICE_INFO)
        if (norm.contains("hoa don") || norm.contains("tong hoa don") || norm.contains("phi sinh hoat") || norm.contains("han thanh toan")) {
            return ChatbotIntent.INVOICE_INFO;
        }

        // 7. Hợp đồng (CONTRACT_INFO)
        if (norm.contains("hop dong") || norm.contains("tien coc") || norm.contains("dat coc") || norm.contains("thoi han") || norm.contains("het han")) {
            return ChatbotIntent.CONTRACT_INFO;
        }

        // 8. Giá thuê niêm yết hiện tại (ROOM_RENT) vs Thông tin phòng (ROOM_INFO)
        if (norm.contains("gia thue") || norm.contains("gia phong") || norm.contains("tien phong niem yet") || norm.contains("gia niem yet")) {
            return ChatbotIntent.ROOM_RENT;
        }
        if (norm.contains("phong") || norm.contains("ban cung phong") || norm.contains("o cung") || norm.contains("suc chua")) {
            // Nếu có chữ "tiền phòng" không kèm tháng -> kiểm tra tiền phòng
            if (norm.contains("tien phong")) {
                return ChatbotIntent.ROOM_RENT;
            }
            return ChatbotIntent.ROOM_INFO;
        }

        return ChatbotIntent.UNKNOWN;
    }
}
