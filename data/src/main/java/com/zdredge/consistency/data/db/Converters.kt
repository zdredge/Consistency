package com.zdredge.consistency.data.db

import androidx.room.TypeConverter
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.Slot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * How domain types reach SQLite. Two rules here are load-bearing, and both guard against the same
 * failure this project keeps designing against: a change that produces plausible wrong data rather
 * than a crash.
 *
 * **Enums persist by [Enum.name], never by ordinal.** Storing an ordinal means reordering
 * [Direction] or [Capture] silently re-labels every historical row — an `AT_LEAST` target quietly
 * becoming `AT_MOST`, with nothing to notice. The cost is a few bytes per row; the alternative is
 * unrecoverable.
 *
 * **[LocalDate] and [LocalTime] persist as ISO-8601 text; [Instant] persists as epoch millis.**
 * ISO-8601 dates sort lexicographically in the same order they sort chronologically, so a
 * `day_date BETWEEN ? AND ?` range query is correct with no conversion inside the WHERE clause.
 * Instants are only ever compared and subtracted, so a number is the honest representation.
 *
 * Note what is *not* here: nothing converts a derived value. Sleep duration, lingering minutes and
 * weekly roll-ups are computed by :domain on read and never stored (docs/CLAUDE.md, architecture T4).
 */
class Converters {

    @TypeConverter fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter fun localTimeToString(value: LocalTime?): String? = value?.toString()

    @TypeConverter fun stringToLocalTime(value: String?): LocalTime? = value?.let(LocalTime::parse)

    @TypeConverter fun instantToEpochMilli(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter fun epochMilliToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun itemKindToString(value: ItemKind?): String? = value?.name

    @TypeConverter fun stringToItemKind(value: String?): ItemKind? = value?.let(ItemKind::valueOf)

    @TypeConverter fun answerTypeToString(value: AnswerType?): String? = value?.name

    @TypeConverter fun stringToAnswerType(value: String?): AnswerType? = value?.let(AnswerType::valueOf)

    @TypeConverter fun classificationToString(value: Classification?): String? = value?.name

    @TypeConverter
    fun stringToClassification(value: String?): Classification? = value?.let(Classification::valueOf)

    @TypeConverter fun slotToString(value: Slot?): String? = value?.name

    @TypeConverter fun stringToSlot(value: String?): Slot? = value?.let(Slot::valueOf)

    @TypeConverter fun captureToString(value: Capture?): String? = value?.name

    @TypeConverter fun stringToCapture(value: String?): Capture? = value?.let(Capture::valueOf)

    @TypeConverter fun checkInStateToString(value: CheckInState?): String? = value?.name

    @TypeConverter
    fun stringToCheckInState(value: String?): CheckInState? = value?.let(CheckInState::valueOf)

    @TypeConverter fun measuredStateToString(value: MeasuredState?): String? = value?.name

    @TypeConverter
    fun stringToMeasuredState(value: String?): MeasuredState? = value?.let(MeasuredState::valueOf)

    @TypeConverter fun periodToString(value: Period?): String? = value?.name

    @TypeConverter fun stringToPeriod(value: String?): Period? = value?.let(Period::valueOf)

    @TypeConverter fun directionToString(value: Direction?): String? = value?.name

    @TypeConverter fun stringToDirection(value: String?): Direction? = value?.let(Direction::valueOf)

    @TypeConverter fun aggregationToString(value: RollUpAggregation?): String? = value?.name

    @TypeConverter
    fun stringToAggregation(value: String?): RollUpAggregation? = value?.let(RollUpAggregation::valueOf)
}
