package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import com.boardinghousebot.observer.InvoiceCreatedEvent;

@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ContractRepository contractRepository;
    private final MeterReadingRepository meterReadingRepository;
    private final RoomStayService roomStayService;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    public InvoiceService(RoomStayService roomStayService, InvoiceRepository invoiceRepository,
                          RoomRepository roomRepository,
                          UserRepository userRepository,
                          ContractRepository contractRepository,
                          MeterReadingRepository meterReadingRepository,
                          AuthorizationService authorizationService,
                          AuditLogService auditLogService,
                          ApplicationEventPublisher eventPublisher) {
        this.invoiceRepository = invoiceRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.contractRepository = contractRepository;
        this.meterReadingRepository = meterReadingRepository;
        this.roomStayService = roomStayService;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly=true)
    public LocalDate previewDueDate(Long roomId,Integer month,Integer year) {
        var c=contractRepository.findActiveContractByRoomId(roomId)
                .orElseThrow(() -> new BusinessException("Phòng chưa có hợp đồng đang hiệu lực."));
        return com.boardinghousebot.util.BillingDates.due(c.getStartDate(),month,year);
    }

    @Transactional
    public Invoice createInvoice(Long landlordTelegramId, Long roomId, Integer billingMonth, Integer billingYear) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));
        authorizationService.requireLandlord(landlord);

        if (billingMonth == null || billingMonth < 1 || billingMonth > 12) {
            throw new BusinessException("Tháng không hợp lệ (phải từ 1 đến 12).");
        }
        if (billingYear == null || billingYear < 2000 || billingYear > 2100) {
            throw new BusinessException("Năm không hợp lệ.");
        }

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin phòng."));

        if (invoiceRepository.existsByRoomIdAndBillingMonthAndBillingYear(roomId, billingMonth, billingYear)) {
            throw new BusinessException(String.format("Phòng %s đã có hóa đơn cho kỳ %02d/%d.",
                    room.getRoomNumber(), billingMonth, billingYear));
        }

        Contract contract = contractRepository.findActiveContractByRoomId(roomId)
                .orElseThrow(() -> new BusinessException(String.format("Phòng %s hiện không có hợp đồng đang hiệu lực.", room.getRoomNumber())));
        if(invoiceRepository.findByContractId(contract.getId()).stream().anyMatch(i -> i.getCheckoutDate()!=null))
            throw new BusinessException("Đã chốt trả phòng, đang chờ thanh toán hóa đơn cuối cùng. Không thể tạo thêm hóa đơn.");

        MeterReading electricity = meterReadingRepository.findByRoomIdAndUtilityTypeAndBillingMonthAndBillingYear(
                roomId, UtilityType.ELECTRICITY, billingMonth, billingYear)
                .orElseThrow(() -> new BusinessException(String.format("Phòng %s chưa ghi chỉ số điện hoặc nước cho kỳ %02d/%d.",
                        room.getRoomNumber(), billingMonth, billingYear)));

        MeterReading water = meterReadingRepository.findByRoomIdAndUtilityTypeAndBillingMonthAndBillingYear(
                roomId, UtilityType.WATER, billingMonth, billingYear)
                .orElseThrow(() -> new BusinessException(String.format("Phòng %s chưa ghi chỉ số điện hoặc nước cho kỳ %02d/%d.",
                        room.getRoomNumber(), billingMonth, billingYear)));

        BigDecimal roomRent = contract.getMonthlyRent();
        BigDecimal electricityAmount = electricity.getTotalCharge();
        BigDecimal waterAmount = water.getTotalCharge();
        BigDecimal total = roomRent.add(electricityAmount).add(waterAmount);

        LocalDate dueDate = com.boardinghousebot.util.BillingDates.due(contract.getStartDate(),billingMonth,billingYear);

        Invoice invoice = new Invoice(room, contract.getMember(), contract, billingMonth, billingYear,
                roomRent, electricityAmount, waterAmount, total, dueDate);
        invoice.setStatus(invoice.calculateCurrentStatus());

        Invoice saved = invoiceRepository.save(invoice);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_CREATE_INVOICE,
                String.format("Landlord %d tạo hóa đơn #%d cho phòng %s (Kỳ %02d/%d), Tổng tiền: %s",
                        landlordTelegramId, saved.getId(), room.getRoomNumber(), billingMonth, billingYear, total));

        log.info("Created Invoice ID={} for Room {} ({}/{})", saved.getId(), room.getRoomNumber(), billingMonth, billingYear);

        eventPublisher.publishEvent(new InvoiceCreatedEvent(saved));

        return saved;
    }

    @Transactional
    public Invoice getInvoiceById(Long invoiceId) {
        Invoice invoice = invoiceRepository.findByIdWithDetails(invoiceId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy hóa đơn."));
        InvoiceStatus oldStatus = invoice.getStatus();
        InvoiceStatus newStatus = invoice.calculateCurrentStatus();
        if (oldStatus != newStatus) {
            invoice.setStatus(newStatus);
            invoiceRepository.save(invoice);
        }
        return invoice;
    }

    @Transactional
    public Invoice getInvoiceForUser(User viewer, Long invoiceId) {
        Invoice invoice = invoiceRepository.findByIdWithDetails(invoiceId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy hóa đơn."));
        if (viewer.getRole() != Role.LANDLORD) {
            authorizationService.requireMember(viewer);
            if (!canReadInvoice(viewer, invoice)) throw new BusinessException("Bạn không có quyền xem hóa đơn này.");
        }
        return getInvoiceById(invoiceId);
    }

    private boolean canReadInvoice(User viewer, Invoice invoice) {
        return roomStayService.canReadInvoice(viewer, invoice);
    }

    @Transactional
    public List<Invoice> getAllInvoices() {
        List<Invoice> invoices = invoiceRepository.findAllWithDetails();
        for (Invoice inv : invoices) {
            InvoiceStatus oldStatus = inv.getStatus();
            InvoiceStatus newStatus = inv.calculateCurrentStatus();
            if (oldStatus != newStatus) {
                inv.setStatus(newStatus);
                invoiceRepository.save(inv);
            }
        }
        return invoices;
    }

    @Transactional
    public List<Invoice> getFilteredInvoices(Long roomId, InvoiceStatus status, Integer month, Integer year) {
        List<Invoice> candidates = invoiceRepository.filterInvoicesWithDetails(roomId, null, month, year);
        for (Invoice inv : candidates) {
            InvoiceStatus oldStatus = inv.getStatus();
            InvoiceStatus newStatus = inv.calculateCurrentStatus();
            if (oldStatus != newStatus) {
                inv.setStatus(newStatus);
                invoiceRepository.save(inv);
            }
        }
        if (status != null) {
            return candidates.stream().filter(i -> i.getStatus() == status).toList();
        }
        return candidates;
    }

    @Transactional(readOnly = true)
    public int[] getLatestBillingPeriod() {
        List<Object[]> periods = invoiceRepository.findDistinctBillingPeriods();
        if (periods.isEmpty()) {
            return new int[]{LocalDateTime.now().getMonthValue(), LocalDateTime.now().getYear()};
        }
        Object[] first = periods.get(0);
        int year = ((Number) first[0]).intValue();
        int month = ((Number) first[1]).intValue();
        return new int[]{month, year};
    }

    @Transactional
    public List<Invoice> getLatestPeriodInvoices() {
        int[] latest = getLatestBillingPeriod();
        return getFilteredInvoices(null, null, latest[0], latest[1]);
    }

    @Transactional(readOnly = true)
    public List<int[]> getAvailableBillingPeriods() {
        List<Object[]> rows = invoiceRepository.findDistinctBillingPeriods();
        List<int[]> result = new ArrayList<>();
        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            result.add(new int[]{month, year});
        }
        return result;
    }

    @Transactional
    public List<Invoice> getMemberInvoices(Long memberTelegramId) {
        User member = userRepository.findByTelegramIdWithRoom(memberTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));

        authorizationService.requireMember(member);

        List<Invoice> invoices = invoiceRepository.findAllWithDetails().stream()
                .filter(invoice -> canReadInvoice(member, invoice)).toList();
        for (Invoice inv : invoices) {
            InvoiceStatus oldStatus = inv.getStatus();
            InvoiceStatus newStatus = inv.calculateCurrentStatus();
            if (oldStatus != newStatus) {
                inv.setStatus(newStatus);
                invoiceRepository.save(inv);
            }
        }
        return invoices;
    }
}
