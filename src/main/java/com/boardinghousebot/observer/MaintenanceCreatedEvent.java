package com.boardinghousebot.observer;

import com.boardinghousebot.domain.maintenance.MaintenanceRequest;

public class MaintenanceCreatedEvent {
    private final MaintenanceRequest request;

    public MaintenanceCreatedEvent(MaintenanceRequest request) {
        this.request = request;
    }

    public MaintenanceRequest getRequest() {
        return request;
    }
}
