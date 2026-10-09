package com.boardinghousebot.observer;

import com.boardinghousebot.domain.meter.MeterReading;

public class MeterReadingCreatedEvent {
    private final MeterReading reading;

    public MeterReadingCreatedEvent(MeterReading reading) {
        this.reading = reading;
    }

    public MeterReading getReading() {
        return reading;
    }
}
