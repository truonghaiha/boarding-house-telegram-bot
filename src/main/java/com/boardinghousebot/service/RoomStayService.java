package com.boardinghousebot.service;

import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStay;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.RoomStayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.repository.InvoiceRepository;
import com.boardinghousebot.state.UserConversationState.Step;

@Service
public class RoomStayService {
    private final RoomStayRepository repository;
    private final UserRepository users;
    private final ContractRepository contracts;
    private final InvoiceRepository invoices;
    public RoomStayService(RoomStayRepository repository, UserRepository users,
                           ContractRepository contracts, InvoiceRepository invoices) {
        this.repository = repository;
        this.users = users;
        this.contracts = contracts;
        this.invoices = invoices;
    }

    public record CurrentResidence(Room room, boolean unconfirmed) {
        public boolean isRenting() { return room != null && !unconfirmed; }
        public String displayStatus() {
            if (unconfirmed) return "Chưa xác nhận thuê phòng – vui lòng liên hệ chủ trọ";
            return isRenting() ? "Đang thuê – Phòng " + room.getRoomNumber() : "Chưa thuê phòng";
        }
    }

    /** Assignment opens a stay; ending the room's contract closes its residents' stays. */
    @Transactional(readOnly = true)
    public CurrentResidence getCurrentResidence(Long telegramId) {
        User member = users.findByTelegramIdWithRoom(telegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        if (member.getRole() != Role.MEMBER) {
            throw new BusinessException("Chỉ MEMBER mới có trạng thái thuê phòng.");
        }
        var openStays = repository.findByMemberTelegramIdAndLeftAtIsNull(telegramId);
        var activeContract = contracts.findActiveContractByMemberTelegramId(telegramId);
        Room room = member.getRoom();
        if (room == null) return new CurrentResidence(null, !openStays.isEmpty() || activeContract.isPresent());
        // Require a single, already started interval matching the current assignment.
        if (openStays.size() != 1 || openStays.get(0).getJoinedAt().isAfter(LocalDateTime.now())
                || !room.getId().equals(openStays.get(0).getRoom().getId())) {
            return new CurrentResidence(null, true);
        }
        if (activeContract.isPresent() && !room.getId().equals(activeContract.get().getRoom().getId())) {
            return new CurrentResidence(null, true);
        }
        // A co-tenant needs a confirmed stay, not a contract in their own name.
        return new CurrentResidence(room, false);
    }

    @Transactional(readOnly = true)
    public Optional<Room> getCurrentRoom(Long telegramId) {
        return Optional.ofNullable(getCurrentResidence(telegramId).room());
    }

    /** Only read routes survive checkout; the existing services still scope each record. */
    @Transactional(readOnly = true)
    public boolean allowsMemberAction(User user, String command, String action, Step step) {
        if (user.getRole() != Role.MEMBER) return true;
        if (command.equals("/start") || command.equals("/help") || command.equals("/menu")) return true;
        if (command.equals("/room") && step == Step.NONE && action != null
                && (action.equals("MEMBER_AVAILABLE_ROOMS") || action.matches("MEMBER_AVAILABLE_ROOMS_[0-9]+"))) return true;
        if (getCurrentResidence(user.getTelegramId()).isRenting()) return true;
        if (step != Step.NONE || command.equals("/room") || command.equals("/tenant")) return false;
        boolean hasHistory = repository.existsByMemberTelegramIdAndJoinedAtLessThanEqual(user.getTelegramId(), LocalDateTime.now())
                || contracts.existsByMemberTelegramId(user.getTelegramId())
                || invoices.existsByMemberTelegramId(user.getTelegramId());
        if (!hasHistory) return false;
        return switch (command) {
            case "/invoice", "/meter", "/notification" -> true;
            case "/contract" -> action.startsWith("CONTRACT_DETAIL_") || action.startsWith("/contract_detail_");
            case "/maintenance" -> action.equals("MAINTENANCE_LIST_MEMBER")
                    || action.startsWith("MAINTENANCE_DETAIL_") || action.startsWith("/maintenance_detail_");
            default -> false;
        };
    }

    @Transactional
    public void enter(User member, Room room, LocalDateTime time) {
        repository.save(new RoomStay(member, room, time));
    }
    @Transactional
    public void leave(User member, LocalDateTime time) {
        repository.findByMemberTelegramIdAndLeftAtIsNull(member.getTelegramId())
                .forEach(stay -> stay.leave(time));
    }
    @Transactional(readOnly = true)
    public boolean canReadPeriod(User member, Long roomId, int month, int year, LocalDateTime createdAt) {
        return repository.findByMemberTelegramIdAndRoomId(member.getTelegramId(), roomId).stream()
                .anyMatch(stay -> stay.covers(month, year, createdAt));
    }

    @Transactional(readOnly = true)
    public boolean canReadInterval(User member, Long roomId, java.time.LocalDate start, java.time.LocalDate end, LocalDateTime createdAt) {
        return repository.findByMemberTelegramIdAndRoomId(member.getTelegramId(),roomId).stream().anyMatch(stay ->
                stay.getJoinedAt().isBefore((end.equals(start)?end.plusDays(1):end).atStartOfDay())
                && (stay.getLeftAt()==null || stay.getLeftAt().toLocalDate().isAfter(start))
                && (createdAt==null || !stay.getJoinedAt().isAfter(createdAt)));
    }

    @Transactional(readOnly = true)
    public boolean canReadInvoice(User member, com.boardinghousebot.domain.invoice.Invoice invoice) {
        return (invoice.getMember() != null && member.getTelegramId().equals(invoice.getMember().getTelegramId()))
                || (invoice.getPeriodStart()!=null
                    ? canReadInterval(member,invoice.getRoom().getId(),invoice.getPeriodStart(),invoice.getPeriodEnd(),invoice.getCreatedAt())
                    : canReadPeriod(member, invoice.getRoom().getId(), invoice.getBillingMonth(), invoice.getBillingYear(), invoice.getCreatedAt()));
    }
}
