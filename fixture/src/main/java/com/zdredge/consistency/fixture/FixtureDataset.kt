package com.zdredge.consistency.fixture

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.ContainerSize
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import java.time.LocalDate

/**
 * A whole history, as domain models, ready for `FixtureLoader` to write.
 *
 * Domain models rather than entities, so a scenario's claims can be checked on the JVM with the same
 * calculators the app scores with — a test that re-derived "is this day conflicted" by hand would
 * prove only that it agrees with itself.
 */
data class FixtureDataset(
    val items: List<Item>,
    val versions: List<ItemVersion>,
    val options: List<SelectOption>,
    val targets: List<Target>,
    val containerSizes: List<ContainerSize>,
    val rollUpSpecs: List<RollUpSpec>,
    val checkIns: List<CheckIn>,
    val answers: List<GivenAnswer>,
    val measuredValues: List<MeasuredValue>,
)

/**
 * An answer and the check-in it arrived through.
 *
 * The check-in is kept because `answers.via_checkin_id` is stored, and because an answer's date alone
 * does not say which check-in gave it — a morning check-in writes answers dated the day before.
 */
data class GivenAnswer(
    val answer: Answer,
    val checkInDay: LocalDate,
    val slot: Slot,
    /** The night a resolved deferral was deferred from, when this answer resolved one. */
    val carriedOverFrom: LocalDate? = null,
)
