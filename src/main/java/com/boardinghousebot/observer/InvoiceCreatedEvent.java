package com.boardinghousebot.observer;

import com.boardinghousebot.domain.invoice.Invoice;

public class InvoiceCreatedEvent {
    private final Invoice invoice;

    public InvoiceCreatedEvent(Invoice invoice) {
        this.invoice = invoice;
    }

    public Invoice getInvoice() {
        return invoice;
    }
}
