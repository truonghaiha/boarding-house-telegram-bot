package com.boardinghousebot.util;

import com.boardinghousebot.domain.room.Room;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Derives floors from existing room numbers without adding persistent floor data. */
public final class RoomFloorUtil {
    public static final String UNKNOWN_FLOOR = "OTHER";
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);

    private RoomFloorUtil() {}

    public static String floorOf(Room room) {
        String number = room.getRoomNumber();
        if (number == null || !number.trim().matches("[0-9]+")) return UNKNOWN_FLOOR;
        BigInteger[] parts = new BigInteger(number.trim()).divideAndRemainder(HUNDRED);
        // 101..199, 201..299, ...; other legacy codes stay accessible separately.
        if (parts[0].signum() <= 0 || parts[1].signum() == 0) return UNKNOWN_FLOOR;
        return parts[0].toString();
    }

    public static String label(String floor) {
        return UNKNOWN_FLOOR.equals(floor) ? "Chưa xác định tầng" : "Tầng " + floor;
    }

    public static Map<String, List<Room>> groupRooms(List<Room> rooms) {
        Map<String, List<Room>> floors = new TreeMap<>((left, right) -> {
            if (left.equals(right)) return 0;
            if (UNKNOWN_FLOOR.equals(left)) return 1;
            if (UNKNOWN_FLOOR.equals(right)) return -1;
            return new BigInteger(left).compareTo(new BigInteger(right));
        });
        for (Room room : rooms) {
            floors.computeIfAbsent(floorOf(room), key -> new ArrayList<>()).add(room);
        }
        floors.values().forEach(floorRooms -> floorRooms.sort(
                Comparator.comparing(Room::getRoomNumber, RoomFloorUtil::compareRoomNumbers)));
        return floors;
    }

    private static int compareRoomNumbers(String left, String right) {
        if (left == null) return right == null ? 0 : -1;
        if (right == null) return 1;
        if (left.matches("[0-9]+") && right.matches("[0-9]+")) {
            int numericOrder = new BigInteger(left).compareTo(new BigInteger(right));
            if (numericOrder != 0) return numericOrder;
        }
        return left.compareTo(right);
    }
}
