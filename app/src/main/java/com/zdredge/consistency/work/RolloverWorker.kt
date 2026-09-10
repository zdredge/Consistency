package com.zdredge.consistency.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zdredge.consistency.container
import com.zdredge.consistency.notify.CheckInAlarmScheduler

/**
 * The 04:00 job.
 *
 * **Deliberately almost empty.** Everything that could be *wrong* rather than merely late lives in
 * `RolloverPlanner` (`:domain`, tested without a device) and `ConsistencyRepository.runRollover`
 * (`:data`, tested against real SQLite). What is left here is "wake up, call it, say what happened",
 * which is the shape build-order asks for: the behaviour where it can be tested, and a wrapper thin
 * enough that hand-verifying it is enough.
 *
 * ### Why every run is logged and recorded
 *
 * Architecture §8 rates this job's silent failure **"High — invisible"**: nothing crashes, the app
 * looks fine, and every figure quietly rots because expected check-in rows are the denominator of
 * the primary metric. Its mitigation is stated as *"log every run, record last-successful-rollover,
 * surface staleness in the app rather than only in logs"* — so a failure is logged **and** written
 * to `rollover_runs`, and Home reads that record. A table of successes only cannot tell "it failed
 * every night" from "it never ran", and those need different fixes.
 *
 * ### Retry, not failure
 *
 * A thrown run returns `Result.retry()`. The work is idempotent by construction — both rules compare
 * stored state against today rather than assuming one run per day — so retrying costs nothing and
 * giving up would leave the record with a hole that only another day's run could fill.
 */
class RolloverWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = applicationContext.container.repository
        val today = applicationContext.container.dayResolver.today()

        return try {
            val outcome = repository.runRollover(today)
            Log.i(
                TAG,
                "rollover for $today: ${outcome.checkInsCreated} created, " +
                    "${outcome.checkInsMissed} missed, ${outcome.valuesFrozen} frozen",
            )

            // **The day's prompts are set here.** Creating the rows and arming the alarms used to be
            // separate concerns owned by nobody together, so the rows appeared at 04:15 and nothing
            // armed anything until the app was next opened -- by which time 08:00 had passed and the
            // morning prompt could no longer be set at all. Running before 08:00 was always for this.
            CheckInAlarmScheduler.reschedule(applicationContext)

            // Anchor tomorrow's run at 04:15, so the schedule cannot walk away from the window it
            // has to land in. Failing to anchor costs a day of drift and self-corrects on the next
            // successful run, so it must not turn a good rollover into a recorded failure.
            runCatching { RolloverScheduler.anchorNextRun(applicationContext) }
                .onFailure { Log.e(TAG, "could not anchor the next rollover", it) }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "rollover for $today failed", e)
            // Best-effort: if storage is the thing that broke, this write fails too, and the log
            // line above is all that is left. Recording it must not turn one failure into a crash.
            runCatching {
                repository.recordRolloverFailure(today, e.toString())
            }
            // Deliberately does not anchor. A retry keeps WorkManager's own backoff, which clears the
            // override; writing a new one here would take priority over that backoff and push the
            // retry to tomorrow's 04:15 instead, losing the day this run was for.
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "RolloverWorker"
    }
}
