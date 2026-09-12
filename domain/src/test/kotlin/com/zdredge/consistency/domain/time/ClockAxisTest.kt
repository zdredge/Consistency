package com.zdredge.consistency.domain.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * The 04:00 axis — the one piece of M8 with a real bug waiting in it.
 *
 * On an ordinary midnight axis a 01:30 bedtime is the smallest number of the month and plots as the
 * earliest night. Everything here is about the two consequences of measuring from 04:00 instead:
 * late nights sort last, and the arithmetic on times stops crossing the wrong midnight.
 */
@DisplayName("The 04:00 clock axis")
class ClockAxisTest {

    private fun at(h: Int, m: Int) = ClockAxis.minuteOf(LocalTime.of(h, m))

    @Test
    @DisplayName("the day starts at 04:00 and ends at 03:59")
    fun theBoundary() {
        assertEquals(0, at(4, 0), "04:00 is the first minute of the day")
        assertEquals(1439, at(3, 59), "03:59 is the last")
        assertEquals(ClockAxis.MINUTES, 1440)
    }

    @Test
    @DisplayName("5.4 - a 01:30 bedtime is the latest night, not the earliest")
    fun lateNightsSortLast() {
        // The whole reason this class exists. On a midnight axis 01:30 is 90 and 23:00 is 1380, so
        // the latest night of the month plots as the earliest.
        assertTrue(at(23, 0) < at(1, 30), "01:30 comes after 23:00")
        assertTrue(at(1, 30) < at(3, 59), "and before the day ends")
        assertTrue(at(20, 0) < at(23, 0))
    }

    @Test
    @DisplayName("a time just before the boundary is the end of the day, not the start")
    fun justBeforeTheBoundary() {
        assertTrue(at(3, 59) > at(22, 0), "03:59 is a very late night")
        assertEquals(0, at(4, 0))
        // 04:30 is the earliest the axis can show. Someone who went to bed then reads as the earliest
        // night rather than the latest -- the price of a fixed boundary (spec constraint 1).
        assertEquals(30, at(4, 30))
    }

    @Test
    @DisplayName("ordinary daytime positions")
    fun daytime() {
        assertEquals(120, at(6, 0))
        assertEquals(480, at(12, 0), "noon is eight hours into the day")
        assertEquals(1080, at(22, 0))
    }

    @Test
    @DisplayName("seconds are dropped rather than rounded up")
    fun secondsAreDropped() {
        assertEquals(at(22, 30), ClockAxis.minuteOf(LocalTime.of(22, 30, 59)))
    }

    @Test
    @DisplayName("every minute of the day converts back to itself")
    fun roundTrips() {
        for (minute in 0 until ClockAxis.MINUTES) {
            assertEquals(minute, ClockAxis.minuteOf(ClockAxis.timeAt(minute.toDouble())))
        }
    }

    @Test
    @DisplayName("an averaged position rounds to the nearest minute and stays on the axis")
    fun timeAtRounds() {
        assertEquals(LocalTime.of(22, 30), ClockAxis.timeAt(at(22, 30).toDouble() + 0.4))
        assertEquals(LocalTime.of(22, 31), ClockAxis.timeAt(at(22, 30).toDouble() + 0.6))
        assertEquals(LocalTime.of(4, 0), ClockAxis.timeAt(1440.0), "a position of exactly a day wraps to the start")
    }

    @Test
    @DisplayName("8.2 - the mean of 23:30 and 00:30 is midnight, not midday")
    fun averagingAcrossMidnight() {
        // The arithmetic reason for the axis. Averaging the clock readings gives 12:00 -- the middle
        // of the following afternoon -- which would put the trend line halfway down the chart.
        val mean = (at(23, 30) + at(0, 30)) / 2.0

        assertEquals(LocalTime.MIDNIGHT, ClockAxis.timeAt(mean))
    }
}
