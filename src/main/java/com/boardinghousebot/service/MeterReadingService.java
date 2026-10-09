package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.MeterReadingRepository;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.strategy.UtilityPricingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import com.boardinghousebot.observer.MeterReadingCreatedEvent;

@Service
public class MeterReadingService {

    private static final Logger log = LoggerFactory.getLogger(MeterReadingService.class);

    private final MeterReadingRepository meterReadingRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final Map<UtilityType, UtilityPricingStrategy> strategyMap;
    private final ApplicationEventPublisher eventPublisher;

    public MeterReadingService(MeterReadingRepository meterReadingRepository,
                               RoomRepository roomRepository,
                               UserRepository userRepository,
                               AuthorizationService authorizationService,
                               AuditLogService auditLogService,
                               List<UtilityPricingStrategy> pricingStrategies,
                               ApplicationEventPublisher eventPublisher) {
        this.meterReadingRepository = meterReadingRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.strategyMap = pricingStrategies.stream()
                .collect(Collectors.toMap(UtilityPricingStrategy::getSupportedUtilityType, Function.identity()));
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public MeterReading createReading(Long landlordTelegramId, Long roomId, UtilityType utilityType,
                                      Integer billingMonth, Integer billingYear,
                                      BigDecimal previousReading, BigDecimal currentReading, BigDecimal unitPrice) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));
        authorizationService.requireLandlord(landlord);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin phòng."));

        validateInputs(billingMonth, billingYear, previousReading, currentReading, unitPrice);

        if (meterReadingRepository.existsByRoomIdAndUtilityTypeAndBillingMonthAndBillingYear(roomId, utilityType, billingMonth, billingYear)) {
            throw new BusinessException(String.format("Đã tồn tại chỉ số %s cho phòng %s trong kỳ %02d/%d.",
                    utilityType.getDisplayName().toLowerCase(), room.getRoomNumber(), billingMonth, billingYear));
        }

        MeterReading reading = new MeterReading(room, utilityType, billingMonth, billingYear, previousReading, currentReading, unitPrice);
        MeterReading saved = meterReadingRepository.save(reading);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_CREATE_METER_READING,
                String.format("Landlord %d tạo chỉ số %s cho phòng %s, kỳ %02d/%d", landlordTelegramId, utilityType, room.getRoomNumber(), billingMonth, billingYear));
        log.info("Created MeterReading ID={} for Room {} ({})", saved.getId(), room.getRoomNumber(), utilityType);

        eventPublisher.publishEvent(new MeterReadingCreatedEvent(saved));

        return saved;
    }

    @Transactional
    public MeterReading updateReading(Long landlordTelegramId, Long readingId,
                                      BigDecimal newPrevious, BigDecimal newCurrent, BigDecimal newUnitPrice) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));
        authorizationService.requireLandlord(landlord);

        MeterReading reading = meterReadingRepository.findByIdWithDetails(readingId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy bản ghi chỉ số."));

        validateReadings(newPrevious, newCurrent, newUnitPrice);

        reading.setPreviousReading(newPrevious);
        reading.setCurrentReading(newCurrent);
        reading.setUnitPrice(newUnitPrice);

        MeterReading updated = meterReadingRepository.save(reading);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_UPDATE_METER_READING,
                String.format("Landlord %d cập nhật chỉ số ID %d cho phòng %s", landlordTelegramId, readingId, reading.getRoom().getRoomNumber()));
        log.info("Updated MeterReading ID={}", readingId);

        return updated;
    }

    @Transactional
    public void deleteReading(Long landlordTelegramId, Long readingId) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));
        authorizationService.requireLandlord(landlord);

        MeterReading reading = meterReadingRepository.findByIdWithDetails(readingId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy bản ghi chỉ số."));

        String roomNum = reading.getRoom().getRoomNumber();
        UtilityType type = reading.getUtilityType();
        Integer month = reading.getBillingMonth();
        Integer year = reading.getBillingYear();

        meterReadingRepository.delete(reading);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_DELETE_METER_READING,
                String.format("Landlord %d xóa chỉ số %s ID %d của phòng %s (kỳ %02d/%d)", landlordTelegramId, type, readingId, roomNum, month, year));
        log.info("Deleted MeterReading ID={}", readingId);
    }

    @Transactional(readOnly = true)
    public MeterReading getReadingById(Long readingId) {
        return meterReadingRepository.findByIdWithDetails(readingId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy bản ghi chỉ số."));
    }

    @Transactional(readOnly = true)
    public List<MeterReading> getAllReadings() {
        return meterReadingRepository.findAllWithDetails();
    }

    @Transactional(readOnly = true)
    public List<MeterReading> getReadingsByRoom(Long roomId) {
        return meterReadingRepository.findByRoomIdWithDetails(roomId);
    }

    @Transactional(readOnly = true)
    public List<MeterReading> getReadingsByUtilityType(UtilityType utilityType) {
        return meterReadingRepository.findByUtilityTypeWithDetails(utilityType);
    }

    @Transactional(readOnly = true)
    public List<MeterReading> getReadingsByRoomAndUtility(Long roomId, UtilityType utilityType) {
        return meterReadingRepository.findByRoomIdAndUtilityTypeWithDetails(roomId, utilityType);
    }

    @Transactional(readOnly = true)
    public List<MeterReading> getFilteredReadings(Long roomId, UtilityType utilityType, Integer month, Integer year) {
        return meterReadingRepository.filterReadingsWithDetails(roomId, utilityType, month, year);
    }

    @Transactional(readOnly = true)
    public int[] getLatestBillingPeriod() {
        List<Object[]> periods = meterReadingRepository.findDistinctBillingPeriods();
        if (periods.isEmpty()) {
            return new int[]{java.time.LocalDateTime.now().getMonthValue(), java.time.LocalDateTime.now().getYear()};
        }
        Object[] first = periods.get(0);
        int year = ((Number) first[0]).intValue();
        int month = ((Number) first[1]).intValue();
        return new int[]{month, year};
    }

    @Transactional(readOnly = true)
    public List<MeterReading> getLatestPeriodReadings() {
        int[] latest = getLatestBillingPeriod();
        return meterReadingRepository.filterReadingsWithDetails(null, null, latest[0], latest[1]);
    }

    @Transactional(readOnly = true)
    public List<int[]> getAvailableBillingPeriods() {
        List<Object[]> rows = meterReadingRepository.findDistinctBillingPeriods();
        List<int[]> result = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            result.add(new int[]{month, year});
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<MeterReading> getMemberRoomReadings(Long memberTelegramId) {
        User member = userRepository.findByTelegramIdWithRoom(memberTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));
        
        if (member.getRole() != Role.MEMBER && member.getRole() != Role.LANDLORD && member.getRole() != Role.ADMIN) {
            throw new BusinessException("Bạn không có quyền truy cập chức năng này.");
        }

        if (member.getRoom() == null) {
            return List.of();
        }

        return meterReadingRepository.findByRoomIdWithDetails(member.getRoom().getId());
    }

    public BigDecimal calculateCharge(UtilityType utilityType, BigDecimal consumption, BigDecimal unitPrice) {
        UtilityPricingStrategy strategy = strategyMap.get(utilityType);
        if (strategy == null) {
            if (consumption == null || unitPrice == null) return BigDecimal.ZERO;
            return consumption.multiply(unitPrice);
        }
        return strategy.calculateCharge(consumption, unitPrice);
    }

    private void validateInputs(Integer month, Integer year, BigDecimal previous, BigDecimal current, BigDecimal unitPrice) {
        if (month == null || month < 1 || month > 12) {
            throw new BusinessException("Tháng không hợp lệ (phải từ 1 đến 12).");
        }
        if (year == null || year < 2000 || year > 2100) {
            throw new BusinessException("Năm không hợp lệ.");
        }
        validateReadings(previous, current, unitPrice);
    }

    private void validateReadings(BigDecimal previous, BigDecimal current, BigDecimal unitPrice) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Chỉ số cũ phải là số không âm (>= 0).");
        }
        if (current == null || current.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Chỉ số mới phải là số không âm (>= 0).");
        }
        if (current.compareTo(previous) < 0) {
            throw new BusinessException("Chỉ số mới phải lớn hơn hoặc bằng chỉ số cũ.");
        }
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Đơn giá phải lớn hơn 0.");
        }
    }
}
