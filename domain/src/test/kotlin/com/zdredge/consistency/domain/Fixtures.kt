package com.zdredge.consistency.domain

import com.zdredge.consistency.domain.detail.ItemHistory
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Target
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Terse builders so the scoring tests read like the tables in docs/scoring-cases.md they come from.
 * Ids are plain strings here and wrapped for you; the value classes earn their keep in production
 * code, not in every line of a 75-case suite.
 */

/** The worked-example Tuesday from architecture section 5. An arbitrary but consistent default. */
val DAY: LocalDate = LocalDate.of(2026, 8, 25)

/** Long before anything under test, so a target is simply "in force" unless a date matters. */
private val ALWAYS: LocalDate = LocalDate.of(2000, 1, 1)

fun target(
    item: String,
    direction: Direction,
    value: Double? = null,
    option: String? = null,
    period: Period = Period.DAY,
    from: LocalDate = ALWAYS,
): Target = Target(
    itemId = ItemId(item),
    period = period,
    direction = direction,
    valueNumber = value,
    optionId = option?.let(::OptionId),
    effectiveFrom = from,
)

fun answer(
    item: String,
    day: LocalDate = DAY,
    number: Double? = null,
    bool: Boolean? = null,
    time: LocalTime? = null,
    scale: Int? = null,
    selections: Set<String> = emptySet(),
    capture: Capture = Capture.IN_WINDOW,
    editedAt: Instant? = null,
    note: String? = null,
): Answer = Answer(
    itemId = ItemId(item),
    itemVersionId = ItemVersionId("$item-v1"),
    day = day,
    capture = capture,
    submittedAt = Instant.EPOCH,
    editedAt = editedAt,
    valueBool = bool,
    valueNumber = number,
    valueTime = time,
    valueScale = scale,
    selections = selections.map(::OptionId).toSet(),
    note = note,
)

fun checkIn(
    day: LocalDate,
    slot: Slot = Slot.NIGHT,
    state: CheckInState = CheckInState.ANSWERED,
    answeredAt: Instant? = null,
): CheckIn = CheckIn(
    day = day,
    slot = slot,
    state = state,
    // Default: answered inside its own day, a little after the 21:00 night slot.
    answeredAt = answeredAt ?: if (state == CheckInState.ANSWERED) day.atTime(21, 30).toInstant() else null,
)

/** Answered the morning after the day it belongs to -- inside grace, so BACKFILLED. */
fun backfilledCheckIn(day: LocalDate, slot: Slot = Slot.NIGHT): CheckIn =
    checkIn(day, slot, CheckInState.ANSWERED, answeredAt = day.plusDays(1).atTime(9, 0).toInstant())

fun missedCheckIn(day: LocalDate, slot: Slot = Slot.NIGHT): CheckIn =
    CheckIn(day = day, slot = slot, state = CheckInState.MISSED, answeredAt = null)

/** Both of a day's scheduled check-ins, answered in window. */
fun answeredDay(day: LocalDate): List<CheckIn> =
    listOf(checkIn(day, Slot.NIGHT), checkIn(day, Slot.MORNING))

internal fun LocalDate.atTime(hour: Int, minute: Int): java.time.LocalDateTime =
    java.time.LocalDateTime.of(this, LocalTime.of(hour, minute))

internal fun java.time.LocalDateTime.toInstant(): Instant = atZone(TEST_ZONE).toInstant()

/** Fixed, deliberately not UTC, so a zone bug cannot hide behind a zero offset. */
val TEST_ZONE: ZoneId = ZoneId.of("America/New_York")

val TEST_CLOCK_ZONE: ZoneId get() = TEST_ZONE

fun item(
    name: String,
    createdOn: LocalDate = LocalDate.of(2020, 1, 1),
    retiredOn: LocalDate? = null,
    kind: ItemKind = ItemKind.ASKED,
    ordinal: Int = 0,
): Item = Item(id = ItemId(name), kind = kind, createdOn = createdOn, retiredOn = retiredOn, ordinal = ordinal)

/**
 * The version id matches [answer]'s, so an answer built here is readable under the version built
 * here without either caller having to say so.
 */
fun version(
    item: String,
    answerType: AnswerType,
    slot: Slot = Slot.NIGHT,
    classification: Classification = Classification.GOAL,
    prompt: String = item,
    unitLabel: String? = null,
    from: LocalDate = ALWAYS,
): ItemVersion = ItemVersion(
    id = ItemVersionId("$item-v1"),
    itemId = ItemId(item),
    versionNo = 1,
    prompt = prompt,
    answerType = answerType,
    classification = classification,
    slot = slot,
    unitLabel = unitLabel,
    effectiveFrom = from,
)

fun option(
    item: String,
    id: String,
    ordinal: Int = 0,
    noOpportunity: Boolean = false,
    retiredOn: LocalDate? = null,
): SelectOption = SelectOption(
    id = OptionId(id),
    itemId = ItemId(item),
    label = id,
    ordinal = ordinal,
    isNoOpportunity = noOpportunity,
    retiredOn = retiredOn,
)

fun measured(
    item: String = "steps",
    day: LocalDate = DAY,
    value: Double,
    state: MeasuredState = MeasuredState.FROZEN,
    lastSyncedAt: Instant? = null,
    origins: List<MeasuredOrigin> = emptyList(),
): MeasuredValue = MeasuredValue(
    itemId = ItemId(item),
    day = day,
    value = value,
    state = state,
    lastSyncedAt = lastSyncedAt,
    origins = origins,
)

fun rollUp(item: String, aggregation: RollUpAggregation): RollUpSpec =
    RollUpSpec(itemId = ItemId(item), sourceItemId = ItemId(item), aggregation = aggregation)

/**
 * One item's whole history, for the detail tests. The single-version case is the common one; an
 * item that was reworded passes its versions explicitly.
 */
fun history(
    item: Item,
    version: ItemVersion,
    options: List<SelectOption> = emptyList(),
    targets: List<Target> = emptyList(),
    rollUp: RollUpSpec? = null,
    answers: List<Answer> = emptyList(),
    measured: List<MeasuredValue> = emptyList(),
): ItemHistory = ItemHistory(
    item = item,
    versions = listOf(version),
    options = options,
    targets = targets,
    rollUp = rollUp,
    answers = answers,
    measured = measured,
)

/** The Monday-start week containing [day], as seven dates. */
fun weekOf(day: LocalDate, resolver: com.zdredge.consistency.domain.time.DayResolver): List<LocalDate> {
    val start = resolver.weekStart(day)
    return (0..6).map { start.plusDays(it.toLong()) }
}
