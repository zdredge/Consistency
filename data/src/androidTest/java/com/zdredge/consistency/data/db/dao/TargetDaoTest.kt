package com.zdredge.consistency.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.data.db.LOCAL_USER_ID
import com.zdredge.consistency.data.db.entity.RollUpSpecEntity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TargetDaoTest : DaoTestBase() {

    private val targets get() = db.targetDao()

    @Before
    fun seedItems() = dao {
        db.itemDao().insertItems(
            listOf(
                Rows.item("steps"),
                Rows.item("water"),
                Rows.item("worked_out"),
                Rows.item("workouts_this_week"),
            ),
        )
    }

    /**
     * Spec 3.4: one item may hold a daily *and* a weekly target at once -- steps at 10,000 a day and
     * 70,000 a week -- scored independently and never collapsed. Both must therefore come back, and
     * be separable by period.
     */
    @Test
    fun anItemMayHoldADailyAndAWeeklyTargetAtOnce() = dao {
        targets.insertTargets(
            listOf(
                Rows.target("steps-day", "steps", period = Period.DAY, valueNumber = 10_000.0),
                Rows.target("steps-week", "steps", period = Period.WEEK, valueNumber = 70_000.0),
            ),
        )

        assertEquals(2, targets.targetsFor("steps").size)
        assertEquals(
            listOf(10_000.0),
            targets.targetsFor("steps", Period.DAY.name).map { it.valueNumber },
        )
        assertEquals(
            listOf(70_000.0),
            targets.targetsFor("steps", Period.WEEK.name).map { it.valueNumber },
        )
    }

    /**
     * Every target for an item comes back, oldest first, rather than the DAO picking one. Which is
     * in force on a date is `TargetResolver`'s job, already proven against scoring-cases 5.1-5.3;
     * resolving it in SQL too would give the rule two homes that could drift apart silently.
     */
    @Test
    fun supersededTargetsAreReturnedAlongsideCurrentOnesOldestFirst() = dao {
        targets.insertTargets(
            listOf(
                Rows.target("water-aug", "water", valueNumber = 2.0, effectiveFrom = "2026-08-01"),
                Rows.target("water-sep", "water", valueNumber = 3.0, effectiveFrom = "2026-09-01"),
            ),
        )

        assertEquals(listOf(2.0, 3.0), targets.targetsFor("water").map { it.valueNumber })
    }

    /** The direction travels with the value: a bare number cannot express "at most" (constraint 3). */
    @Test
    fun aTargetCarriesItsDirection() = dao {
        targets.insertTargets(
            listOf(
                Rows.target(
                    "coffee-week",
                    "worked_out",
                    period = Period.WEEK,
                    direction = Direction.AT_MOST,
                    valueNumber = 14.0,
                ),
            ),
        )

        assertEquals(Direction.AT_MOST, targets.targetsFor("worked_out").single().direction)
    }

    /**
     * Container size is a table with an effective date, not a column on the item, so switching
     * bottles does not re-score history (scoring-cases 5.4).
     */
    @Test
    fun containerSizesAreKeptPerEffectiveDate() = dao {
        targets.insertContainerSizes(
            listOf(
                Rows.containerSize("water-40oz", "water", size = 40.0, effectiveFrom = "2026-08-01"),
                Rows.containerSize("water-32oz", "water", size = 32.0, effectiveFrom = "2026-09-01"),
            ),
        )

        assertEquals(listOf(40.0, 32.0), targets.containerSizesFor("water").map { it.sizeNumber })
    }

    /**
     * Roll-ups are explicit, not inferred (spec 3.4): the derived item names its source and its
     * aggregation. Only the recipe is stored -- the weekly total itself is computed on read.
     */
    @Test
    fun aRollUpSpecNamesItsSourceItemAndAggregation() = dao {
        targets.insertRollUpSpecs(
            listOf(
                RollUpSpecEntity(
                    id = "workouts-week",
                    userId = LOCAL_USER_ID,
                    itemId = "workouts_this_week",
                    sourceItemId = "worked_out",
                    aggregation = RollUpAggregation.COUNT_OF_YES,
                ),
            ),
        )

        val spec = targets.rollUpSpecFor("workouts_this_week")!!
        assertEquals("worked_out", spec.sourceItemId)
        assertEquals(RollUpAggregation.COUNT_OF_YES, spec.aggregation)
    }
}
