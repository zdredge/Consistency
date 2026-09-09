package com.zdredge.consistency.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Puts the rollover on the calendar, once.
 *
 * **Inexact on purpose.** Periodic work can drift — architecture §4 accepts 04:07 and notes nothing
 * user-facing depends on the minute, which is why this job uses WorkManager while the 21:00 check-in
 * prompt will need an exact alarm in M6. What matters here is that it survives a reboot and retries
 * on failure, and that it eventually runs; the day boundary is `DayResolver`'s, not the scheduler's,
 * so a run at 04:07 and a run at 05:30 do the same work.
 *
 * **A missed run is not a lost day.** If the device is off at 04:00 the job runs when it can, and
 * both rollover rules compare stored state against today rather than assuming one run per day, so a
 * single late run resolves everything it slept through. That property is what makes an inexact
 * scheduler acceptable for the one thing that writes without the user.
 */
object RolloverScheduler {

    /** Just after the 04:00 day boundary, so the day it closes is genuinely over. */
    private val RunAt: LocalTime = LocalTime.of(4, 15)

    private const val WORK_NAME = "rollover"

    /**
     * Schedules the daily job if it is not already scheduled.
     *
     * `KEEP` rather than `UPDATE`: called from `Application.onCreate`, so it runs on every process
     * start, including the one WorkManager itself creates to execute the job. `UPDATE` would
     * reschedule the work each time and could push the next run past its slot indefinitely.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RolloverWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(untilNextRun().toMillis(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * How long until the next [RunAt].
     *
     * Uses the system clock rather than the injected one: this schedules against wall-clock time the
     * OS will wake us at, and a test clock would produce a delay measured from a date that is not
     * today. The work itself takes its date from `DayResolver`, which is where the 04:00 boundary and
     * any test clock belong.
     */
    private fun untilNextRun(): Duration {
        val now = ZonedDateTime.now()
        val todaysRun = now.with(RunAt)
        val next = if (todaysRun.isAfter(now)) todaysRun else todaysRun.plusDays(1)
        return Duration.between(now, next)
    }
}
