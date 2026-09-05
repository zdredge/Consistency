package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * **This table is the origin-grouping guard** (architecture section 5, section 8 risk). More than
 * one row for a day means a second step source appeared, and the values must not be silently summed.
 *
 * M0 confirmed exactly one origin on the device today -- the synthetic on-device package
 * `com.android.healthconnect.phone.jf9fc...` -- but Samsung Health and Google Health are both
 * already installed and simply are not writing steps. A second origin therefore needs no new
 * hardware, and because the dashboard shows a 14-day window an inflated count would read as
 * improvement rather than as a bug. Storing per-origin values day one is what makes that detectable;
 * it is a few rows, not a reconciliation system.
 *
 * The composite key is (measured_value_id, origin_package): one row per source per day.
 */
@Entity(
    tableName = "measured_origins",
    primaryKeys = ["measured_value_id", "origin_package"],
    foreignKeys = [
        ForeignKey(
            entity = MeasuredValueEntity::class,
            parentColumns = ["id"],
            childColumns = ["measured_value_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["measured_value_id"])],
)
data class MeasuredOriginEntity(
    @ColumnInfo(name = "measured_value_id")
    val measuredValueId: String,
    @ColumnInfo(name = "origin_package")
    val originPackage: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "value_number")
    val valueNumber: Double,
)
