package com.zdredge.consistency.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.zdredge.consistency.data.db.entity.CheckInEntity
import com.zdredge.consistency.domain.model.CheckInState

/**
 * Expected check-ins -- the denominator of the primary metric.
 *
 * Ranges are inclusive at both ends and compare `day_date` as text. That is correct rather than
 * lucky: dates are stored ISO-8601, which sorts lexicographically in the same order it sorts
 * chronologically, so BETWEEN needs no conversion inside the WHERE clause and the index stays usable.
 */
@Dao
interface CheckInDao {

    @Query(
        "SELECT * FROM checkins WHERE day_date BETWEEN :from AND :to ORDER BY day_date, scheduled_at",
    )
    suspend fun between(from: String, to: String): List<CheckInEntity>

    @Query("SELECT * FROM checkins WHERE day_date = :day ORDER BY scheduled_at")
    suspend fun onDay(day: String): List<CheckInEntity>

    @Query("SELECT * FROM checkins WHERE day_date = :day AND slot = :slot")
    suspend fun onDayInSlot(day: String, slot: String): CheckInEntity?

    @Query("SELECT * FROM checkins WHERE state = :state ORDER BY day_date, scheduled_at")
    suspend fun inState(state: String): List<CheckInEntity>

    @Query("SELECT * FROM checkins ORDER BY day_date, scheduled_at")
    suspend fun all(): List<CheckInEntity>

    /**
     * The most recent day any check-in was expected on, or null on a fresh install.
     *
     * This is where check-in generation resumes from. Null means generate from today and no
     * earlier: an install has no history to owe, and fabricating missed days before the app existed
     * would open the record with a failure that never happened.
     */
    @Query("SELECT MAX(day_date) FROM checkins")
    suspend fun latestDay(): String?

    /**
     * The oldest check-in on record, or null on an empty database.
     *
     * Used to tell "the rollover has never run" from "the app was installed this morning". Without
     * it, a job that has failed since day one looks identical to one that has simply not been due
     * yet, and the failure architecture 8 calls invisible stays invisible.
     */
    @Query("SELECT MIN(day_date) FROM checkins")
    suspend fun earliestDay(): String?

    /**
     * Check-ins still answerable: not yet answered, and within the grace window. `:domain` decides
     * which capture that earns; this only decides what is still offered.
     */
    @Query(
        """
        SELECT * FROM checkins
        WHERE day_date BETWEEN :from AND :to
          AND state != :answered
          AND scheduled_at <= :now
        ORDER BY day_date, scheduled_at
        """,
    )
    suspend fun outstanding(
        from: String,
        to: String,
        now: Long,
        answered: String = CheckInState.ANSWERED.name,
    ): List<CheckInEntity>

    /**
     * Moves one check-in's state.
     *
     * **Which check-ins deserve this is not decided here.** `RolloverPlanner` applies the grace rule
     * and hands over a list; this only writes it. A `WHERE day_date < ...` in SQL would be a second
     * home for the rule that decides the primary metric, and the two would drift.
     */
    @Query("UPDATE checkins SET state = :state WHERE day_date = :day AND slot = :slot")
    suspend fun setState(day: String, slot: String, state: String)

    @Insert suspend fun insert(checkIns: List<CheckInEntity>)

    @Upsert suspend fun upsert(checkIn: CheckInEntity)
}
