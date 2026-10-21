package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.repository.ContractRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    @org.springframework.beans.factory.annotation.Autowired
    private UtilityRateService utilityRateService;

    public String utilityPriceDisplay(com.boardinghousebot.domain.meter.UtilityType type) {
        if (utilityRateService == null) return "Chưa thiết lập";
        return utilityRateService.current(type).map(r -> r.getUnitPrice().toPlainString() + " VNĐ/" + type.getUnit())
                .orElse("Chưa thiết lập");
    }

    private final RoomStayService roomStayService;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ContractRepository contractRepository;

    public RoomService(RoomStayService roomStayService, RoomRepository roomRepository,
                       UserRepository userRepository,
                       AuthorizationService authorizationService,
                       AuditLogService auditLogService,
                       org.springframework.context.ApplicationEventPublisher eventPublisher,
                       ContractRepository contractRepository) {
        this.roomStayService = roomStayService;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.contractRepository = contractRepository;
    }

    @Transactional
    public Room createRoom(Long landlordTelegramId, String roomNumber, BigDecimal monthlyRent, Integer maxOccupants, String description) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        validateRoomInputs(roomNumber, monthlyRent, maxOccupants);

        if (roomRepository.existsByRoomNumber(roomNumber.trim())) {
            throw new BusinessException("Số phòng đã tồn tại.");
        }

        Room room = new Room(roomNumber.trim(), monthlyRent, maxOccupants, description != null ? description.trim() : "");
        Room savedRoom = roomRepository.save(room);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_CREATE_ROOM,
                "Landlord " + landlordTelegramId + " tạo phòng " + roomNumber);
        log.info("Created room {}: ID={}, Rent={}, MaxOccupants={}", roomNumber, savedRoom.getId(), monthlyRent, maxOccupants);

        return savedRoom;
    }

    @Transactional
    public Room updateRoom(Long landlordTelegramId, Long roomId, String newRoomNumber, BigDecimal newMonthlyRent, Integer newMaxOccupants, String newDescription) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy phòng."));

        validateRoomInputs(newRoomNumber, newMonthlyRent, newMaxOccupants);

        String trimmedNumber = newRoomNumber.trim();
        if (!trimmedNumber.equals(room.getRoomNumber()) && roomRepository.existsByRoomNumber(trimmedNumber)) {
            throw new BusinessException("Số phòng đã tồn tại.");
        }

        if (newMaxOccupants < getCurrentOccupantCount(room)) {
            throw new BusinessException("Số người tối đa không thể nhỏ hơn số người hiện tại đang ở.");
        }

        room.setRoomNumber(trimmedNumber);
        room.setMonthlyRent(newMonthlyRent);
        room.setMaxOccupants(newMaxOccupants);
        room.setDescription(newDescription != null ? newDescription.trim() : "");

        Room updatedRoom = roomRepository.save(room);
        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_UPDATE_ROOM,
                "Landlord " + landlordTelegramId + " cập nhật phòng " + trimmedNumber);

        return updatedRoom;
    }

    @Transactional
    public Room createRoom(Long landlordTelegramId, String roomNumber, BigDecimal monthlyRent,
                           Integer maxOccupants, String description, BigDecimal area, BigDecimal deposit) {
        validateDetails(area, deposit);
        Room room = createRoom(landlordTelegramId, roomNumber, monthlyRent, maxOccupants, description);
        room.setArea(area);
        room.setRequiredDeposit(deposit);
        return roomRepository.save(room);
    }

    @Transactional
    public Room updateRoomDetails(Long landlordTelegramId, Long roomId, BigDecimal area, BigDecimal deposit) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);
        if (area != null && area.signum() <= 0) throw new BusinessException("Diện tích phải lớn hơn 0.");
        if (deposit != null && deposit.signum() < 0) throw new BusinessException("Tiền cọc không được âm.");
        Room room = getRoomById(roomId);
        if (area != null) room.setArea(area);
        if (deposit != null) room.setRequiredDeposit(deposit);
        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_UPDATE_ROOM,
                "Cập nhật diện tích/tiền cọc yêu cầu phòng " + room.getRoomNumber());
        return roomRepository.save(room);
    }

    private void validateDetails(BigDecimal area, BigDecimal deposit) {
        if (area == null || area.signum() <= 0) throw new BusinessException("Diện tích phải lớn hơn 0.");
        if (deposit == null || deposit.signum() < 0) throw new BusinessException("Tiền cọc phải là số không âm.");
    }

    @Transactional
    public Room updateRoomRent(Long landlordTelegramId, Long roomId, BigDecimal rent) {
        Room room = getRoomById(roomId);
        return updateRoom(landlordTelegramId, roomId, room.getRoomNumber(), rent, room.getMaxOccupants(), room.getDescription());
    }

    @Transactional
    public Room updateRoomDescription(Long landlordTelegramId, Long roomId, String description) {
        Room room = getRoomById(roomId);
        return updateRoom(landlordTelegramId, roomId, room.getRoomNumber(), room.getMonthlyRent(), room.getMaxOccupants(), description);
    }

    @Transactional
    public void deleteRoom(Long landlordTelegramId, Long roomId) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy phòng."));

        if (getCurrentOccupantCount(room) > 0) {
            throw new BusinessException("Không thể xóa phòng đang có người thuê.");
        }

        String roomNum = room.getRoomNumber();
        roomRepository.delete(room);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_DELETE_ROOM,
                "Landlord " + landlordTelegramId + " xóa phòng " + roomNum);
        log.info("Deleted room {}", roomNum);
    }

    @Transactional(readOnly = true)
    public Room getRoomById(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy phòng."));
    }

    @Transactional(readOnly = true)
    public Optional<Room> getRoomByRoomNumber(String roomNumber) {
        if (roomNumber == null || roomNumber.isBlank()) {
            return Optional.empty();
        }
        return roomRepository.findByRoomNumber(roomNumber.trim());
    }

    @Transactional(readOnly = true)
    public List<Room> getAllRooms() {
        return roomRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Room> getAvailableRooms() {
        return roomRepository.findAll().stream()
                .filter(room -> room.hasAvailableCapacity(getCurrentOccupantCount(room.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Room> getOccupiedRooms() {
        return roomRepository.findByStatus(RoomStatus.OCCUPIED);
    }

    @Transactional
    public User assignMemberToRoom(Long landlordTelegramId, Long targetTelegramId, Long roomId) {
        return assignMember(landlordTelegramId, targetTelegramId, roomId, null);
    }

    @Transactional
    public User assignMemberToRoom(Long landlordTelegramId, Long targetTelegramId, Long roomId, String phoneNumber) {
        return assignMember(landlordTelegramId, targetTelegramId, roomId,
                com.boardinghousebot.util.PhoneNumberUtil.normalizeRequired(phoneNumber));
    }

    private User assignMember(Long landlordTelegramId, Long targetTelegramId, Long roomId, String phoneNumber) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        User targetUser = userRepository.findByTelegramId(targetTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));

        if (targetUser.getRole() != Role.MEMBER) {
            throw new BusinessException("Chỉ MEMBER mới có thể được xếp vào phòng.");
        }

        if (targetUser.getRoom() != null) {
            throw new BusinessException("Người này đã thuê phòng, không thể thêm vào phòng mới.");
        }

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy phòng."));

        if (isRoomFull(room)) {
            throw new BusinessException("Phòng đã đủ số người.");
        }

        if (phoneNumber != null) targetUser.setPhoneNumber(com.boardinghousebot.util.PhoneNumberUtil.normalizeRequired(phoneNumber));
        roomStayService.enter(targetUser, room, java.time.LocalDateTime.now());
        targetUser.setRoom(room);
        User savedUser = userRepository.save(targetUser);

        if (room.getStatus() == RoomStatus.AVAILABLE) {
            room.setStatus(RoomStatus.OCCUPIED);
            roomRepository.save(room);
        }

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_ASSIGN_MEMBER,
                "Landlord " + landlordTelegramId + " xếp member " + targetTelegramId + " vào phòng " + room.getRoomNumber());

        eventPublisher.publishEvent(new com.boardinghousebot.observer.MemberRoomChangedEvent(
                savedUser, com.boardinghousebot.observer.MemberRoomChangedEvent.ChangeType.ASSIGNED, null, room));

        return savedUser;
    }

    @Transactional
    public User removeMemberFromRoom(Long landlordTelegramId, Long targetTelegramId) {
        return removeMemberFromRoom(landlordTelegramId, targetTelegramId, null);
    }

    @Transactional(readOnly = true)
    public Optional<Long> getMemberActiveContractId(Long telegramId) {
        return contractRepository.findActiveContractByMemberTelegramId(telegramId).map(Contract::getId);
    }

    @Transactional
    public User removeMemberFromRoom(Long landlordTelegramId, Long targetTelegramId, Long expectedRoomId) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        User targetUser = userRepository.findByTelegramId(targetTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));

        Room currentRoom = targetUser.getRoom();
        if (currentRoom == null) {
            throw new BusinessException("Người dùng chưa được xếp vào phòng nào.");
        }
        if (expectedRoomId != null && !expectedRoomId.equals(currentRoom.getId())) {
            throw new BusinessException("Người thuê không còn ở phòng đã chọn. Vui lòng kiểm tra lại.");
        }

        if (contractRepository.existsByMemberTelegramIdAndStatus(targetTelegramId, ContractStatus.ACTIVE)) {
            throw new BusinessException("Cần kết thúc hợp đồng đang hiệu lực trước khi đưa người thuê ra khỏi phòng.");
        }
        roomStayService.leave(targetUser, java.time.LocalDateTime.now());
        targetUser.setRoom(null);
        User savedUser = userRepository.save(targetUser);

        if (getCurrentOccupantCount(currentRoom) == 0) {
            currentRoom.setStatus(RoomStatus.AVAILABLE);
            roomRepository.save(currentRoom);
        }

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_REMOVE_MEMBER,
                "Landlord " + landlordTelegramId + " xóa member " + targetTelegramId + " khỏi phòng " + currentRoom.getRoomNumber());

        eventPublisher.publishEvent(new com.boardinghousebot.observer.MemberRoomChangedEvent(
                savedUser, com.boardinghousebot.observer.MemberRoomChangedEvent.ChangeType.REMOVED, currentRoom, null));

        return savedUser;
    }

    @Transactional
    public User moveMemberToRoom(Long landlordTelegramId, Long targetTelegramId, Long targetRoomId) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        User targetUser = userRepository.findByTelegramId(targetTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));

        if (targetUser.getRole() != Role.MEMBER) {
            throw new BusinessException("Chỉ MEMBER mới có thể được xếp vào phòng.");
        }

        Room currentRoom = targetUser.getRoom();
        if (currentRoom == null) {
            throw new BusinessException("Người dùng chưa được xếp vào phòng nào.");
        }

        Room targetRoom = roomRepository.findById(targetRoomId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy phòng."));

        if (currentRoom.getId().equals(targetRoom.getId())) {
            throw new BusinessException("Người dùng hiện đã ở trong phòng này.");
        }

        if (isRoomFull(targetRoom)) {
            throw new BusinessException("Phòng đích đã đủ số người.");
        }

        // Active Contract migration
        LocalDate moveDate = LocalDate.now();
        Optional<Contract> activeContractOpt = contractRepository.findActiveContractByMemberTelegramId(targetTelegramId);
        Contract oldContract = null;
        Contract newContract = null;

        if (activeContractOpt.isPresent()) {
            oldContract = activeContractOpt.get();
            LocalDate originalEndDate = oldContract.getEndDate();

            // 1. END old contract (keep old room on old contract)
            oldContract.setStatus(ContractStatus.ENDED);
            oldContract.setEndDate(moveDate.isBefore(oldContract.getStartDate()) ? oldContract.getStartDate() : moveDate);
            contractRepository.save(oldContract);

            // 2. Determine end date for new contract
            LocalDate newEndDate = moveDate.plusMonths(6);
            if (originalEndDate != null && originalEndDate.isAfter(moveDate)) {
                newEndDate = originalEndDate;
            }

            BigDecimal deposit = oldContract.getDeposit() != null ? oldContract.getDeposit() : BigDecimal.ZERO;

            // 3. Create new ACTIVE contract for target room
            newContract = new Contract(targetUser, targetRoom, moveDate, newEndDate, targetRoom.getMonthlyRent(), deposit);
            newContract.setStatus(ContractStatus.ACTIVE);
            contractRepository.save(newContract);
        }

        // Update targetUser's room
        java.time.LocalDateTime movedAt = java.time.LocalDateTime.now();
        roomStayService.leave(targetUser, movedAt);
        roomStayService.enter(targetUser, targetRoom, movedAt);
        targetUser.setRoom(targetRoom);
        User savedUser = userRepository.save(targetUser);

        // Update room status
        if (getCurrentOccupantCount(currentRoom) == 0) {
            currentRoom.setStatus(RoomStatus.AVAILABLE);
            roomRepository.save(currentRoom);
        }

        if (targetRoom.getStatus() == RoomStatus.AVAILABLE) {
            targetRoom.setStatus(RoomStatus.OCCUPIED);
            roomRepository.save(targetRoom);
        }

        // Audit Log
        StringBuilder auditMsg = new StringBuilder();
        auditMsg.append("Landlord ").append(landlordTelegramId)
                .append(" chuyển member ").append(targetTelegramId)
                .append(" từ phòng ").append(currentRoom.getRoomNumber())
                .append(" sang phòng ").append(targetRoom.getRoomNumber());
        if (oldContract != null) {
            auditMsg.append(". Old Contract: #").append(oldContract.getId()).append(" -> ENDED");
        }
        if (newContract != null) {
            auditMsg.append(". New Contract: #").append(newContract.getId()).append(" -> ACTIVE");
        }

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_MOVE_MEMBER, auditMsg.toString());

        // Publish event for notification
        eventPublisher.publishEvent(new com.boardinghousebot.observer.MemberRoomChangedEvent(
                savedUser, com.boardinghousebot.observer.MemberRoomChangedEvent.ChangeType.MOVED, currentRoom, targetRoom));

        return savedUser;
    }

    @Transactional(readOnly = true)
    public List<User> getRoomTenants(Long roomId) {
        return userRepository.findByRoomId(roomId);
    }

    @Transactional(readOnly = true)
    public Optional<Room> getMemberRoom(Long memberTelegramId) {
        return roomStayService.getCurrentRoom(memberTelegramId);
    }

    public int getCurrentOccupantCount(Long roomId) {
        if (roomId == null) return 0;
        return (int) userRepository.countByRoomId(roomId);
    }

    public int getCurrentOccupantCount(Room room) {
        if (room == null || room.getId() == null) return 0;
        return getCurrentOccupantCount(room.getId());
    }

    public boolean isRoomFull(Room room) {
        if (room == null) return false;
        int currentOccupants = getCurrentOccupantCount(room.getId());
        return room.isFull(currentOccupants);
    }

    private void validateRoomInputs(String roomNumber, BigDecimal monthlyRent, Integer maxOccupants) {
        if (roomNumber == null || roomNumber.isBlank()) {
            throw new BusinessException("Số phòng không được để trống.");
        }
        if (monthlyRent == null || monthlyRent.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Giá thuê phải lớn hơn 0.");
        }
        if (maxOccupants == null || maxOccupants <= 0) {
            throw new BusinessException("Số người tối đa phải lớn hơn 0.");
        }
    }
}
