package com.zdredge.consistency.data

import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.data.db.LOCAL_USER_ID
import com.zdredge.consistency.data.db.entity.CheckInEntity
import com.zdredge.consistency.data.db.entity.RolloverRunEntity
import com.zdredge.consistency.data.export.DatabaseSnapshot
import com.zdredge.consistency.data.export.SnapshotSummary
import com.zdredge.consistency.data.health.StepSource
import com.zdredge.consistency.data.health.StepSourceStatus
import com.zdredge.consistency.data.health.UnavailableStepSource
import com.zdredge.consistency.data.mapper.originEntities
import com.zdredge.consistency.data.mapper.selectionEntities
import com.zdredge.consistency.data.mapper.toDomain
import com.zdredge.consistency.data.mapper.toEntity
import com.zdredge.consistency.domain.checkin.AnswerDay
import com.zdredge.consistency.domain.checkin.AnswerRevision
import com.zdredge.consistency.domain.checkin.CheckInContent
import com.zdredge.consistency.domain.checkin.CheckInEntry
import com.zdredge.consistency.domain.checkin.CheckInPlanner
import com.zdredge.consistency.domain.checkin.CheckInTimes
import com.zdredge.consistency.domain.checkin.Grace
import com.zdredge.consistency.domain.checkin.RolloverPlanner
import com.zdredge.consistency.domain.checkin.StepMapper
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.ContainerSize
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.time.DayResolver
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one rollover changed. Returned to the worker, and written to `rollover_runs`. */
data class RolloverOutcome(
    val forDay: LocalDate,
    val checkInsCreated: Int = 0,
    val checkInsMissed: Int = 0,
    val valuesFrozen: Int = 0,
)

/** Stored in `rollover_runs.outcome`. Plain strings: the set is not a domain concept. */
const val ROLLOVER_SUCCEEDED = "SUCCEEDED"
const val ROLLOVER_FAILED = "FAILED"

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
    /**
     * Where steps come from. Defaults to unavailable so a repository built without one -- every JVM
     * and instrumented test -- behaves like a device whose permission was declined, rather than
     * pretending a source exists and reading nothing from it.
     */
    private val stepSource: StepSource = UnavailableStepSource,
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
     * One check-in, or null if it was never expected.
     *
     * Added for M6. An alarm firing knows only a day and a slot — the process that set it is long
     * gone — and it has to ask whether that one check-in is still unanswered before posting. Filtering
     * `outstandingCheckIns` would answer a different question: that list is grace-bounded and returns
     * `MISSED` rows too, so it cannot say whether *this* check-in still wants prompting.
     */
    suspend fun checkIn(day: LocalDate, slot: Slot): CheckIn? =
        db.checkInDao().onDayInSlot(day.toString(), slot.name)?.toDomain()

    /** Records that a notification fired for a check-in. See `CheckInDao.recordNotification`. */
    suspend fun recordNotification(day: LocalDate, slot: Slot) {
        db.checkInDao().recordNotification(day.toString(), slot.name)
    }

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

        // Only the genuinely missing ones, decided and written in one transaction -- see
        // CheckInDao.insertMissing for why the two halves must not be separable now that every
        // process start generates.
        return db.checkInDao().insertMissing(
            from = from.toString(),
            to = today.toString(),
            candidates = planned.map {
                CheckInEntity(
                    id = newId(),
                    userId = LOCAL_USER_ID,
                    dayDate = it.day,
                    slot = it.slot,
                    scheduledAt = it.scheduledAt,
                    state = CheckInState.PENDING,
                )
            },
        )
    }

    /**
     * The check-ins the alarm scheduler plans from, with the day's rows guaranteed to exist.
     *
     * **This exists so that the ordering cannot be got wrong.** Arming an alarm requires a row to
     * arm it from, and until this was one call it was five callers' job to generate first and
     * schedule second. Three of them did not: the rollover created the day's rows and never armed
     * anything, and `ConsistencyApp.onCreate` raced its own worker, arming from rows that did not
     * exist yet. The result was that on a day nobody opened the app, **no prompt was armed at all**
     * -- and the 08:00 morning prompt, whose row is created after it is already too late to arm,
     * had never once fired.
     *
     * M6 fixed the same mistake in `HomeViewModel` by making `refresh()` suspend so the caller could
     * sequence it. That fixed one instance and left the class of bug alive in two more places, which
     * is why this is a precondition here rather than a rule callers are asked to remember.
     *
     * It also moves the ordering somewhere it can be tested. Architecture T3's complaint is that
     * alarm wiring lives in `:app`, which has no tests; this much of it now lives in `:data` and is
     * covered against real SQLite.
     *
     * The window reaches back one day because a check-in stays answerable until the end of the next
     * day (`Grace`), and forward no further than today because [ensureCheckInsExist] generates no
     * further than today -- an alarm horizon past the generation horizon reaches rows that cannot
     * exist. The two move together or not at all.
     */
    suspend fun checkInsForAlarms(today: LocalDate): List<CheckIn> {
        ensureCheckInsExist(today)
        return checkIns(today.minusDays(1), today)
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
            // The same boundary the rollover marks MISSED from. Restating "yesterday" here would
            // let the two drift, and a check-in in the gap would be neither offered nor missed.
            from = Grace.oldestAnswerableDay(today).toString(),
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
            // Spec §3.3: if health permission is declined, steps is **hidden**, not downgraded to
            // manual entry -- manual step entry is permanently out of scope (spec §2). Asked here
            // rather than in the ViewModel so the rule stays in the layer that has tests, and asked
            // every time rather than remembered, because the permission can be revoked in system
            // settings without the app being told.
            measuredAvailable = stepSource.status() == StepSourceStatus.Available,
        )

    /** Unresolved "not yet" answers, for carry-over into the next morning. */
    suspend fun deferrals(): List<Answer> =
        db.answerDao().withCapture(Capture.PENDING.name).map { it.toDomain() }

    // ---- Rollover ----------------------------------------------------------------------------

    /**
     * The 04:00 job, in one call. `RolloverWorker` is the wrapper; this is the work.
     *
     * Three steps, and only the middle one is new to M5:
     *
     * 1. **Generate the check-ins that should exist** — [ensureCheckInsExist], the same function the
     *    home screen calls. One implementation of the response-rate denominator, two callers.
     * 2. **Close what has run out of grace** — the rule is `RolloverPlanner`'s; this writes its
     *    answer. Nothing in this app had ever set `MISSED` before.
     * 3. **Read the steps for the day that just closed** (M7), which is what finally populates
     *    `last_synced_at`.
     * 4. **Freeze measured values past their provisional window** (spec O4).
     *
     * **Steps are read before the freeze is planned, and only for the day that just closed.** Both
     * halves matter. Reading first means the day just read is 24 hours from freezing rather than
     * frozen on the spot; reading *only* that day means every older value keeps the anchor it already
     * has, so it freezes on schedule. Re-reading the whole history each night would reset every
     * anchor and nothing would ever freeze — the rule would look present and never fire, which is the
     * failure mode M5 built `rollover_runs` to make visible.
     *
     * **Only candidates are read**, not the whole history: `PENDING` check-ins and `PROVISIONAL`
     * values are the only rows either rule can act on, and both sets stay small because this job is
     * what drains them.
     *
     * Safe to run twice, and safe to run late. Both rules compare stored state against [today]
     * rather than assuming they run once per day, so a run after the device was off for three days
     * resolves all three at once — see `RolloverPlanner`.
     */
    suspend fun runRollover(today: LocalDate = dayResolver.today()): RolloverOutcome {
        val created = ensureCheckInsExist(today)

        // The day that just closed. At 04:15 on day D that is D-1, and it is the last chance to read
        // it before the 24-hour provisional window starts running against it.
        syncSteps(today.minusDays(1))

        val plan = RolloverPlanner.plan(
            today = today,
            checkIns = db.checkInDao().inState(CheckInState.PENDING.name).map { it.toDomain() },
            measuredValues = db.measuredDao().inState(MeasuredState.PROVISIONAL.name)
                .map { it.toDomain() },
            now = dayResolver.now(),
        )

        plan.checkInsToMiss.forEach {
            db.checkInDao().setState(it.day.toString(), it.slot.name, CheckInState.MISSED.name)
        }
        plan.valuesToFreeze.forEach {
            db.measuredDao()
                .setState(it.itemId.value, it.day.toString(), MeasuredState.FROZEN.name)
        }

        return RolloverOutcome(
            forDay = today,
            checkInsCreated = created,
            checkInsMissed = plan.checkInsToMiss.size,
            valuesFrozen = plan.valuesToFreeze.size,
        ).also { recordRolloverRun(it) }
    }

    private suspend fun recordRolloverRun(outcome: RolloverOutcome) {
        db.rolloverDao().insert(
            RolloverRunEntity(
                id = newId(),
                userId = LOCAL_USER_ID,
                ranAt = dayResolver.now(),
                forDay = outcome.forDay,
                outcome = ROLLOVER_SUCCEEDED,
                checkInsCreated = outcome.checkInsCreated,
                checkInsMissed = outcome.checkInsMissed,
                valuesFrozen = outcome.valuesFrozen,
            ),
        )
    }

    /**
     * Records a run that threw.
     *
     * A table holding only successes cannot tell "it failed every night" from "it never ran", and
     * those need different fixes. Architecture §8 rates this job's silent failure as high-impact and
     * invisible; a failure row is what makes it visible.
     */
    suspend fun recordRolloverFailure(today: LocalDate, error: String) {
        db.rolloverDao().insert(
            RolloverRunEntity(
                id = newId(),
                userId = LOCAL_USER_ID,
                ranAt = dayResolver.now(),
                forDay = today,
                outcome = ROLLOVER_FAILED,
                error = error.take(500),
            ),
        )
    }

    /** When the job last completed. Null means it never has — ordinary on a fresh install. */
    suspend fun lastSuccessfulRollover(): Instant? =
        db.rolloverDao().lastSuccessful(ROLLOVER_SUCCEEDED)?.ranAt

    /**
     * The oldest check-in on record. Null on an empty database.
     *
     * Exists to date the install, so "the rollover has never run" can be told from "the app was
     * installed this morning". A job broken since day one otherwise looks exactly like one that has
     * not been due yet.
     */
    suspend fun earliestCheckInDay(): LocalDate? =
        db.checkInDao().earliestDay()?.let(LocalDate::parse)

    /** Recent runs, failures included, newest first. */
    suspend fun recentRolloverRuns(limit: Int = 20): List<RolloverRunEntity> =
        db.rolloverDao().recent(limit)

    /**
     * Marks a check-in answered **only if something was actually recorded for it**, returning whether
     * it did.
     *
     * Reaching the end of a set used to be enough on its own. It is not: response rate is the primary
     * metric and it counts check-ins in this state, so a check-in opened and closed without a single
     * answer was inflating the one number the product exists to report. That is not hypothetical --
     * it happened on the real device, when a check-in screen was left open and later dismissed, and
     * the row was marked answered with zero answers behind it.
     *
     * **Measured entries do not count.** Steps are read, not given (spec §3.3); a night check-in
     * where the user looked at their step count and answered nothing is not a check-in they answered.
     *
     * **A deferral does count.** "Not yet" is a response the app deliberately offers, and A2.2 is
     * explicit that a check-in answered that way stays ANSWERED even if the deferral is never
     * resolved. This asks whether the user responded, not whether the data is complete.
     */
    suspend fun markCheckInAnsweredIfAnswered(day: LocalDate, slot: Slot, at: Instant): Boolean {
        val responded = checkInQuestions(day, slot)
            .filterNot { it.readOnly }
            .any { entry ->
                val answersDay = entry.carriedOverFrom ?: AnswerDay.forCheckIn(day, slot)
                answer(entry.item.id, answersDay) != null
            }

        if (responded) markCheckInAnswered(day, slot, at)
        return responded
    }

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

        // Preserves how and when the answer was FIRST given, and decides whether this counts as an
        // edit. Without it the replace below restamps `submitted_at` and re-resolves `capture`,
        // which is the silent overwrite spec §3.2 forbids. The rule and its deferral exceptions live
        // in `:domain` with tests -- it is not something to re-derive here.
        val resolved = AnswerRevision.resolve(
            existing = existing?.toDomain(),
            incoming = answer,
            // Corrections made while giving a check-in are part of that answering. Null on either
            // side means we cannot claim they are the same sitting, so it is treated as an edit.
            sameCheckIn = viaCheckInId != null &&
                viaCheckInId == existing?.answer?.submittedViaCheckinId,
            now = dayResolver.now(),
        )

        val row = resolved.toEntity(id, viaCheckInId ?: existing?.answer?.submittedViaCheckinId)
        db.answerDao().replace(row, resolved.selectionEntities(id))
    }

    /**
     * Removes the answer for an item and day, if there is one.
     *
     * This is what "the user cleared it" means at the storage boundary, and it is a delete rather
     * than a blanking on purpose — see [com.zdredge.consistency.data.db.dao.AnswerDao.deleteForItemOnDay].
     * Recorded as defect 2 of M4.5: before this there was no delete path at all, so clearing an
     * answer left the old row in place, the screen reported it gone, and reopening showed it back.
     */
    suspend fun deleteAnswer(itemId: ItemId, day: LocalDate) {
        db.answerDao().deleteForItemOnDay(itemId.value, day.toString())
    }

    // ---- Export ------------------------------------------------------------------------------

    /**
     * Writes a complete copy of the database to [out]. The caller owns and closes [out].
     *
     * Routed through the repository because `AppContainer` deliberately does not expose the database
     * -- `:app` cannot reach a DAO, and it should not be able to reach the file either. The caller
     * supplies the path via `context.getDatabasePath(DATABASE_NAME)`, which is public for exactly
     * this.
     *
     * On `Dispatchers.IO` explicitly: unlike every other method here this is file I/O rather than a
     * Room query, so it does not get Room's dispatcher for free.
     */
    suspend fun writeSnapshotTo(databaseFile: File, out: OutputStream): SnapshotSummary =
        withContext(Dispatchers.IO) { DatabaseSnapshot.writeTo(db, databaseFile, out) }

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
    /**
     * Reads [day]'s steps and records them, returning what was written or null if there was nothing.
     *
     * **One entry point for both triggers**, so they cannot drift: the 04:15 rollover calls it for
     * the day that just closed, and opening the night check-in calls it for today. Spec §3.3 wants a
     * current number in the moment; O4 wants the day re-readable for 24 hours after its last read.
     * Those are the same operation at different times, and writing it twice is how they would come
     * to disagree.
     *
     * Re-reading a day deliberately resets its `lastSyncedAt`, which restarts the O4 window. That is
     * the rule working, not a bug: a value re-read late is young again, which is what lets a late
     * sync correct a day instead of being locked out by a freeze.
     */
    suspend fun syncSteps(day: LocalDate): MeasuredValue? {
        if (stepSource.status() != StepSourceStatus.Available) return null

        val value = StepMapper.map(
            itemId = ItemId(SeedLibrary.STEPS),
            day = day,
            origins = stepSource.readDay(day),
            now = dayResolver.now(),
        ) ?: return null

        recordMeasuredValue(value)
        return value
    }

    suspend fun recordMeasuredValue(value: MeasuredValue) {
        val existing = db.measuredDao().forItemOnDay(value.itemId.value, value.day.toString())
        val id = existing?.value?.id ?: newId()
        db.measuredDao().upsertValue(value.toEntity(id))
        db.measuredDao().replaceOrigins(id, value.originEntities(id))
    }
}
