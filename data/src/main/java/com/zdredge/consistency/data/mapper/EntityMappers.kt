package com.zdredge.consistency.data.mapper

import com.zdredge.consistency.data.db.LOCAL_USER_ID
import com.zdredge.consistency.data.db.entity.AnswerEntity
import com.zdredge.consistency.data.db.entity.AnswerSelectionEntity
import com.zdredge.consistency.data.db.entity.CheckInEntity
import com.zdredge.consistency.data.db.entity.ContainerSizeEntity
import com.zdredge.consistency.data.db.entity.ItemEntity
import com.zdredge.consistency.data.db.entity.ItemVersionEntity
import com.zdredge.consistency.data.db.entity.MeasuredOriginEntity
import com.zdredge.consistency.data.db.entity.MeasuredValueEntity
import com.zdredge.consistency.data.db.entity.RollUpSpecEntity
import com.zdredge.consistency.data.db.entity.SelectOptionEntity
import com.zdredge.consistency.data.db.entity.TargetEntity
import com.zdredge.consistency.data.db.relation.AnswerWithSelections
import com.zdredge.consistency.data.db.relation.MeasuredValueWithOrigins
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.ContainerSize
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.time.DayResolver

/*
 * Room rows in, domain types out.
 *
 * Two things this layer does, and one it must never do.
 *
 * It puts the typed ids back on. Rows carry raw Strings; the domain carries ItemId, OptionId and
 * ItemVersionId, so that an option id passed where an item id belongs stops compiling rather than
 * scoring wrongly.
 *
 * It resolves timestamps to days through DayResolver, and only through it. The created_at and
 * retired_at columns are stored as instants, but "was this active in this period" is a day-level
 * question under the 04:00 boundary. Nothing here may compute a day any other way (spec constraint
 * 1, architecture section 5).
 *
 * What it must never do is derive. Sleep duration, lingering minutes, weekly roll-ups and hit rates
 * are the domain layer's to compute on read; a mapper that helpfully filled one in would be
 * persisting a derived value one layer up from the schema that forbids it (docs/CLAUDE.md,
 * architecture T4).
 */

fun ItemEntity.toDomain(dayResolver: DayResolver) = Item(
    id = ItemId(id),
    kind = kind,
    createdOn = dayResolver.dayFor(createdAt),
    retiredOn = retiredAt?.let(dayResolver::dayFor),
    ordinal = ordinal,
)

fun ItemVersionEntity.toDomain() = ItemVersion(
    id = ItemVersionId(id),
    itemId = ItemId(itemId),
    versionNo = versionNo,
    prompt = prompt,
    answerType = answerType,
    classification = classification,
    slot = slot,
    unitLabel = unitLabel,
    effectiveFrom = effectiveFrom,
)

fun SelectOptionEntity.toDomain(dayResolver: DayResolver) = SelectOption(
    id = OptionId(id),
    itemId = ItemId(itemId),
    label = label,
    ordinal = ordinal,
    isNoOpportunity = isNoOpportunity,
    retiredOn = retiredAt?.let(dayResolver::dayFor),
)

fun TargetEntity.toDomain() = Target(
    itemId = ItemId(itemId),
    period = period,
    direction = direction,
    valueNumber = valueNumber,
    optionId = optionId?.let(::OptionId),
    effectiveFrom = effectiveFrom,
)

fun ContainerSizeEntity.toDomain() = ContainerSize(
    itemId = ItemId(itemId),
    size = sizeNumber,
    unitLabel = unitLabel,
    effectiveFrom = effectiveFrom,
)

fun RollUpSpecEntity.toDomain() = RollUpSpec(
    itemId = ItemId(itemId),
    sourceItemId = ItemId(sourceItemId),
    aggregation = aggregation,
)

fun CheckInEntity.toDomain() = CheckIn(
    day = dayDate,
    slot = slot,
    state = state,
    answeredAt = answeredAt,
    // Carried up for M6: AlarmPlanner schedules the prompt and its repeats from this. It was dropped
    // here until then, so nothing above the DAO knew when a check-in was actually due.
    scheduledAt = scheduledAt,
)

fun AnswerWithSelections.toDomain() = Answer(
    itemId = ItemId(answer.itemId),
    itemVersionId = ItemVersionId(answer.itemVersionId),
    day = answer.dayDate,
    capture = answer.capture,
    submittedAt = answer.submittedAt,
    editedAt = answer.editedAt,
    valueBool = answer.valueBool,
    valueNumber = answer.valueNumber,
    valueTime = answer.valueTime,
    valueScale = answer.valueScale,
    selections = selections.map { OptionId(it.id) }.toSet(),
    note = answer.note,
)

fun MeasuredValueWithOrigins.toDomain() = MeasuredValue(
    itemId = ItemId(value.itemId),
    day = value.dayDate,
    value = value.valueNumber,
    state = value.state,
    lastSyncedAt = value.lastSyncedAt,
    origins = origins.map { MeasuredOrigin(it.originPackage, it.valueNumber) },
)

/*
 * The write direction.
 *
 * An item created on a day becomes 04:00 on that day rather than midnight, so a value written and
 * read back lands on the day it started on. Midnight would sit BEFORE the boundary and resolve to
 * the day before, making the round trip silently move the date.
 */

fun Item.toEntity(dayResolver: DayResolver) = ItemEntity(
    id = id.value,
    userId = LOCAL_USER_ID,
    kind = kind,
    createdAt = dayResolver.startOfDay(createdOn),
    retiredAt = retiredOn?.let(dayResolver::startOfDay),
    ordinal = ordinal,
)

fun ItemVersion.toEntity() = ItemVersionEntity(
    id = id.value,
    userId = LOCAL_USER_ID,
    itemId = itemId.value,
    versionNo = versionNo,
    prompt = prompt,
    answerType = answerType,
    classification = classification,
    slot = slot,
    unitLabel = unitLabel,
    effectiveFrom = effectiveFrom,
)

fun SelectOption.toEntity(dayResolver: DayResolver) = SelectOptionEntity(
    id = id.value,
    userId = LOCAL_USER_ID,
    itemId = itemId.value,
    label = label,
    ordinal = ordinal,
    isNoOpportunity = isNoOpportunity,
    retiredAt = retiredOn?.let(dayResolver::startOfDay),
)

fun Target.toEntity(id: String) = TargetEntity(
    id = id,
    userId = LOCAL_USER_ID,
    itemId = itemId.value,
    period = period,
    direction = direction,
    valueNumber = valueNumber,
    optionId = optionId?.value,
    effectiveFrom = effectiveFrom,
)

fun ContainerSize.toEntity(id: String) = ContainerSizeEntity(
    id = id,
    userId = LOCAL_USER_ID,
    itemId = itemId.value,
    sizeNumber = size,
    unitLabel = unitLabel,
    effectiveFrom = effectiveFrom,
)

fun RollUpSpec.toEntity(id: String) = RollUpSpecEntity(
    id = id,
    userId = LOCAL_USER_ID,
    itemId = itemId.value,
    sourceItemId = sourceItemId.value,
    aggregation = aggregation,
)

fun Answer.toEntity(id: String, viaCheckInId: String?) = AnswerEntity(
    id = id,
    userId = LOCAL_USER_ID,
    itemId = itemId.value,
    itemVersionId = itemVersionId.value,
    dayDate = day,
    submittedViaCheckinId = viaCheckInId,
    submittedAt = submittedAt,
    capture = capture,
    editedAt = editedAt,
    valueBool = valueBool,
    valueNumber = valueNumber,
    valueTime = valueTime,
    valueScale = valueScale,
    note = note,
)

fun Answer.selectionEntities(answerId: String) = selections.map {
    AnswerSelectionEntity(answerId = answerId, optionId = it.value, userId = LOCAL_USER_ID)
}

fun MeasuredValue.toEntity(id: String) = MeasuredValueEntity(
    id = id,
    userId = LOCAL_USER_ID,
    itemId = itemId.value,
    dayDate = day,
    valueNumber = value,
    state = state,
    lastSyncedAt = lastSyncedAt,
)

fun MeasuredValue.originEntities(measuredValueId: String) = origins.map {
    MeasuredOriginEntity(
        measuredValueId = measuredValueId,
        originPackage = it.originPackage,
        userId = LOCAL_USER_ID,
        valueNumber = it.value,
    )
}
