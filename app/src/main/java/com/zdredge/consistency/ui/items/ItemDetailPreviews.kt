package com.zdredge.consistency.ui.items

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.zdredge.consistency.domain.detail.ItemDetails
import com.zdredge.consistency.domain.detail.ItemHistory
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.time.DayResolver
import com.zdredge.consistency.ui.theme.ConsistencyTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * M8's two screens, on invented data built to contain the awkward cases.
 *
 * **`:app` has no tests, so these are the check.** The same posture M8 Phase 1 took with the chart
 * mockups, and for the same reason: the only device that runs this app now holds real data, and a
 * screen is not worth breaking a week of collection to look at.
 *
 * Each preview runs the **real** pipeline — an invented `ItemHistory` through `ItemDetails.assemble`
 * and then through `presentItemDetail`. A preview fed a hand-written `ItemDetailUiState` would agree
 * with the screen by construction and prove nothing about either.
 *
 * The data is invented, never seeded, and deliberately includes: a fortnight of near misses, a
 * no-opportunity streak, an unresolved deferral, a backfilled day with a note, a day two step sources
 * reported, a provisional day, and an item three days old whose chart is mostly days it did not exist
 * on.
 */

/** A Wednesday, so the running week is genuinely open and the two before it have closed. */
private val today = LocalDate.of(2026, 9, 30)

private val resolver = DayResolver(Clock.systemDefaultZone())

private val longAgo = LocalDate.of(2026, 6, 1)

@Composable
private fun Rendered(history: ItemHistory) {
    ConsistencyTheme {
        ItemDetailScreen(
            state = presentItemDetail(history, ItemDetails.assemble(history, today, resolver)),
            onBack = {},
        )
    }
}

/** The list, in the order the check-ins ask them — which is not alphabetical and not by id. */
@Preview(name = "Items", showBackground = true, heightDp = 900)
@Composable
private fun Items() {
    ConsistencyTheme {
        ItemsScreen(
            state = ItemsUiState(
                loading = false,
                rows = listOf(
                    ItemRow(ItemId("bedtime"), "What time did you go to bed?", "Morning · Observation"),
                    ItemRow(ItemId("pre_sleep"), "What did you do before bed?", "Morning · Goal"),
                    ItemRow(ItemId("meals"), "How many meals did you eat?", "Night · Goal"),
                    ItemRow(
                        ItemId("took_time"),
                        "Did you take time when time could be taken?",
                        "Night · Goal",
                    ),
                    ItemRow(ItemId("steps"), "Steps", "Measured · Goal"),
                    ItemRow(ItemId("saw_friends"), "Did you see friends this week?", "Weekly · Goal"),
                ),
            ),
            listState = LazyListState(),
            onOpenItem = {},
            onBack = {},
        )
    }
}

/**
 * Case 2.3, which is the whole argument for constraint 16: 1.5 bottles every day for a fortnight.
 *
 * Hit rate 0%, average attainment 75%. Either number alone misreads the same fortnight — one as total
 * failure, the other as nearly fine.
 */
@Preview(name = "Water — every day just short", showBackground = true, heightDp = 1600)
@Composable
private fun WaterNearMisses() = Rendered(
    ItemHistory(
        item = item("water"),
        versions = listOf(version("water", "How much water did you drink? (Bottles)", AnswerType.NUMBER)),
        targets = listOf(target("water", Direction.AT_LEAST, 2.0)),
        answers = (0..13).map { answer("water", today.minusDays(it.toLong()), number = 1.5) },
    ),
)

/**
 * Case 10.5 and the marks: a no-opportunity streak, an unresolved deferral, a backfill with a note.
 *
 * The no-opportunity days cost nothing — no hit rate, and the run is neither extended nor broken —
 * and constraint 17 requires that leaning on the neutral answer stays visible rather than hidden,
 * which is the "No opportunity" figure.
 */
@Preview(name = "Took time — leaning on the neutral answer", showBackground = true, heightDp = 1600)
@Composable
private fun TookTimeNoOpportunity() = Rendered(
    ItemHistory(
        item = item("took_time"),
        versions = listOf(
            version("took_time", "Did you take time when time could be taken?", AnswerType.SINGLE_SELECT),
        ),
        options = listOf(
            option("took_time", "yes", "Yes", 0),
            option("took_time", "no", "No", 1),
            option("took_time", "no_opportunity", "No opportunity", 2, noOpportunity = true),
        ),
        targets = listOf(target("took_time", Direction.MUST_INCLUDE, option = "took_time.yes")),
        answers = listOf(
            answer("took_time", today.minusDays(1), selections = setOf("took_time.yes")),
            answer("took_time", today.minusDays(2), capture = Capture.PENDING),
            answer("took_time", today.minusDays(3), selections = setOf("took_time.no")),
            answer(
                "took_time", today.minusDays(4), selections = setOf("took_time.yes"),
                capture = Capture.BACKFILLED, note = "Rang mum on the walk back.",
            ),
            answer(
                "took_time", today.minusDays(5), selections = setOf("took_time.yes"),
                editedAt = Instant.EPOCH,
            ),
        ) + (6..11).map {
            answer("took_time", today.minusDays(it.toLong()), selections = setOf("took_time.no_opportunity"))
        },
    ),
)

/**
 * Case 7.2 and the two states only a measured day has.
 *
 * Daily and weekly are scored separately and reported separately — a cap by the day and a floor by
 * the week are different questions. The conflicted day carries no figure at all: the column holds the
 * two sources' total, which is not a step count.
 */
@Preview(name = "Steps — two periods, and two sources on one day", showBackground = true, heightDp = 1600)
@Composable
private fun StepsWithAConflict() = Rendered(
    ItemHistory(
        item = item("steps", kind = ItemKind.MEASURED),
        versions = listOf(version("steps", "Steps", AnswerType.NUMBER, Slot.NONE)),
        targets = listOf(
            target("steps", Direction.AT_LEAST, 8_000.0),
            target("steps", Direction.AT_LEAST, 56_000.0, Period.WEEK),
        ),
        measured = listOf(
            MeasuredValue(ItemId("steps"), today.minusDays(1), 9_240.0, MeasuredState.PROVISIONAL),
            MeasuredValue(ItemId("steps"), today.minusDays(2), 17_800.0, MeasuredState.CONFLICTED),
        ) + (3..13).map {
            MeasuredValue(
                itemId = ItemId("steps"),
                day = today.minusDays(it.toLong()),
                value = if (it % 3 == 0) 6_100.0 else 9_800.0,
                state = MeasuredState.FROZEN,
            )
        },
    ),
)

/**
 * An observation: recorded, never scored.
 *
 * No hit rate and no run — a recording count in their place, labelled *recorded* rather than
 * *streak*, because these are the items the app deliberately never judges. The typical time is a
 * median over the nights the filter shows, which opens on Sunday to Thursday.
 */
@Preview(name = "Bedtime — recorded, never scored", showBackground = true, heightDp = 1600)
@Composable
private fun BedtimeObservation() = Rendered(
    ItemHistory(
        item = item("bedtime"),
        versions = listOf(
            version(
                "bedtime", "What time did you go to bed?", AnswerType.TIME,
                Slot.MORNING, Classification.OBSERVATION,
            ),
        ),
        answers = (1..20).map {
            val night = today.minusDays(it.toLong())
            answer(
                "bedtime", night,
                // A 01:30 night is the latest reading on a 04:00 axis, not the earliest.
                time = if (it % 7 == 0) LocalTime.of(1, 30) else LocalTime.of(23, 10),
            )
        },
    ),
)

/**
 * The real first week: an item created on a Thursday with its target effective the same day.
 *
 * The chart's Monday precedes the target, so the week is not a goal week at all — no verdict, and it
 * neither extends a run nor breaks one. Most of the five weeks is days the item did not exist on, and
 * none of it is failure.
 */
@Preview(name = "Stretched — three days old", showBackground = true, heightDp = 1600)
@Composable
private fun StretchedOnDayThree() = Rendered(
    ItemHistory(
        item = item("stretched", createdOn = today.minusDays(2)),
        versions = listOf(
            version("stretched", "Did you stretch?", AnswerType.BOOL, from = today.minusDays(2)),
        ),
        targets = listOf(
            target("stretched", Direction.AT_LEAST, 6.0, Period.WEEK, from = today.minusDays(2)),
        ),
        rollUp = RollUpSpec(ItemId("stretched"), ItemId("stretched"), RollUpAggregation.COUNT_OF_YES),
        answers = listOf(
            answer("stretched", today.minusDays(2), bool = true),
            answer("stretched", today.minusDays(1), bool = false),
        ),
    ),
)

/** A weekly question: one answer a week, and the running week's square still ahead of it. */
@Preview(name = "Saw friends — one square a week", showBackground = true, heightDp = 1600)
@Composable
private fun WeeklyQuestion() = Rendered(
    ItemHistory(
        item = item("saw_friends"),
        versions = listOf(
            version("saw_friends", "Did you see friends this week?", AnswerType.SINGLE_SELECT, Slot.WEEKLY),
        ),
        options = listOf(
            option("saw_friends", "yes", "Yes", 0),
            option("saw_friends", "no", "No", 1),
            option("saw_friends", "no_opportunity", "No opportunity", 2, noOpportunity = true),
        ),
        targets = listOf(
            target("saw_friends", Direction.MUST_INCLUDE, option = "saw_friends.yes", period = Period.WEEK),
        ),
        answers = listOf(
            answer("saw_friends", resolver.weekEnd(today.minusWeeks(1)), selections = setOf("saw_friends.yes")),
            answer("saw_friends", resolver.weekEnd(today.minusWeeks(2)), selections = setOf("saw_friends.no")),
            answer(
                "saw_friends", resolver.weekEnd(today.minusWeeks(3)),
                selections = setOf("saw_friends.no_opportunity"),
            ),
        ),
    ),
)

// ---- Invented rows. Not the seed, and never written anywhere. --------------------------------

private fun item(id: String, createdOn: LocalDate = longAgo, kind: ItemKind = ItemKind.ASKED) =
    Item(id = ItemId(id), kind = kind, createdOn = createdOn)

private fun version(
    id: String,
    prompt: String,
    answerType: AnswerType,
    slot: Slot = Slot.NIGHT,
    classification: Classification = Classification.GOAL,
    from: LocalDate = longAgo,
) = ItemVersion(
    id = ItemVersionId("$id.v1"),
    itemId = ItemId(id),
    versionNo = 1,
    prompt = prompt,
    answerType = answerType,
    classification = classification,
    slot = slot,
    effectiveFrom = from,
)

private fun option(
    itemId: String,
    key: String,
    label: String,
    ordinal: Int,
    noOpportunity: Boolean = false,
) = SelectOption(
    id = OptionId("$itemId.$key"),
    itemId = ItemId(itemId),
    label = label,
    ordinal = ordinal,
    isNoOpportunity = noOpportunity,
)

private fun target(
    itemId: String,
    direction: Direction,
    value: Double? = null,
    period: Period = Period.DAY,
    option: String? = null,
    from: LocalDate = longAgo,
) = Target(
    itemId = ItemId(itemId),
    period = period,
    direction = direction,
    valueNumber = value,
    optionId = option?.let(::OptionId),
    effectiveFrom = from,
)

private fun answer(
    itemId: String,
    day: LocalDate,
    number: Double? = null,
    bool: Boolean? = null,
    time: LocalTime? = null,
    scale: Int? = null,
    selections: Set<String> = emptySet(),
    capture: Capture = Capture.IN_WINDOW,
    editedAt: Instant? = null,
    note: String? = null,
) = Answer(
    itemId = ItemId(itemId),
    itemVersionId = ItemVersionId("$itemId.v1"),
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
