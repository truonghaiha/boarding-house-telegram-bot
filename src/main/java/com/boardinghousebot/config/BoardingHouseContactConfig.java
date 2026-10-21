package com.boardinghousebot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicitly public contact for the single boarding house, independent of landlord accounts. */
@Component
public class BoardingHouseContactConfig {
    private final String name;
    private final String phone;
    private final String telegram;

    public BoardingHouseContactConfig(@Value("${boarding-house.contact.name:}") String name,
                                     @Value("${boarding-house.contact.phone:}") String phone,
                                     @Value("${boarding-house.contact.telegram:}") String telegram) {
        this.name = name.trim();
        this.phone = phone.trim();
        this.telegram = telegram.trim();
    }

    public String displayContact() {
        if (!isConfigured()) {
            return "Vui lòng liên hệ trực tiếp với chủ trọ để được hỗ trợ.";
        }
        StringBuilder text = new StringBuilder("📞 THÔNG TIN LIÊN HỆ CHỦ TRỌ\n\n");
        if (!name.isEmpty()) text.append("Chủ trọ: ").append(name).append("\n");
        if (!phone.isEmpty()) text.append("Số điện thoại: ").append(phone).append("\n");
        if (!telegram.isEmpty()) text.append("Telegram: ").append(telegram.startsWith("@") ? telegram : "@" + telegram).append("\n");
        return text.toString().stripTrailing();
    }

    public boolean isConfigured() {
        return !name.isEmpty() || !phone.isEmpty() || !telegram.isEmpty();
    }
}
