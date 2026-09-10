package com.zdredge.consistency.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zdredge.consistency.container
import com.zdredge.consistency.domain.checkin.AnswerDay
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * One check-in prompt firing.
 *
 * **Everything it needs arrives in the Intent.** The process that set this alarm is usually long
 * gone — architecture §1.1: the system wakes the app, it does one small thing, and it goes away — so
 * nothing may be held in memory between setting an alarm and it firing. Day and slot come through
 * the extras, and the container is rebuilt lazily on whatever process the system happens to start.
 */
class CheckInAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val day = intent.getStringExtra(Notifications.EXTRA_DAY)?.let(LocalDate::parse) ?: return
        val slot = intent.getStringExtra(Notifications.EXTRA_SLOT)?.let(Slot::valueOf) ?: return

        // The work touches storage, so the broadcast has to be held open. Ten seconds is the budget;
        // this is two queries and a notification.
        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                deliver(app, day, slot)
            } catch (e: Exception) {
                Log.e(TAG, "check-in prompt for $day $slot failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * **Re-checks before posting.** Cancelling the remaining repeats when a check-in is answered is
     * the polite path, but correctness cannot depend on it: an alarm that survives cancellation must
     * still be harmless. So the state is read at fire time, and a check-in that is no longer waiting
     * is not prompted — it is un-notified instead, in case a banner is still sitting in the shade.
     */
    private suspend fun deliver(context: Context, day: LocalDate, slot: Slot) {
        val repository = context.container.repository
        val checkIn = repository.checkIn(day, slot)

        if (checkIn == null || checkIn.state != CheckInState.PENDING) {
            Log.i(TAG, "$day $slot no longer pending; not prompting")
            Notifications.cancel(context, day, slot)
            return
        }

        Notifications.postCheckIn(
            context = context,
            day = day,
            slot = slot,
            // A morning check-in covers last night, and the prompt should name the same day the
            // screen it opens does (spec §3.1).
            answersDay = AnswerDay.forCheckIn(day, slot),
        )
        repository.recordNotification(day, slot)

        // Keeps the window populated without chaining: the next run recomputes everything, so a
        // firing that never happens cannot end the sequence.
        CheckInAlarmScheduler.reschedule(context)
    }

    private companion object {
        const val TAG = "CheckInAlarms"
    }
}
