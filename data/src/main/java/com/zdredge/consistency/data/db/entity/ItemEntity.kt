package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zdredge.consistency.domain.model.ItemKind
import java.time.Instant

/**
 * Stable identity only; nothing mutable lives here (architecture section 5). The wording, type,
 * classification and slot all live in `item_versions`, so rewording a question never retroactively
 * changes what an old answer meant.
 *
 * Ids are raw Strings here rather than the domain's `ItemId` value class. The value classes exist to
 * stop an option id being passed where an item id belongs, and that risk lives in scoring, not in a
 * table; the mapping layer puts the types back on. Keeping Room off value classes avoids fighting
 * the annotation processor for a guarantee it was never providing.
 *
 * [createdAt] and [retiredAt] are timestamps, not dates. The domain asks a day-level question ("was
 * this item active in this period"), so the mapper resolves them through `DayResolver` on the way
 * out — the 04:00 boundary applies here as it does everywhere else (spec constraint 1).
 *
 * [userId] is unused in v1 and non-null (spec constraint 15). It is present from the first schema
 * rather than added later because retrofitting a column onto every table is a migration nobody
 * wants to write for a field that costs nothing now.
 */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "kind")
    val kind: ItemKind,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    /** Null while active. A retired item stays visible for the periods it was active in (spec 3.3). */
    @ColumnInfo(name = "retired_at")
    val retiredAt: Instant? = null,
)
