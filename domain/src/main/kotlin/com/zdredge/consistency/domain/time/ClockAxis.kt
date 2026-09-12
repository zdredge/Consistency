package com.zdredge.consistency.domain.time

import java.time.Duration
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * A clock time as a position on the app's day.
 *
 * **This exists because a bedtime chart on an ordinary time axis is wrong, not merely ugly.** Under a
 * midnight axis a 01:30 bedtime sits at the very bottom and reads as the *earliest* night of the
 * month, when it was the latest. The app's day starts at 04:00 (spec appendix constraint 1), so
 * measuring from there puts 23:00, 01:30 and 03:59 in the order a person would put them in.
 *
 * It also fixes arithmetic, not just placement: the mean of 23:30 and 00:30 is midnight, but averaging
 * their clock values gives **12:00**, the middle of the following day. Every average and median of a
 * time in this app is computed in these minutes and converted back at the end.
 *
 * Deliberately `LocalTime`, never `Instant`: a wall-clock bedtime is what the user reported, so the
 * night the clocks change cannot shift a dot.
 */
object ClockAxis {

    /** Minutes in a day. A position is always in `0 until MINUTES`. */
    const val MINUTES: Int = 24 * 60

    /**
     * Where [time] falls on the day that starts at 04:00. 04:00 is 0 and 03:59 is 1439.
     *
     * Reads [DayResolver.DAY_START] rather than restating 04:00, so the boundary has one home
     * (`CLAUDE.md`). Seconds are dropped: the app records times to the minute.
     */
    fun minuteOf(time: LocalTime): Int {
        val fromDayStart = Duration.between(DayResolver.DAY_START, time).toMinutes()
        return ((fromDayStart % MINUTES) + MINUTES).toInt() % MINUTES
    }

    /**
     * The clock time at [minute] on the axis — the inverse of [minuteOf], for axis labels and for
     * turning an averaged position back into something to show.
     *
     * Takes a `Double` because averages land between minutes; it rounds to the nearest.
     */
    fun timeAt(minute: Double): LocalTime {
        val rounded = ((minute.roundToInt() % MINUTES) + MINUTES) % MINUTES
        return DayResolver.DAY_START.plusMinutes(rounded.toLong())
    }
}
