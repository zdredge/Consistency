package com.zdredge.consistency.domain.model

import java.time.LocalDate

/**
 * Stable identity only -- nothing mutable lives here (architecture section 5). Items are versioned
 * and retired, never deleted (spec constraint 6): deleting one orphans or misrepresents its history.
 *
 * Dates rather than timestamps: "was this item active in this period" is a day-level question, and
 * :data converts the stored timestamps through DayResolver on the way in.
 */
data class Item(
    val id: ItemId,
    val kind: ItemKind,
    val createdOn: LocalDate,
    /** Null while active. A retired item stays visible for the periods it was active in (spec 3.3). */
    val retiredOn: LocalDate? = null,
)

/**
 * The mutable definition. Answers reference a *version*, so rewording a question never retroactively
 * changes what an old answer meant (spec constraint 6).
 */
data class ItemVersion(
    val id: ItemVersionId,
    val itemId: ItemId,
    val versionNo: Int,
    val prompt: String,
    val answerType: AnswerType,
    val classification: Classification,
    val slot: Slot,
    val unitLabel: String? = null,
    val effectiveFrom: LocalDate,
)

/**
 * Hangs off the ITEM, not the version (architecture section 5). Adding an option to a multi-select is
 * not a change to the question, so it must not bump the version and re-point every prior answer at an
 * older definition (scoring-cases 6.4).
 */
data class SelectOption(
    val id: OptionId,
    val itemId: ItemId,
    val label: String,
    val ordinal: Int,
    /**
     * Marks the single "no opportunity" option, on goals that have one (spec constraint 17).
     * Scoring checks this BEFORE evaluating direction and returns EXCLUDED regardless of the target.
     */
    val isNoOpportunity: Boolean = false,
    val retiredOn: LocalDate? = null,
)
