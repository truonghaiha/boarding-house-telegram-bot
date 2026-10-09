package com.boardinghousebot.domain.maintenance.state;

import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.exception.BusinessException;

public class ResolvedState implements MaintenanceState {

    @Override
    public void updateStatus(MaintenanceRequest request, MaintenanceStatus targetStatus, String landlordNote) {
        throw new BusinessException("Yêu cầu bảo trì đã được xử lý xong, không thể thay đổi trạng thái.");
    }
}
