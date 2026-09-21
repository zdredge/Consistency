package com.zdredge.consistency.fixture

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate

/**
 * What a scenario asks of the generator: how long its history is, and where it departs from an
 * ordinary one.
 *
 * Every hook is keyed by the **install day offset** -- day 0 is the install day -- so a scenario reads
 * "the week starting day 14" rather than naming dates that would move every time it loads.
 *
 * The hooks say *what happened*, never what state it produced. "The night of day 9 was deferred" is a
 * hook; "answer X is PENDING" is the generator asking `CaptureResolver`. That is what keeps a
 * scenario inside the states the app can actually reach.
 */
internal data class HistoryShape(
    val days: Int,
    /** Share of past check-ins nobody answered. */
    val missRate: Double = 0.08,
    /** Share of answered check-ins answered the next day rather than the same evening. */
    val backfillRate: Double = 0.06,
    /** Share of days with no step reading at all -- a phone left off, a day nothing synced. */
    val stepGapRate: Double = 0.03,
    /** Changes to the seed library, given the install day, made before anything is asked. */
    val library: (FixtureLibrary, LocalDate) -> FixtureLibrary = { library, _ -> library },
    /** Check-ins nobody answered, whatever the miss roll says. */
    val unanswered: (offset: Long, Slot) -> Boolean = { _, _ -> false },
    /** Check-ins always answered, the same evening, whatever the miss roll says. */
    val answered: (offset: Long, Slot) -> Boolean = { _, _ -> false },
    /** Days with no step reading. */
    val noSteps: (offset: Long) -> Boolean = { false },
    /** Days a second app also reported steps -- which `StepMapper` flags as conflicted. */
    val secondStepOrigin: (offset: Long) -> Boolean = { false },
    /** Nights whose goals were answered "not yet". Resolved next morning if that check-in is given. */
    val deferredNights: (offset: Long) -> Boolean = { false },
    /**
     * Nights missed and answered days afterwards, which `CaptureResolver` records as LATE.
     *
     * **No screen produces this yet.** The check-in UI only reaches check-ins inside their grace;
     * the data model and scoring support a late answer (A1.2), and M8's day cells draw one, so the
     * fixture makes it checkable ahead of the path that will create it.
     */
    val lateNights: (offset: Long) -> Boolean = { false },
    /** Check-ins whose answers were corrected a day later, through no check-in. */
    val corrected: (offset: Long, Slot) -> Boolean = { _, _ -> false },
    /** Rewrites a generated value. Must leave the item, version, day, capture and times alone. */
    val value: (Answer, offset: Long) -> Answer = { answer, _ -> answer },
)
