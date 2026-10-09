package com.boardinghousebot.observer;

import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.User;

public class MemberRoomChangedEvent {

    public enum ChangeType {
        ASSIGNED,
        REMOVED,
        MOVED
    }

    private final User member;
    private final ChangeType changeType;
    private final Room oldRoom;
    private final Room newRoom;

    public MemberRoomChangedEvent(User member, ChangeType changeType, Room oldRoom, Room newRoom) {
        this.member = member;
        this.changeType = changeType;
        this.oldRoom = oldRoom;
        this.newRoom = newRoom;
    }

    public User getMember() {
        return member;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public Room getOldRoom() {
        return oldRoom;
    }

    public Room getNewRoom() {
        return newRoom;
    }
}
