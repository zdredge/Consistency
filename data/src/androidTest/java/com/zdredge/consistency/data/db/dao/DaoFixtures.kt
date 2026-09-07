package com.zdredge.consistency.data.db.dao

import com.zdredge.consistency.data.db.LOCAL_USER_ID
import com.zdredge.consistency.data.db.entity.AnswerEntity
import com.zdredge.consistency.data.db.entity.AnswerSelectionEntity
import com.zdredge.consistency.data.db.entity.CheckInEntity
import com.zdredge.consistency.data.db.entity.ContainerSizeEntity
import com.zdredge.consistency.data.db.entity.ItemEntity
import com.zdredge.consistency.data.db.entity.ItemVersionEntity
import com.zdredge.consistency.data.db.entity.MeasuredOriginEntity
import com.zdredge.consistency.data.db.entity.MeasuredValueEntity
import com.zdredge.consistency.data.db.entity.SelectOptionEntity
import com.zdredge.consistency.data.db.entity.TargetEntity
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Row builders with sensible defaults, so a test states only the fields it is actually about.
 *
 * The point is legibility of intent: a test asserting date-range behaviour should not be half made
 * of unit labels and notify counts, because the reader then cannot tell which values matter.
 */
internal object Rows {

    fun item(
        id: String,
        kind: ItemKind = ItemKind.ASKED,
        createdAt: Instant = Instant.EPOCH,
        retiredAt: Instant? = null,
        ordinal: Int = 0,
    ) = ItemEntity(
        id = id,
        userId = LOCAL_USER_ID,
        kind = kind,
        ordinal = ordinal,
        createdAt = createdAt,
        retiredAt = retiredAt,
    )

    fun version(
        id: String,
        itemId: String,
        versionNo: Int = 1,
        prompt: String = "prompt",
        answerType: AnswerType = AnswerType.NUMBER,
        classification: Classification = Classification.GOAL,
        slot: Slot = Slot.NIGHT,
        unitLabel: String? = null,
        effectiveFrom: String = "2026-08-01",
    ) = ItemVersionEntity(
        id, LOCAL_USER_ID, itemId, versionNo, prompt, answerType, classification, slot,
        unitLabel, LocalDate.parse(effectiveFrom),
    )

    fun option(
        id: String,
        itemId: String,
        label: String = id,
        ordinal: Int = 0,
        isNoOpportunity: Boolean = false,
        retiredAt: Instant? = null,
    ) = SelectOptionEntity(id, LOCAL_USER_ID, itemId, label, ordinal, isNoOpportunity, retiredAt)

    fun target(
        id: String,
        itemId: String,
        period: Period = Period.DAY,
        direction: Direction = Direction.AT_LEAST,
        valueNumber: Double? = 3.0,
        optionId: String? = null,
        effectiveFrom: String = "2026-08-01",
    ) = TargetEntity(
        id, LOCAL_USER_ID, itemId, period, direction, valueNumber, optionId,
        LocalDate.parse(effectiveFrom),
    )

    fun containerSize(
        id: String,
        itemId: String,
        size: Double = 40.0,
        unitLabel: String = "oz",
        effectiveFrom: String = "2026-08-01",
    ) = ContainerSizeEntity(
        id, LOCAL_USER_ID, itemId, size, unitLabel, LocalDate.parse(effectiveFrom),
    )

    fun checkIn(
        id: String,
        day: String,
        slot: Slot = Slot.NIGHT,
        scheduledAt: Instant = Instant.EPOCH,
        state: CheckInState = CheckInState.ANSWERED,
        answeredAt: Instant? = null,
        notifyAttempts: Int = 0,
    ) = CheckInEntity(
        id, LOCAL_USER_ID, LocalDate.parse(day), slot, scheduledAt, state, answeredAt,
        notifyAttempts,
    )

    fun answer(
        id: String,
        itemId: String,
        versionId: String,
        day: String,
        viaCheckIn: String? = null,
        submittedAt: Instant = Instant.EPOCH,
        capture: Capture = Capture.IN_WINDOW,
        editedAt: Instant? = null,
        valueBool: Boolean? = null,
        valueNumber: Double? = null,
        valueTime: LocalTime? = null,
        valueScale: Int? = null,
        note: String? = null,
    ) = AnswerEntity(
        id, LOCAL_USER_ID, itemId, versionId, LocalDate.parse(day), viaCheckIn, submittedAt,
        capture, editedAt, valueBool, valueNumber, valueTime, valueScale, note,
    )

    fun selection(answerId: String, optionId: String) =
        AnswerSelectionEntity(answerId, optionId, LOCAL_USER_ID)

    fun measured(
        id: String,
        itemId: String,
        day: String,
        value: Double,
        state: MeasuredState = MeasuredState.FROZEN,
        lastSyncedAt: Instant? = null,
    ) = MeasuredValueEntity(
        id, LOCAL_USER_ID, itemId, LocalDate.parse(day), value, state, lastSyncedAt,
    )

    fun origin(measuredValueId: String, originPackage: String, value: Double) =
        MeasuredOriginEntity(measuredValueId, originPackage, LOCAL_USER_ID, value)
}
