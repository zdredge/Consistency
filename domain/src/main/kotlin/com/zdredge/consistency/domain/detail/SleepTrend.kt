package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.time.ClockAxis
import java.time.LocalDate
import java.time.LocalTime

/** One point on the trend line: the night it sits over, and its position on the 04:00 axis. */
data class TrendPoint(val night: LocalDate, val minute: Double)

/**
 * The line through a sleep chart, and the one number beside it.
 *
 * **Every calculation here is in axis minutes, never clock minutes.** The mean of 23:30 and 00:30 is
 * midnight; averaging the clock readings gives 12:00, the middle of the next afternoon. Measuring from
 * 04:00 first makes the arithmetic ordinary — see [ClockAxis].
 */
object SleepTrend {

    /** Nights in the trailing window. */
    const val NIGHTS: Int = 7

    /** Below this many answered nights in the window, no point is drawn. */
    const val MINIMUM: Int = 3

    /**
     * The rolling average, one point per shown night that has enough history behind it.
     *
     * The window is the last [NIGHTS] nights **the filter shows**, answered or not, so a gap reads as
     * a gap: after several unanswered nights the line stops instead of drawing a straight bridge over
     * them. Nights before [from] still feed the window, so the first week of the chart is not blank
     * when there is history behind it.
     */
    fun rollingAverage(
        timesByNight: Map<LocalDate, LocalTime>,
        filter: NightFilter,
        from: LocalDate,
        to: LocalDate,
    ): List<TrendPoint> {
        val shown = shownNights(timesByNight, filter, to)
        if (shown.isEmpty()) return emptyList()

        return shown.withIndex()
            // A point only sits over a night that was actually answered. Plotting one over a blank
            // night would draw the line on across silence using older readings, which is exactly the
            // bridging the night-shaped window is meant to prevent.
            .filter { (_, night) -> !night.isBefore(from) && timesByNight.containsKey(night) }
            .mapNotNull { (index, night) ->
                val window = shown.subList(maxOf(0, index - (NIGHTS - 1)), index + 1)
                val answered = window.mapNotNull { timesByNight[it] }
                if (answered.size < MINIMUM) return@mapNotNull null
                TrendPoint(night, answered.map { ClockAxis.minuteOf(it).toDouble() }.average())
            }
    }

    /**
     * The typical time over the nights the filter shows within `from..to` — the median, so one very
     * late night does not drag the figure the way a mean would.
     *
     * An even count takes the mean of the middle two, which is why this returns a position rather than
     * a time: converting once, at the end, is what keeps it on the right side of midnight.
     */
    fun typicalMinute(
        timesByNight: Map<LocalDate, LocalTime>,
        filter: NightFilter,
        from: LocalDate,
        to: LocalDate,
    ): Double? {
        val minutes = timesByNight
            .filterKeys { !it.isBefore(from) && !it.isAfter(to) && filter.shows(it) }
            .values
            .map { ClockAxis.minuteOf(it).toDouble() }
            .sorted()

        if (minutes.isEmpty()) return null
        val middle = minutes.size / 2
        return if (minutes.size % 2 == 1) minutes[middle] else (minutes[middle - 1] + minutes[middle]) / 2
    }

    /**
     * Every night the filter shows, up to [to], in order — including unanswered ones, which is what
     * makes the window above a window of *nights* rather than of answers.
     *
     * Walks from the earliest night on record rather than counting backwards, so an empty [Custom]
     * set simply yields nothing instead of searching for a seventh night that will never come.
     */
    private fun shownNights(
        timesByNight: Map<LocalDate, LocalTime>,
        filter: NightFilter,
        to: LocalDate,
    ): List<LocalDate> {
        val earliest = timesByNight.keys.minOrNull() ?: return emptyList()
        if (filter.nights.isEmpty()) return emptyList()

        return generateSequence(earliest) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .filter(filter::shows)
            .toList()
    }
}
