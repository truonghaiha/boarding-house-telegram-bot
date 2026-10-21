package com.boardinghousebot.util;

import com.boardinghousebot.domain.room.Room;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomFloorUtilTest {
    @ParameterizedTest
    @CsvSource({"101,1", "199,1", "201,2", "299,2", "1001,10", "99999,999", "214748364801,2147483648", "0101,1"})
    void derivesFloorFromAllButLastTwoDigits(String code, String floor) {
        assertEquals(floor, RoomFloorUtil.floorOf(room(code)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"A101", "99", "100", "200", "-101", "0"})
    void unsupportedLegacyCodesDoNotDisappear(String code) {
        assertEquals(RoomFloorUtil.UNKNOWN_FLOOR, RoomFloorUtil.floorOf(room(code)));
    }

    @Test
    void groupsSortNumericallyWithoutInventingEmptyFloors() {
        var groups = RoomFloorUtil.groupRooms(List.of(room("1001"), room("199"), room("101"), room("201"), room("A01")));
        assertEquals(List.of("1", "2", "10", "OTHER"), List.copyOf(groups.keySet()));
        assertEquals(List.of("101", "199"), groups.get("1").stream().map(Room::getRoomNumber).toList());
    }

    private Room room(String number) {
        Room room = new Room();
        room.setRoomNumber(number);
        return room;
    }
}
