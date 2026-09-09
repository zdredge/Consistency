package com.zdredge.consistency.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zdredge.consistency.container

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
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "rollover for $today failed", e)
            // Best-effort: if storage is the thing that broke, this write fails too, and the log
            // line above is all that is left. Recording it must not turn one failure into a crash.
            runCatching {
                repository.recordRolloverFailure(today, e.toString())
            }
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "RolloverWorker"
    }
}
