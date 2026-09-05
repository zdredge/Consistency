package com.zdredge.consistency.data.db.relation

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.zdredge.consistency.data.db.entity.AnswerEntity
import com.zdredge.consistency.data.db.entity.AnswerSelectionEntity
import com.zdredge.consistency.data.db.entity.SelectOptionEntity

/**
 * An answer with the options it selected, loaded in one go.
 *
 * The domain's `Answer` carries its selections inline, so loading them separately would leave the
 * mapper stitching two lists together by id -- work Room already does correctly, and a place where
 * an empty selection set could silently be mistaken for an unanswered multi-select. That distinction
 * is not cosmetic: "answered, nothing selected" and "not answered" score differently
 * (scoring-cases 1.13).
 */
data class AnswerWithSelections(
    @Embedded val answer: AnswerEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = AnswerSelectionEntity::class,
            parentColumn = "answer_id",
            entityColumn = "option_id",
        ),
    )
    val selections: List<SelectOptionEntity>,
)
