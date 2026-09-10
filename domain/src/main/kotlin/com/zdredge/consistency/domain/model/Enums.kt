package com.zdredge.consistency.domain.model

/** Asked items appear in check-ins; measured items come from Health Connect (spec 3.3). */
enum class ItemKind { ASKED, MEASURED }

/** Spec 3.3. Short text is deliberately not a primary type -- prose belongs in the note. */
enum class AnswerType { BOOL, NUMBER, TIME, SCALE, SINGLE_SELECT, MULTI_SELECT }

/** A goal has targets and is scored; an observation is recorded and charted, never scored. */
enum class Classification { GOAL, OBSERVATION }

/** When an asked item appears. Measured items have NONE. */
enum class Slot { MORNING, NIGHT, WEEKLY, NONE }

/**
 * How an answer was FIRST recorded. Spec constraint 4: this is separate from whether it was later
 * edited, which is a nullable timestamp. Merging the two loses the ability to describe a backfilled
 * answer that was subsequently corrected.
 */
enum class Capture { IN_WINDOW, BACKFILLED, LATE, PENDING }

/** A row exists for every check-in that was expected -- the denominator for response rate. */
enum class CheckInState { PENDING, ANSWERED, MISSED }

/**
 * Spec O4: provisional for 24h after the 04:00 read, then frozen.
 *
 * [CONFLICTED] is the origin guard's verdict, not part of O4: more than one source reported steps for
 * the day, so the figure cannot be trusted and the day is not scored. It is a state rather than a
 * nullable value because `value_number` is NOT NULL and this needed no migration -- the column keeps
 * the origins' total purely so a flagged day can be investigated, and nothing reads it as a step
 * count. A conflicted day never freezes; a later read finding one origin returns it to [PROVISIONAL].
 */
enum class MeasuredState { PROVISIONAL, FROZEN, CONFLICTED }

/** Targets key on (item, period). Month is a viewing window, not a target granularity (spec 3.1). */
enum class Period { DAY, WEEK }

/** Spec 3.4. A bare number cannot express "at most two" -- constraint 3. */
enum class Direction {
    AT_LEAST,
    AT_MOST,
    EXACTLY,
    IS_TRUE,
    IS_FALSE,
    MUST_INCLUDE,
    MUST_NOT_INCLUDE,
}

/** Spec 3.4: roll-ups are explicit, not inferred. */
enum class RollUpAggregation { COUNT_OF_YES, SUM, AVERAGE, MAX }
