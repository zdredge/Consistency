package com.zdredge.consistency.domain.detail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Which nights a sleep chart shows.
 *
 * The one fact worth a test of its own: Sunday-to-Thursday is the nights before a working day for
 * *all three* sleep items, and that is only true because every sleep answer is filed under the night
 * the user went to bed. If that ever changed, this filter would quietly start showing wake-ups from
 * the wrong days.
 */
@DisplayName("The night filter")
class NightFilterTest {

    private val sunday = LocalDate.of(2026, 9, 6)

    @Test
    @DisplayName("Sunday to Thursday is the nights before a working day")
    fun theWeeknightSet() {
        val weeknights = NightFilter.SundayToThursday

        assertTrue(weeknights.shows(sunday), "Sunday night comes before Monday")
        assertTrue(weeknights.shows(sunday.plusDays(4)), "Thursday night comes before Friday")
        assertFalse(weeknights.shows(sunday.plusDays(5)), "Friday night does not")
        assertFalse(weeknights.shows(sunday.plusDays(6)), "nor Saturday")
        assertEquals(5, weeknights.nights.size)
    }

    @Test
    @DisplayName("3.1 - a Monday wake-up is filed under Sunday, so one set covers bedtime and waking")
    fun wakeUpsFollowTheirNight() {
        // Monday morning's wake-up belongs to Sunday night. Filtering on the answer's own day is
        // therefore right for all three sleep items without a second rule for the morning ones.
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertTrue(NightFilter.SundayToThursday.shows(sunday))
    }

    @Test
    @DisplayName("every night shows all seven")
    fun everyNight() {
        assertEquals(7, NightFilter.EveryNight.nights.size)
        assertTrue((0..6).all { NightFilter.EveryNight.shows(sunday.plusDays(it.toLong())) })
    }

    @Test
    @DisplayName("the chart opens on Sunday to Thursday")
    fun theOpeningFilter() {
        // Deliberately not remembered between visits: a filter set weeks ago would be hiding nights
        // nobody had asked it to hide.
        assertEquals(NightFilter.SundayToThursday, NightFilter.OPENING)
    }

    @Test
    @DisplayName("custom starts from what is already showing, so adding Friday is one tap")
    fun customStartsFromTheCurrentSet() {
        val custom = NightFilter.customFrom(NightFilter.SundayToThursday)

        assertEquals(NightFilter.SundayToThursday.nights, custom.nights)
        assertTrue(NightFilter.Custom(custom.nights + DayOfWeek.FRIDAY).shows(sunday.plusDays(5)))
    }
}
