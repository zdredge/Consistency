package com.zdredge.consistency.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zdredge.consistency.data.db.entity.RolloverRunEntity

/**
 * The rollover's own record of itself.
 *
 * Insert-only. A run happened or it did not, and rewriting one would be rewriting history about the
 * component whose failures are supposed to be visible.
 */
@Dao
interface RolloverDao {

    @Insert suspend fun insert(run: RolloverRunEntity)

    /**
     * The most recent successful run. Null means the job has never completed — which on a fresh
     * install is ordinary, and on a week-old install is the thing the user needs telling about.
     */
    @Query(
        "SELECT * FROM rollover_runs WHERE outcome = :succeeded ORDER BY ran_at DESC LIMIT 1",
    )
    suspend fun lastSuccessful(succeeded: String = "SUCCEEDED"): RolloverRunEntity?

    /** Recent runs, successes and failures alike, newest first. For diagnosing a silent failure. */
    @Query("SELECT * FROM rollover_runs ORDER BY ran_at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<RolloverRunEntity>
}
