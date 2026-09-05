package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zdredge.consistency.domain.model.RollUpAggregation

/**
 * A weekly figure derived from one daily item plus an aggregation: workouts this week
 * (count-of-yes), coffee this week (sum), average mindset (spec 3.4, section 4).
 *
 * **This table is an addition to architecture section 5, agreed during M3 planning.** :domain
 * already carried a `RollUpSpec` type and the spec requires roll-ups, but section 5 listed no table
 * to store them -- a real gap rather than an omission from the reading. It sits outside the three
 * ask-first guarded tables (answers, checkins, targets), and putting it in schema v1 avoids a
 * certain migration for a requirement already written down.
 *
 * Roll-ups are **explicit, not inferred** (spec 3.4): the derived item names its source and its
 * aggregation rather than the app guessing from types. [itemId] is unique because a derived item has
 * exactly one definition; the aggregate itself is never stored (docs/CLAUDE.md: never persist a
 * derived value), only this recipe for computing it.
 */
@Entity(
    tableName = "roll_up_specs",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["item_id"], unique = true),
        Index(value = ["source_item_id"]),
    ],
)
data class RollUpSpecEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    /** The derived item this recipe defines. */
    @ColumnInfo(name = "item_id")
    val itemId: String,
    /** The daily item it aggregates. */
    @ColumnInfo(name = "source_item_id")
    val sourceItemId: String,
    @ColumnInfo(name = "aggregation")
    val aggregation: RollUpAggregation,
)
