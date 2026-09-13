package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.time.DayResolver
import java.time.LocalDate

/**
 * The stretch of time every figure is computed over.
 *
 * Spec §5.2 fixes one rolling window — 14 days — and case 9.8 requires *every* figure on a screen to
 * use it, so a hit rate and the run beside it can never be talking about different fortnights. This
 * object exists so that number has one home rather than being written out wherever a figure is
 * computed.
 *
 * The window ends on the item's **latest answerable day**, not on today: a morning item cannot have an
 * answer for tonight, so ending on today would give it thirteen real days and a guaranteed blank.
 */
object FiguresWindow {

    /** Spec §5.2. Changing this changes every figure in the app. */
    const val DAYS: Int = 14

    /** The window ending on [lastDay], oldest first. */
    fun days(lastDay: LocalDate): List<LocalDate> =
        (DAYS - 1 downTo 0).map { lastDay.minusDays(it.toLong()) }

    /**
     * The Mondays of the weeks that **closed** inside [window].
     *
     * A week counts once its Sunday is behind us. Spec §5.3 and case 11.4: the current week is
     * progress, not a verdict, so it is deliberately absent here — a week in flight must never be
     * counted as met or missed, and the running week is always in flight.
     */
    fun closedWeekStarts(window: List<LocalDate>, today: LocalDate, weeks: DayResolver): List<LocalDate> =
        window.map(weeks::weekStart)
            .distinct()
            .filter { today.isAfter(weeks.weekEnd(it)) }
            .sorted()
}
