package com.zdredge.consistency.domain

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Target
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

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
