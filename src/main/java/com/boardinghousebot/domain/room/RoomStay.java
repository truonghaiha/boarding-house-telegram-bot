package com.boardinghousebot.domain.room;

import com.boardinghousebot.domain.user.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** A recorded occupancy interval; moving never erases a previous stay. */
@Entity
@Table(name = "room_stays", indexes = @Index(name = "idx_stay_member_room", columnList = "member_id,room_id"))
public class RoomStay {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "member_id", nullable = false)
    private User member;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "room_id", nullable = false)
    private Room room;
    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;
    @Column(name = "left_at")
    private LocalDateTime leftAt;

    protected RoomStay() {}
    public RoomStay(User member, Room room, LocalDateTime joinedAt) {
        this.member = member;
        this.room = room;
        this.joinedAt = joinedAt;
    }
    public Long getId() { return id; }
    public Room getRoom() { return room; }
    public LocalDateTime getJoinedAt() { return joinedAt; }
    public LocalDateTime getLeftAt() { return leftAt; }
    public void leave(LocalDateTime time) { leftAt = time.isBefore(joinedAt) ? joinedAt : time; }

    public boolean covers(int month, int year, LocalDateTime createdAt) {
        YearMonth period = YearMonth.of(year, month);
        LocalDateTime start = period.atDay(1).atStartOfDay();
        LocalDateTime end = period.plusMonths(1).atDay(1).atStartOfDay();
        return joinedAt.isBefore(end) && (leftAt == null || leftAt.isAfter(start))
                && createdAt != null && !createdAt.isBefore(joinedAt);
    }
}
