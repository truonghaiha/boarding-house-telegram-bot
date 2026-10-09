package com.boardinghousebot.domain.maintenance.state;

import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;

public interface MaintenanceState {
    void updateStatus(MaintenanceRequest request, MaintenanceStatus targetStatus, String landlordNote);
}
