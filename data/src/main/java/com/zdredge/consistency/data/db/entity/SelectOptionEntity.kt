package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Hangs off the ITEM, not the version (architecture section 5). Adding "meditated" to the pre-sleep
 * list is not a change to the question, so it must not bump the version and re-point every prior
 * answer at an older definition (scoring-cases 6.4).
 *
 * [isNoOpportunity] marks the single option, on the goals that have one, meaning *no opportunity*
 * (spec constraint 17). Keeping it a flag on the option rather than a new answer state is
 * deliberate: a no-opportunity answer is an ordinary answered single-select, so the `answers` schema
 * needs no change and this stays out of the ask-first guarded tables. :domain checks the flag
 * *before* evaluating direction.
 */
@Entity(
    tableName = "select_options",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["item_id"])],
)
data class SelectOptionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "label")
    val label: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int,
    @ColumnInfo(name = "is_no_opportunity")
    val isNoOpportunity: Boolean = false,
    /** Options are retired, never deleted, for the same reason items are. */
    @ColumnInfo(name = "retired_at")
    val retiredAt: Instant? = null,
)
