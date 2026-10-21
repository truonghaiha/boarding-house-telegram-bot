package com.boardinghousebot.observer;

import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.room.Room;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

public class PaymentRecordedEvent extends ApplicationEvent {

    private final Payment payment;
    private final Invoice invoice;
    private final Room room;
    private final String roomNumber;
    private final Integer billingMonth;
    private final Integer billingYear;
    private final BigDecimal paymentAmount;
    private final BigDecimal totalAmount;
    private final BigDecimal paidAmount;
    private final BigDecimal remainingAmount;
    private final InvoiceStatus invoiceStatus;
    private final List<Long> recipientTelegramIds;

    public PaymentRecordedEvent(Payment payment) {
        this(payment, null);
    }

    public PaymentRecordedEvent(Payment payment, List<Long> recipientTelegramIds) {
        super(payment != null ? payment : new Object());
        this.payment = payment;
        if (payment != null && payment.getInvoice() != null) {
            this.invoice = payment.getInvoice();
            this.invoiceStatus = invoice.getStatus();
            this.billingMonth = invoice.getBillingMonth();
            this.billingYear = invoice.getBillingYear();
            this.totalAmount = invoice.getTotalAmount();
            this.paidAmount = invoice.getPaidAmount();
            this.remainingAmount = invoice.getRemainingAmount();
            this.room = invoice.getRoom();
            this.roomNumber = this.room != null ? this.room.getRoomNumber() : null;
        } else {
            this.invoice = null;
            this.invoiceStatus = null;
            this.billingMonth = null;
            this.billingYear = null;
            this.totalAmount = BigDecimal.ZERO;
            this.paidAmount = BigDecimal.ZERO;
            this.remainingAmount = BigDecimal.ZERO;
            this.room = null;
            this.roomNumber = null;
        }
        this.paymentAmount = payment != null ? payment.getAmount() : BigDecimal.ZERO;

        if (recipientTelegramIds != null) {
            this.recipientTelegramIds = List.copyOf(recipientTelegramIds);
        } else if (this.room != null && this.room.getTenants() != null) {
            List<Long> ids = Collections.emptyList();
            try {
                ids = this.room.getTenants().stream()
                        .filter(u -> u.getRole() == com.boardinghousebot.domain.user.Role.MEMBER)
                        .map(com.boardinghousebot.domain.user.User::getTelegramId)
                        .toList();
            } catch (Exception ignored) {
            }
            this.recipientTelegramIds = ids;
        } else {
            this.recipientTelegramIds = Collections.emptyList();
        }
    }

    public Payment getPayment() {
        return payment;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public Room getRoom() {
        return room;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public Integer getBillingMonth() {
        return billingMonth;
    }

    public Integer getBillingYear() {
        return billingYear;
    }

    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }

    public InvoiceStatus getInvoiceStatus() {
        return invoiceStatus;
    }

    public List<Long> getRecipientTelegramIds() {
        return recipientTelegramIds;
    }
}
