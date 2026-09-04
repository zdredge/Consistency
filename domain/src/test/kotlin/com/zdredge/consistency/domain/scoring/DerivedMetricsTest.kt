package com.zdredge.consistency.domain.scoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalTime

/**
 * docs/scoring-cases.md section 8. Spec constraint 13: the two cross-item metrics are **hardcoded**
 * to the three sleep/wake time items and do not generalise to user-created questions. That
 * special-casing is deliberate, not technical debt -- generalising it would need a type system,
 * validation, retired-source handling and a UI, for a feature with two known uses.
 *
 * Never persisted; computed on read (architecture section 5).
 */
@DisplayName("Derived sleep metrics - scoring-cases section 8")
class DerivedMetricsTest {

    private fun at(h: Int, m: Int) = LocalTime.of(h, m)

    @Test
    @DisplayName("8.1 - bedtime 23:30, woke 08:30, got up 08:52 gives 9h 00m sleep and 22m lingering")
    fun theWorkedExample() {
        val metrics = DerivedMetrics.sleep(
            bedtime = at(23, 30),
            wokeAt = at(8, 30),
            gotUpAt = at(8, 52),
        )
        assertEquals(Duration.ofHours(9), metrics.sleepDuration)
        assertEquals(Duration.ofMinutes(22), metrics.lingering)
    }

    @Test
    @DisplayName("8.2 - a 01:30 bedtime with waking at 09:00 gives 7h 30m")
    fun aLateBedtime() {
        // 01:30 belongs to the previous day under the 04:00 rule, so no wrap is needed here: the
        // clock simply runs forward from 01:30 to 09:00.
        val metrics = DerivedMetrics.sleep(bedtime = at(1, 30), wokeAt = at(9, 0), gotUpAt = null)
        assertEquals(Duration.ofMinutes(450), metrics.sleepDuration)
    }

    @Test
    @DisplayName("8.3 - waking earlier on the clock than bedtime wraps forward, never negative")
    fun crossingMidnightWraps() {
        val metrics = DerivedMetrics.sleep(bedtime = at(23, 30), wokeAt = at(8, 30), gotUpAt = null)
        assertEquals(Duration.ofHours(9), metrics.sleepDuration)
        assertTrue(metrics.sleepDuration!! > Duration.ZERO, "the naive subtraction would be -15h")
    }

    @Test
    @DisplayName("8.4 - a missing got-up leaves lingering UNAVAILABLE, not zero")
    fun missingEndpointsAreUnavailable() {
        // Zero would claim the user got straight up, which is a different fact from not knowing.
        val metrics = DerivedMetrics.sleep(bedtime = at(23, 30), wokeAt = at(8, 30), gotUpAt = null)
        assertNull(metrics.lingering)
        assertEquals(Duration.ofHours(9), metrics.sleepDuration, "sleep needs only its own endpoints")
    }

    @Test
    @DisplayName("8.4 - sleep duration is unavailable if either of ITS endpoints is missing")
    fun sleepNeedsBothOfItsOwnEndpoints() {
        assertNull(DerivedMetrics.sleep(null, at(8, 30), at(8, 52)).sleepDuration)
        assertNull(DerivedMetrics.sleep(at(23, 30), null, at(8, 52)).sleepDuration)
        // With no waking time there is no lingering either, since it is measured from waking.
        assertNull(DerivedMetrics.sleep(at(23, 30), null, at(8, 52)).lingering)
    }

    @Test
    @DisplayName("getting up the moment you wake is zero lingering, which is not the same as unknown")
    fun zeroLingeringIsARealAnswer() {
        val metrics = DerivedMetrics.sleep(at(23, 30), at(8, 30), at(8, 30))
        assertEquals(Duration.ZERO, metrics.lingering)
    }

    @Test
    @DisplayName("lingering wraps too, for someone who wakes before midnight and rises after")
    fun lingeringWrapsAsWell() {
        val metrics = DerivedMetrics.sleep(at(20, 0), at(23, 50), at(0, 10))
        assertEquals(Duration.ofMinutes(20), metrics.lingering)
    }
}
