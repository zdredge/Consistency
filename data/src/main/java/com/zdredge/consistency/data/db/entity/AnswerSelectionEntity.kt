package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The chosen options for a single-select or multi-select answer.
 *
 * CASCADE on the answer, RESTRICT on the option, and the asymmetry is meaningful. A selection has no
 * existence apart from its answer, so editing a multi-select replaces these rows wholesale. An
 * option, by contrast, is retired and never deleted -- deleting one would silently rewrite what past
 * answers said.
 */
@Entity(
    tableName = "answer_selections",
    primaryKeys = ["answer_id", "option_id"],
    foreignKeys = [
        ForeignKey(
            entity = AnswerEntity::class,
            parentColumns = ["id"],
            childColumns = ["answer_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SelectOptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["option_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["option_id"])],
)
data class AnswerSelectionEntity(
    @ColumnInfo(name = "answer_id")
    val answerId: String,
    @ColumnInfo(name = "option_id")
    val optionId: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
)
