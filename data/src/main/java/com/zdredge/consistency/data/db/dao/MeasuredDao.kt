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

    @Insert suspend fun insertValue(value: MeasuredValueEntity)

    @Insert suspend fun insertOrigins(origins: List<MeasuredOriginEntity>)

    @Upsert suspend fun upsertValue(value: MeasuredValueEntity)
}
