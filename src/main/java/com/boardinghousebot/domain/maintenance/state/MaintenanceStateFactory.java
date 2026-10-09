package com.boardinghousebot.domain.maintenance.state;

import com.boardinghousebot.domain.maintenance.MaintenanceStatus;

public class MaintenanceStateFactory {

    public static MaintenanceState getState(MaintenanceStatus status) {
        if (status == null) {
            return new PendingState();
        }
        return switch (status) {
            case PENDING -> new PendingState();
            case IN_PROGRESS -> new InProgressState();
            case RESOLVED -> new ResolvedState();
        };
    }
}
