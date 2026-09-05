package com.zdredge.consistency.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class CheckInAndMeasuredDaoTest : DaoTestBase() {

    private val checkIns get() = db.checkInDao()
    private val measured get() = db.measuredDao()

    /** The response-rate denominator: every check-in that was expected in the window. */
    @Test
    fun aRangeReturnsEveryExpectedCheckInInIt() = dao {
        checkIns.insert(
            listOf(
                Rows.checkIn("n24", "2026-08-24", Slot.NIGHT),
                Rows.checkIn("n25", "2026-08-25", Slot.NIGHT),
                Rows.checkIn("m25", "2026-08-25", Slot.MORNING),
                Rows.checkIn("n26", "2026-08-26", Slot.NIGHT),
            ),
        )

        val found = checkIns.between("2026-08-25", "2026-08-25")

        assertEquals(setOf("n25", "m25"), found.map { it.id }.toSet())
    }

    /**
     * A missed check-in is a row in a state, not an absent row. Without it the denominator shrinks
     * and skipping a day would silently improve the primary metric instead of damaging it.
     */
    @Test
    fun missedCheckInsAreRowsAndAreFindableByState() = dao {
        checkIns.insert(
            listOf(
                Rows.checkIn("n25", "2026-08-25", state = CheckInState.ANSWERED),
                Rows.checkIn("n26", "2026-08-26", state = CheckInState.MISSED),
            ),
        )

        assertEquals(listOf("n26"), checkIns.inState(CheckInState.MISSED.name).map { it.id })
        assertEquals(2, checkIns.all().size)
    }

    @Test
    fun aCheckInIsAddressableByItsDayAndSlot() = dao {
        checkIns.insert(
            listOf(
                Rows.checkIn("n25", "2026-08-25", Slot.NIGHT),
                Rows.checkIn("m25", "2026-08-25", Slot.MORNING),
            ),
        )

        assertEquals("m25", checkIns.onDayInSlot("2026-08-25", Slot.MORNING.name)?.id)
        assertEquals("n25", checkIns.onDayInSlot("2026-08-25", Slot.NIGHT.name)?.id)
    }

    /**
     * **The origin-grouping guard, as a query.** Two origins for one day must come back as two rows
     * so the caller can see a second source appeared. Summing them here would produce a single
     * plausible inflated number and, against a 14-day window, read as improvement rather than a bug
     * (architecture section 5, section 8 risk).
     */
    @Test
    fun aDayWithTwoStepOriginsReturnsBothRatherThanTheirSum() = dao {
        db.itemDao().insertItems(listOf(Rows.item("steps", kind = ItemKind.MEASURED)))
        measured.insertValue(Rows.measured("steps-25", "steps", "2026-08-25", value = 11_240.0))
        measured.insertOrigins(
            listOf(
                Rows.origin("steps-25", "com.android.healthconnect.phone.jf9fc", 11_240.0),
                Rows.origin("steps-25", "com.samsung.health", 9_980.0),
            ),
        )

        val found = measured.forItemOnDay("steps", "2026-08-25")!!

        assertEquals(2, found.origins.size)
        assertTrue(
            "origins must stay separable, not summed",
            found.origins.map { it.valueNumber }.toSet() == setOf(11_240.0, 9_980.0),
        )
    }

    @Test
    fun theOrdinarySingleOriginDayCarriesExactlyOneOrigin() = dao {
        db.itemDao().insertItems(listOf(Rows.item("steps", kind = ItemKind.MEASURED)))
        measured.insertValue(Rows.measured("steps-25", "steps", "2026-08-25", value = 11_240.0))
        measured.insertOrigins(
            listOf(Rows.origin("steps-25", "com.android.healthconnect.phone.jf9fc", 11_240.0)),
        )

        assertEquals(1, measured.forItemOnDay("steps", "2026-08-25")!!.origins.size)
    }

    /** Spec O4: provisional for 24 hours after the read, then frozen. This is the rollover's list. */
    @Test
    fun provisionalValuesAreSeparableFromFrozenOnes() = dao {
        db.itemDao().insertItems(listOf(Rows.item("steps", kind = ItemKind.MEASURED)))
        measured.insertValue(
            Rows.measured("steps-24", "steps", "2026-08-24", 9_000.0, MeasuredState.FROZEN),
        )
        measured.insertValue(
            Rows.measured("steps-25", "steps", "2026-08-25", 11_240.0, MeasuredState.PROVISIONAL),
        )

        val provisional = measured.inState(MeasuredState.PROVISIONAL.name)

        assertEquals(listOf("steps-25"), provisional.map { it.value.id })
        assertNotNull(measured.forItemOnDay("steps", "2026-08-24"))
    }
}
