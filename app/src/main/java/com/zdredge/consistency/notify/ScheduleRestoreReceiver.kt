package com.zdredge.consistency.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Puts the check-in prompts back after the system throws them away.
 *
 * **Pending alarms do not survive a reboot.** Without this, one restart silently ends every future
 * notification — nothing crashes, nothing is logged, and the app simply stops asking. Architecture §4
 * calls it "a few lines that remove an entire class of mystery bug", and rejects the obvious
 * alternative of rescheduling on app open with the observation that it "works until you stop opening
 * the app because it stopped notifying you".
 *
 * **They do not survive an app update either**, which is the second half of the same door and was
 * left open until now. Android cancels a package's alarms when the package is replaced, so every
 * install ended all prompting until the app was next opened by hand — and on an app that is actively
 * being developed, an update is *more* frequent than a reboot, not less. `MY_PACKAGE_REPLACED` is
 * delivered only to the app it concerns and needs no permission.
 *
 * **`BOOT_COMPLETED`, not `LOCKED_BOOT_COMPLETED`.** The database is credential-encrypted and cannot
 * be read before the first unlock, and the schedule is computed from the check-ins in it. Waiting for
 * the unlock costs nothing here: the earliest prompt is 08:00, long after the phone is first used.
 *
 * The rollover needs no equivalent — WorkManager persists its own work across both events. That
 * asymmetry is the price of exact alarms, and it is why this file exists at all.
 */
class ScheduleRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app update"
            else -> return
        }

        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                CheckInAlarmScheduler.reschedule(app)
                Log.i(TAG, "check-in alarms restored after $reason")
            } catch (e: Exception) {
                // A failure here is the silent kind: no alarms, no symptom until a prompt does not
                // arrive. The log line is the only trace, so it is worth one.
                Log.e(TAG, "failed to restore check-in alarms after $reason", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "CheckInAlarms"
    }
}
