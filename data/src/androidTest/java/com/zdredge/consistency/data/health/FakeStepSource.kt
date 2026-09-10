package com.zdredge.consistency.data.health

import com.zdredge.consistency.domain.model.MeasuredOrigin
import java.time.LocalDate

/**
 * A step source with no Health Connect behind it.
 *
 * The live read is hand-verified on the device and always will be (architecture T3). What is worth
 * testing without one is everything downstream: that a granted permission puts steps on the night
 * check-in and a missing one takes it off, that two origins are flagged rather than summed, and that
 * re-reading the same day twice does not throw.
 */
class FakeStepSource(
    var status: StepSourceStatus = StepSourceStatus.Available,
    private var days: Map<LocalDate, List<MeasuredOrigin>> = emptyMap(),
) : StepSource {

    /** How many times [readDay] has been called, so a test can prove a read did not happen. */
    var reads: Int = 0
        private set

    override suspend fun status(): StepSourceStatus = status

    override suspend fun readDay(day: LocalDate): List<MeasuredOrigin> {
        reads++
        return days[day].orEmpty()
    }

    fun record(day: LocalDate, vararg origins: MeasuredOrigin) {
        days = days + (day to origins.toList())
    }
}
