package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import com.boardinghousebot.observer.ContractCreatedEvent;

@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final ContractRepository contractRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    public ContractService(ContractRepository contractRepository,
                           UserRepository userRepository,
                           AuthorizationService authorizationService,
                           AuditLogService auditLogService,
                           ApplicationEventPublisher eventPublisher) {
        this.contractRepository = contractRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Contract createContract(Long landlordTelegramId, Long memberTelegramId, LocalDate startDate, LocalDate endDate, BigDecimal monthlyRent, BigDecimal deposit) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        User member = userRepository.findByTelegramId(memberTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người thuê."));

        if (member.getRole() != Role.MEMBER) {
            throw new BusinessException("Chỉ MEMBER mới có thể tạo hợp đồng thuê.");
        }

        Room room = member.getRoom();
        if (room == null) {
            throw new BusinessException("Người thuê chưa được xếp vào phòng nào.");
        }

        if (contractRepository.existsByMemberTelegramIdAndStatus(memberTelegramId, ContractStatus.ACTIVE)) {
            throw new BusinessException("Người thuê này đã có hợp đồng đang hiệu lực.");
        }

        if (contractRepository.existsByRoomIdAndStatus(room.getId(), ContractStatus.ACTIVE)) {
            throw new BusinessException("Phòng " + room.getRoomNumber() + " đã có hợp đồng đang hiệu lực.");
        }

        validateContractInputs(startDate, endDate, monthlyRent, deposit);

        Contract contract = new Contract(member, room, startDate, endDate, monthlyRent, deposit);
        Contract savedContract = contractRepository.save(contract);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_CREATE_CONTRACT,
                "Landlord " + landlordTelegramId + " tạo hợp đồng cho member " + memberTelegramId + " ở phòng " + room.getRoomNumber());
        log.info("Created contract ID={} for Member {} in Room {}", savedContract.getId(), memberTelegramId, room.getRoomNumber());

        eventPublisher.publishEvent(new ContractCreatedEvent(savedContract));

        return savedContract;
    }

    @Transactional
    public Contract updateContract(Long landlordTelegramId, Long contractId, LocalDate newStartDate, LocalDate newEndDate, BigDecimal newMonthlyRent, BigDecimal newDeposit) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        Contract contract = contractRepository.findByIdWithDetails(contractId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy hợp đồng."));

        if (contract.getStatus() == ContractStatus.ENDED) {
            throw new BusinessException("Không thể chỉnh sửa hợp đồng đã kết thúc.");
        }

        validateContractInputs(newStartDate, newEndDate, newMonthlyRent, newDeposit);

        contract.setStartDate(newStartDate);
        contract.setEndDate(newEndDate);
        contract.setMonthlyRent(newMonthlyRent);
        contract.setDeposit(newDeposit);

        Contract updatedContract = contractRepository.save(contract);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_UPDATE_CONTRACT,
                "Landlord " + landlordTelegramId + " cập nhật hợp đồng ID " + contractId);
        log.info("Updated contract ID={}", contractId);

        return updatedContract;
    }

    @Transactional
    public Contract endContract(Long landlordTelegramId, Long contractId) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);

        Contract contract = contractRepository.findByIdWithDetails(contractId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy hợp đồng."));

        if (contract.getStatus() == ContractStatus.ENDED) {
            throw new BusinessException("Hợp đồng này đã kết thúc.");
        }

        contract.setStatus(ContractStatus.ENDED);
        Contract savedContract = contractRepository.save(contract);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_END_CONTRACT,
                "Landlord " + landlordTelegramId + " kết thúc hợp đồng ID " + contractId);
        log.info("Ended contract ID={}", contractId);

        return savedContract;
    }

    @Transactional(readOnly = true)
    public List<Contract> getAllContracts() {
        return contractRepository.findAllWithDetails();
    }

    @Transactional(readOnly = true)
    public List<Contract> getActiveContracts() {
        return contractRepository.findByStatusWithDetails(ContractStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<Contract> getEndedContracts() {
        return contractRepository.findByStatusWithDetails(ContractStatus.ENDED);
    }

    @Transactional(readOnly = true)
    public Contract getContractById(Long contractId) {
        return contractRepository.findByIdWithDetails(contractId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy hợp đồng."));
    }

    @Transactional(readOnly = true)
    public Optional<Contract> getMemberActiveContract(Long memberTelegramId) {
        User member = userRepository.findByTelegramId(memberTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireMember(member);
        return contractRepository.findActiveContractByMemberTelegramId(memberTelegramId);
    }

    @Transactional(readOnly = true)
    public List<Contract> getExpiringContracts(int daysThreshold) {
        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.plusDays(daysThreshold);
        return contractRepository.findExpiringContracts(today, targetDate);
    }

    private void validateContractInputs(LocalDate startDate, LocalDate endDate, BigDecimal monthlyRent, BigDecimal deposit) {
        if (startDate == null) {
            throw new BusinessException("Ngày bắt đầu không được để trống.");
        }
        if (endDate == null) {
            throw new BusinessException("Ngày kết thúc không được để trống.");
        }
        if (!endDate.isAfter(startDate)) {
            throw new BusinessException("Ngày kết thúc phải sau ngày bắt đầu.");
        }
        if (monthlyRent == null || monthlyRent.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Giá thuê phải lớn hơn 0.");
        }
        if (deposit == null || deposit.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Tiền cọc không được nhỏ hơn 0.");
        }
    }
}
