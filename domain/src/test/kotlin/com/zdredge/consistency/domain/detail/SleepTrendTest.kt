package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.time.ClockAxis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The trend line and the typical time.
 *
 * Both exist because the user asked for them after seeing round 1, and both have the same trap: a
 * time is not a number until it is measured from 04:00. Everything here is also filtered, which is the
 * milestone's stated exit criterion — an average that quietly included Friday nights would make the
 * weeknight view a lie.
 */
@DisplayName("The sleep trend line and typical time")
class SleepTrendTest {

    /** Monday 2026-08-31 through Sunday 2026-09-13, so both weeks are whole. */
    private val monday = LocalDate.of(2026, 8, 31)
    private fun day(n: Long) = monday.plusDays(n)

    private fun nights(vararg pairs: Pair<Long, String>) =
        pairs.associate { (n, t) -> day(n) to LocalTime.parse(t) }

    private fun at(t: String) = ClockAxis.minuteOf(LocalTime.parse(t)).toDouble()

    @Test
    @DisplayName("no point is drawn until three of the window's nights have been answered")
    fun theMinimum() {
        val two = nights(0L to "22:30", 1L to "23:00")
        assertTrue(SleepTrend.rollingAverage(two, NightFilter.EveryNight, monday, day(6)).isEmpty())

        val three = nights(0L to "22:30", 1L to "23:00", 2L to "23:30")
        val points = SleepTrend.rollingAverage(three, NightFilter.EveryNight, monday, day(6))
        assertEquals(1, points.size, "only the third night has enough behind it")
        assertEquals(day(2), points.single().night)
        assertEquals(at("23:00"), points.single().minute)
    }

    @Test
    @DisplayName("8.2 - the average crosses midnight without landing in the afternoon")
    fun acrossMidnight() {
        val late = nights(0L to "23:30", 1L to "23:30", 2L to "00:30")
        val point = SleepTrend.rollingAverage(late, NightFilter.EveryNight, monday, day(6)).last()

        // The mean of 23:30, 23:30 and 00:30 is 23:50. On clock readings it would be about 15:50.
        assertEquals(LocalTime.of(23, 50), ClockAxis.timeAt(point.minute))
    }

    @Test
    @DisplayName("the window is seven nights, so an old night drops out")
    fun theWindowMovesOn() {
        val eight = nights(
            0L to "01:00", 1L to "22:00", 2L to "22:00", 3L to "22:00",
            4L to "22:00", 5L to "22:00", 6L to "22:00", 7L to "22:00",
        )
        val points = SleepTrend.rollingAverage(eight, NightFilter.EveryNight, monday, day(7))

        // The eighth night's window has dropped the 01:00, so it averages seven identical 22:00s.
        assertEquals(at("22:00"), points.last().minute)
    }

    @Test
    @DisplayName("a filtered-out night never reaches the average — the milestone's exit criterion")
    fun theFilterIsHonoured() {
        // Friday 2026-09-04 is day(4). A very late Friday must not move a weeknight average at all.
        val withFriday = nights(0L to "22:00", 1L to "22:00", 2L to "22:00", 4L to "03:00")
        val weeknights = SleepTrend.rollingAverage(withFriday, NightFilter.SundayToThursday, monday, day(6))

        assertEquals(DayOfWeek.FRIDAY, day(4).dayOfWeek)
        assertTrue(weeknights.none { it.night == day(4) }, "Friday gets no point of its own")
        assertTrue(weeknights.all { it.minute == at("22:00") }, "and pulls no other point later")

        val everyNight = SleepTrend.rollingAverage(withFriday, NightFilter.EveryNight, monday, day(6))
        assertTrue(everyNight.any { it.night == day(4) }, "but it is there when every night is shown")
    }

    @Test
    @DisplayName("nights before the chart still feed its first points")
    fun historyBeforeTheWindow() {
        val history = nights(0L to "22:00", 1L to "22:00", 2L to "22:00", 3L to "22:30")
        val points = SleepTrend.rollingAverage(history, NightFilter.EveryNight, from = day(3), to = day(6))

        assertEquals(1, points.size)
        assertEquals(day(3), points.single().night, "the first chart night has a point immediately")
    }

    @Test
    @DisplayName("a run of unanswered nights leaves a gap rather than a straight line across it")
    fun gapsStayGaps() {
        // Answered, then a week of silence, then answered again. The window is nights, not answers, so
        // the line stops until three answers are back inside it.
        val gapped = nights(0L to "22:00", 1L to "22:00", 2L to "22:00", 10L to "23:00", 11L to "23:00")
        val points = SleepTrend.rollingAverage(gapped, NightFilter.EveryNight, monday, day(11))

        assertTrue(points.none { it.night == day(10) }, "two answers in the window is not enough")
        assertTrue(points.none { it.night == day(11) })
    }

    @Test
    @DisplayName("an empty custom selection draws nothing instead of searching for a night that never comes")
    fun emptyCustom() {
        val some = nights(0L to "22:00", 1L to "22:00", 2L to "22:00")

        assertTrue(SleepTrend.rollingAverage(some, NightFilter.Custom(emptySet()), monday, day(6)).isEmpty())
        assertNull(SleepTrend.typicalMinute(some, NightFilter.Custom(emptySet()), monday, day(6)))
    }

    @Test
    @DisplayName("the typical time is the middle night, not the average of them")
    fun typicalIsAMedian() {
        // One 03:00 night must not drag the figure the way a mean would.
        val skewed = nights(0L to "22:00", 1L to "22:30", 2L to "23:00", 3L to "03:00")
        val typical = SleepTrend.typicalMinute(skewed, NightFilter.EveryNight, monday, day(6))!!

        // An even count takes the middle two: 22:30 and 23:00.
        assertEquals(LocalTime.of(22, 45), ClockAxis.timeAt(typical))
    }

    @Test
    @DisplayName("an odd count takes the middle night exactly")
    fun typicalWithAnOddCount() {
        val three = nights(0L to "22:00", 1L to "23:00", 2L to "01:00")
        val typical = SleepTrend.typicalMinute(three, NightFilter.EveryNight, monday, day(6))!!

        assertEquals(LocalTime.of(23, 0), ClockAxis.timeAt(typical))
    }

    @Test
    @DisplayName("the typical time follows the filter and its window")
    fun typicalRespectsFilterAndWindow() {
        val withWeekend = nights(0L to "22:00", 1L to "22:00", 4L to "02:00", 5L to "02:00")

        assertEquals(
            LocalTime.of(22, 0),
            ClockAxis.timeAt(SleepTrend.typicalMinute(withWeekend, NightFilter.SundayToThursday, monday, day(6))!!),
        )
        assertNull(
            SleepTrend.typicalMinute(withWeekend, NightFilter.EveryNight, from = day(2), to = day(3)),
            "a window with no answered nights has no typical time",
        )
    }
}
