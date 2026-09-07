package com.zdredge.consistency.data

import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.data.db.LOCAL_USER_ID
import com.zdredge.consistency.data.db.entity.CheckInEntity
import com.zdredge.consistency.data.mapper.originEntities
import com.zdredge.consistency.data.mapper.selectionEntities
import com.zdredge.consistency.data.mapper.toDomain
import com.zdredge.consistency.data.mapper.toEntity
import com.zdredge.consistency.domain.checkin.CheckInContent
import com.zdredge.consistency.domain.checkin.CheckInEntry
import com.zdredge.consistency.domain.checkin.CheckInPlanner
import com.zdredge.consistency.domain.checkin.CheckInTimes
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.ContainerSize
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.time.DayResolver
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The one way in and out of storage.
 *
 * Its shape is dictated by the scoring path being one-directional (architecture section 6): the
 * repository hands raw rows to the domain layer, and the domain layer hands numbers back. So the
 * reads here are exactly the inputs the M2 calculators already take as parameters, and nothing is
 * shaped for a screen.
 *
 * There is deliberately **no dashboard-shaped aggregate loader**. M10 knows what the dashboard
 * wants; guessing now would produce an API written against nothing and tested against less.
 *
 * **Nothing computed is stored, and nothing stored is computed here.** Sleep duration, lingering
 * minutes, weekly roll-ups, hit rates and both goal-completion ratios are the domain layer's, on
 * read (docs/CLAUDE.md, architecture T4). This class moves rows.
 */
class ConsistencyRepository(
    private val db: ConsistencyDatabase,
    private val dayResolver: DayResolver,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    // ---- First run ---------------------------------------------------------------------------

    /**
     * Populates the spec section 4 library, once, on an empty database.
     *
     * Guarded on emptiness rather than on a "seeded" flag because the flag and the rows can disagree
     * -- and because the library is editable and removable from the moment it lands (spec section
     * 4). Once the user has deleted an item, re-adding it would be the app overruling them.
     *
     * Everything takes effect from [on], so targets apply from the first day rather than from a date
     * baked into the source, and no period before installation is scored.
     */
    suspend fun seedLibraryIfEmpty(on: LocalDate): Boolean {
        if (db.itemDao().allItems().isNotEmpty()) return false

        db.itemDao().insertItems(SeedLibrary.items(dayResolver.startOfDay(on)))
        db.itemDao().insertVersions(SeedLibrary.versions(on))
        db.itemDao().insertOptions(SeedLibrary.options())
        db.targetDao().insertTargets(SeedLibrary.targets(on))
        db.targetDao().insertContainerSizes(SeedLibrary.containerSizes(on))
        db.targetDao().insertRollUpSpecs(SeedLibrary.rollUpSpecs())
        return true
    }

    // ---- Definitions -------------------------------------------------------------------------

    suspend fun items(): List<Item> = db.itemDao().allItems().map { it.toDomain(dayResolver) }

    suspend fun item(itemId: ItemId): Item? =
        db.itemDao().item(itemId.value)?.toDomain(dayResolver)

    suspend fun versions(): List<ItemVersion> = db.itemDao().allVersions().map { it.toDomain() }

    suspend fun versions(itemId: ItemId): List<ItemVersion> =
        db.itemDao().versionsFor(itemId.value).map { it.toDomain() }

    /** The version in force on a date. Null before the first one took effect. */
    suspend fun versionInForce(itemId: ItemId, on: LocalDate): ItemVersion? =
        db.itemDao().versionInForce(itemId.value, on.toString())?.toDomain()

    /** Every option, retired included, so a past answer referencing one still renders. */
    suspend fun options(itemId: ItemId): List<SelectOption> =
        db.itemDao().optionsFor(itemId.value).map { it.toDomain(dayResolver) }

    /** Only the options still offered. This is the check-in screen's list, not history's. */
    suspend fun activeOptions(itemId: ItemId): List<SelectOption> =
        db.itemDao().activeOptionsFor(itemId.value).map { it.toDomain(dayResolver) }

    suspend fun allOptions(): List<SelectOption> =
        db.itemDao().allOptions().map { it.toDomain(dayResolver) }

    /**
     * The options a goal treats as "no opportunity" (spec constraint 17). The domain scorer takes
     * this set and checks it *before* evaluating direction, so it must be supplied rather than
     * inferred at scoring time.
     */
    suspend fun noOpportunityOptionIds() =
        db.itemDao().allOptions().filter { it.isNoOpportunity }
            .map { com.zdredge.consistency.domain.model.OptionId(it.id) }
            .toSet()

    // ---- Targets -----------------------------------------------------------------------------

    /**
     * Every target for the item, superseded ones included. Which is in force on a date is
     * `TargetResolver`'s question, not a WHERE clause -- see TargetDao for why that rule keeps one
     * home.
     */
    suspend fun targets(itemId: ItemId): List<Target> =
        db.targetDao().targetsFor(itemId.value).map { it.toDomain() }

    suspend fun targets(itemId: ItemId, period: Period): List<Target> =
        db.targetDao().targetsFor(itemId.value, period.name).map { it.toDomain() }

    suspend fun allTargets(): List<Target> = db.targetDao().allTargets().map { it.toDomain() }

    suspend fun containerSizes(itemId: ItemId): List<ContainerSize> =
        db.targetDao().containerSizesFor(itemId.value).map { it.toDomain() }

    suspend fun allContainerSizes(): List<ContainerSize> =
        db.targetDao().allContainerSizes().map { it.toDomain() }

    suspend fun rollUpSpecs(): List<RollUpSpec> =
        db.targetDao().allRollUpSpecs().map { it.toDomain() }

    // ---- Check-ins ---------------------------------------------------------------------------

    /** The response-rate denominator over a window: every check-in that was expected in it. */
    suspend fun checkIns(from: LocalDate, to: LocalDate): List<CheckIn> =
        db.checkInDao().between(from.toString(), to.toString()).map { it.toDomain() }

    suspend fun checkIns(day: LocalDate): List<CheckIn> =
        db.checkInDao().onDay(day.toString()).map { it.toDomain() }

    /**
     * Creates any check-in rows that should exist and do not, up to and including [today].
     *
     * Called on app open. Without it a day the user never opened the app on has no row, and a day
     * with no row is a day that silently never counted — so skipping a week would *improve* response
     * rate instead of damaging it (architecture §5). Returns how many were created.
     *
     * **Generation starts from the day after the last one already covered, or from [today] on a
     * fresh install.** An install owes no history, and fabricating missed check-ins for days before
     * the app existed would open the record with a failure that never happened.
     *
     * M5 wraps `CheckInPlanner` in the rollover worker for the same purpose. This is the same
     * function, so the two cannot disagree; what M5 adds is running without the user present.
     */
    suspend fun ensureCheckInsExist(
        today: LocalDate,
        times: CheckInTimes = CheckInTimes(),
    ): Int {
        val from = db.checkInDao().latestDay()?.let { LocalDate.parse(it).plusDays(1) } ?: today
        // Items and versions are passed so a check-in that would ask nothing is never expected.
        // On install day the morning check-in covers yesterday, when no item existed, and generating
        // it would put an unanswerable guaranteed miss into the response-rate denominator.
        val planned = CheckInPlanner(dayResolver)
            .planRange(from, today, times, items(), versions())
        if (planned.isEmpty()) return 0

        // Only the genuinely missing ones. The unique index on (day_date, slot) would reject a
        // duplicate anyway, but relying on a constraint violation as control flow would make a real
        // bug indistinguishable from ordinary re-entry.
        val existing = db.checkInDao().between(from.toString(), today.toString())
            .map { it.dayDate to it.slot }
            .toSet()

        val rows = planned
            .filterNot { (it.day to it.slot) in existing }
            .map {
                CheckInEntity(
                    id = newId(),
                    userId = LOCAL_USER_ID,
                    dayDate = it.day,
                    slot = it.slot,
                    scheduledAt = it.scheduledAt,
                    state = CheckInState.PENDING,
                )
            }

        db.checkInDao().insert(rows)
        return rows.size
    }

    /**
     * Check-ins the user can still answer: unanswered, already due, and inside the grace window.
     *
     * The window is today and yesterday, because backfill runs until the end of the next day (spec
     * §3.2). Anything older is still *answerable* — a late answer keeps the data — but it is no
     * longer outstanding, because it can no longer repair the metric (A1.2), and a banner that never
     * empties is one the user stops reading.
     */
    suspend fun outstandingCheckIns(today: LocalDate): List<CheckIn> =
        db.checkInDao().outstanding(
            from = today.minusDays(1).toString(),
            to = today.toString(),
            now = dayResolver.now().toEpochMilli(),
        ).map { it.toDomain() }

    /**
     * The questions a check-in asks, resolved through `:domain`.
     *
     * Three reads composed by one pure function, which is why it sits here rather than in a
     * ViewModel: item activity, version-in-force and deferral carry-over are rules, and rules belong
     * where they can be tested without a device.
     */
    suspend fun checkInQuestions(day: LocalDate, slot: Slot): List<CheckInEntry> =
        CheckInContent.forCheckIn(
            checkInDay = day,
            slot = slot,
            items = items(),
            versions = versions(),
            deferrals = deferrals(),
        )

    /** Unresolved "not yet" answers, for carry-over into the next morning. */
    suspend fun deferrals(): List<Answer> =
        db.answerDao().withCapture(Capture.PENDING.name).map { it.toDomain() }

    /**
     * Marks a check-in answered. Note it does not touch the answers: a check-in answered "not yet"
     * stays ANSWERED even if the deferral is never resolved, because the user did complete the
     * check-in (scoring-cases A2.2), and a LATE answer must not repair a MISSED one (A1.2). Deriving
     * this state from the answers present would break both.
     */
    suspend fun markCheckInAnswered(day: LocalDate, slot: Slot, at: Instant) {
        val existing = db.checkInDao().onDayInSlot(day.toString(), slot.name) ?: return
        db.checkInDao().upsert(
            existing.copy(state = CheckInState.ANSWERED, answeredAt = at),
        )
    }

    // ---- Answers -----------------------------------------------------------------------------

    /**
     * Answers by the day they *belong to*, never the day they were submitted. The sleep items make
     * those different: they arrive through the next morning's check-in and still belong to the night
     * before (spec 3.1).
     */
    suspend fun answers(from: LocalDate, to: LocalDate): List<Answer> =
        db.answerDao().between(from.toString(), to.toString()).map { it.toDomain() }

    suspend fun answers(day: LocalDate): List<Answer> =
        db.answerDao().onDay(day.toString()).map { it.toDomain() }

    suspend fun answers(itemId: ItemId, from: LocalDate, to: LocalDate): List<Answer> =
        db.answerDao().forItemBetween(itemId.value, from.toString(), to.toString())
            .map { it.toDomain() }

    suspend fun answer(itemId: ItemId, day: LocalDate): Answer? =
        db.answerDao().forItemOnDay(itemId.value, day.toString())?.toDomain()

    /**
     * Records an answer, replacing any existing one for that item and day.
     *
     * Resolving a deferral is this same call with a different capture -- there is no separate
     * method, because "not yet" then answered is one answer that changed, not two. The row keeps its
     * identity across the edit so nothing referencing it dangles.
     */
    /**
     * Records an answer given in the check-in held on [checkInDay] in [slot].
     *
     * It takes the check-in by day and slot rather than by id because the domain `CheckIn`
     * deliberately carries no id — a check-in is identified by *when it was expected*, which is also
     * what makes it unique in the schema.
     *
     * **It deliberately does not mark the check-in answered** — [markCheckInAnswered] does, and only
     * when the user finishes the set.
     *
     * M4 paired the two here, on the reasoning that leaving the second to the caller is how a
     * completed check-in ends up counting as missed. That was right while a single Done wrote every
     * answer at once. M4.5 writes each answer as the user leaves its question, which makes the
     * pairing wrong twice over: the *first* answer would mark the check-in answered, so the Done
     * button would be decorative — and the primary metric would be satisfied by opening a check-in
     * and tapping one chip. Response rate measures showing up, and showing up has to mean reaching
     * the end.
     *
     * The consequence is deliberate: abandoning a check-in halfway keeps every answer given and
     * leaves the check-in honestly outstanding.
     */
    suspend fun recordAnswer(answer: Answer, checkInDay: LocalDate, slot: Slot) {
        val checkIn = db.checkInDao().onDayInSlot(checkInDay.toString(), slot.name)
        recordAnswer(answer, checkIn?.id)
    }

    suspend fun recordAnswer(answer: Answer, viaCheckInId: String? = null) {
        val existing = db.answerDao().forItemOnDay(answer.itemId.value, answer.day.toString())
        val id = existing?.answer?.id ?: newId()
        val row = answer.toEntity(id, viaCheckInId ?: existing?.answer?.submittedViaCheckinId)
        db.answerDao().replace(row, answer.selectionEntities(id))
    }

    // ---- Measured values ---------------------------------------------------------------------

    suspend fun measuredValues(from: LocalDate, to: LocalDate): List<MeasuredValue> =
        db.measuredDao().between(from.toString(), to.toString()).map { it.toDomain() }

    suspend fun measuredValue(itemId: ItemId, day: LocalDate): MeasuredValue? =
        db.measuredDao().forItemOnDay(itemId.value, day.toString())?.toDomain()

    /**
     * Writes a measured value with its per-origin breakdown. The origins are written even when there
     * is only one, because the guard is the *comparison* between days: a second origin appearing is
     * only detectable if the single-origin days recorded theirs too (architecture section 5).
     */
    suspend fun recordMeasuredValue(value: MeasuredValue) {
        val existing = db.measuredDao().forItemOnDay(value.itemId.value, value.day.toString())
        val id = existing?.value?.id ?: newId()
        db.measuredDao().upsertValue(value.toEntity(id))
        db.measuredDao().insertOrigins(value.originEntities(id))
    }
}
