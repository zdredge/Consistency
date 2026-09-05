package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate

/**
 * The mutable definition of an item. Answers reference a *version*, so rewording a question never
 * retroactively changes what an old answer meant (spec constraint 6).
 *
 * [effectiveFrom] is a date rather than a timestamp: which version was in force is a day-level
 * question, the same shape as target resolution.
 *
 * The foreign key is RESTRICT, not CASCADE. Items are versioned and retired, never deleted, so a
 * cascade would be wrong by construction -- it would quietly make deletion possible and take the
 * history with it.
 */
@Entity(
    tableName = "item_versions",
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
        Index(value = ["item_id", "version_no"], unique = true),
    ],
)
data class ItemVersionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "version_no")
    val versionNo: Int,
    @ColumnInfo(name = "prompt")
    val prompt: String,
    @ColumnInfo(name = "answer_type")
    val answerType: AnswerType,
    @ColumnInfo(name = "classification")
    val classification: Classification,
    @ColumnInfo(name = "slot")
    val slot: Slot,
    /** User-defined unit for numeric items, including counted containers (spec 3.3). */
    @ColumnInfo(name = "unit_label")
    val unitLabel: String? = null,
    @ColumnInfo(name = "effective_from")
    val effectiveFrom: LocalDate,
)
