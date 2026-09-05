package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zdredge.consistency.domain.model.Capture
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * One answer to one item on one day.
 *
 * **[dayDate] is deliberately not derivable from [submittedViaCheckinId].** Sleep items belong to
 * the day the user went to bed, so the morning check-in writes answers dated *yesterday* (spec 3.1,
 * the sleep-day convention). This looks like denormalisation worth removing; removing it breaks the
 * sleep items and the "not yet" carry-over at the same time. The worked-example round-trip test
 * exists specifically to keep that fact demonstrated rather than merely asserted in a comment.
 *
 * **[capture] and [editedAt] are two separate fields, never one state enum** (spec constraint 4).
 * Capture is how the answer was FIRST recorded; editedAt is whether it was later corrected. An
 * answer can be backfilled *and* subsequently edited, and merging them loses one of those facts.
 * The in-window-only figure must always be recoverable from [capture] alone.
 *
 * Values are typed nullable columns rather than a blob (architecture T2). Exactly one is populated,
 * per the item version's answer type; select types use `answer_selections` instead.
 *
 * The unique index on (item_id, day_date) is the one-answer-per-item-per-day rule
 * (docs/CLAUDE.md conventions) enforced by the database rather than by application code, so it holds
 * against the rollover job and the debug fixture too, not only against the check-in screen.
 *
 * [submittedViaCheckinId] is nullable because a LATE answer may be filled in with no check-in to
 * attribute it to.
 *
 * Schema note: one of the three ask-first guarded tables (docs/CLAUDE.md). Realises architecture
 * section 5 exactly.
 */
@Entity(
    tableName = "answers",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ItemVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_version_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CheckInEntity::class,
            parentColumns = ["id"],
            childColumns = ["submitted_via_checkin_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["item_id", "day_date"], unique = true),
        Index(value = ["day_date"]),
        Index(value = ["item_version_id"]),
        Index(value = ["submitted_via_checkin_id"]),
    ],
)
data class AnswerEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "item_version_id")
    val itemVersionId: String,
    @ColumnInfo(name = "day_date")
    val dayDate: LocalDate,
    @ColumnInfo(name = "submitted_via_checkin_id")
    val submittedViaCheckinId: String? = null,
    @ColumnInfo(name = "submitted_at")
    val submittedAt: Instant,
    @ColumnInfo(name = "capture")
    val capture: Capture,
    /** Null unless the answer was later corrected. Never inferred from [capture]. */
    @ColumnInfo(name = "edited_at")
    val editedAt: Instant? = null,
    @ColumnInfo(name = "value_bool")
    val valueBool: Boolean? = null,
    @ColumnInfo(name = "value_number")
    val valueNumber: Double? = null,
    @ColumnInfo(name = "value_time")
    val valueTime: LocalTime? = null,
    @ColumnInfo(name = "value_scale")
    val valueScale: Int? = null,
    /** Never required, never scored (spec 3.3): one primary answer format per item, plus a note. */
    @ColumnInfo(name = "note")
    val note: String? = null,
)
