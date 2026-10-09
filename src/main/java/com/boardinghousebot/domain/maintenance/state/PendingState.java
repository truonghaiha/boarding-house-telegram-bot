package com.boardinghousebot.domain.maintenance.state;

import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.exception.BusinessException;

public class PendingState implements MaintenanceState {

    @Override
    public void updateStatus(MaintenanceRequest request, MaintenanceStatus targetStatus, String landlordNote) {
        if (targetStatus == MaintenanceStatus.IN_PROGRESS) {
            request.setStatus(MaintenanceStatus.IN_PROGRESS);
            if (landlordNote != null && !landlordNote.isBlank()) {
                request.setLandlordNote(landlordNote);
            }
        } else if (targetStatus == MaintenanceStatus.RESOLVED) {
            request.setStatus(MaintenanceStatus.RESOLVED);
            request.setResolvedAt(java.time.LocalDateTime.now());
            if (landlordNote != null && !landlordNote.isBlank()) {
                request.setLandlordNote(landlordNote);
            }
        } else {
            throw new BusinessException("Yêu cầu bảo trì đang ở trạng thái 'Chờ xử lý' chỉ được phép chuyển sang 'Đang xử lý' hoặc 'Đã xử lý'.");
        }
    }
}
