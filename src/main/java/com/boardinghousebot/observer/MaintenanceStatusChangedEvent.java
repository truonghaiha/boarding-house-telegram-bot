package com.boardinghousebot.observer;

import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;

public class MaintenanceStatusChangedEvent {
    private final MaintenanceRequest request;
    private final MaintenanceStatus previousStatus;
    private final MaintenanceStatus newStatus;

    public MaintenanceStatusChangedEvent(MaintenanceRequest request, MaintenanceStatus previousStatus, MaintenanceStatus newStatus) {
        this.request = request;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
    }

    public MaintenanceRequest getRequest() {
        return request;
    }

    public MaintenanceStatus getPreviousStatus() {
        return previousStatus;
    }

    public MaintenanceStatus getNewStatus() {
        return newStatus;
    }
}
