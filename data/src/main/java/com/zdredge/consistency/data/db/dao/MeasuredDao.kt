package com.zdredge.consistency.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.zdredge.consistency.data.db.entity.MeasuredOriginEntity
import com.zdredge.consistency.data.db.entity.MeasuredValueEntity
import com.zdredge.consistency.data.db.relation.MeasuredValueWithOrigins

/**
 * Measured values, always loaded with their origins -- see [MeasuredValueWithOrigins] for why the
 * two are inseparable.
 */
@Dao
interface MeasuredDao {

    @Transaction
    @Query(
        """
        SELECT * FROM measured_values
        WHERE day_date BETWEEN :from AND :to
        ORDER BY day_date, item_id
        """,
    )
    suspend fun between(from: String, to: String): List<MeasuredValueWithOrigins>

    @Transaction
    @Query("SELECT * FROM measured_values WHERE item_id = :itemId AND day_date = :day")
    suspend fun forItemOnDay(itemId: String, day: String): MeasuredValueWithOrigins?

    /** Provisional values freeze 24 hours after the read (spec O4); this is the rollover's list. */
    @Transaction
    @Query("SELECT * FROM measured_values WHERE state = :state ORDER BY day_date")
    suspend fun inState(state: String): List<MeasuredValueWithOrigins>

    @Transaction
    @Query("SELECT * FROM measured_values ORDER BY day_date, item_id")
    suspend fun all(): List<MeasuredValueWithOrigins>

    /**
     * Freezes or unfreezes one day's measured value. The O4 window is `RolloverPlanner`'s to apply;
     * this writes the result.
     */
    @Query("UPDATE measured_values SET state = :state WHERE item_id = :itemId AND day_date = :day")
    suspend fun setState(itemId: String, day: String, state: String)

    @Insert suspend fun insertValue(value: MeasuredValueEntity)

    @Insert suspend fun insertOrigins(origins: List<MeasuredOriginEntity>)

    @Upsert suspend fun upsertValue(value: MeasuredValueEntity)

    @Query("DELETE FROM measured_origins WHERE measured_value_id = :measuredValueId")
    suspend fun clearOrigins(measuredValueId: String)

    /**
     * Replaces one value's origins wholesale.
     *
     * **A plain insert cannot be used here, and that is not a style preference.** The primary key is
     * `(measured_value_id, origin_package)`, so re-recording a day whose origin has not changed
     * aborts -- and M7 re-reads the same day repeatedly, because O4 keeps a value provisional for 24
     * hours precisely so a late sync can correct it. The second read of any day would throw.
     *
     * Delete-then-insert rather than an upsert, because an origin can *disappear* between reads. An
     * upsert would leave the stale row behind, and a day that had quietly stopped being conflicted
     * would go on looking conflicted for ever.
     */
    @Transaction
    suspend fun replaceOrigins(measuredValueId: String, origins: List<MeasuredOriginEntity>) {
        clearOrigins(measuredValueId)
        if (origins.isNotEmpty()) insertOrigins(origins)
    }
}
