package com.boardinghousebot.util;

import com.boardinghousebot.exception.BusinessException;

public final class PhoneNumberUtil {
    private PhoneNumberUtil() {}
    public static String normalizeRequired(String input) {
        if (input == null || input.isBlank()) throw new BusinessException("Số điện thoại là bắt buộc.");
        String phone = input.trim().replaceAll("[\\s().-]", "");
        if (!phone.matches("0[0-9]{9}|\\+[1-9][0-9]{7,14}"))
            throw new BusinessException("Số điện thoại không hợp lệ. Nhập 10 chữ số bắt đầu bằng 0 (ví dụ: 0912345678), hoặc số quốc tế có dấu + (ví dụ: +84912345678).");
        return phone;
    }
}
