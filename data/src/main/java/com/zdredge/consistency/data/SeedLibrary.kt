package com.zdredge.consistency.data

import com.zdredge.consistency.data.db.LOCAL_USER_ID
import com.zdredge.consistency.data.db.entity.ContainerSizeEntity
import com.zdredge.consistency.data.db.entity.ItemEntity
import com.zdredge.consistency.data.db.entity.ItemVersionEntity
import com.zdredge.consistency.data.db.entity.RollUpSpecEntity
import com.zdredge.consistency.data.db.entity.SelectOptionEntity
import com.zdredge.consistency.data.db.entity.TargetEntity
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.Slot
import java.time.Instant
import java.time.LocalDate

/**
 * The seed library from spec section 4, as rows.
 *
 * Sixteen items in five groups, everything editable and removable afterwards -- this is a starting
 * point, not a fixed set. Ids are stable strings rather than UUIDs so the library is legible in a
 * database dump and re-runnable without duplicating.
 *
 * **Targets here are illustrative and editable** (spec section 4). What is *not* negotiable is each
 * direction describing what would genuinely count as failing, which is the rule three separate
 * product errors have now been traced back to.
 *
 * **On granularity, and the rule M3 added to that list.** Worked out and stretched carry weekly
 * targets only: they are lower bounds on inherently non-daily behaviours, and a daily "must be yes"
 * would read as a ~50% hit rate and land in "going badly" for no real failure. Coffee looks like the
 * same case and is not. It is an *upper* bound, and weekly granularity forgives clustering -- five
 * coffees in one day and one on each of the others sums to ten and passes a weekly cap of fourteen
 * cleanly, which is exactly the day worth seeing. So coffee carries **both** a daily and a weekly
 * cap, scored independently and reported separately (spec 3.4, scoring-cases 7.1-7.2).
 *
 * The general rule: **moving a target to weekly forgives clustering, which is right for a lower
 * bound and wrong for an upper bound.**
 */
object SeedLibrary {

    // Sleep items. Observations, not goals: they carry no targets and exist to be recorded and to
    // feed the two hardcoded derived metrics (spec constraint 13).
    private const val BEDTIME = "bedtime"
    private const val WOKE_AT = "woke_at"
    private const val GOT_UP_AT = "got_up_at"
    private const val PRE_SLEEP = "pre_sleep"

    private const val MEALS = "meals"
    private const val VITAMINS = "vitamins"
    private const val WATER = "water"
    private const val WORKED_OUT = "worked_out"
    private const val STRETCHED = "stretched"
    private const val COFFEE = "coffee"
    private const val TOOK_TIME = "took_time"
    private const val MINDSET = "mindset"

    /**
     * The one measured item (spec §3.3), and the only id outside this file that anything needs.
     *
     * Public because `ConsistencyRepository.syncSteps` has to know which item a Health Connect read
     * belongs to. The alternative -- looking up "whichever item is MEASURED" -- reads as more general
     * but is not: `StepSource` reads `StepsRecord` specifically, so a second measured item would need
     * its own source anyway, and the lookup would silently write step counts into it.
     */
    const val STEPS = "steps"

    private const val SAW_FRIENDS = "saw_friends"
    private const val DID_SOMETHING_FUN = "did_something_fun"
    private const val INVITED_SOMEONE = "invited_someone"

    /** The three weekly social goals and "took time" share this option shape (spec section 4). */
    private val yesNoNoOpportunity = listOf("yes", "no", "no_opportunity")

    /**
     * **Order is spec section 4's listing order, and it is deliberate rather than incidental.**
     * The morning set runs chronologically through the night -- went to bed, what you did before
     * sleeping, woke, got up -- which is the order the questions are answerable in. Ordering by id
     * instead would ask when you got out of bed before asking when you woke.
     */
    fun items(createdAt: Instant): List<ItemEntity> =
        (askedItemIds + weeklyItemIds + STEPS).mapIndexed { ordinal, id ->
            item(id, if (id == STEPS) ItemKind.MEASURED else ItemKind.ASKED, createdAt, ordinal)
        }

    private val askedItemIds = listOf(
        BEDTIME, PRE_SLEEP, WOKE_AT, GOT_UP_AT,
        MEALS, VITAMINS, WATER, WORKED_OUT, STRETCHED, COFFEE, TOOK_TIME, MINDSET,
    )

    private val weeklyItemIds = listOf(SAW_FRIENDS, DID_SOMETHING_FUN, INVITED_SOMEONE)

    fun versions(effectiveFrom: LocalDate): List<ItemVersionEntity> = listOf(
        version(BEDTIME, "What time did you go to bed?", AnswerType.TIME, Slot.MORNING, Classification.OBSERVATION, effectiveFrom),
        version(PRE_SLEEP, "What did you do before bed?", AnswerType.MULTI_SELECT, Slot.MORNING, Classification.GOAL, effectiveFrom),
        version(WOKE_AT, "What time did you wake up?", AnswerType.TIME, Slot.MORNING, Classification.OBSERVATION, effectiveFrom),
        version(GOT_UP_AT, "What time did you get out of bed?", AnswerType.TIME, Slot.MORNING, Classification.OBSERVATION, effectiveFrom),

        version(MEALS, "How many meals did you eat?", AnswerType.NUMBER, Slot.NIGHT, Classification.GOAL, effectiveFrom),
        version(VITAMINS, "Did you take your vitamins?", AnswerType.BOOL, Slot.NIGHT, Classification.GOAL, effectiveFrom),
        version(WATER, "How much water did you drink? (Bottles)", AnswerType.NUMBER, Slot.NIGHT, Classification.GOAL, effectiveFrom, unitLabel = "bottles"),
        version(WORKED_OUT, "Did you work out?", AnswerType.BOOL, Slot.NIGHT, Classification.GOAL, effectiveFrom),
        version(STRETCHED, "Did you stretch?", AnswerType.BOOL, Slot.NIGHT, Classification.GOAL, effectiveFrom),
        version(COFFEE, "How many coffees did you have?", AnswerType.NUMBER, Slot.NIGHT, Classification.GOAL, effectiveFrom),
        version(TOOK_TIME, "Did you take time when time could be taken?", AnswerType.SINGLE_SELECT, Slot.NIGHT, Classification.GOAL, effectiveFrom),
        // Never a goal. Scoring a mood reintroduces exactly the shame this product is designed
        // against; the derived average is the signal, watched rather than targeted (spec section 4).
        version(MINDSET, "How positive was your mindset today? (1 - Very Negative, 5 - Very Positive)", AnswerType.SCALE, Slot.NIGHT, Classification.OBSERVATION, effectiveFrom),

        // Measured: no schedule slot, never asked, read-only in the night check-in (spec 3.3).
        version(STEPS, "Steps", AnswerType.NUMBER, Slot.NONE, Classification.GOAL, effectiveFrom),

        version(SAW_FRIENDS, "Did you see friends this week?", AnswerType.SINGLE_SELECT, Slot.WEEKLY, Classification.GOAL, effectiveFrom),
        version(DID_SOMETHING_FUN, "Did you do something fun this week?", AnswerType.SINGLE_SELECT, Slot.WEEKLY, Classification.GOAL, effectiveFrom),
        version(INVITED_SOMEONE, "Did you invite someone to something this week?", AnswerType.SINGLE_SELECT, Slot.WEEKLY, Classification.GOAL, effectiveFrom),
    )

    /**
     * Option ids are namespaced by item because they share a table and a primary key -- four items
     * carry an option labelled "yes", and unprefixed ids would collide on the first insert.
     */
    fun options(): List<SelectOptionEntity> = listOf(
        option(PRE_SLEEP, "read_a_book", "Read a book", 0),
        option(PRE_SLEEP, "watched_youtube", "Watched YouTube", 1),
        option(PRE_SLEEP, "scrolled_on_phone", "Scrolled on phone", 2),
        option(PRE_SLEEP, "watched_tv", "Watched TV", 3),
    ) + (listOf(TOOK_TIME) + weeklyItemIds).flatMap { itemId ->
        yesNoNoOpportunity.mapIndexed { ordinal, key ->
            option(
                itemId = itemId,
                key = key,
                label = when (key) {
                    "yes" -> "Yes"
                    "no" -> "No"
                    else -> "No opportunity"
                },
                ordinal = ordinal,
                // Spec constraint 17: the neutral answer is a flag on the option, so choosing it
                // excludes the period without costing the check-in or breaking the run.
                isNoOpportunity = key == "no_opportunity",
            )
        }
    }

    fun targets(effectiveFrom: LocalDate): List<TargetEntity> = listOf(
        // At least three, not exactly three. An extra meal is not a failure and must not score as
        // one -- the direction was wrong, not the arithmetic (spec section 4, decided during M2).
        target(MEALS, Period.DAY, Direction.AT_LEAST, 3.0, effectiveFrom),
        target(VITAMINS, Period.DAY, Direction.IS_TRUE, null, effectiveFrom),
        target(WATER, Period.DAY, Direction.AT_LEAST, 2.0, effectiveFrom),

        // Lower bounds on non-daily behaviours: weekly only, assessed against the roll-up.
        target(WORKED_OUT, Period.WEEK, Direction.AT_LEAST, 3.0, effectiveFrom),
        // Six, not seven: the aim is daily, but a target only ever met by a perfect week reads as
        // missed more often than it reads as true. One day of slack keeps it achievable.
        target(STRETCHED, Period.WEEK, Direction.AT_LEAST, 6.0, effectiveFrom),

        // An upper bound, so it needs both. The weekly cap alone would pass a five-coffee day
        // (see the granularity note on this object); the daily cap alone would miss a steady week.
        target(COFFEE, Period.DAY, Direction.AT_MOST, 2.0, effectiveFrom),
        target(COFFEE, Period.WEEK, Direction.AT_MOST, 14.0, effectiveFrom),

        // Must be yes. The no-opportunity option is evaluated before this and excludes the day.
        target(TOOK_TIME, Period.DAY, Direction.MUST_INCLUDE, null, effectiveFrom, "$TOOK_TIME.yes"),

        // Spec 3.4: the goal is "did not scroll", not "read specifically", so YouTube and TV both
        // pass. Expressing several acceptable activities as an absence rule on the unacceptable one
        // is deliberate -- a must-include naming one option would fail an evening of YouTube.
        target(PRE_SLEEP, Period.DAY, Direction.MUST_NOT_INCLUDE, null, effectiveFrom, "$PRE_SLEEP.scrolled_on_phone"),

        // The week is seven times the day deliberately. Spec 3.4 keeps the two independent and
        // reports them separately, so this is a choice rather than arithmetic -- but a weekly target
        // quietly stricter than the daily one would read as a bug the first time a perfect week
        // scored as missed.
        target(STEPS, Period.DAY, Direction.AT_LEAST, 8_000.0, effectiveFrom),
        target(STEPS, Period.WEEK, Direction.AT_LEAST, 56_000.0, effectiveFrom),
    ) + weeklyItemIds.map {
        target(it, Period.WEEK, Direction.MUST_INCLUDE, null, effectiveFrom, "$it.yes")
    }

    /** Water: one bottle is 40 oz, so entering 2 resolves to 80 without 80 being stored. */
    fun containerSizes(effectiveFrom: LocalDate): List<ContainerSizeEntity> = listOf(
        ContainerSizeEntity(
            id = "$WATER.size",
            userId = LOCAL_USER_ID,
            itemId = WATER,
            sizeNumber = 40.0,
            unitLabel = "oz",
            effectiveFrom = effectiveFrom,
        ),
    )

    /**
     * How a daily item folds into a weekly figure. Roll-ups are explicit, not inferred (spec 3.4):
     * nothing guesses that a yes/no item should be counted rather than summed.
     *
     * At v1 the declaring item and its source are the same row, because no derived figure has an
     * identity of its own yet -- "workouts this week" is a *view* of `worked_out`, not a separate
     * question, and it was deliberately removed as an asked item to avoid two contradicting sources
     * of truth (spec section 4 change log). `sourceItemId` stays distinct in the model for when a
     * derived figure does need its own identity; M8 is where that becomes concrete.
     */
    fun rollUpSpecs(): List<RollUpSpecEntity> = listOf(
        rollUp(WORKED_OUT, RollUpAggregation.COUNT_OF_YES),
        rollUp(STRETCHED, RollUpAggregation.COUNT_OF_YES),
        rollUp(COFFEE, RollUpAggregation.SUM),
        // Mindset has no target and never will. The weekly average is the signal (spec section 4).
        rollUp(MINDSET, RollUpAggregation.AVERAGE),
    )

    private fun item(id: String, kind: ItemKind, createdAt: Instant, ordinal: Int) =
        ItemEntity(
            id = id,
            userId = LOCAL_USER_ID,
            kind = kind,
            createdAt = createdAt,
            ordinal = ordinal,
        )

    private fun version(
        itemId: String,
        prompt: String,
        answerType: AnswerType,
        slot: Slot,
        classification: Classification,
        effectiveFrom: LocalDate,
        unitLabel: String? = null,
    ) = ItemVersionEntity(
        id = "$itemId.v1",
        userId = LOCAL_USER_ID,
        itemId = itemId,
        versionNo = 1,
        prompt = prompt,
        answerType = answerType,
        classification = classification,
        slot = slot,
        unitLabel = unitLabel,
        effectiveFrom = effectiveFrom,
    )

    private fun option(
        itemId: String,
        key: String,
        label: String,
        ordinal: Int,
        isNoOpportunity: Boolean = false,
    ) = SelectOptionEntity(
        id = "$itemId.$key",
        userId = LOCAL_USER_ID,
        itemId = itemId,
        label = label,
        ordinal = ordinal,
        isNoOpportunity = isNoOpportunity,
    )

    private fun target(
        itemId: String,
        period: Period,
        direction: Direction,
        valueNumber: Double?,
        effectiveFrom: LocalDate,
        optionId: String? = null,
    ) = TargetEntity(
        id = "$itemId.${period.name.lowercase()}",
        userId = LOCAL_USER_ID,
        itemId = itemId,
        period = period,
        direction = direction,
        valueNumber = valueNumber,
        optionId = optionId,
        effectiveFrom = effectiveFrom,
    )

    private fun rollUp(itemId: String, aggregation: RollUpAggregation) = RollUpSpecEntity(
        id = "$itemId.rollup",
        userId = LOCAL_USER_ID,
        itemId = itemId,
        sourceItemId = itemId,
        aggregation = aggregation,
    )
}
