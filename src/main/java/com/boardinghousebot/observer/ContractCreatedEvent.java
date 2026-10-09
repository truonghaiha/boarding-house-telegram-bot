package com.boardinghousebot.observer;

import com.boardinghousebot.domain.contract.Contract;

public class ContractCreatedEvent {
    private final Contract contract;

    public ContractCreatedEvent(Contract contract) {
        this.contract = contract;
    }

    public Contract getContract() {
        return contract;
    }
}
