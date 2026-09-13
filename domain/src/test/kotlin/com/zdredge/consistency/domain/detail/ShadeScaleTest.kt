package com.zdredge.consistency.domain.detail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The shades on a counted calendar.
 *
 * Meals and water were agreed by eye — meals in four shades, water in three — after a flat five-step
 * ramp made the difference between two meals and three the faintest step on the chart, which is
 * exactly where the answers and the target both sit. These tests pin both agreed tables and then show
 * that one rule produces them, rather than a lookup wearing a rule's clothes.
 */
@DisplayName("Shades on a counted calendar")
class ShadeScaleTest {

    private val meals = ShadeScale.targetAnchored(3.0)
    private val water = ShadeScale.targetAnchored(2.0)

    @Test
    @DisplayName("meals: 0-1, 2, 3, 4+")
    fun mealsTable() {
        assertEquals(4, meals.buckets.size)
        assertEquals(listOf(0, 0, 1, 2, 3, 3), listOf(0.0, 1.0, 2.0, 3.0, 4.0, 9.0).map(meals::bucketOf))
    }

    @Test
    @DisplayName("water: 0-1, 2, 3+ — the low shade folds away when nothing sits below it")
    fun waterTable() {
        // A target of 2 leaves nothing between zero and the step below it, so zero and one share.
        assertEquals(3, water.buckets.size)
        assertEquals(listOf(0, 0, 1, 2, 2), listOf(0.0, 1.0, 2.0, 3.0, 7.0).map(water::bucketOf))
    }

    @Test
    @DisplayName("a target of four gives 0-2, 3, 4, 5+ — the same rule, not a second lookup")
    fun anUnseededTarget() {
        val four = ShadeScale.targetAnchored(4.0)

        assertEquals(4, four.buckets.size)
        assertEquals(listOf(0, 0, 0, 1, 2, 3), listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0).map(four::bucketOf))
    }

    @Test
    @DisplayName("a half is shaded as the whole number below it")
    fun halvesRoundDown() {
        // The quick-pick buttons are whole numbers, but the keypad can still enter 1.5 bottles. Half a
        // bottle short of the target must not be painted in the colour of a day that reached it.
        assertEquals(water.bucketOf(1.0), water.bucketOf(1.5), "1.5 bottles shades as 1")
        assertFalse(water.buckets[water.bucketOf(1.5)].reachesTarget)
        assertEquals(meals.bucketOf(2.0), meals.bucketOf(2.5))
    }

    @Test
    @DisplayName("landing in a target shade always means the target was actually reached")
    fun theInvariant() {
        // The property the whole scale exists to keep. If this ever fails, some day is being painted
        // as a success it did not earn.
        for (target in 1..5) {
            val scale = ShadeScale.targetAnchored(target.toDouble())
            for (tenths in 0..80) {
                val value = tenths / 10.0
                val reaches = scale.buckets[scale.bucketOf(value)].reachesTarget
                assertEquals(value >= target, reaches, "value $value against target $target")
            }
        }
    }

    @Test
    @DisplayName("a target of one still has somewhere to put zero")
    fun theSmallestTarget() {
        val one = ShadeScale.targetAnchored(1.0)

        assertEquals(0, one.bucketOf(0.0))
        assertFalse(one.buckets[one.bucketOf(0.0)].reachesTarget)
        assertTrue(one.buckets[one.bucketOf(1.0)].reachesTarget)
    }

    @Test
    @DisplayName("mindset takes one shade per value, with nothing to reach")
    fun theScaleRamp() {
        val mindset = ShadeScale.perValue(ShadeScale.SCALE_RANGE)

        assertEquals(5, mindset.buckets.size)
        assertEquals(listOf(0, 1, 2, 3, 4), (1..5).map { mindset.bucketOf(it.toDouble()) })
        assertTrue(mindset.buckets.none { it.reachesTarget }, "a mood is never scored")
    }

    @Test
    @DisplayName("values off either end of the scale still land somewhere")
    fun outOfRange() {
        assertEquals(0, meals.bucketOf(-1.0))
        assertEquals(meals.buckets.lastIndex, meals.bucketOf(99.0))
    }
}
