package com.boardinghousebot.service;

import com.boardinghousebot.domain.meter.*;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class UtilityRateService {
    private final UtilityRateRepository rates;
    private final UserRepository users;
    private final AuthorizationService authorization;
    private final NotificationService notifications;
    private final AuditLogService audit;
    private final org.springframework.context.ApplicationEventPublisher events;
    public UtilityRateService(UtilityRateRepository rates, UserRepository users,
            AuthorizationService authorization, NotificationService notifications, AuditLogService audit,
            org.springframework.context.ApplicationEventPublisher events) {
        this.rates = rates; this.users = users; this.authorization = authorization;
        this.notifications = notifications; this.events = events; this.audit = audit;
    }
    public static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")); }
    @Transactional(readOnly = true)
    public Optional<UtilityRate> current(UtilityType type) { return at(type, today()); }
    @Transactional(readOnly = true)
    public Optional<UtilityRate> at(UtilityType type, LocalDate date) {
        return rates.findFirstByUtilityTypeAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(type, date);
    }
    // Monthly readings have no dated consumption breakdown. New readings use the latest
    // effective rate in that billing month; saved readings/invoices retain their snapshots.
    @Transactional(readOnly = true)
    public BigDecimal forPeriod(UtilityType type, int month, int year) {
        if (month < 1 || month > 12 || year < 2000 || year > 2100)
            throw new BusinessException("Kỳ thanh toán không hợp lệ.");
        LocalDate end = YearMonth.of(year, month).atEndOfMonth();
        return at(type, end).orElseThrow(() -> new BusinessException(
                "Chưa thiết lập giá " + type.getDisplayName() + " chung cho kỳ này. Vui lòng cập nhật trong Điện nước.")).getUnitPrice();
    }
    @Transactional
    public UtilityRate update(Long landlordId, UtilityType type, BigDecimal price) {
        var landlord = users.findByTelegramId(landlordId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorization.requireLandlord(landlord);
        if (type == null || price == null || price.signum() <= 0 || price.scale() > 2 || price.precision() > 12)
            throw new BusinessException("Đơn giá phải lớn hơn 0, tối đa 2 chữ số thập phân.");
        var old = current(type);
        if (old.isPresent() && old.get().getUnitPrice().compareTo(price) == 0) return old.get();
        var rate = rates.save(new UtilityRate(type, price, today()));
        audit.logAction(landlordId, "LANDLORD_UPDATE_UTILITY_RATE",
                "Cập nhật giá " + type + " cả tòa: " + old.map(r -> r.getUnitPrice().toPlainString()).orElse("chưa thiết lập")
                        + " -> " + price.toPlainString() + "; áp dụng " + rate.getEffectiveDate());
        String message = "🔔 THAY ĐỔI GIÁ " + type.getDisplayName().toUpperCase()
                + " CẢ TÒA\n\nĐơn giá cũ: " + old.map(r -> r.getUnitPrice().toPlainString() + " VNĐ/" + type.getUnit()).orElse("Chưa thiết lập")
                + "\nĐơn giá mới: " + price.toPlainString() + " VNĐ/" + type.getUnit()
                + "\nNgày áp dụng: " + today().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + "\nĐơn giá mới áp dụng từ kỳ tiếp theo của từng phòng. Kỳ đang sử dụng giữ đơn giá tại đầu kỳ; hóa đơn đã lập giữ nguyên.";
        for (var tenant : users.findByRoleWithRoom(Role.MEMBER)) {
            if (tenant.getRoom() == null) continue;
            notifications.createNotification(tenant.getTelegramId(), NotificationType.UTILITY_RATE_CHANGED,
                    "Thay đổi giá " + type.getDisplayName(), message, "UTILITY_RATE", rate.getId());
            events.publishEvent(new com.boardinghousebot.observer.UtilityRateChangedEvent(tenant.getTelegramId(), message));
        }
        return rate;
    }
}
