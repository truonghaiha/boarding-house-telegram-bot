package com.boardinghousebot.config;

import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.repository.RoomStayRepository;
import com.boardinghousebot.repository.UserRepository;
import com.boardinghousebot.service.RoomStayService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** Runs after JPA creates the additive room_stays table. Existing records are preserved. */
@Component
public class RoomStayBackfill implements ApplicationRunner {
    private final UserRepository users;
    private final ContractRepository contracts;
    private final RoomStayRepository stays;
    private final RoomStayService service;
    public RoomStayBackfill(UserRepository users, ContractRepository contracts, RoomStayRepository stays, RoomStayService service) {
        this.users = users; this.contracts = contracts; this.stays = stays; this.service = service;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.now();
        for (var member : users.findByRoleWithRoom(Role.MEMBER)) {
            if (member.getRoom() == null || !stays.findByMemberTelegramIdAndRoomId(member.getTelegramId(), member.getRoom().getId()).isEmpty()) continue;
            // Only the named tenant's matching active contract establishes an earlier date.
            LocalDateTime joined = contracts.findActiveContractByMemberTelegramId(member.getTelegramId())
                    .filter(c -> c.getRoom().getId().equals(member.getRoom().getId()))
                    .map(c -> c.getStartDate().atStartOfDay()).filter(date -> !date.isAfter(now)).orElse(now);
            service.enter(member, member.getRoom(), joined);
        }
    }
}
