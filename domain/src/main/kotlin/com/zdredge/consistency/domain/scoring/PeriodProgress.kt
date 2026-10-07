package com.zdredge.consistency.domain.scoring

/**
 * A target's standing partway through a period that has not closed.
 *
 * Spec 5.3: weekly figures mid-week are shown as **progress against elapsed days**, not scored
 * against the full week. This type exists so an open period has something honest to display instead
 * of a verdict it has not earned.
 */
data class PeriodProgress(
    val observed: Double,
    val target: Double,
    val elapsedDays: Int,
    val totalDays: Int,
) {
    // There is deliberately no `closed` here. It read `elapsedDays >= totalDays`, which calls a week
    // closed for the whole of Sunday -- a day on which the week can still be changed. Whether a period
    // has closed is a question about today, not about a count of elapsed days, and it now has exactly
    // one home: `WeekFigure.closed`.

    /** How far through the target the observed figure is. Uncapped: overshooting mid-week is real. */
    val fractionOfTarget: Double? get() = if (target == 0.0) null else observed / target
}
