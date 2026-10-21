package com.boardinghousebot.service;

import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Release the room only after the final checkout invoice and all older debts are paid. */
@Service
public class CheckoutSettlementService {
    private final InvoiceRepository invoices;
    private final ContractRepository contracts;
    private final ContractService contractService;

    public CheckoutSettlementService(InvoiceRepository invoices, ContractRepository contracts,
            ContractService contractService) {
        this.invoices=invoices; this.contracts=contracts;
        this.contractService=contractService;
    }

    @Transactional
    public void settle(Long actor, Long contractId) {
        if(contractId==null) return;
        var contract=contracts.findByIdWithDetails(contractId).orElseThrow();
        if(contract.getStatus()!=ContractStatus.ACTIVE) return;
        var bills=invoices.findByContractId(contractId);
        if(bills.stream().noneMatch(i -> i.getCheckoutDate()!=null)
                || bills.stream().anyMatch(i -> i.getRemainingAmount().signum()>0)) return;
        contractService.endContract(actor,contractId);
    }
}
