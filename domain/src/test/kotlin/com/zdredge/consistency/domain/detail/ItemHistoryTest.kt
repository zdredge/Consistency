package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.DAY
import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.history
import com.zdredge.consistency.domain.item
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The guards on one item's history.
 *
 * The calculators underneath take flat lists and trust them — `RollUpCalculator.weekly` does not filter
 * by item — so a stray row would be counted into the wrong week as a plausible wrong number rather than
 * a crash. That check is worth writing once, here, instead of at every call site.
 */
@DisplayName("Gathering one item's history")
class ItemHistoryTest {

    private val meals = item("meals")
    private val mealsV1 = version("meals", AnswerType.NUMBER)

    @Test
    @DisplayName("a row belonging to another item is refused rather than quietly counted")
    fun strayRowsAreRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            history(item = meals, version = mealsV1, answers = listOf(answer("water", number = 2.0)))
        }

        assertTrue(failure.message!!.contains("water"), failure.message)
    }

    @Test
    @DisplayName("a target for another item is refused too -- it would be resolved as this one's")
    fun strayTargetsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            history(
                item = meals,
                version = mealsV1,
                targets = listOf(target("water", Direction.AT_LEAST, value = 2.0)),
            )
        }
    }

    @Test
    @DisplayName("two answers on one day are refused: the week's figure would leave the calendar behind")
    fun oneAnswerADay() {
        assertThrows(IllegalArgumentException::class.java) {
            history(
                item = meals,
                version = mealsV1,
                answers = listOf(answer("meals", number = 2.0), answer("meals", number = 3.0)),
            )
        }
    }

    @Test
    @DisplayName("constraint 6 - the version in force is by date, never simply the latest")
    fun theVersionInForce() {
        val v1 = version("meals", AnswerType.NUMBER, from = LocalDate.of(2026, 1, 1))
        val v2 = version("meals", AnswerType.NUMBER, from = LocalDate.of(2026, 6, 1))
            .copy(versionNo = 2, prompt = "How many meals did you eat today?")

        val history = ItemHistory(item = meals, versions = listOf(v1, v2))

        assertEquals(v1, history.versionOn(LocalDate.of(2026, 5, 31)))
        assertEquals(v2, history.versionOn(LocalDate.of(2026, 6, 1)))
        assertEquals(v2, history.latestVersion)
        assertNull(history.versionOn(LocalDate.of(2025, 12, 31)), "before the item had a definition")
    }

    @Test
    @DisplayName("a deferral never reaches the sleep trend, whatever its row happens to hold")
    fun deferralsAreNotTimes() {
        val history = history(
            item = item("bedtime"),
            version = version("bedtime", AnswerType.TIME),
            answers = listOf(
                answer("bedtime", day = DAY, time = LocalTime.of(23, 0)),
                answer("bedtime", day = DAY.plusDays(1), time = LocalTime.of(1, 0), capture = Capture.PENDING),
            ),
        )

        assertEquals(setOf(DAY), history.timesByNight.keys)
    }

    @Test
    @DisplayName("the periods an item has targets in are what decide its view")
    fun targetsOnADate() {
        val history = history(
            item = meals,
            version = mealsV1,
            targets = listOf(
                target("meals", Direction.AT_LEAST, value = 3.0, from = LocalDate.of(2026, 1, 1)),
                target("meals", Direction.AT_LEAST, value = 20.0, period = Period.WEEK, from = LocalDate.of(2026, 6, 1)),
            ),
        )

        assertEquals(1, history.targetsOn(LocalDate.of(2026, 5, 1)).size)
        assertEquals(2, history.targetsOn(LocalDate.of(2026, 6, 1)).size)
        assertTrue(history.hasTargetIn(Period.WEEK))
    }
}
