package com.zdredge.consistency.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.zdredge.consistency.domain.dashboard.Dashboard
import com.zdredge.consistency.domain.detail.ItemHistory
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.time.DayResolver
import com.zdredge.consistency.ui.theme.ConsistencyTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * The dashboard's three states, on invented data run through the real pipeline: invented histories
 * through `Dashboard.assemble`, then `presentDashboard`. `:app` has no tests, so these are the check.
 */

/** A Monday. */
private val today = LocalDate.of(2026, 9, 21)
private val resolver = DayResolver(Clock.systemDefaultZone())
private val longAgo = LocalDate.of(2026, 6, 1)

@Composable
private fun Rendered(histories: List<ItemHistory>, days: Int, missed: Set<Int> = emptySet()) {
    val figures = Dashboard.assemble(histories, checkIns(days, missed), today, resolver)
    ConsistencyTheme {
        HomeScreen(
            state = HomeUiState(
                loading = false,
                today = today,
                outstanding = listOf(CheckIn(today.minusDays(1), Slot.NIGHT, CheckInState.PENDING)),
                itemCount = histories.size,
                dashboard = presentDashboard(figures),
            ),
            notificationsEnabled = true,
            exportStatus = null,
            onExport = {},
            onOpenCheckIn = { _, _ -> },
            onOpenItems = {},
        )
    }
}

@Preview(name = "Dashboard — day 9", showBackground = true, heightDp = 900)
@Composable
private fun FirstRun() = Rendered(goals(), days = 9, missed = setOf(4))

@Preview(name = "Dashboard — day 18, trends waiting", showBackground = true, heightDp = 1100)
@Composable
private fun TrendsWaiting() = Rendered(goals(), days = 18, missed = setOf(6))

@Preview(name = "Dashboard — an ordinary fortnight", showBackground = true, heightDp = 1400)
@Composable
private fun Ordinary() = Rendered(goals(), days = 60, missed = setOf(3, 9, 30))

// ---- Invented goals: water slipping, coffee improving, vitamins holding, workouts slipping. -------

private fun goals(): List<ItemHistory> = listOf(
    numberGoal("water", "How much water did you drink? (Bottles)", Direction.AT_LEAST, 2.0, "Bottles") { back ->
        if (back < 14) (if (back % 3 == 0) 2.0 else 1.5) else 2.0
    },
    numberGoal("coffee", "How many coffees did you have?", Direction.AT_MOST, 2.0) { back ->
        if (back < 14) 1.0 else if (back % 2 == 0) 3.0 else 2.0
    },
    boolGoal("vitamins", "Did you take your vitamins?") { back -> back % 7 != 3 },
    ItemHistory(
        item = item("worked_out"),
        versions = listOf(version("worked_out", "Did you work out?", AnswerType.BOOL)),
        targets = listOf(Target(ItemId("worked_out"), Period.WEEK, Direction.AT_LEAST, 3.0, effectiveFrom = longAgo)),
        rollUp = RollUpSpec(ItemId("worked_out"), ItemId("worked_out"), RollUpAggregation.COUNT_OF_YES),
        answers = (0..60).map { back ->
            val d = today.minusDays(back.toLong())
            answer("worked_out", d, bool = if (back < 14) d.dayOfWeek.value == 1 else d.dayOfWeek.value in setOf(1, 3, 5))
        },
    ),
)

private fun numberGoal(
    id: String,
    prompt: String,
    direction: Direction,
    value: Double,
    unit: String? = null,
    amount: (Int) -> Double,
) = ItemHistory(
    item = item(id),
    versions = listOf(version(id, prompt, AnswerType.NUMBER, unit)),
    targets = listOf(Target(ItemId(id), Period.DAY, direction, value, effectiveFrom = longAgo)),
    answers = (0..60).map { answer(id, today.minusDays(it.toLong()), number = amount(it)) },
)

private fun boolGoal(id: String, prompt: String, yes: (Int) -> Boolean) = ItemHistory(
    item = item(id),
    versions = listOf(version(id, prompt, AnswerType.BOOL)),
    targets = listOf(Target(ItemId(id), Period.DAY, Direction.IS_TRUE, effectiveFrom = longAgo)),
    answers = (0..60).map { answer(id, today.minusDays(it.toLong()), bool = yes(it)) },
)

private fun checkIns(days: Int, missed: Set<Int>): List<CheckIn> =
    (days - 1 downTo 1).flatMap { back ->
        val state = if (back in missed) CheckInState.MISSED else CheckInState.ANSWERED
        listOf(Slot.MORNING, Slot.NIGHT).map { CheckIn(today.minusDays(back.toLong()), it, state) }
    } + CheckIn(today, Slot.MORNING, CheckInState.ANSWERED) + CheckIn(today, Slot.NIGHT, CheckInState.PENDING)

private fun item(id: String) = Item(id = ItemId(id), kind = ItemKind.ASKED, createdOn = longAgo)

private fun version(id: String, prompt: String, type: AnswerType, unit: String? = null) = ItemVersion(
    id = ItemVersionId("$id.v1"),
    itemId = ItemId(id),
    versionNo = 1,
    prompt = prompt,
    answerType = type,
    classification = Classification.GOAL,
    slot = Slot.NIGHT,
    unitLabel = unit,
    effectiveFrom = longAgo,
)

private fun answer(id: String, day: LocalDate, number: Double? = null, bool: Boolean? = null) = Answer(
    itemId = ItemId(id),
    itemVersionId = ItemVersionId("$id.v1"),
    day = day,
    capture = Capture.IN_WINDOW,
    submittedAt = Instant.EPOCH,
    valueBool = bool,
    valueNumber = number,
)
