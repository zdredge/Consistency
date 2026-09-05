package com.zdredge.consistency.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zdredge.consistency.data.db.entity.ContainerSizeEntity
import com.zdredge.consistency.data.db.entity.RollUpSpecEntity
import com.zdredge.consistency.data.db.entity.TargetEntity

/**
 * Targets, container sizes and roll-up recipes.
 *
 * These queries deliberately return *every* row for an item rather than resolving which one is in
 * force. Effective-from resolution belongs to :domain's `TargetResolver`, already tested against
 * scoring-cases 5.1-5.4; duplicating that logic in SQL would create a second place for it to drift,
 * and the two would then disagree silently rather than fail.
 */
@Dao
interface TargetDao {

    @Query("SELECT * FROM targets ORDER BY item_id, period, effective_from")
    suspend fun allTargets(): List<TargetEntity>

    @Query("SELECT * FROM targets WHERE item_id = :itemId ORDER BY period, effective_from")
    suspend fun targetsFor(itemId: String): List<TargetEntity>

    @Query(
        "SELECT * FROM targets WHERE item_id = :itemId AND period = :period ORDER BY effective_from",
    )
    suspend fun targetsFor(itemId: String, period: String): List<TargetEntity>

    @Query("SELECT * FROM container_sizes ORDER BY item_id, effective_from")
    suspend fun allContainerSizes(): List<ContainerSizeEntity>

    @Query("SELECT * FROM container_sizes WHERE item_id = :itemId ORDER BY effective_from")
    suspend fun containerSizesFor(itemId: String): List<ContainerSizeEntity>

    @Query("SELECT * FROM roll_up_specs ORDER BY item_id")
    suspend fun allRollUpSpecs(): List<RollUpSpecEntity>

    @Query("SELECT * FROM roll_up_specs WHERE item_id = :itemId")
    suspend fun rollUpSpecFor(itemId: String): RollUpSpecEntity?

    @Insert suspend fun insertTargets(targets: List<TargetEntity>)

    @Insert suspend fun insertContainerSizes(sizes: List<ContainerSizeEntity>)

    @Insert suspend fun insertRollUpSpecs(specs: List<RollUpSpecEntity>)
}
