package com.zdredge.consistency.fixture

import com.zdredge.consistency.data.SeedLibrary
import com.zdredge.consistency.domain.checkin.AnswerDay
import com.zdredge.consistency.domain.checkin.AnswerRevision
import com.zdredge.consistency.domain.checkin.CaptureResolver
import com.zdredge.consistency.domain.checkin.CheckInContent
import com.zdredge.consistency.domain.checkin.CheckInPlanner
import com.zdredge.consistency.domain.checkin.CheckInTimes
import com.zdredge.consistency.domain.checkin.Grace
import com.zdredge.consistency.domain.checkin.PlannedCheckIn
import com.zdredge.consistency.domain.checkin.RolloverPlanner
import com.zdredge.consistency.domain.checkin.StepMapper
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Plausible history for the seed library, ending at the clock's "now".
 *
 * **Every state comes from the app's own rules, not from this file's opinion of them.** Which
 * check-ins exist is `CheckInPlanner`'s answer; which questions a check-in asks, including a deferral
 * carried into the next morning, is `CheckInContent`'s; the capture an answer earns is
 * `CaptureResolver`'s, asked at the moment the answer is given; a correction is `AnswerRevision`'s;
 * whether an old check-in is missed is `Grace`'s; and a step day is `StepMapper`'s, frozen by
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
) {
    private val dayResolver = DayResolver(clock)
    private val today: LocalDate = dayResolver.today()
    private val now: Instant = dayResolver.now()

    fun generate(days: Int): FixtureDataset = generate(HistoryShape(days))

    /**
     * [HistoryShape.days] days of history: the library is installed `days - 1` days before today, so
     * the first check-in falls on the install day and the last on today.
     */
    fun generate(shape: HistoryShape): FixtureDataset {
        require(shape.days >= 1) { "a history needs at least one day" }
        val random = Random(seed)
        val installDay = today.minusDays(shape.days - 1L)
        val offset = { day: LocalDate -> ChronoUnit.DAYS.between(installDay, day) }
        val library = shape.library(FixtureLibrary.effectiveFrom(installDay, dayResolver), installDay)

        val planned = CheckInPlanner(dayResolver)
            .planRange(installDay, today, times, library.items, library.versions)
            .sortedBy { it.scheduledAt }
        val answeredAt = planned.associateWith { answerTime(it, offset(it.day), shape, random) }

        // Keyed as the schema keys an answer -- one per item per day -- so a resolved deferral
        // replaces its "not yet" instead of sitting beside it.
        val answers = LinkedHashMap<Pair<ItemId, LocalDate>, GivenAnswer>()
        val checkIns = mutableListOf<CheckIn>()

        for (check in planned) {
            val at = answeredAt.getValue(check)
            val off = offset(check.day)
            if (at == null) {
                val pastGrace = Grace.isPastGrace(check.day, today)
                checkIns += CheckIn(
                    day = check.day,
                    slot = check.slot,
                    // Only the rule decides missed. A check-in still inside its grace is pending,
                    // however unlikely it is that anyone will answer it.
                    state = if (pastGrace) CheckInState.MISSED else CheckInState.PENDING,
                    scheduledAt = check.scheduledAt,
                )
                if (pastGrace && check.slot == Slot.NIGHT && shape.lateNights(off)) {
                    lateAnswers(check, library, shape, random).forEach { answers[it.key] = it }
                }
                continue
            }

            checkIns += CheckIn(check.day, check.slot, CheckInState.ANSWERED, at, check.scheduledAt)
            val given = answersFor(
                check = check,
                at = at,
                library = library,
                shape = shape,
                deferring = check.slot == Slot.NIGHT && shape.deferredNights(off),
                pending = answers.values.map { it.answer }.filter { it.capture == Capture.PENDING },
                random = random,
            )
            given.forEach { answers[it.key] = it }

            if (shape.corrected(off, check.slot)) {
                given.filter { it.answer.capture != Capture.PENDING }
                    .mapNotNull { correct(it) }
                    .forEach { answers[it.key] = it }
            }
        }

        return FixtureDataset(
            items = library.items,
            versions = library.versions,
            options = library.options,
            targets = library.targets,
            containerSizes = library.containerSizes,
            rollUpSpecs = library.rollUpSpecs,
            checkIns = checkIns.sortedWith(compareBy({ it.day }, { it.scheduledAt })),
            answers = answers.values.toList(),
            measuredValues = steps(installDay, shape, random),
        )
    }

    /**
     * When a check-in was answered, or null if it was not.
     *
     * Nothing is answered before it was due or after now, so a check-in not yet due is always pending.
     */
    private fun answerTime(check: PlannedCheckIn, off: Long, shape: HistoryShape, random: Random): Instant? {
        if (check.scheduledAt.isAfter(now)) return null
        if (shape.unanswered(off, check.slot)) return null
        if (check.slot == Slot.NIGHT && shape.lateNights(off) && Grace.isPastGrace(check.day, today)) {
            return null
        }

        val sameEvening = check.scheduledAt.plus(Duration.ofMinutes(random.nextLong(2, 75)))
        // A deferral is answered the same evening, so the morning that resolves it comes after it.
        val forced = shape.answered(off, check.slot) ||
            (check.slot == Slot.NIGHT && shape.deferredNights(off))
        if (forced) return sameEvening.takeIf { !it.isAfter(now) }

        if (random.nextDouble() < shape.missRate) return null
        val nextDay = dayResolver.instantAt(check.day.plusDays(1), LocalTime.of(12, random.nextInt(0, 60)))
        val preferred = if (random.nextDouble() < shape.backfillRate) nextDay else sameEvening
        return listOf(preferred, sameEvening).firstOrNull { !it.isAfter(now) }
    }

    private fun answersFor(
        check: PlannedCheckIn,
        at: Instant,
        library: FixtureLibrary,
        shape: HistoryShape,
        deferring: Boolean,
        pending: List<Answer>,
        random: Random,
    ): List<GivenAnswer> {
        // Decided as the app decides it: by the clock at the moment of answering.
        val capture = CaptureResolver(DayResolver(Clock.fixed(at, clock.zone)))

        return CheckInContent.forCheckIn(
            checkInDay = check.day,
            slot = check.slot,
            items = library.items,
            versions = library.versions,
            deferrals = pending,
            measuredAvailable = false,
        ).map { entry ->
            val answerDay = entry.carriedOverFrom ?: AnswerDay.forCheckIn(check.day, check.slot)
            val base = Answer(
                itemId = entry.item.id,
                itemVersionId = entry.version.id,
                day = answerDay,
                capture = if (deferring && entry.canDefer) {
                    capture.deferred()
                } else {
                    capture.forEntry(check.day, entry.carriedOverFrom)
                },
                submittedAt = at,
            )
            val answer = if (base.capture == Capture.PENDING) {
                base
            } else {
                val filled = AnswerValues.fill(
                    base = base,
                    type = entry.version.answerType,
                    options = library.options.filter { it.itemId == entry.item.id },
                    random = random,
                )
                shape.value(filled, ChronoUnit.DAYS.between(library.items.minOf { it.createdOn }, answerDay))
            }
            GivenAnswer(answer, check.day, check.slot, entry.carriedOverFrom)
        }
    }

    /** A missed night answered three days on, past its grace. */
    private fun lateAnswers(
        check: PlannedCheckIn,
        library: FixtureLibrary,
        shape: HistoryShape,
        random: Random,
    ): List<GivenAnswer> {
        val at = dayResolver.instantAt(check.day.plusDays(3), LocalTime.of(12, random.nextInt(0, 60)))
        if (at.isAfter(now)) return emptyList()
        return answersFor(check, at, library, shape, deferring = false, pending = emptyList(), random = random)
    }

    /**
     * The same answer corrected a day later, outside any check-in, through `AnswerRevision` -- which
     * keeps the original capture and time and sets the edit stamp. Null when a day later is the future.
     */
    private fun correct(given: GivenAnswer): GivenAnswer? {
        val editedAt = given.answer.submittedAt.plus(Duration.ofDays(1))
        if (editedAt.isAfter(now)) return null
        val changed = AnswerValues.corrected(given.answer) ?: return null
        val incoming = changed.copy(
            submittedAt = editedAt,
            capture = CaptureResolver(DayResolver(Clock.fixed(editedAt, clock.zone)))
                .forEntry(given.checkInDay, given.carriedOverFrom),
        )
        val revised = AnswerRevision.resolve(given.answer, incoming, sameCheckIn = false, now = editedAt)
        return given.copy(answer = revised)
    }

    /**
     * One step reading per day, today's so far included.
     *
     * Each day is read the morning after, as `syncRecentSteps` reads it, and frozen by the rollover's
     * own rule once its window has passed -- so the last day or two stay provisional, as they would.
     */
    private fun steps(installDay: LocalDate, shape: HistoryShape, random: Random): List<MeasuredValue> {
        val read = generateSequence(installDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .mapNotNull { day ->
                val off = ChronoUnit.DAYS.between(installDay, day)
                val gap = random.nextDouble() < shape.stepGapRate
                if (gap || shape.noSteps(off)) return@mapNotNull null
                val phone = MeasuredOrigin(PHONE_ORIGIN, random.nextInt(3_500, 16_000).toDouble())
                val origins = if (shape.secondStepOrigin(off)) {
                    listOf(phone, MeasuredOrigin(WATCH_ORIGIN, random.nextInt(3_000, 14_000).toDouble()))
                } else {
                    listOf(phone)
                }
                StepMapper.map(
                    itemId = ItemId(SeedLibrary.STEPS),
                    day = day,
                    origins = origins,
                    now = minOf(dayResolver.instantAt(day.plusDays(1), times.morning), now),
                )
            }
            .toList()

        val toFreeze = RolloverPlanner.plan(today, emptyList(), read, now).valuesToFreeze.toSet()
        return read.map { if (it in toFreeze) it.copy(state = MeasuredState.FROZEN) else it }
    }

    private val GivenAnswer.key get() = answer.itemId to answer.day

    companion object {
        /** The on-device origin M0 found on the Pixel 9 Pro. */
        const val PHONE_ORIGIN = "com.android.healthconnect.phone.jf9fc11088d6938c28480cb1ae667b25e"

        /** Samsung Health, installed on the same phone and not yet writing steps (architecture §8). */
        const val WATCH_ORIGIN = "com.samsung.health"
    }
}
