package com.zdredge.consistency.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Puts the rollover on the calendar, and keeps it there.
 *
 * **Inexact, but no longer indifferent to when it runs.** Architecture §4 chose WorkManager because
 * the job must survive reboots and retry on failure, and originally justified the inexactness with
 * "nothing user-facing depends on the minute". **M6 made that false and nobody noticed**: this job
 * creates the day's check-in rows, and an 08:00 prompt can only be armed from a row that already
 * exists. A run at 04:15 and a run at 11:00 no longer do the same work — the second one silently
 * costs that day's morning prompt.
 *
 * **A periodic request drifts, which is what went wrong.** `setInitialDelay` governs only the first
 * run; afterwards WorkManager re-anchors the period to whenever the job actually executed. On the
 * real device that walked the 04:15 job out to 09:47, and the morning prompt could never fire again.
 *
 * So each run re-anchors the next one to the next 04:15 via `setNextScheduleTimeOverride`, an API
 * added for exactly this and documented with exactly this example. Drift cannot accumulate, because
 * the anchor is recomputed from the wall clock every night however late a given run was.
 *
 * **Still periodic, deliberately.** A one-time request that re-enqueues itself is the "each firing
 * arms the next" shape this project already rejected for `CheckInAlarmScheduler` — one missed link
 * and it is silent for ever. Keeping the request periodic leaves WorkManager's own recurrence
 * underneath the anchor as the safety net.
 */
object RolloverScheduler {

    /** Just after the 04:00 day boundary, so the day it closes is genuinely over. */
    private val RunAt: LocalTime = LocalTime.of(4, 15)

    private const val WORK_NAME = "rollover-daily"

    /**
     * The pre-anchor schedule, left drifted at 09:47 on the device this was found on.
     *
     * Unique names are one namespace, so simply enqueuing under a new name would leave the old work
     * running for ever beside it. Cancelling is a no-op on any device that never had it, so this
     * costs nothing and can go once no install predates the fix.
     */
    private const val DRIFTED_WORK_NAME = "rollover"

    /**
     * Schedules the daily job if it is not already scheduled.
     *
     * `KEEP`, and **not** `UPDATE`, for a reason that is easy to get backwards: at 04:15 it is the
     * job itself that starts this process, so `Application.onCreate` runs *before* the worker is
     * handed over. `UPDATE` at that moment would find the work not yet in-flight and cancel the very
     * JobScheduler job that started the process. `KEEP` cannot disturb a run that is starting or
     * retrying, and it heals the case that matters here: if the work is ever missing, the next
     * process start puts it back.
     *
     * **The cost of `KEEP`, found by trying it:** changing [RunAt] in code has no effect on a device
     * that already has this work enqueued -- `KEEP` keeps the old anchor, and only a run or an
     * explicit `UPDATE` moves it. When M8 makes check-in times configurable, changing the time must
     * re-anchor deliberately rather than assume this function will notice.
     */
    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(DRIFTED_WORK_NAME)
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request(),
        )
    }

    /**
     * Re-anchors the next run at the next 04:15, called from the run that has just finished.
     *
     * `UPDATE` is the documented partner of `setNextScheduleTimeOverride` and is safe from inside a
     * running worker: because the work is in-flight, the update applies to the next run only and
     * neither cancels the current one nor reschedules its job. It bumps the override's generation
     * counter, which is what stops the tidy-up that follows a periodic run from clearing the anchor
     * that was just written.
     *
     * `REPLACE` would be the obvious choice and is the wrong one — it cancels every unfinished
     * request under the name, and a running worker is unfinished, so the run would cancel itself and
     * its result would be discarded.
     */
    suspend fun anchorNextRun(context: Context) {
        val operation = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request(),
        )
        // Waited on, so the run does not end before the anchor is committed. `Operation.await()`
        // would read better but is inline over a runtime-only dependency and does not compile here.
        withContext(Dispatchers.IO) { operation.result.get() }
    }

    /** One builder for both callers: `UPDATE` replaces the whole spec, so they must not disagree. */
    private fun request() = PeriodicWorkRequestBuilder<RolloverWorker>(1, TimeUnit.DAYS)
        .setNextScheduleTimeOverride(nextRunAt().toInstant().toEpochMilli())
        .build()

    /**
     * The next [RunAt] by the wall clock.
     *
     * Uses the system clock rather than the injected one: this schedules against the time the OS
     * will wake us at, and a test clock would anchor to a date that is not today. The work itself
     * takes its date from `DayResolver`, which is where the 04:00 boundary and any test clock belong.
     */
    private fun nextRunAt(): ZonedDateTime {
        val now = ZonedDateTime.now()
        val todaysRun = now.with(RunAt)
        return if (todaysRun.isAfter(now)) todaysRun else todaysRun.plusDays(1)
    }
}
