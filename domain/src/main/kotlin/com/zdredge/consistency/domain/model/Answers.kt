package com.zdredge.consistency.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * One answer to one item on one day.
 *
 * [day] is deliberately NOT derivable from the check-in it arrived through. Sleep items belong to the
 * day the user went to bed, so the morning check-in writes answers dated *yesterday* (spec 3.1, the
 * sleep-day convention). This looks like denormalisation worth removing; removing it breaks the sleep
 * items and the "not yet" carry-over at once (architecture section 5).
 *
 * [capture] and [editedAt] are two separate fields, never one state enum (spec constraint 4). Capture
 * is how the answer was FIRST recorded; editedAt is whether it was later corrected. An answer can be
 * backfilled *and* subsequently edited, and merging them loses one of those facts.
 *
 * Values are typed nullable fields rather than a blob (architecture T2): a blob makes the schema tidy
 * and every aggregation miserable. Exactly one value field is populated, per the item's answer type;
 * select types use [selections].
 */
data class Answer(
    val itemId: ItemId,
    val itemVersionId: ItemVersionId,
    val day: LocalDate,
    val capture: Capture,
    val submittedAt: Instant,
    /** Null unless the answer was later corrected. Never inferred from [capture]. */
    val editedAt: Instant? = null,
    val valueBool: Boolean? = null,
    val valueNumber: Double? = null,
    val valueTime: LocalTime? = null,
    val valueScale: Int? = null,
    /** For SINGLE_SELECT and MULTI_SELECT. */
    val selections: Set<OptionId> = emptySet(),
    val note: String? = null,
)

/**
 * A check-in that was *expected*. This is the denominator for response rate -- the primary metric --
 * so without these rows it is unmeasurable (architecture section 5). Generated at rollover.
 *
 * Note that a check-in answered "not yet" stays ANSWERED even if the deferral is never resolved: the
 * user did complete the check-in (scoring-cases A2.2). And a LATE answer does not repair a MISSED
 * check-in (A1.2).
 */
data class CheckIn(
    val day: LocalDate,
    val slot: Slot,
    val state: CheckInState,
    val answeredAt: Instant? = null,
)

/**
 * A measured value, from Health Connect. Never asked; scored like any other goal (spec 3.3).
 *
 * [origins] backs the day-one origin grouping guard (architecture section 5): more than one origin for
 * a day means a second source appeared, and the values must not be silently summed. M0 confirmed one
 * origin today, but Samsung Health and Google Health are both installed on the device.
 */
data class MeasuredValue(
    val itemId: ItemId,
    val day: LocalDate,
    val value: Double,
    val state: MeasuredState,
    /**
     * When the read that produced this value happened.
     *
     * The anchor for spec O4: a value is provisional for 24 hours after its read and then frozen, so
     * the clock runs from the sync rather than from the day being measured — a value re-synced late
     * is still young, which is the whole point of tolerating late syncing. `RolloverPlanner` reads
     * it; nothing populates it until Health Connect arrives in M7, and until then a null here means
     * nothing freezes rather than something freezing on a guess.
     */
    val lastSyncedAt: Instant? = null,
    val origins: List<MeasuredOrigin> = emptyList(),
)

data class MeasuredOrigin(
    val originPackage: String,
    val value: Double,
)
