package com.zdredge.consistency.domain.detail

import kotlin.math.ceil
import kotlin.math.floor

/** One shade on a calendar, and whether landing in it means the target was reached. */
data class ShadeBucket(val from: Int, val toInclusive: Int?, val reachesTarget: Boolean)

/**
 * How much of something a day's shade stands for.
 *
 * **The contrast goes where the answers are.** Meals are nearly always 2 or 3, and 3 is the target, so
 * a flat five-step ramp made the difference that matters the faintest step on the chart. The shades
 * are therefore anchored on the target: everything well below it collapses into one dim shade, the
 * step just below the target gets its own, the target gets its own, and anything above shares the
 * brightest. Meals (target 3) gets 0–1, 2, 3, 4+; water (target 2) gets 0–1, 2, 3+ — the low bucket
 * folds away when there is nothing between zero and the step below the target.
 */
data class ShadeScale(val buckets: List<ShadeBucket>) {

    /**
     * Which shade [value] takes.
     *
     * **Halves round down.** The quick-pick buttons are whole numbers but the keypad can still enter
     * 1.5 bottles, and 1.5 is not 2: rounding up would paint a day that fell short in the colour of a
     * day that reached the target. The invariant this preserves is worth stating — a value lands in a
     * bucket marked [ShadeBucket.reachesTarget] only when it genuinely reached the target.
     */
    fun bucketOf(value: Double): Int {
        val whole = floor(value).toInt().coerceAtLeast(0)
        val index = buckets.indexOfLast { whole >= it.from }
        return if (index < 0) 0 else index
    }

    companion object {
        /** The 1–5 mindset scale: every value its own shade, and nothing to reach. */
        val SCALE_RANGE: IntRange = 1..5

        /**
         * Shades anchored on [target], as described above. A fractional target is rounded up, because
         * the bucket that "reaches" it must not include values below it.
         */
        fun targetAnchored(target: Double): ShadeScale {
            val t = ceil(target).toInt().coerceAtLeast(1)
            // The dim shade is only worth its own step when something sits between zero and the step
            // below the target. For a target of 2 that range is empty, so zero folds in with one and
            // the shade below the target starts at zero -- which is what gives water 0-1, 2, 3+ and
            // meals 0-1, 2, 3, 4+ from the same rule.
            val lowest = t - 2 >= 1
            val buckets = buildList {
                if (lowest) add(ShadeBucket(from = 0, toInclusive = t - 2, reachesTarget = false))
                add(ShadeBucket(from = if (lowest) t - 1 else 0, toInclusive = t - 1, reachesTarget = false))
                add(ShadeBucket(from = t, toInclusive = t, reachesTarget = true))
                add(ShadeBucket(from = t + 1, toInclusive = null, reachesTarget = true))
            }
            return ShadeScale(buckets)
        }

        /** One shade per value, for a fixed scale with no target — mindset. */
        fun perValue(range: IntRange): ShadeScale = ShadeScale(
            range.map { ShadeBucket(from = it, toInclusive = it, reachesTarget = false) },
        )
    }
}
