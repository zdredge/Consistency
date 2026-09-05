package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.time.DayResolver
import java.time.LocalDate

/**
 * Decides which [Capture] an answer gets at the moment it is recorded.
 *
 * This is the input **response rate** is computed from, so a wrong rule here produces a plausible
 * wrong percentage on a dashboard whose whole purpose is to be believed — never a crash. That is why
 * it is a pure function with the clock injected rather than a branch inside the check-in screen.
 *
 * **The window is the check-in's own day**, which ends at 04:00. Three statements have to fit
 * together and only this reading does so: spec §2 says notifications "repeat twice at ~20 min, then
 * mark missed"; spec §3.2 gives the grace as "until the end of the next day"; and scoring-case 3.2
 * makes an answer given the next morning `BACKFILLED`. So §2 describes the *notification sequence*
 * stopping, not the window shutting. Practically, an answer at 22:00 — an hour after the 21:00
 * prompt, the same evening — still counts, which the alternative reading would have called a
 * backfill.
 *
 * Note this never consults the check-in's own state. Capture is decided by the clock alone, so it is
 * correct before M5 exists to mark anything `MISSED`, and it cannot drift from that state machine
 * later.
 */
class CaptureResolver(private val dayResolver: DayResolver) {

    /**
     * The capture state for an answer to a check-in held on [checkInDay], recorded now.
     *
     * [resolvingDeferral] marks the one exception in the rulebook: a "not yet" answered in the next
     * morning's check-in is `IN_WINDOW`, not `BACKFILLED` (spec §3.2, scoring-case 3.3). The
     * deferral was honoured, so it costs nothing — which is what makes "not yet" safe to offer at
     * all, since it exists to stop a check-in arriving while items are still actionable.
     *
     * The exception covers the next morning and no later. A deferral answered a week on is `LATE`
     * like any other stale answer; otherwise deferring once would buy indefinite in-window credit.
     */
    fun forAnswer(checkInDay: LocalDate, resolvingDeferral: Boolean = false): Capture =
        when (dayResolver.today()) {
            checkInDay -> Capture.IN_WINDOW
            checkInDay.plusDays(1) ->
                if (resolvingDeferral) Capture.IN_WINDOW else Capture.BACKFILLED
            else -> Capture.LATE
        }

    /**
     * The state a "not yet" is stored with until it is resolved.
     *
     * An unresolved deferral converts to a **missed goal** at rollover while the check-in it was
     * given in stays answered (scoring-cases A2.1, A2.2) — the only case in the rulebook where an
     * absent value scores as missed rather than being excluded. Storing it as its own capture state
     * is what makes that conversion findable.
     */
    fun deferred(): Capture = Capture.PENDING
}
