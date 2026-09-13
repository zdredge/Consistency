package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.item
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * What an observation shows in place of a run.
 *
 * Bedtime and mindset have no target, so there is nothing to have met. What they have is whether they
 * were written down, which is the habit the product is actually about. The rules are borrowed rather
 * than invented: a late answer does not count, exactly as A1.3 says a late answer does not restore the
 * run, and a night still inside its window is not yet a gap.
 */
@DisplayName("The recording count on an observation")
class RecordingCountTest {

    private val today = LocalDate.of(2026, 9, 11)
    private val bedtime = item("bedtime", createdOn = LocalDate.of(2026, 8, 1))
    private val lastNight = today.minusDays(1)

    private fun night(daysAgo: Long, capture: Capture = Capture.IN_WINDOW) =
        answer("bedtime", day = today.minusDays(daysAgo), time = LocalTime.of(23, 0), capture = capture)

    private fun countOf(vararg answers: com.zdredge.consistency.domain.model.Answer) =
        RecordingCount.of(bedtime, Slot.MORNING, answers.toList(), today = today, lastDay = lastNight)

    @Test
    @DisplayName("consecutive recorded nights, counted back from the latest one that could be answered")
    fun countsConsecutiveNights() {
        val summary = countOf(night(1), night(2), night(3), night(4))

        assertEquals(4, summary.current)
        assertEquals(4, summary.longest)
    }

    @Test
    @DisplayName("tonight's unanswered night is skipped, not counted as a break")
    fun anOpenNightDoesNotBreakIt() {
        // Last night can still be answered until tomorrow. A count that dropped to zero every evening
        // and recovered every morning would be worthless.
        val summary = countOf(night(2), night(3), night(4))

        assertEquals(3, summary.current, "last night is still answerable, so it is skipped")
    }

    @Test
    @DisplayName("A1.3 - a late answer keeps the data but not the streak")
    fun lateAnswersDoNotCount() {
        // The same rule the global run uses: backfilling inside the window preserves a run, answering
        // after it does not.
        val late = countOf(night(2), night(3, capture = Capture.LATE), night(4))
        assertEquals(1, late.current, "the late night breaks it")

        val backfilled = countOf(night(2), night(3, capture = Capture.BACKFILLED), night(4))
        assertEquals(3, backfilled.current, "a backfilled night does not")
    }

    @Test
    @DisplayName("a night that closed unanswered breaks the count, and the longest survives it")
    fun aGapBreaksIt() {
        val summary = countOf(night(2), night(3), night(5), night(6), night(7), night(8))

        assertEquals(2, summary.current, "the four-night run ended at the gap")
        assertEquals(4, summary.longest)
    }

    @Test
    @DisplayName("a deferral is not a recording")
    fun deferralsDoNotCount() {
        val summary = countOf(night(2), night(3, capture = Capture.PENDING), night(4))

        assertEquals(1, summary.current)
    }

    @Test
    @DisplayName("nights before the item existed are not nights it could have been recorded on")
    fun beforeTheItemExisted() {
        // The real case: the library was seeded on 2026-09-10, so almost every night on a five-week
        // chart predates it and must not read as a missed night.
        val newItem = item("bedtime", createdOn = today.minusDays(2))
        val summary = RecordingCount.of(
            newItem, Slot.MORNING, listOf(night(2)), today = today, lastDay = lastNight,
        )

        assertEquals(1, summary.current)
        assertEquals(1, summary.longest)
    }

    @Test
    @DisplayName("nothing recorded at all is zero, not an error")
    fun nothingRecorded() {
        val summary = countOf()

        assertEquals(0, summary.current)
        assertEquals(0, summary.longest)
    }
}
