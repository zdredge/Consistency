package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.model.MeasuredState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The origin guard, which is the only part of the step read that can be wrong quietly.
 *
 * Whether a number arrives from Health Connect is obvious the moment you look at the screen. Whether
 * two sources were silently added together is not — it looks like a good day. Architecture §8 calls
 * that risk dormant rather than hypothetical, so it is pinned here rather than left to be noticed.
 */
class StepMapperTest {

    private val steps = ItemId("steps")
    private val day = LocalDate.of(2026, 9, 10)
    private val readAt: Instant = Instant.parse("2026-09-11T08:15:00Z")

    /** The M0 origin: the device-specific synthetic package, not the generic `android`. */
    private val phone = "com.android.healthconnect.phone.jf9fc11088d6938c28480cb1ae667b25e"
    private val watch = "com.samsung.health"

    private fun map(vararg origins: MeasuredOrigin) =
        StepMapper.map(steps, day, origins.toList(), readAt)

    // ---- The ordinary day ----------------------------------------------------------------------

    @Test
    @DisplayName("one origin is taken at its word and is provisional")
    fun oneOriginIsProvisional() {
        val value = map(MeasuredOrigin(phone, 11_240.0))!!

        assertEquals(11_240.0, value.value)
        assertEquals(MeasuredState.PROVISIONAL, value.state)
        assertEquals(listOf(MeasuredOrigin(phone, 11_240.0)), value.origins)
    }

    @Test
    @DisplayName("O4 - the read time is recorded, because it anchors the freeze window")
    fun theReadTimeIsTheAnchor() {
        // Not the day being measured. A value re-read late is young again, which is what lets O4
        // tolerate a late sync without permanently mis-scoring the day.
        assertEquals(readAt, map(MeasuredOrigin(phone, 9_000.0))!!.lastSyncedAt)
    }

    // ---- The origin guard ----------------------------------------------------------------------

    @Test
    @DisplayName("two origins are flagged, never summed")
    fun twoOriginsAreConflictedNotSummed() {
        val value = map(MeasuredOrigin(phone, 11_240.0), MeasuredOrigin(watch, 9_980.0))!!

        // The state is the assertion that matters. If this ever reads PROVISIONAL, a phone and a
        // watch have been added together and 21,220 steps will be scored as a very good day.
        assertEquals(MeasuredState.CONFLICTED, value.state)
    }

    @Test
    @DisplayName("a conflicted day keeps every origin, so it can be investigated")
    fun conflictedKeepsEveryOrigin() {
        val value = map(MeasuredOrigin(phone, 11_240.0), MeasuredOrigin(watch, 9_980.0))!!

        assertEquals(
            listOf(phone to 11_240.0, watch to 9_980.0),
            value.origins.map { it.originPackage to it.value },
        )
    }

    // ---- Absence -------------------------------------------------------------------------------

    @Test
    @DisplayName("no records is no row, because it is not the same as no steps")
    fun noRecordsProducesNoRow() {
        // "Did not walk" and "has not synced" are indistinguishable from here. Writing 0.0 would
        // resolve that in the direction that scores a miss the user cannot have earned.
        assertNull(map())
    }

    @Test
    @DisplayName("an origin reporting nothing does not make the day conflicted")
    fun emptyOriginsAreIgnored() {
        // A source present but silent is not a second opinion. Counting it would flag a perfectly
        // ordinary day and stop it being scored.
        val value = map(MeasuredOrigin(phone, 7_100.0), MeasuredOrigin(watch, 0.0))!!

        assertEquals(MeasuredState.PROVISIONAL, value.state)
        assertEquals(7_100.0, value.value)
    }
}
