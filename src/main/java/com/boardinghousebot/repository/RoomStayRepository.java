package com.boardinghousebot.repository;

import com.boardinghousebot.domain.room.RoomStay;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RoomStayRepository extends JpaRepository<RoomStay, Long> {
    List<RoomStay> findByMemberTelegramIdAndRoomId(Long telegramId, Long roomId);
    List<RoomStay> findByMemberTelegramIdAndLeftAtIsNull(Long telegramId);
    boolean existsByMemberTelegramIdAndJoinedAtLessThanEqual(Long telegramId, java.time.LocalDateTime time);
}
