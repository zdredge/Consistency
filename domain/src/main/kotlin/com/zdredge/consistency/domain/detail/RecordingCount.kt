package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.checkin.Answerability
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.scoring.ItemLifecycle
import com.zdredge.consistency.domain.scoring.RunCalculator
import com.zdredge.consistency.domain.scoring.RunSummary
import java.time.LocalDate

/**
 * How consistently an observation has been recorded.
 *
 * Bedtime, waking, getting up and mindset have no target, so there is no goal to have met and no run
 * to show. What they do have is whether they were written down — which is the habit this product is
 * actually about (spec §1: the first habit is answering).
 *
 * **It takes no filter.** The sleep chart can be narrowed to weeknights, and the average and typical
 * time follow that; this must not, because it measures answering every night. Leaving the parameter
 * out means a caller cannot pass one by mistake.
 */
object RecordingCount {

    /**
     * Consecutive recorded days, current and longest, up to [lastDay].
     *
     * Three rules, each borrowed rather than invented:
     * - A **late** answer does not count. It kept the data but not the discipline, exactly as A1.3
     *   says a late answer does not restore the run. A **backfilled** one counts, as it does there.
     * - A day still inside its backfill window is **skipped**, neither extending nor breaking:
     *   tonight's unanswered bedtime is not yet a gap, and a count that dropped to zero every evening
     *   would be worthless.
     * - Days before the item existed are not days it could have been recorded on.
     */
    fun of(
        item: Item,
        slot: Slot,
        answers: List<Answer>,
        today: LocalDate,
        lastDay: LocalDate,
    ): RunSummary {
        val byDay = answers.associateBy { it.day }

        val outcomes = generateSequence(item.createdOn) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastDay) }
            .filter { ItemLifecycle.isActiveOn(item, it) }
            .mapNotNull { day ->
                val answer = byDay[day]
                val recorded = answer != null &&
                    answer.capture != Capture.LATE &&
                    answer.capture != Capture.PENDING
                when {
                    recorded -> day to GoalOutcome.MET
                    // Still answerable, so not yet a gap. RunCalculator skips EXCLUDED entirely.
                    Answerability.isOpen(day, slot, today) -> day to GoalOutcome.EXCLUDED
                    else -> day to GoalOutcome.MISSED
                }
            }
            .toMap()

        return RunCalculator.itemRun(outcomes, upTo = lastDay)
    }
}
