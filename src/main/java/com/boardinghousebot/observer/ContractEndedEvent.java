package com.boardinghousebot.observer;

import com.boardinghousebot.domain.contract.Contract;

public record ContractEndedEvent(Contract contract) {
}
