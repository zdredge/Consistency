package com.zdredge.consistency.domain.scoring

import java.time.Duration
import java.time.LocalTime

/**
 * The two cross-item metrics, both null when they cannot be computed.
 *
 * Null means **unavailable**, never zero. Zero lingering is a real answer -- the user got straight
 * up -- and is a different fact from not knowing when they got up (scoring-cases 8.4).
 */
data class SleepMetrics(
    val sleepDuration: Duration?,
    val lingering: Duration?,
)

/**
 * Sleep duration and minutes lingering in bed.
 *
 * Spec constraint 13: this pair is **hardcoded** to the three sleep/wake *time* items -- bedtime,
 * woke at, got out of bed; not the pre-sleep multi-select -- and does not generalise to user-created
 * questions. Taking three LocalTimes rather than a formula over arbitrary items *is* that
 * special-casing, and it is deliberate: generalising it would need a type system, validation,
 * retired-source handling and a UI, for a feature with two known uses.
 *
 * Neither figure is ever persisted; both are computed on read (architecture section 5).
 *
 * **On the formulas.** Sleep duration is `woke - bedtime` and lingering is `got up - woke`, so the
 * two are disjoint and together account for the whole time in bed. Spec 3.4 briefly read
 * "(got up - bedtime)" for sleep duration; that was a typo, since it would fold the lingering
 * minutes into sleep and double-count them, and it contradicted every worked number -- 8.1, 8.2 and
 * the architecture worked example all give 9h 00m for a 23:30/08:30/08:52 night. Corrected in the
 * spec during M2.
 */
object DerivedMetrics {

    fun sleep(bedtime: LocalTime?, wokeAt: LocalTime?, gotUpAt: LocalTime?): SleepMetrics =
        SleepMetrics(
            sleepDuration = elapsed(from = bedtime, to = wokeAt),
            lingering = elapsed(from = wokeAt, to = gotUpAt),
        )

    /**
     * Clock time from one moment to another within a single night, wrapping forward over midnight.
     *
     * Scoring-cases 8.3: 23:30 to 08:30 subtracts to minus fifteen hours, so when the end is earlier
     * on the clock than the start, a day is added. A night never legitimately spans more than 24
     * hours, so this is unambiguous.
     */
    private fun elapsed(from: LocalTime?, to: LocalTime?): Duration? {
        if (from == null || to == null) return null
        val naive = Duration.between(from, to)
        return if (naive.isNegative) naive.plusDays(1) else naive
    }
}
