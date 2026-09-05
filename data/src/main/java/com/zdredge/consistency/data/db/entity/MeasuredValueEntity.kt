package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zdredge.consistency.domain.model.MeasuredState
import java.time.Instant
import java.time.LocalDate

/**
 * A value read from Health Connect rather than asked (spec 3.3). Steps is the only one in v1. It
 * carries targets and is scored like any other goal.
 *
 * [state] is provisional for 24 hours after the 04:00 read and then frozen (spec O4). That is
 * unrelated to an answer's `capture`, which describes how a *person* recorded something; a measured
 * value has no capture state and never will.
 */
@Entity(
    tableName = "measured_values",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["item_id", "day_date"], unique = true),
        Index(value = ["day_date"]),
    ],
)
data class MeasuredValueEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "day_date")
    val dayDate: LocalDate,
    @ColumnInfo(name = "value_number")
    val valueNumber: Double,
    @ColumnInfo(name = "state")
    val state: MeasuredState,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Instant? = null,
)
