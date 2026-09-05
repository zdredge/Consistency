package com.zdredge.consistency.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.zdredge.consistency.data.db.entity.MeasuredOriginEntity
import com.zdredge.consistency.data.db.entity.MeasuredValueEntity

/**
 * A measured value with its per-source breakdown.
 *
 * The origins travel with the value rather than being fetched on demand because the multi-origin
 * case is the one that must never be missed: two origins for a day means a second step source
 * appeared and the numbers must not be silently summed (architecture section 5, section 8 risk).
 * Making callers ask for origins separately is how that check gets skipped.
 */
data class MeasuredValueWithOrigins(
    @Embedded val value: MeasuredValueEntity,
    @Relation(parentColumn = "id", entityColumn = "measured_value_id")
    val origins: List<MeasuredOriginEntity>,
)
