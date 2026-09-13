package com.zdredge.consistency.data

import com.zdredge.consistency.data.mapper.toDomain
import com.zdredge.consistency.domain.detail.DayState
import com.zdredge.consistency.domain.detail.ItemDetails
import com.zdredge.consistency.domain.detail.ItemHistory
import com.zdredge.consistency.domain.detail.ItemView
import com.zdredge.consistency.domain.detail.ItemViews
import com.zdredge.consistency.domain.detail.ShadeScale
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.scoring.inForce
import com.zdredge.consistency.domain.time.DayResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate

/**
 * **The view rule, run over the rows that are actually seeded.**
 *
 * `ItemViewTest` in `:domain` pins all sixteen views, but against a hand-written mirror of the library:
 * it proves the rule reproduces the agreed answers, and would keep passing if the real seed changed
 * underneath it. This runs the same rule over `SeedLibrary` itself, so a target moved from daily to
 * weekly — which silently turns water from a shaded calendar into bars — fails here.
 *
 * On the JVM with no device, because it touches no SQLite: these are the seed's rows in memory, mapped
 * to domain types by the same mappers the repository uses.
 */
class SeedLibraryViewsTest {

    /** Day 0, the day the library was really seeded on this phone. */
    private val dayZero = LocalDate.of(2026, 9, 10)

    private val resolver = DayResolver(Clock.systemDefaultZone())

    private val items = SeedLibrary.items(resolver.startOfDay(dayZero)).map { it.toDomain(resolver) }
    private val versions = SeedLibrary.versions(dayZero).map { it.toDomain() }
    private val targets = SeedLibrary.targets(dayZero).map { it.toDomain() }
    private val options = SeedLibrary.options().map { it.toDomain(resolver) }
    private val rollUps = SeedLibrary.rollUpSpecs().map { it.toDomain() }

    /** Spec §5.4's table, as agreed with the user over three rounds of mockups. */
    private val agreed: Map<String, ItemView> = mapOf(
        "bedtime" to ItemView.ClockDots,
        "woke_at" to ItemView.ClockDots,
        "got_up_at" to ItemView.ClockDots,
        "pre_sleep" to ItemView.ActivityRows(OptionId("pre_sleep.scrolled_on_phone")),
        "meals" to ItemView.ShadedCalendar(ShadeScale.targetAnchored(3.0)),
        "vitamins" to ItemView.DayCalendar(weeklyCount = false),
        "water" to ItemView.ShadedCalendar(ShadeScale.targetAnchored(2.0)),
        "worked_out" to ItemView.DayCalendar(weeklyCount = true),
        "stretched" to ItemView.DayCalendar(weeklyCount = true),
        "coffee" to ItemView.DailyBars(weeklyTotals = true),
        "took_time" to ItemView.DayCalendar(weeklyCount = false),
        "mindset" to ItemView.ShadedCalendar(ShadeScale.perValue(ShadeScale.SCALE_RANGE)),
        "saw_friends" to ItemView.WeekSquares,
        "did_something_fun" to ItemView.WeekSquares,
        "invited_someone" to ItemView.WeekSquares,
        "steps" to ItemView.StepBars,
    )

    @Test
    fun everySeededItemGetsTheViewItWasAgreedToHave() {
        assertEquals("the seeded library has 16 items", 16, items.size)

        for (item in items) {
            val version = versions.inForce(item.id, dayZero)
            assertNotNull("${item.id.value} has no version in force on day 0", version)

            assertEquals(
                item.id.value,
                agreed[item.id.value],
                ItemViews.derive(item.kind, version!!, targetsFor(item.id)),
            )
        }
    }

    /**
     * Day 0 as it really was: sixteen items, nothing answered yet, and the chart five weeks long.
     *
     * The thing being checked is that **none of it reads as failure.** Every day before the item
     * existed is inactive and every day since is either still answerable or silent, and silence is
     * excluded rather than missed. A regression here would greet the user with a month of misses on
     * their third day of use.
     */
    @Test
    fun nothingOnAFreshInstallReadsAsAMissedDay() {
        val today = dayZero.plusDays(2)

        for (item in items) {
            val detail = ItemDetails.assemble(
                ItemHistory(
                    item = item,
                    versions = versions.filter { it.itemId == item.id },
                    options = options.filter { it.itemId == item.id },
                    targets = targetsFor(item.id),
                    rollUp = rollUps.firstOrNull { it.itemId == item.id },
                ),
                today,
                resolver,
            )

            // A weekly question is drawn by the week rather than the day, so the counts differ; what
            // must hold for both is that nothing on screen is a judgement, because nothing was asked
            // yet and nothing has run out of time to answer.
            assertEquals(
                "${item.id.value} shows a missed day on a fresh install",
                emptySet<DayState>(),
                detail.days.map { it.state }.toSet() -
                    setOf(DayState.NOT_ACTIVE, DayState.FUTURE, DayState.OPEN, DayState.NOT_ANSWERED),
            )
            assertTrue(
                "${item.id.value} should reach back past the day it was created",
                detail.days.any { it.state == DayState.NOT_ACTIVE },
            )
            assertNull(
                "${item.id.value} has no hit rate before anything is answered",
                detail.figures.daily?.summary?.hitRate,
            )
        }
    }

    private fun targetsFor(itemId: ItemId) = targets.filter { it.itemId == itemId }
}
