package com.zdredge.consistency.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.zdredge.consistency.data.db.entity.ItemEntity
import com.zdredge.consistency.data.db.entity.ItemVersionEntity
import com.zdredge.consistency.data.db.entity.SelectOptionEntity

/** Item identity, versions and options -- the definition side of the model. */
@Dao
interface ItemDao {

    @Query("SELECT * FROM items ORDER BY id")
    suspend fun allItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :itemId")
    suspend fun item(itemId: String): ItemEntity?

    /**
     * Retired items are included. A retired item stays visible for the periods in which it was
     * active (spec 3.3), so filtering them out here would erase history rather than tidy a list;
     * whether an item was active on a date is :domain's `ItemLifecycle` question, not a WHERE clause.
     */
    @Query("SELECT * FROM items WHERE kind = :kind ORDER BY id")
    suspend fun itemsOfKind(kind: String): List<ItemEntity>

    @Query("SELECT * FROM item_versions ORDER BY item_id, version_no")
    suspend fun allVersions(): List<ItemVersionEntity>

    @Query("SELECT * FROM item_versions WHERE item_id = :itemId ORDER BY version_no")
    suspend fun versionsFor(itemId: String): List<ItemVersionEntity>

    /**
     * The version in force on a date: the latest one that had taken effect by then. Same
     * effective-from shape as target resolution, and for the same reason -- rewording a question
     * must not retroactively change what an older answer meant (spec constraint 6).
     */
    @Query(
        """
        SELECT * FROM item_versions
        WHERE item_id = :itemId AND effective_from <= :on
        ORDER BY effective_from DESC, version_no DESC
        LIMIT 1
        """,
    )
    suspend fun versionInForce(itemId: String, on: String): ItemVersionEntity?

    /**
     * All options, retired included, because a past answer may reference one and rendering that
     * answer must not turn into a blank.
     */
    @Query("SELECT * FROM select_options WHERE item_id = :itemId ORDER BY ordinal")
    suspend fun optionsFor(itemId: String): List<SelectOptionEntity>

    /** Only the options still offered. This is the check-in screen's list, not history's. */
    @Query(
        "SELECT * FROM select_options WHERE item_id = :itemId AND retired_at IS NULL ORDER BY ordinal",
    )
    suspend fun activeOptionsFor(itemId: String): List<SelectOptionEntity>

    @Query("SELECT * FROM select_options ORDER BY item_id, ordinal")
    suspend fun allOptions(): List<SelectOptionEntity>

    @Insert suspend fun insertItems(items: List<ItemEntity>)

    @Insert suspend fun insertVersions(versions: List<ItemVersionEntity>)

    @Insert suspend fun insertOptions(options: List<SelectOptionEntity>)

    @Upsert suspend fun upsertItem(item: ItemEntity)

    @Upsert suspend fun upsertVersion(version: ItemVersionEntity)
}
