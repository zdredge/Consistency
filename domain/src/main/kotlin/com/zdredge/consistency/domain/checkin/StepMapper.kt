package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import java.time.Instant
import java.time.LocalDate

/**
 * What a day's step records mean.
 *
 * **The origin guard lives here, and it is the reason this is a pure function rather than three lines
 * inside the Health Connect reader.** Architecture §8 rates step double-counting Medium and *dormant
 * rather than hypothetical*: M0 found exactly one origin on the device, but Samsung Health and Google
 * Health are both installed and simply are not writing steps. A second origin needs no new hardware —
 * just one of them starting to sync — and because the dashboard shows a 14-day window, silently
 * summing two sources would read as **improvement** rather than as a bug. That is a failure nobody
 * would ever catch by looking, so it is caught by a test instead.
 *
 * Deliberately not a reconciliation system (architecture §5). It groups, it refuses to add, and it
 * stops. Actual multi-source merging waits until a second source genuinely exists.
 */
object StepMapper {

    /**
     * The measured value for [day], or **null when nothing was recorded**.
     *
     * No records is not zero steps. "Did not walk" and "has not synced yet" are indistinguishable
     * from here, and writing `0.0` would resolve that ambiguity in the direction that scores a miss
     * the user cannot have earned. A missing row is honest; `RolloverPlanner` leaves it alone, and a
     * later read fills it in if the data arrives.
     *
     * [now] is the read time, not the day: it becomes `lastSyncedAt`, which anchors the O4 freeze
     * window. A value re-read late is young again, which is the whole point of tolerating late syncs.
     */
    fun map(
        itemId: ItemId,
        day: LocalDate,
        origins: List<MeasuredOrigin>,
        now: Instant,
    ): MeasuredValue? {
        val reported = origins.filter { it.value > 0.0 }
        if (reported.isEmpty()) return null

        return MeasuredValue(
            itemId = itemId,
            day = day,
            // On a conflicted day this total is diagnostic only -- it is what the sources add up to,
            // kept so a flagged day can be investigated. Nothing scores it and nothing displays it
            // as a step count. See MeasuredState.CONFLICTED.
            value = reported.sumOf { it.value },
            state = if (reported.size > 1) {
                MeasuredState.CONFLICTED
            } else {
                MeasuredState.PROVISIONAL
            },
            lastSyncedAt = now,
            origins = reported,
        )
    }
}
