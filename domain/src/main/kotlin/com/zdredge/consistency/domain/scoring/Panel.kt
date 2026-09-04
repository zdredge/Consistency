package com.zdredge.consistency.domain.scoring

/**
 * Which dashboard panel an item belongs in, by hit rate. Thresholds from spec 5.2.
 *
 * Membership is automatic and not a user setting. The going-badly panel loads first on the dashboard
 * (spec 5.1): this is an accountability product, and the bad news must not be the thing the user
 * waits for.
 */
enum class Panel {
    GOING_WELL,
    MIDDLING,
    GOING_BADLY,
    ;

    companion object {
        const val WELL_THRESHOLD = 0.80
        const val MIDDLING_THRESHOLD = 0.60

        /**
         * Null for an item with no hit rate at all. Such an item belongs in **no** panel: it was
         * never scored, so dropping it into going-badly would blame it for a failure it never had
         * (10.5, 11.7). An empty panel is hidden rather than padded.
         */
        fun forHitRate(hitRate: Double?): Panel? = when {
            hitRate == null -> null
            hitRate >= WELL_THRESHOLD -> GOING_WELL
            hitRate >= MIDDLING_THRESHOLD -> MIDDLING
            else -> GOING_BADLY
        }
    }
}

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
    val closed: Boolean get() = elapsedDays >= totalDays

    /** How far through the target the observed figure is. Uncapped: overshooting mid-week is real. */
    val fractionOfTarget: Double? get() = if (target == 0.0) null else observed / target
}
