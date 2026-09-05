package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.Period
import java.time.LocalDate

/**
 * A target in force from [effectiveFrom] onward (spec constraint 2). Raising a target never
 * re-scores closed periods, which is why the effective date is stored per row rather than the
 * current value being overwritten in place.
 *
 * Rows key on (item, period, effective_from). One item may hold a daily *and* a weekly target at
 * once -- steps at 10,000 a day and 70,000 a week -- and they are scored independently and never
 * collapsed (spec 3.4, scoring-cases 7.1-7.2). The unique index enforces that two targets cannot
 * take effect for the same item and period on the same day, which would make resolution ambiguous.
 *
 * [direction] travels with the value because a bare number cannot express "at most two"
 * (spec constraint 3). [optionId] is set only for MUST_INCLUDE / MUST_NOT_INCLUDE.
 *
 * Schema note: this is one of the three ask-first guarded tables (docs/CLAUDE.md). It realises
 * architecture section 5 exactly and changes nothing.
 */
@Entity(
    tableName = "targets",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SelectOptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["option_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["item_id"]),
        Index(value = ["option_id"]),
        Index(value = ["item_id", "period", "effective_from"], unique = true),
    ],
)
data class TargetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "period")
    val period: Period,
    @ColumnInfo(name = "direction")
    val direction: Direction,
    @ColumnInfo(name = "value_number")
    val valueNumber: Double? = null,
    @ColumnInfo(name = "option_id")
    val optionId: String? = null,
    @ColumnInfo(name = "effective_from")
    val effectiveFrom: LocalDate,
)
