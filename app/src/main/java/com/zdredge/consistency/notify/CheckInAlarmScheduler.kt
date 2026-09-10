package com.zdredge.consistency.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zdredge.consistency.container
import com.zdredge.consistency.domain.checkin.AlarmPlanner
import com.zdredge.consistency.domain.checkin.AlarmSpec
import com.zdredge.consistency.domain.model.CheckIn

/**
 * Sets the check-in prompts.
 *
 * **Exact alarms, unlike the rollover.** Architecture §4 chose `AlarmManager` here and WorkManager
 * there for one reason: a 21:00 accountability prompt depends on the minute and a 04:00 bookkeeping
 * job does not. M0 measured the cost of that choice — `USE_EXACT_ALARM` is install-granted with no
 * runtime prompt, and an exact alarm fired in confirmed deep Doze with a **0.6 s slip**.
 *
 * ### It re-sets everything, every time
 *
 * Each run recomputes the whole window and sets every alarm in it. Setting an alarm that already
 * exists with the same request code simply replaces it, so this is idempotent and safe to call from
 * anywhere — app start, boot, after each firing, and after a check-in is answered.
 *
 * The alternative, a chain where each firing arms the next, is **one missed firing away from silence
 * for ever** — and silence is indistinguishable from nothing having been due, so it would never be
 * noticed. Recomputing makes every run self-healing, the same property that made the rollover's
 * inexact scheduling acceptable in M5.
 */
object CheckInAlarmScheduler {

    private const val TAG = "CheckInAlarms"

    /**
     * Recomputes and sets every prompt that should exist.
     *
     * Suspending because it reads the check-ins; callers on the main thread already have a scope, and
     * the receivers use `goAsync`.
     */
    suspend fun reschedule(context: Context) {
        val app = context.applicationContext
        val resolver = app.container.dayResolver
        val today = resolver.today()

        // One call, and deliberately not two. The rows have to exist before there is anything to
        // arm, and while that was the caller's job to sequence, three of five callers got it wrong
        // -- see ConsistencyRepository.checkInsForAlarms. There is no longer an order to get wrong.
        //
        // The window is the whole thing regardless of state, because answered check-ins are exactly
        // the ones whose alarms need taking down. AlarmPlanner decides which of these deserve one.
        //
        // It ends at today because generation ends at today: an alarm horizon reaching past the
        // generation horizon reaches rows that cannot exist, which is what the old DAYS_AHEAD did.
        val window = app.container.repository.checkInsForAlarms(today)
        val alarms = AlarmPlanner.plan(window, now = resolver.now(), through = today)

        val manager = app.getSystemService(AlarmManager::class.java)
        if (manager == null) {
            Log.e(TAG, "no AlarmManager; no check-in will be prompted")
            return
        }

        alarms.forEach { spec ->
            // RTC_WAKEUP so it fires at wall-clock time and wakes the device, and
            // setExactAndAllowWhileIdle so Doze does not defer it -- the combination M0 proved.
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                spec.at.toEpochMilli(),
                pendingIntent(app, spec),
            )
        }

        val cancelled = cancelAlarmsNoLongerWanted(app, manager, window, alarms)
        Log.i(TAG, "set ${alarms.size} check-in alarms, cancelled $cancelled")
    }

    /**
     * Takes down alarms the plan no longer wants — above all, the repeats for a check-in that has
     * just been answered.
     *
     * **Not re-setting an alarm does not unset it.** That was worth learning on the device: after
     * answering, the scheduler logged "set 0" and an alarm was still armed, because a plan that omits
     * something says nothing about what is already scheduled. The firing would have been harmless —
     * `CheckInAlarmReceiver` re-checks the state and posts nothing — but it still wakes the device to
     * do nothing, and the safety net should not be doing the work of the mechanism.
     *
     * `FLAG_NO_CREATE` returns null when no such alarm exists, so this only cancels what is really
     * there. It relies on `AlarmSpec.requestCode` being derived rather than stored: an alarm whose
     * intent cannot be rebuilt cannot be cancelled.
     */
    private fun cancelAlarmsNoLongerWanted(
        context: Context,
        manager: AlarmManager,
        window: List<CheckIn>,
        wanted: List<AlarmSpec>,
    ): Int {
        val keep = wanted.map { it.requestCode }.toSet()
        var cancelled = 0

        window.forEach { checkIn ->
            (0 until AlarmPlanner.ATTEMPTS_PER_CHECK_IN).forEach { attempt ->
                val spec = AlarmSpec(checkIn.day, checkIn.slot, attempt, checkIn.scheduledAt)
                if (spec.requestCode in keep) return@forEach

                val existing = PendingIntent.getBroadcast(
                    context,
                    spec.requestCode,
                    Intent(context, CheckInAlarmReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (existing != null) {
                    manager.cancel(existing)
                    existing.cancel()
                    cancelled++
                }
            }
        }
        return cancelled
    }

    /**
     * Rebuilt identically to set or cancel, which is why `AlarmSpec.requestCode` is derived from the
     * check-in rather than stored: an alarm whose intent cannot be reproduced cannot be cancelled.
     */
    private fun pendingIntent(context: Context, spec: AlarmSpec): PendingIntent {
        val intent = Intent(context, CheckInAlarmReceiver::class.java)
            .putExtra(Notifications.EXTRA_DAY, spec.day.toString())
            .putExtra(Notifications.EXTRA_SLOT, spec.slot.name)

        return PendingIntent.getBroadcast(
            context,
            spec.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
