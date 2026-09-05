package com.zdredge.consistency.data

import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.data.mapper.originEntities
import com.zdredge.consistency.data.mapper.selectionEntities
import com.zdredge.consistency.data.mapper.toDomain
import com.zdredge.consistency.data.mapper.toEntity
import com.zdredge.consistency.domain.model.Answer
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
