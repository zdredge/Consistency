package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.TEST_ZONE
import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.history
import com.zdredge.consistency.domain.item
import com.zdredge.consistency.domain.measured
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.option
import com.zdredge.consistency.domain.rollUp
import com.zdredge.consistency.domain.scoring.Panel
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.time.ClockAxis
import com.zdredge.consistency.domain.time.DayResolver
import com.zdredge.consistency.domain.version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * An item's history in, its screen out.
 *
 * These are the shapes the seeded library actually has — a daily goal, a weekly roll-up, a weekly
 * question, an observation, a measured item, and coffee, which is two goals at once. The point of
 * testing at this level rather than only underneath it is that the chart and the figures are assembled
 * here: if they were ever to disagree about the same fortnight, it would happen in this file.
 */
@DisplayName("Assembling an item's detail")
class ItemDetailsTest {

    private val weeks = DayResolver(Clock.fixed(Instant.EPOCH, TEST_ZONE))

    /** A Wednesday, so the running week is genuinely open and the two before it have closed. */
    private val today = LocalDate.of(2026, 9, 30)

    private val longAgo = LocalDate.of(2026, 6, 1)

    // ---------------------------------------------------------------- daily goals

    @Test
    @DisplayName("2.3 - 1.5 bottles every day: hit rate 0%, average attainment 75%, both reported")
    fun attainmentIsNotMergedIntoHitRate() {
        val detail = ItemDetails.assemble(
            history(
                item = item("water", createdOn = longAgo),
                version = version("water", AnswerType.NUMBER),
                targets = listOf(target("water", Direction.AT_LEAST, value = 2.0)),
                answers = (0..13).map { answer("water", day = today.minusDays(it.toLong()), number = 1.5) },
            ),
            today, weeks,
        )

        val daily = detail.figures.daily!!
        assertEquals(0, daily.summary.met)
        assertEquals(14, daily.summary.missed)
        assertEquals(0.0, daily.summary.hitRate!!)
        assertEquals(0.75, daily.summary.averageAttainment!!)
        // The pair is the point: a fortnight of near-misses is not a fortnight of doing nothing.
        assertEquals(Panel.GOING_BADLY, Panel.forHitRate(daily.summary.hitRate))
    }

    @Test
    @DisplayName("4.5 - a run of 12 broken on the 13th day: longest 12, current 0")
    fun theRunIsTheHighWaterMark() {
        val start = today.minusDays(12)
        val detail = ItemDetails.assemble(
            history(
                item = item("vitamins", createdOn = start),
                version = version("vitamins", AnswerType.BOOL),
                targets = listOf(target("vitamins", Direction.IS_TRUE)),
                answers = (0..11).map { answer("vitamins", day = start.plusDays(it.toLong()), bool = true) } +
                    answer("vitamins", day = today, bool = false),
            ),
            today, weeks,
        )

        val daily = detail.figures.daily!!
        assertEquals(12, daily.run.longest)
        assertEquals(0, daily.run.current)
    }

    @Test
    @DisplayName("1.13 - a silent fortnight scores neither well nor badly")
    fun silenceIsNotSuccessOrFailure() {
        val detail = ItemDetails.assemble(
            history(
                item = item("vitamins", createdOn = longAgo),
                version = version("vitamins", AnswerType.BOOL),
                targets = listOf(target("vitamins", Direction.IS_TRUE)),
            ),
            today, weeks,
        )

        val daily = detail.figures.daily!!
        assertEquals(0, daily.summary.met)
        assertEquals(0, daily.summary.missed, "silence is not a miss")
        assertEquals(14, daily.summary.excluded)
        assertNull(daily.summary.hitRate, "and it is not a hit rate of zero either")
        assertNull(Panel.forHitRate(daily.summary.hitRate), "so the item belongs in no panel")
    }

    @Test
    @DisplayName("10.5 - a fortnight of no-opportunity: no panel, an unbroken run, and usage visible")
    fun noOpportunityCostsNothingAndStaysVisible() {
        val detail = ItemDetails.assemble(
            history(
                item = item("took_time", createdOn = longAgo),
                version = version("took_time", AnswerType.SINGLE_SELECT),
                options = listOf(
                    option("took_time", "took_time.yes", 0),
                    option("took_time", "took_time.no_opportunity", 2, noOpportunity = true),
                ),
                targets = listOf(target("took_time", Direction.MUST_INCLUDE, option = "took_time.yes")),
                answers = (0..13).map {
                    answer(
                        "took_time",
                        day = today.minusDays(it.toLong()),
                        selections = setOf("took_time.no_opportunity"),
                    )
                },
            ),
            today, weeks,
        )

        val daily = detail.figures.daily!!
        assertNull(daily.summary.hitRate)
        assertEquals(0, daily.run.longest, "neither extended nor broken -- there is nothing to extend")
        // Constraint 17: leaning on the neutral answer has to be legible rather than hidden.
        assertEquals(14, daily.summary.noOpportunityCount)
    }

    @Test
    @DisplayName("6.2 - a goal retired a month ago: no figures for this fortnight, its history intact")
    fun aRetiredGoalHasNoCurrentFigures() {
        val retired = today.minusDays(30)
        val detail = ItemDetails.assemble(
            history(
                item = item("water", createdOn = longAgo, retiredOn = retired),
                version = version("water", AnswerType.NUMBER),
                targets = listOf(target("water", Direction.AT_LEAST, value = 2.0)),
                answers = (30..60).map { answer("water", day = today.minusDays(it.toLong()), number = 2.0) },
            ),
            today, weeks,
        )

        // Spec §5.4: the figures cover the same 14 days as the dashboard, and the item was not active
        // in any of them -- so nothing is scored, rather than its final fortnight standing in for now.
        val daily = detail.figures.daily!!
        assertEquals(today, detail.figures.windowDays.last())
        assertEquals(0, daily.summary.met + daily.summary.missed)
        assertNull(daily.summary.hitRate)

        // The chart, the log and the run still describe the periods it was active in (spec §3.3).
        assertEquals(retired, detail.lastDay)
        assertEquals(DayState.MET, detail.log.last().state)
        assertEquals(retired, detail.log.last().day)
        assertTrue(detail.days.any { it.state == DayState.MET })
        assertEquals(31, daily.run.longest)
    }

    // ---------------------------------------------------------------- weekly roll-ups

    @Test
    @DisplayName("11.4 - the running week is progress, not a verdict, and stays out of the figures")
    fun theOpenWeekIsNotScored() {
        val workedOut = item("worked_out", createdOn = longAgo)
        val thisWeek = weeks.weekStart(today)
        val lastWeek = thisWeek.minusWeeks(1)

        val detail = ItemDetails.assemble(
            history(
                item = workedOut,
                version = version("worked_out", AnswerType.BOOL),
                targets = listOf(target("worked_out", Direction.AT_LEAST, value = 3.0, period = Period.WEEK)),
                rollUp = rollUp("worked_out", RollUpAggregation.COUNT_OF_YES),
                answers = (0..2).map { answer("worked_out", day = thisWeek.plusDays(it.toLong()), bool = true) } +
                    (0..2).map { answer("worked_out", day = lastWeek.plusDays(it.toLong()), bool = true) },
            ),
            today, weeks,
        )

        val weekly = detail.figures.weekly!!
        assertEquals(Period.WEEK, weekly.period)
        assertEquals(1, weekly.summary.met, "only last week counts; this week is still in flight")
        // And the running week is absent rather than excluded. Two weeks of the window have closed,
        // so two is the whole tally -- counting the week in flight as an exclusion would say it was
        // set aside when in truth it has not happened yet.
        assertEquals(
            2,
            weekly.summary.met + weekly.summary.missed + weekly.summary.excluded,
            "the weekly figures cover the closed weeks of the window and nothing else",
        )

        val running = (detail.chart as Chart.DayCalendar).weeks.single { it.weekStart == thisWeek }
        assertFalse(running.closed)
        assertEquals(ExclusionReason.PERIOD_OPEN, running.result?.exclusionReason)
        // 9.7: an open week shows how far it has got, in place of a verdict it has not earned.
        assertEquals(3.0, running.progress?.observed)
    }

    @Test
    @DisplayName("A3.1 - a week with unanswered days reports what was observed, and says it is incomplete")
    fun anIncompleteWeekIsFlagged() {
        val lastWeek = weeks.weekStart(today).minusWeeks(1)
        val detail = ItemDetails.assemble(
            history(
                item = item("worked_out", createdOn = longAgo),
                version = version("worked_out", AnswerType.BOOL),
                targets = listOf(target("worked_out", Direction.AT_LEAST, value = 4.0, period = Period.WEEK)),
                rollUp = rollUp("worked_out", RollUpAggregation.COUNT_OF_YES),
                answers = (0..3).map { answer("worked_out", day = lastWeek.plusDays(it.toLong()), bool = true) } +
                    answer("worked_out", day = lastWeek.plusDays(4), bool = false),
            ),
            today, weeks,
        )

        val week = (detail.chart as Chart.DayCalendar).weeks.single { it.weekStart == lastWeek }
        assertEquals(4.0, week.value, "four workouts, not four of five")
        assertTrue(week.incomplete, "two days were never answered")
        assertEquals(1, detail.figures.weekly!!.summary.met, "A3.2 - assessed against the observed four")
    }

    // ---------------------------------------------------------------- two periods at once

    @Test
    @DisplayName("7.2 - steps: daily and weekly are scored separately and never merged")
    fun stepsReportsBothPeriods() {
        val week = weeks.weekStart(today).minusWeeks(1)
        val detail = ItemDetails.assemble(
            history(
                item = item("steps", createdOn = longAgo, kind = ItemKind.MEASURED),
                version = version("steps", AnswerType.NUMBER, Slot.NONE),
                targets = listOf(
                    target("steps", Direction.AT_LEAST, value = 8_000.0),
                    target("steps", Direction.AT_LEAST, value = 56_000.0, period = Period.WEEK),
                ),
                measured = (0..2).map { measured(day = week.plusDays(it.toLong()), value = 25_000.0) } +
                    (3..6).map { measured(day = week.plusDays(it.toLong()), value = 0.0) },
            ),
            today, weeks,
        )

        val daily = detail.figures.daily!!
        assertEquals(3, daily.summary.met)
        assertEquals(4, daily.summary.missed)

        val weekly = detail.figures.weekly!!
        assertEquals(1, weekly.summary.met, "75,000 clears the weekly target the daily one failed")
        assertNotEquals(daily.summary.hitRate, weekly.summary.hitRate)
    }

    @Test
    @DisplayName("a conflicted day leaves the week incomplete rather than inflating its total")
    fun conflictedDaysDropOutOfTheWeek() {
        val week = weeks.weekStart(today).minusWeeks(1)
        val detail = ItemDetails.assemble(
            history(
                item = item("steps", createdOn = longAgo, kind = ItemKind.MEASURED),
                version = version("steps", AnswerType.NUMBER, Slot.NONE),
                targets = listOf(target("steps", Direction.AT_LEAST, value = 56_000.0, period = Period.WEEK)),
                measured = (0..5).map { measured(day = week.plusDays(it.toLong()), value = 10_000.0) } +
                    measured(
                        day = week.plusDays(6), value = 30_000.0,
                        state = com.zdredge.consistency.domain.model.MeasuredState.CONFLICTED,
                    ),
            ),
            today, weeks,
        )

        val figure = (detail.chart as Chart.StepBars).weeks.single { it.weekStart == week }
        assertEquals(60_000.0, figure.value, "the conflicted day contributes nothing, not 30,000")
        assertEquals(6, figure.observedDays)
        assertTrue(figure.incomplete)
    }

    @Test
    @DisplayName("coffee carries two goals at once, and the screen keeps them apart")
    fun coffeeHasBothPeriods() {
        val detail = ItemDetails.assemble(
            history(
                item = item("coffee", createdOn = longAgo),
                version = version("coffee", AnswerType.NUMBER),
                targets = listOf(
                    target("coffee", Direction.AT_MOST, value = 2.0),
                    target("coffee", Direction.AT_MOST, value = 14.0, period = Period.WEEK),
                ),
                rollUp = rollUp("coffee", RollUpAggregation.SUM),
                answers = (0..13).map { answer("coffee", day = today.minusDays(it.toLong()), number = 2.0) },
            ),
            today, weeks,
        )

        assertEquals(Period.DAY, detail.figures.daily!!.period)
        assertEquals(Period.WEEK, detail.figures.weekly!!.period)
        assertEquals(ItemView.DailyBars(weeklyTotals = true), detail.view)
        // An upper bound has no coherent "how close was I", and inventing one would read as failure.
        assertNull(detail.figures.daily!!.summary.averageAttainment)
    }

    // ---------------------------------------------------------------- weekly questions

    @Test
    @DisplayName("a weekly question gets one square a week, and this week's is still ahead")
    fun weeklyQuestionsAreDrawnByTheWeek() {
        val detail = ItemDetails.assemble(
            history(
                item = item("saw_friends", createdOn = longAgo),
                version = version("saw_friends", AnswerType.SINGLE_SELECT, Slot.WEEKLY),
                options = listOf(option("saw_friends", "saw_friends.yes", 0)),
                targets = listOf(
                    target("saw_friends", Direction.MUST_INCLUDE, option = "saw_friends.yes", period = Period.WEEK),
                ),
                answers = (1..4).map {
                    answer(
                        "saw_friends",
                        day = weeks.weekEnd(today.minusWeeks(it.toLong())),
                        selections = setOf("saw_friends.yes"),
                    )
                },
            ),
            today, weeks,
        )

        val squares = (detail.chart as Chart.WeekSquares).weeks
        assertEquals(ItemDetails.CHART_WEEKS, squares.size)
        assertEquals(DayState.FUTURE, squares.last().cell.state, "this week is answered on Sunday")
        assertFalse(squares.last().closed)
        assertTrue(squares.dropLast(1).all { it.cell.state == DayState.MET })
        // The window is 14 days, so two of those Sundays fall inside it.
        assertEquals(2, detail.figures.weekly!!.summary.met)
    }

    // ---------------------------------------------------------------- observations

    @Test
    @DisplayName("the filter moves the average and the typical time, and leaves the count alone")
    fun theFilterReachesTheTrendAndNotTheCount() {
        // Weeknights at 23:00, weekends at 02:00 -- which is the *latest* position on a 04:00 axis,
        // not the earliest. Filtering the weekends out must pull the average back to 23:00 exactly.
        val bedtime = item("bedtime", createdOn = today.minusDays(40))
        val answers = (0..39).map {
            val night = today.minusDays(it.toLong())
            val late = night.dayOfWeek == DayOfWeek.FRIDAY || night.dayOfWeek == DayOfWeek.SATURDAY
            answer(
                "bedtime",
                day = night,
                time = if (late) LocalTime.of(2, 0) else LocalTime.of(23, 0),
            )
        }
        val history = history(
            item = bedtime,
            version = version("bedtime", AnswerType.TIME, Slot.MORNING, Classification.OBSERVATION),
            answers = answers,
        )

        val weeknights = ItemDetails.assemble(history, today, weeks, NightFilter.SundayToThursday)
        val everyNight = ItemDetails.assemble(history, today, weeks, NightFilter.EveryNight)

        val chart = weeknights.chart as Chart.ClockDots
        assertTrue(
            chart.nights.none { it.day.dayOfWeek == DayOfWeek.FRIDAY || it.day.dayOfWeek == DayOfWeek.SATURDAY },
            "a filtered night is not drawn",
        )

        val elevenPm = ClockAxis.minuteOf(LocalTime.of(23, 0)).toDouble()
        assertEquals(elevenPm, chart.trend.last().minute, "the average is over the shown nights only")
        assertEquals(elevenPm, weeknights.figures.typicalMinute!!)
        assertNotEquals(elevenPm, (everyNight.chart as Chart.ClockDots).trend.last().minute)

        // The recording count measures answering every night, so the filter must not touch it.
        assertEquals(
            weeknights.figures.recording!!.longest,
            everyNight.figures.recording!!.longest,
        )
        assertNull(weeknights.figures.daily, "an observation has no goal to have met")
    }

    @Test
    @DisplayName("an observation shows how consistently it was recorded, not a streak of successes")
    fun observationsShowARecordingCount() {
        val start = today.minusDays(9)
        val detail = ItemDetails.assemble(
            history(
                item = item("mindset", createdOn = start),
                version = version("mindset", AnswerType.SCALE, Slot.NIGHT, Classification.OBSERVATION),
                answers = (0..6).map { answer("mindset", day = start.plusDays(it.toLong()), scale = 3) },
            ),
            today, weeks,
        )

        assertNull(detail.figures.daily)
        assertNull(detail.figures.weekly)
        assertEquals(7, detail.figures.recording!!.longest)
    }

    // ---------------------------------------------------------------- the shapes of the charts

    @Test
    @DisplayName("before bed: one row per activity, the forbidden one last")
    fun activityRows() {
        val detail = ItemDetails.assemble(
            history(
                item = item("pre_sleep", createdOn = longAgo),
                version = version("pre_sleep", AnswerType.MULTI_SELECT, Slot.MORNING),
                options = listOf(
                    option("pre_sleep", "pre_sleep.read_a_book", 0),
                    option("pre_sleep", "pre_sleep.watched_youtube", 1),
                    option("pre_sleep", "pre_sleep.scrolled_on_phone", 2),
                    option("pre_sleep", "pre_sleep.watched_tv", 3),
                ),
                targets = listOf(
                    target("pre_sleep", Direction.MUST_NOT_INCLUDE, option = "pre_sleep.scrolled_on_phone"),
                ),
                answers = listOf(
                    answer(
                        "pre_sleep", day = today.minusDays(2),
                        selections = setOf("pre_sleep.read_a_book", "pre_sleep.scrolled_on_phone"),
                    ),
                ),
            ),
            today, weeks,
        )

        val rows = (detail.chart as Chart.ActivityRows).rows
        assertEquals(4, rows.size)
        // Its ordinal is 2, so ordering alone would leave "watched TV" beneath it.
        assertEquals("pre_sleep.scrolled_on_phone", rows.last().option.id.value)
        assertTrue(rows.last().flagged)
        assertEquals(setOf(today.minusDays(2)), rows.last().picked)
        assertEquals(setOf(today.minusDays(2)), rows.first().picked)
    }

    @Test
    @DisplayName("meals: a half is shaded as the whole number below it")
    fun shadedCalendar() {
        val detail = ItemDetails.assemble(
            history(
                item = item("meals", createdOn = longAgo),
                version = version("meals", AnswerType.NUMBER),
                targets = listOf(target("meals", Direction.AT_LEAST, value = 3.0)),
                answers = listOf(
                    answer("meals", day = today.minusDays(1), number = 3.0),
                    answer("meals", day = today.minusDays(2), number = 2.5),
                    answer("meals", day = today.minusDays(3), number = 5.0),
                ),
            ),
            today, weeks,
        )

        val chart = detail.chart as Chart.ShadedCalendar
        assertEquals(4, chart.scale.buckets.size)
        assertEquals(2, chart.shades.getValue(today.minusDays(1)).bucket, "three meals is the target shade")
        assertEquals(1, chart.shades.getValue(today.minusDays(2)).bucket, "2.5 is shaded as 2")
        assertEquals(3, chart.shades.getValue(today.minusDays(3)).bucket)
        assertTrue(chart.shades.getValue(today.minusDays(1)).reachesTarget)
        assertFalse(chart.shades.getValue(today.minusDays(2)).reachesTarget)
    }

    // ---------------------------------------------------------------- a target that changed

    /** Water raised from 2 bottles to 3 twelve days ago, inside the chart's five weeks. */
    private val raisedOn = today.minusDays(12)

    private fun waterRaised(answers: List<com.zdredge.consistency.domain.model.Answer>) = history(
        item = item("water", createdOn = longAgo),
        version = version("water", AnswerType.NUMBER),
        targets = listOf(
            target("water", Direction.AT_LEAST, value = 2.0),
            target("water", Direction.AT_LEAST, value = 3.0, from = raisedOn),
        ),
        answers = answers,
    )

    @Test
    @DisplayName("5.4 - a day is shaded against the target in force that day, not today's")
    fun shadesFollowTheTargetOfTheirDay() {
        val before = raisedOn.minusDays(2)
        val after = raisedOn.plusDays(2)
        val detail = ItemDetails.assemble(
            waterRaised(listOf(answer("water", day = before, number = 2.0), answer("water", day = after, number = 2.0))),
            today, weeks,
        )

        val chart = detail.chart as Chart.ShadedCalendar
        assertTrue(chart.shades.getValue(before).reachesTarget, "two bottles met the target of two")
        assertFalse(chart.shades.getValue(after).reachesTarget, "and did not meet the target of three")
        assertEquals(3, chart.shades.getValue(before).scale.buckets.size, "the scale of a target of two")
        assertEquals(4, chart.shades.getValue(after).scale.buckets.size, "the scale of a target of three")
        assertEquals(4, chart.scale.buckets.size, "the key keeps today's scale")
        // Drawn on today's key by where each day stood against its own target: two bottles against a
        // target of two is the key's target shade; against a target of three it is one short.
        assertEquals(chart.scale.buckets.indexOfFirst { it.reachesTarget }, chart.shades.getValue(before).keyBucket)
        assertEquals(chart.scale.buckets.indexOfFirst { it.reachesTarget } - 1, chart.shades.getValue(after).keyBucket)
        // The shade and the judgement of the same day cannot disagree.
        assertEquals(DayState.MET, detail.days.single { it.day == before }.state)
    }

    @Test
    @DisplayName("5.4 - a changed target is named under the key, with the last day it applied")
    fun theEarlierTargetIsNamed() {
        val detail = ItemDetails.assemble(waterRaised(emptyList()), today, weeks)

        assertEquals(EarlierTarget(value = 2.0, until = raisedOn.minusDays(1)), detail.earlierTarget)
    }

    @Test
    @DisplayName("5.4 - a target that changed before the chart began is not news")
    fun aChangeBeforeTheChartIsNotNamed() {
        val detail = ItemDetails.assemble(
            history(
                item = item("coffee", createdOn = longAgo),
                version = version("coffee", AnswerType.NUMBER),
                targets = listOf(
                    target("coffee", Direction.AT_MOST, value = 3.0),
                    target("coffee", Direction.AT_MOST, value = 2.0, from = today.minusWeeks(10)),
                ),
            ),
            today, weeks,
        )

        assertNull(detail.earlierTarget)
        val chart = detail.chart as Chart.DailyBars
        assertEquals(listOf(TargetSpan(detail.days.first().day, today, 2.0)), chart.dailyTargets)
    }

    @Test
    @DisplayName("5.4 - a daily limit that changed on screen draws as two lines, each over its own days")
    fun barTargetsStepWhereTheyChanged() {
        val loweredOn = today.minusDays(9)
        val detail = ItemDetails.assemble(
            history(
                item = item("coffee", createdOn = longAgo),
                version = version("coffee", AnswerType.NUMBER),
                targets = listOf(
                    target("coffee", Direction.AT_MOST, value = 3.0),
                    target("coffee", Direction.AT_MOST, value = 2.0, from = loweredOn),
                ),
            ),
            today, weeks,
        )

        val chart = detail.chart as Chart.DailyBars
        assertEquals(
            listOf(
                TargetSpan(detail.days.first().day, loweredOn.minusDays(1), 3.0),
                TargetSpan(loweredOn, today, 2.0),
            ),
            chart.dailyTargets,
        )
        assertEquals(EarlierTarget(value = 3.0, until = loweredOn.minusDays(1)), detail.earlierTarget)
    }

    @Test
    @DisplayName("5.4 - days before a goal had any target draw no line at all")
    fun noLineWhereNoTargetApplied() {
        val setOn = today.minusDays(5)
        val detail = ItemDetails.assemble(
            history(
                item = item("coffee", createdOn = longAgo),
                version = version("coffee", AnswerType.NUMBER),
                targets = listOf(target("coffee", Direction.AT_MOST, value = 2.0, from = setOn)),
            ),
            today, weeks,
        )

        assertEquals(listOf(TargetSpan(setOn, today, 2.0)), (detail.chart as Chart.DailyBars).dailyTargets)
        assertNull(detail.earlierTarget, "a goal gaining its first target has no earlier target to name")
    }

    // ---------------------------------------------------------------- windows and ranges

    @Test
    @DisplayName("5.4 - the chart is five weeks and the figures are the same fourteen days")
    fun theChartAndTheWindow() {
        val detail = ItemDetails.assemble(
            history(
                item = item("vitamins", createdOn = longAgo),
                version = version("vitamins", AnswerType.BOOL),
                targets = listOf(target("vitamins", Direction.IS_TRUE)),
            ),
            today, weeks,
        )

        assertEquals(14, detail.figures.windowDays.size)
        assertEquals(today, detail.lastDay)
        assertEquals(DayOfWeek.MONDAY, detail.days.first().day.dayOfWeek, "five whole weeks back")
        assertEquals(weeks.weekStart(today).minusWeeks(4), detail.days.first().day)
        assertEquals(today, detail.days.last().day)
        assertTrue(detail.figures.windowDays.all { day -> detail.days.any { it.day == day } })
    }

    @Test
    @DisplayName("5.4 - the table reaches back to the item's first day; the chart stays at five weeks")
    fun theLogCoversTheWholeHistory() {
        val detail = ItemDetails.assemble(
            history(
                item = item("vitamins", createdOn = longAgo),
                version = version("vitamins", AnswerType.BOOL),
                targets = listOf(target("vitamins", Direction.IS_TRUE)),
                answers = listOf(answer("vitamins", day = longAgo.plusDays(3), bool = true)),
            ),
            today, weeks,
        )

        assertEquals(longAgo, detail.log.first().day)
        assertEquals(today, detail.log.last().day)
        assertEquals(DayState.MET, detail.log.single { it.day == longAgo.plusDays(3) }.state)
        assertEquals(weeks.weekStart(today).minusWeeks(4), detail.days.first().day, "the chart is unchanged")
        // The same judgements: the log's last five weeks are the chart's cells.
        assertEquals(detail.days, detail.log.filter { !it.day.isBefore(detail.days.first().day) })
    }

    @Test
    @DisplayName("3.1 - a morning item's window ends yesterday, so it gets fourteen real days")
    fun theWindowEndsOnTheLatestAnswerableDay() {
        val detail = ItemDetails.assemble(
            history(
                item = item("bedtime", createdOn = longAgo),
                version = version("bedtime", AnswerType.TIME, Slot.MORNING, Classification.OBSERVATION),
            ),
            today, weeks,
        )

        assertEquals(today.minusDays(1), detail.lastDay)
        assertEquals(today.minusDays(1), detail.figures.windowDays.last())
        assertTrue(
            detail.days.none { it.state == DayState.FUTURE },
            "ending on today would give it thirteen real days and one guaranteed blank",
        )
    }

    @Test
    @DisplayName("9.8 - what the chart shows and what the figures count are the same judgements")
    fun theChartAndTheFiguresReconcile() {
        val detail = ItemDetails.assemble(
            history(
                item = item("took_time", createdOn = today.minusDays(20)),
                version = version("took_time", AnswerType.SINGLE_SELECT),
                options = listOf(
                    option("took_time", "took_time.yes", 0),
                    option("took_time", "took_time.no", 1),
                    option("took_time", "took_time.no_opportunity", 2, noOpportunity = true),
                ),
                targets = listOf(target("took_time", Direction.MUST_INCLUDE, option = "took_time.yes")),
                answers = listOf(
                    answer("took_time", day = today.minusDays(2), selections = setOf("took_time.yes")),
                    answer("took_time", day = today.minusDays(3), selections = setOf("took_time.no")),
                    answer("took_time", day = today.minusDays(4), selections = setOf("took_time.no_opportunity")),
                    answer("took_time", day = today.minusDays(5), selections = setOf("took_time.yes"), capture = Capture.LATE),
                    answer("took_time", day = today.minusDays(6), capture = Capture.PENDING),
                ),
            ),
            today, weeks,
        )

        val window = detail.figures.windowDays.toSet()
        val drawn = detail.days.filter { it.day in window }
        val summary = detail.figures.daily!!.summary

        assertEquals(drawn.count { it.state == DayState.MET }, summary.met)
        assertEquals(drawn.count { it.state == DayState.MISSED }, summary.missed)
        assertEquals(drawn.count { it.state == DayState.NO_OPPORTUNITY }, summary.noOpportunityCount)
        // The unresolved deferral is one of the misses, not a category of its own.
        assertEquals(2, summary.met)
        assertEquals(2, summary.missed)
    }

    // ---------------------------------------------------------------- the real first week

    @Test
    @DisplayName("day 0 - a goal created on Thursday does not make Monday to Wednesday a failed week")
    fun theFirstPartialWeek() {
        // The real seeding: everything created on 2026-09-10, a Thursday, with its targets effective
        // the same day. The week's Monday is 09-07, before any target existed -- so the week is simply
        // not a goal week, rather than a stretching target of six judged on four possible days.
        val dayZero = LocalDate.of(2026, 9, 10)
        val saturday = LocalDate.of(2026, 9, 12)
        val detail = ItemDetails.assemble(
            history(
                item = item("stretched", createdOn = dayZero),
                version = version("stretched", AnswerType.BOOL, from = dayZero),
                targets = listOf(
                    target("stretched", Direction.AT_LEAST, value = 6.0, period = Period.WEEK, from = dayZero),
                ),
                rollUp = rollUp("stretched", RollUpAggregation.COUNT_OF_YES),
                answers = listOf(
                    answer("stretched", day = dayZero, bool = true),
                    answer("stretched", day = dayZero.plusDays(1), bool = true),
                ),
            ),
            saturday, weeks,
        )

        val firstWeek = (detail.chart as Chart.DayCalendar).weeks.single { it.weekStart == LocalDate.of(2026, 9, 7) }
        assertNull(firstWeek.target, "no weekly target was in force on the Monday")
        assertNull(firstWeek.result, "so the week is not a goal instance, met or missed")
        assertEquals(0, detail.figures.weekly!!.run.longest, "and it neither extends the run nor breaks it")

        // Most of a five-week chart precedes an item that is three days old, and none of it is failure.
        assertTrue(detail.days.count { it.state == DayState.NOT_ACTIVE } > 25)
        assertEquals(0, detail.days.count { it.state == DayState.MISSED })
    }
}
