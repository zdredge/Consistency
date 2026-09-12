package com.zdredge.consistency.domain.detail

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Which nights a sleep chart shows.
 *
 * **Sunday to Thursday is one set for all three sleep items**, which is only true because of how the
 * app files them. Every sleep answer belongs to the night the user went to bed (spec §3.1), so Monday
 * morning's wake-up is stored under Sunday. The nights before a working day are therefore Sunday to
 * Thursday whether you are looking at bedtimes or at wake-ups, and the same filter serves both.
 *
 * It is a view control, not a stored setting: the chart opens on [OPENING] every time. A filter
 * remembered from weeks ago would be hiding nights nobody had asked it to hide.
 */
sealed interface NightFilter {

    val nights: Set<DayOfWeek>

    /** Tests the night itself — the answer's own day, never the day the check-in happened. */
    fun shows(night: LocalDate): Boolean = night.dayOfWeek in nights

    data object EveryNight : NightFilter {
        override val nights: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    }

    /** The nights before a working day. */
    data object SundayToThursday : NightFilter {
        override val nights: Set<DayOfWeek> = setOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        )
    }

    data class Custom(override val nights: Set<DayOfWeek>) : NightFilter

    companion object {
        /**
         * What the chart opens on.
         *
         * A getter rather than a stored value on purpose. As `val OPENING = SundayToThursday` this
         * read **null** at runtime: the companion initialises before the nested object it points at,
         * so the constant was captured before it existed. It cost nothing to find here and would have
         * opened the chart on no filter at all.
         */
        val OPENING: NightFilter get() = SundayToThursday

        /**
         * Custom, starting from whatever is on screen — so adding Friday to Sunday-to-Thursday is one
         * tap rather than seven.
         */
        fun customFrom(current: NightFilter): Custom = Custom(current.nights)
    }
}
