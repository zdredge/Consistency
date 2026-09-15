package com.zdredge.consistency.fixture

import com.zdredge.consistency.data.SeedLibrary
import com.zdredge.consistency.domain.checkin.AnswerDay
import com.zdredge.consistency.domain.checkin.CaptureResolver
import com.zdredge.consistency.domain.checkin.CheckInContent
import com.zdredge.consistency.domain.checkin.CheckInPlanner
import com.zdredge.consistency.domain.checkin.CheckInTimes
import com.zdredge.consistency.domain.checkin.Grace
import com.zdredge.consistency.domain.checkin.PlannedCheckIn
import com.zdredge.consistency.domain.checkin.RolloverPlanner
import com.zdredge.consistency.domain.checkin.StepMapper
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.time.DayResolver
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * Plausible history for the seed library, ending at the clock's "now".
 *
 * **Every state comes from the app's own rules, not from this file's opinion of them.** Which
 * check-ins exist is `CheckInPlanner`'s answer; which questions a check-in asks is `CheckInContent`'s;
 * the capture an answer earns is `CaptureResolver`'s, asked at the moment the answer is given; whether
 * an old check-in is missed is `Grace`'s; and a step day is `StepMapper`'s, frozen by
 * `RolloverPlanner`. A fixture that restated those rules would manufacture states the app can never
 * reach, and verify nothing.
 *
 * Deterministic for a given [seed] and clock, so a scenario loads the same history every time and
 * tests can pin exact states.
 */
internal class HistoryGenerator(
    private val clock: Clock,
    private val seed: Int,
    private val times: CheckInTimes = CheckInTimes(),
    /** Share of past check-ins nobody answered. */
    private val missRate: Double = 0.08,
    /** Share of answered check-ins answered the next day rather than the same evening. */
    private val backfillRate: Double = 0.06,
    /** Share of days with no step reading at all -- a phone left off, a day nothing synced. */
    private val stepGapRate: Double = 0.03,
) {
    private val dayResolver = DayResolver(clock)
    private val today: LocalDate = dayResolver.today()
    private val now: Instant = dayResolver.now()

    /**
     * [days] days of history: the library is installed `days - 1` days before today, so the first
     * check-in falls on the install day and the last on today.
     */
    fun generate(days: Int): FixtureDataset {
        require(days >= 1) { "a history needs at least one day" }
        val random = Random(seed)
        val installDay = today.minusDays(days - 1L)
        val library = FixtureLibrary.effectiveFrom(installDay, dayResolver)

        val planned = CheckInPlanner(dayResolver)
            .planRange(installDay, today, times, library.items, library.versions)

        val checkIns = mutableListOf<CheckIn>()
        val answers = mutableListOf<GivenAnswer>()
        for (check in planned) {
            val answeredAt = answerTime(check, random)
            if (answeredAt == null) {
                checkIns += CheckIn(
                    day = check.day,
                    slot = check.slot,
                    // Only the rule decides missed. A check-in still inside its grace is pending,
                    // however unlikely it is that anyone will answer it.
                    state = if (Grace.isPastGrace(check.day, today)) {
                        CheckInState.MISSED
                    } else {
                        CheckInState.PENDING
                    },
                    scheduledAt = check.scheduledAt,
                )
                continue
            }
            checkIns += CheckIn(check.day, check.slot, CheckInState.ANSWERED, answeredAt, check.scheduledAt)
            answers += answersFor(check, answeredAt, library, random)
        }

        return FixtureDataset(
            items = library.items,
            versions = library.versions,
            options = library.options,
            targets = library.targets,
            containerSizes = library.containerSizes,
            rollUpSpecs = library.rollUpSpecs,
            checkIns = checkIns,
            answers = answers,
            measuredValues = steps(installDay, random),
        )
    }

    /**
     * When a check-in was answered, or null if it was not.
     *
     * Nothing is answered before it was due or after now, so a check-in not yet due is always pending.
     */
    private fun answerTime(check: PlannedCheckIn, random: Random): Instant? {
        if (check.scheduledAt.isAfter(now)) return null
        if (random.nextDouble() < missRate) return null

        val sameEvening = check.scheduledAt.plus(Duration.ofMinutes(random.nextLong(2, 75)))
        val nextDay = dayResolver.instantAt(check.day.plusDays(1), LocalTime.of(12, random.nextInt(0, 60)))
        val preferred = if (random.nextDouble() < backfillRate) nextDay else sameEvening
        return listOf(preferred, sameEvening).firstOrNull { !it.isAfter(now) }
    }

    private fun answersFor(
        check: PlannedCheckIn,
        answeredAt: Instant,
        library: FixtureLibrary,
        random: Random,
    ): List<GivenAnswer> {
        // Decided as the app decides it: by the clock at the moment of answering.
        val capture = CaptureResolver(DayResolver(Clock.fixed(answeredAt, clock.zone)))
            .forEntry(check.day)
        val answerDay = AnswerDay.forCheckIn(check.day, check.slot)

        return CheckInContent.forCheckIn(
            checkInDay = check.day,
            slot = check.slot,
            items = library.items,
            versions = library.versions,
            measuredAvailable = false,
        ).map { entry ->
            val base = Answer(
                itemId = entry.item.id,
                itemVersionId = entry.version.id,
                day = answerDay,
                capture = capture,
                submittedAt = answeredAt,
            )
            GivenAnswer(
                answer = AnswerValues.fill(
                    base = base,
                    type = entry.version.answerType,
                    options = library.options.filter { it.itemId == entry.item.id },
                    random = random,
                ),
                checkInDay = check.day,
                slot = check.slot,
            )
        }
    }

    /**
     * One step reading per day, today's so far included.
     *
     * Each day is read the morning after, as `syncRecentSteps` reads it, and frozen by the rollover's
     * own rule once its window has passed -- so the last day or two stay provisional, as they would.
     */
    private fun steps(installDay: LocalDate, random: Random): List<MeasuredValue> {
        val read = generateSequence(installDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .filter { random.nextDouble() >= stepGapRate }
            .mapNotNull { day ->
                StepMapper.map(
                    itemId = ItemId(SeedLibrary.STEPS),
                    day = day,
                    origins = listOf(MeasuredOrigin(PHONE_ORIGIN, random.nextInt(3_500, 16_000).toDouble())),
                    now = minOf(dayResolver.instantAt(day.plusDays(1), times.morning), now),
                )
            }
            .toList()

        val toFreeze = RolloverPlanner.plan(today, emptyList(), read, now).valuesToFreeze.toSet()
        return read.map { if (it in toFreeze) it.copy(state = MeasuredState.FROZEN) else it }
    }

    companion object {
        /** The on-device origin M0 found on the Pixel 9 Pro. */
        const val PHONE_ORIGIN = "com.android.healthconnect.phone.jf9fc11088d6938c28480cb1ae667b25e"
    }
}
