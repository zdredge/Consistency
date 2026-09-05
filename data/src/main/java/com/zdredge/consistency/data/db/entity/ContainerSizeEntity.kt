package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * A counted container's size (spec 3.3, constraint 2). Entering "2" for water records the count and
 * resolves to 80 oz without 80 being stored anywhere -- switching to a different bottle later must
 * not rewrite history (scoring-cases 5.4).
 *
 * That is the whole reason this is a table with an effective date rather than a column on the item.
 */
@Entity(
    tableName = "container_sizes",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["item_id"]),
        Index(value = ["item_id", "effective_from"], unique = true),
    ],
)
data class ContainerSizeEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "size_number")
    val sizeNumber: Double,
    @ColumnInfo(name = "unit_label")
    val unitLabel: String,
    @ColumnInfo(name = "effective_from")
    val effectiveFrom: LocalDate,
)
