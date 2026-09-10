package com.zdredge.consistency.data.health

import com.zdredge.consistency.domain.model.MeasuredOrigin
import java.time.LocalDate

/**
 * Where step counts come from.
 *
 * **One implementation, deliberately** (architecture §5). This interface exists to pin a moving API
 * behind a stable shape, *not* to abstract over providers. A second implementation wrapping the raw
 * step sensor was considered and rejected as premature: M0 proved Health Connect counts steps
 * on-device with no sync chain, so the escape hatch stays unbuilt. If that ever changes, the swap is
 * cheap — which is the entire point of having the interface at all. **Do not add one for symmetry.**
 */
interface StepSource {

    /** Whether steps can be read at all right now. Re-checked rather than remembered. */
    suspend fun status(): StepSourceStatus

    /**
     * Per-origin step counts for [day], using the app's **04:00 day boundary**, not the calendar day.
     *
     * Returns one entry per reporting source. Grouping rather than summing is the origin guard, and
     * the decision about what a multi-origin day *means* belongs to `StepMapper`, not here: this
     * reports what the platform said, and the pure layer decides what to believe.
     *
     * Empty when nothing was recorded — which is not the same as zero steps.
     */
    suspend fun readDay(day: LocalDate): List<MeasuredOrigin>
}

/**
 * Whether steps are available, and if not, why.
 *
 * The distinction matters for what the user is shown. [PermissionMissing] is a choice they made and
 * can unmake; [Unavailable] is the platform's answer and nothing in the app can change it. Both hide
 * steps (spec §3.3), but only one of them is worth offering to fix.
 */
enum class StepSourceStatus {
    Available,
    PermissionMissing,
    Unavailable,
}

/**
 * The step source for a build that has none — every test, and any code path constructed without one.
 *
 * Reports [StepSourceStatus.Unavailable] rather than pretending to be available and returning
 * nothing, because those are different states with different consequences: unavailable **hides**
 * steps (spec §3.3), while available-but-empty would show a permanent "Not available yet" and file a
 * day with no value. Defaulting to the honest one means a repository built without a source behaves
 * like a device whose permission was declined, which is a real state worth exercising.
 */
object UnavailableStepSource : StepSource {
    override suspend fun status(): StepSourceStatus = StepSourceStatus.Unavailable
    override suspend fun readDay(day: LocalDate): List<MeasuredOrigin> = emptyList()
}
