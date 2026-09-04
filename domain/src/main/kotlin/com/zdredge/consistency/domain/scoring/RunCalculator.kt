package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.GoalOutcome
import java.time.LocalDate

/** A run's live length and the historical high-water mark. */
data class RunSummary(val current: Int, val longest: Int)

/**
 * Spec 3.5. The global run counts consecutive days on which **every scheduled check-in was
 * answered** -- it measures showing up, not performing.
 *
 * The rationale is worth keeping: an all-goals-met run would sit near zero permanently with ten-plus
 * goals, and under that definition the user's incentive on a bad day is to not open the app. Under
 * this definition the bad day and the honest answer are the same action, so the run rewards honesty
 * instead of punishing it. **Goal outcomes are not an input to the global run at all.**
 */
object RunCalculator {

    /**
     * A day counts toward the run when it had at least one scheduled check-in and all of them were
     * answered. Backfill within grace preserves the run (4.3) because such a check-in is ANSWERED; a
     * LATE answer does not (A1.3) because its check-in stays MISSED.
     */
    fun globalRun(checkIns: List<CheckIn>, upTo: LocalDate): RunSummary {
        val byDay = checkIns.groupBy { it.day }
        val qualifying = byDay.mapValues { (_, day) -> day.all { it.state == CheckInState.ANSWERED } }
        return runOver(byDay.keys.sorted(), upTo) { qualifying[it] }
    }

    /**
     * A per-item run, independent of the global one (4.5). MET extends it, MISSED breaks it, and
     * EXCLUDED is **neutral** -- skipped over entirely, since a no-opportunity answer is neither a
     * performance nor a failure (spec 3.5).
     */
    fun itemRun(outcomesByDay: Map<LocalDate, GoalOutcome>, upTo: LocalDate): RunSummary =
        runOver(outcomesByDay.keys.sorted(), upTo) { day ->
            when (outcomesByDay[day]) {
                GoalOutcome.MET -> true
                GoalOutcome.MISSED -> false
                // Neutral: neither extends nor breaks. Returning null skips the day.
                GoalOutcome.EXCLUDED, null -> null
            }
        }

    /**
     * Walks days in order. [qualifies] returns true to extend, false to break, null to skip the day
     * without affecting the run either way.
     */
    private inline fun runOver(
        days: List<LocalDate>,
        upTo: LocalDate,
        qualifies: (LocalDate) -> Boolean?,
    ): RunSummary {
        var current = 0
        var longest = 0
        for (day in days) {
            if (day > upTo) break
            when (qualifies(day)) {
                true -> {
                    current++
                    if (current > longest) longest = current
                }
                false -> current = 0
                null -> Unit // neutral
            }
        }
        return RunSummary(current = current, longest = longest)
    }
}
