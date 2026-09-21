package com.zdredge.consistency.fixture

import com.zdredge.consistency.data.health.StepSource
import com.zdredge.consistency.data.health.StepSourceStatus
import com.zdredge.consistency.domain.model.MeasuredOrigin
import java.time.LocalDate

/**
 * The fixture install's step source: available, and never reporting anything.
 *
 * Not the "second implementation for symmetry" `StepSource` warns against — it never ships in the
 * real app. It exists for two reasons. **Available** keeps steps on the check-in and item screens, so
 * a loaded step history is visible. **Empty** means opening a check-in reads nothing and writes
 * nothing, so `syncRecentSteps` can never overwrite a generated step day with the user's real steps
 * — and the fixture install never needs Health Connect permission at all.
 */
object FixtureStepSource : StepSource {
    override suspend fun status(): StepSourceStatus = StepSourceStatus.Available

    override suspend fun readDay(day: LocalDate): List<MeasuredOrigin> = emptyList()
}
