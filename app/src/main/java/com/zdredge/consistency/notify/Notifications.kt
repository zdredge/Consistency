package com.zdredge.consistency.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zdredge.consistency.MainActivity
import com.zdredge.consistency.R
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Matches the check-in header and the home cards, so the prompt names what the screen will. */
private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM")

/**
 * The check-in prompt.
 *
 * **High importance, and that is the end of it.** A high-importance channel gets a heads-up banner
 * and sound; the user can silence it in system settings and the app can neither override that nor
 * reliably detect it (architecture §4). Nothing here tries. That limitation is *why* spec §2 puts
 * confrontation at app-open and puts escalating-after-misses permanently out of scope — the mitigation
 * for muting is the product's shape, not a louder notification.
 *
 * **The copy is flat on purpose.** Spec §2 rules out motivational or encouraging copy, and §1's frame
 * is "accountability, not motivation": the app states what is unanswered and stops. So the prompt
 * names the check-in and the day it covers, and says nothing about streaks, progress or how the user
 * is doing.
 */
object Notifications {

    const val CHANNEL_ID = "check_ins"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Creating an existing channel is a no-op, so this is safe to call on every delivery --
        // which matters because the process is usually created by the alarm itself.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Check-in reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Prompts for the morning and nightly check-ins."
            },
        )
    }

    /**
     * Posts the prompt for one check-in.
     *
     * **One notification id per check-in**, derived from the day and slot, so an escalation repeat
     * *replaces* the banner rather than stacking a second copy of the same question. Three unanswered
     * prompts in the shade would read as three things to do.
     */
    fun postCheckIn(context: Context, day: LocalDate, slot: Slot, answersDay: LocalDate) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_checkin)
            .setContentTitle(
                when (slot) {
                    Slot.MORNING -> "Morning check-in"
                    Slot.NIGHT -> "Nightly check-in"
                    else -> "Check-in"
                },
            )
            // The day the answers belong to, not the day the prompt fired: a morning check-in covers
            // last night (spec §3.1), and the screen it opens says the same date.
            .setContentText(answersDay.format(dayFormat))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openCheckIn(context, day, slot))
            .build()

        manager.notify(notificationId(day, slot), notification)
    }

    /** Removes the prompt once its check-in is dealt with. */
    fun cancel(context: Context, day: LocalDate, slot: Slot) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(notificationId(day, slot))
    }

    /**
     * Opens the check-in the prompt is about, not the home screen.
     *
     * Spec §1: the first habit is answering, and the nudge is "come and answer". Landing on Home and
     * making the user find the right card again is friction in the one path the product cannot afford
     * it in.
     *
     * `FLAG_IMMUTABLE` because nothing needs to fill this intent in, and it is required on modern
     * Android anyway. `FLAG_UPDATE_CURRENT` so a repeat reuses the same pending intent with fresh
     * extras rather than accumulating.
     */
    private fun openCheckIn(context: Context, day: LocalDate, slot: Slot): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHECK_IN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_DAY, day.toString())
            .putExtra(EXTRA_SLOT, slot.name)

        return PendingIntent.getActivity(
            context,
            notificationId(day, slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stable per check-in, so a repeat replaces rather than stacks. */
    private fun notificationId(day: LocalDate, slot: Slot): Int =
        (day.toEpochDay().toInt() * 2) + if (slot == Slot.MORNING) 0 else 1

    const val ACTION_OPEN_CHECK_IN = "com.zdredge.consistency.OPEN_CHECK_IN"
    const val EXTRA_DAY = "day"
    const val EXTRA_SLOT = "slot"
}
