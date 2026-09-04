package com.zdredge.consistency.domain.model

import java.time.LocalDate

/**
 * A target in force from [effectiveFrom] onward. Spec constraint 2: targets are stored per period
 * with an effective-from date, so raising a target never re-scores closed periods (scoring-cases
 * 5.1-5.3). Collapsing these to a single current value destroys the truthfulness of history.
 *
 * Keyed on (item, period), so one item may hold a daily and a weekly target at once and they are
 * scored independently, never collapsed (spec 3.4, scoring-cases 7.1-7.2).
 *
 * [direction] travels with the value because a bare number cannot express "at most two"
 * (spec constraint 3). [optionId] is set only for MUST_INCLUDE / MUST_NOT_INCLUDE.
 */
data class Target(
    val itemId: ItemId,
    val period: Period,
    val direction: Direction,
    val valueNumber: Double? = null,
    val optionId: OptionId? = null,
    val effectiveFrom: LocalDate,
)

/**
 * A counted container's size, per period (spec constraint 2). Entering "2" for water records both the
 * count and the absolute amount; changing bottles later must not re-score history (scoring-cases 5.4).
 */
data class ContainerSize(
    val itemId: ItemId,
    val size: Double,
    val unitLabel: String,
    val effectiveFrom: LocalDate,
)

/**
 * A weekly figure derived from one daily item plus an aggregation. Roll-ups are explicit, not
 * inferred (spec 3.4), and derived figures must be labelled as derived with their source visible.
 */
data class RollUpSpec(
    val itemId: ItemId,
    val sourceItemId: ItemId,
    val aggregation: RollUpAggregation,
)
