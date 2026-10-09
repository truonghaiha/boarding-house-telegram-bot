package com.boardinghousebot.domain.maintenance.state;

import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.exception.BusinessException;

import java.time.LocalDateTime;

public class InProgressState implements MaintenanceState {

    @Override
    public void updateStatus(MaintenanceRequest request, MaintenanceStatus targetStatus, String landlordNote) {
        if (targetStatus == MaintenanceStatus.RESOLVED) {
            request.setStatus(MaintenanceStatus.RESOLVED);
            request.setResolvedAt(LocalDateTime.now());
            if (landlordNote != null && !landlordNote.isBlank()) {
                request.setLandlordNote(landlordNote);
            }
        } else {
            throw new BusinessException("Yêu cầu bảo trì đang ở trạng thái 'Đang xử lý' chỉ được phép chuyển sang 'Đã xử lý'.");
        }
    }
}
