package com.boardinghousebot.util;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
class BillingDatesTest {
    @Test void january31ReturnsTo31AfterFebruary() {
        var anchor=LocalDate.of(2027,1,31);
        assertEquals(LocalDate.of(2027,2,28),BillingDates.next(anchor,anchor));
        assertEquals(LocalDate.of(2027,3,31),BillingDates.next(anchor,LocalDate.of(2027,2,28)));
        assertEquals(LocalDate.of(2027,4,30),BillingDates.next(anchor,LocalDate.of(2027,3,31)));
        assertEquals(LocalDate.of(2027,5,31),BillingDates.next(anchor,LocalDate.of(2027,4,30)));
    }
    @Test void leapYearsAndOtherEndOfMonthAnchors() {
        assertEquals(LocalDate.of(2028,2,29),BillingDates.next(LocalDate.of(2028,1,31),LocalDate.of(2028,1,31)));
        assertEquals(LocalDate.of(2028,3,29),BillingDates.next(LocalDate.of(2028,2,29),LocalDate.of(2028,2,29)));
        assertEquals(LocalDate.of(2027,2,28),BillingDates.due(LocalDate.of(2026,10,30),2,2027));
        assertEquals(LocalDate.of(2027,3,30),BillingDates.due(LocalDate.of(2026,10,30),3,2027));
    }
    @Test void normalAndLateStartsRemainAnchored() {
        assertEquals(LocalDate.of(2026,11,18),BillingDates.next(LocalDate.of(2026,10,18),LocalDate.of(2026,10,18)));
        assertEquals(LocalDate.of(2026,12,18),BillingDates.next(LocalDate.of(2026,10,18),LocalDate.of(2026,11,20)));
    }
}
