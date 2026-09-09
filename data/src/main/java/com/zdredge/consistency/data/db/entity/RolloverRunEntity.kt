package com.zdredge.consistency.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * One execution of the 04:00 rollover job.
 *
 * **This table exists because of a risk, not a feature.** Architecture §8 rates a silently failing
 * rollover as *"High — invisible"*, because the app looks fine while every figure quietly rots: no
 * expected check-in rows means no denominator, and no denominator means response rate is not wrong
 * but unmeasurable. Its mitigation is stated as *"log every run, record last-successful-rollover,
 * surface staleness in the app rather than only in logs"*, and this is the record half of that.
 *
 * **A history rather than one timestamp.** A single "last success" field says the job is broken; a
 * table says *when it stopped, what it was doing, and what it managed*, which is the difference
 * between detecting the failure and diagnosing it. Architecture §4 puts small state in DataStore and
 * *records* in Room — a run is a record.
 *
 * Failures are rows too. A run that threw still writes its row with [outcome] `FAILED`, because a
 * table containing only successes cannot distinguish "it failed every night" from "it never ran",
 * and those call for different fixes.
 *
 * Schema note: **not** one of the three ask-first guarded tables, but new in v3 — see `Migrations.kt`
 * and `SchemaVersionPinTest`.
 */
@Entity(
    tableName = "rollover_runs",
    indices = [Index(value = ["ran_at"])],
)
data class RolloverRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    /** When the job ran. Ordering and staleness both read this. */
    @ColumnInfo(name = "ran_at")
    val ranAt: Instant,
    /**
     * The day the job treated as "today".
     *
     * Stored separately from [ranAt] because they can legitimately disagree — a run at 03:50 belongs
     * to the previous day under the 04:00 boundary, and a catch-up run resolves days that are not
     * the one it happens to execute on.
     */
    @ColumnInfo(name = "for_day")
    val forDay: LocalDate,
    @ColumnInfo(name = "outcome")
    val outcome: String,
    @ColumnInfo(name = "checkins_created")
    val checkInsCreated: Int = 0,
    @ColumnInfo(name = "checkins_missed")
    val checkInsMissed: Int = 0,
    @ColumnInfo(name = "values_frozen")
    val valuesFrozen: Int = 0,
    /** The failure, when there was one. Null on success. */
    @ColumnInfo(name = "error")
    val error: String? = null,
)
