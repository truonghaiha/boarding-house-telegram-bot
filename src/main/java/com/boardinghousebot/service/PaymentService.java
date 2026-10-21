package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.invoice.PaymentMethod;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.InvoiceRepository;
import com.boardinghousebot.repository.PaymentRepository;
import com.boardinghousebot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final RoomStayService roomStayService;
    private final AuditLogService auditLogService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final CheckoutSettlementService checkoutSettlement;

    public PaymentService(RoomStayService roomStayService, PaymentRepository paymentRepository,
                          InvoiceRepository invoiceRepository,
                          UserRepository userRepository,
                          AuthorizationService authorizationService,
                          AuditLogService auditLogService,
                          org.springframework.context.ApplicationEventPublisher eventPublisher,
                          CheckoutSettlementService checkoutSettlement) {
        this.checkoutSettlement=checkoutSettlement;
        this.paymentRepository = paymentRepository;
        this.roomStayService = roomStayService;
        this.invoiceRepository = invoiceRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Payment recordFullPayment(Long actor, Long invoiceId, PaymentMethod method, String note) {
        var invoice=invoiceRepository.findByIdWithDetails(invoiceId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy hóa đơn."));
        return recordPayment(actor,invoiceId,invoice.getRemainingAmount(),method,note);
    }

    @Transactional
    public Payment recordPayment(Long landlordTelegramId, Long invoiceId, BigDecimal amount, PaymentMethod paymentMethod, String note) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));
        authorizationService.requireLandlord(landlord);

        Invoice invoice = invoiceRepository.findByIdWithDetails(invoiceId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy hóa đơn."));

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Số tiền thanh toán phải lớn hơn 0.");
        }

        BigDecimal remaining = invoice.getRemainingAmount();
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException(String.format("Số tiền thanh toán (%s VNĐ) vượt quá số tiền còn lại (%s VNĐ).",
                    formatNumber(amount), formatNumber(remaining)));
        }

        if (paymentMethod == null) {
            paymentMethod = PaymentMethod.CASH;
        }

        Payment payment = new Payment(invoice, amount, paymentMethod, note);
        Payment savedPayment = paymentRepository.save(payment);

        BigDecimal newPaidAmount = invoice.getPaidAmount().add(amount);
        invoice.setPaidAmount(newPaidAmount);
        invoice.setStatus(invoice.calculateCurrentStatus());

        invoiceRepository.save(invoice);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_RECORD_PAYMENT,
                String.format("Landlord %d ghi nhận thanh toán %s VNĐ (%s) cho hóa đơn #%d của phòng %s. Trạng thái mới: %s",
                        landlordTelegramId, amount, paymentMethod.getDisplayName(), invoice.getId(), invoice.getRoom().getRoomNumber(), invoice.getStatus().getDisplayName()));

        log.info("Recorded Payment ID={} for Invoice ID={}, Amount={}, Status={}", savedPayment.getId(), invoiceId, amount, invoice.getStatus());

        List<Long> recipientTelegramIds = userRepository.findByRole(com.boardinghousebot.domain.user.Role.MEMBER).stream()
                .filter(u -> u.getRole() == com.boardinghousebot.domain.user.Role.MEMBER)
                .filter(u -> roomStayService.canReadInvoice(u, invoice))
                .map(User::getTelegramId)
                .toList();

        eventPublisher.publishEvent(new com.boardinghousebot.observer.PaymentRecordedEvent(savedPayment, recipientTelegramIds));
        checkoutSettlement.settle(landlordTelegramId,invoice.getContract()==null?null:invoice.getContract().getId());

        return savedPayment;
    }

    @Transactional(readOnly = true)
    public List<Payment> getInvoicePayments(Long invoiceId) {
        return paymentRepository.findByInvoiceIdOrderByPaymentDateDesc(invoiceId);
    }

    private String formatNumber(BigDecimal val) {
        if (val == null) return "0";
        if (val.stripTrailingZeros().scale() <= 0) {
            return String.format(java.util.Locale.US, "%,d", val.longValue());
        }
        return String.format(java.util.Locale.US, "%,.2f", val.doubleValue());
    }
}
