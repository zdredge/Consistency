package com.zdredge.consistency.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.zdredge.consistency.data.db.entity.AnswerEntity
import com.zdredge.consistency.data.db.entity.AnswerSelectionEntity
import com.zdredge.consistency.data.db.relation.AnswerWithSelections

/**
 * Answers, always loaded with their selections.
 *
 * Every read here returns [AnswerWithSelections] rather than a bare row. A multi-select answer whose
 * selections were never loaded looks exactly like an answer with nothing selected, and those two do
 * not score alike -- silence is excluded, an answered-but-empty multi-select is not (scoring-cases
 * 1.13, spec constraint 11). Making the pair inseparable removes the chance to get it wrong.
 *
 * Ranges filter on `day_date`, the day an answer *belongs to*, never the day it was submitted. The
 * sleep items are the reason: they arrive through the next morning's check-in and still belong to
 * the night before (spec 3.1).
 */
@Dao
interface AnswerDao {

    @Transaction
    @Query("SELECT * FROM answers WHERE day_date BETWEEN :from AND :to ORDER BY day_date, item_id")
    suspend fun between(from: String, to: String): List<AnswerWithSelections>

    @Transaction
    @Query("SELECT * FROM answers WHERE day_date = :day ORDER BY item_id")
    suspend fun onDay(day: String): List<AnswerWithSelections>

    @Transaction
    @Query("SELECT * FROM answers WHERE item_id = :itemId AND day_date = :day")
    suspend fun forItemOnDay(itemId: String, day: String): AnswerWithSelections?

    @Transaction
    @Query(
        """
        SELECT * FROM answers
        WHERE item_id = :itemId AND day_date BETWEEN :from AND :to
        ORDER BY day_date
        """,
    )
    suspend fun forItemBetween(itemId: String, from: String, to: String): List<AnswerWithSelections>

    /**
     * Deferred answers awaiting resolution in the next morning's check-in. The rollover job converts
     * any left unresolved into missed goals while leaving the check-in they were given in answered
     * (scoring-cases A2.1, A2.2).
     */
    @Transaction
    @Query("SELECT * FROM answers WHERE capture = :pending ORDER BY day_date, item_id")
    suspend fun withCapture(pending: String): List<AnswerWithSelections>

    @Transaction
    @Query("SELECT * FROM answers ORDER BY day_date, item_id")
    suspend fun all(): List<AnswerWithSelections>

    @Insert suspend fun insertAnswer(answer: AnswerEntity)

    @Insert suspend fun insertSelections(selections: List<AnswerSelectionEntity>)

    @Upsert suspend fun upsertAnswer(answer: AnswerEntity)

    @Query("DELETE FROM answer_selections WHERE answer_id = :answerId")
    suspend fun clearSelections(answerId: String)

    /**
     * Removes an answer entirely, for an item and day.
     *
     * **Clearing an answer has to delete the row, not blank it.** A row that exists with nothing in
     * it is not "no answer" — for a select item it is the real answer *"none of these"*, which
     * satisfies a must-not-include target where silence is excluded (`DirectionEvaluator`,
     * scoring-cases 1.13). Blanking would silently convert a retracted answer into a met goal.
     *
     * Selections go with it through the `ON DELETE CASCADE` on `answer_selections`.
     */
    @Query("DELETE FROM answers WHERE item_id = :itemId AND day_date = :day")
    suspend fun deleteForItemOnDay(itemId: String, day: String)

    /**
     * Replace an answer and its selections together. Editing a multi-select is a wholesale swap, and
     * doing it in two ungrouped statements leaves a window in which the answer carries the old
     * selections -- a window the rollover job or the debug fixture could read.
     */
    @Transaction
    suspend fun replace(answer: AnswerEntity, selections: List<AnswerSelectionEntity>) {
        upsertAnswer(answer)
        clearSelections(answer.id)
        if (selections.isNotEmpty()) insertSelections(selections)
    }
}
