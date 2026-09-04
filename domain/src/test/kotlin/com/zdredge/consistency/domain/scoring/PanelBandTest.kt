package com.zdredge.consistency.domain.scoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * docs/scoring-cases.md 9.1 to 9.4. Thresholds from spec 5.2: going well at 80% or above, middling
 * from 60% up to 80%, going badly below 60%. Panel membership is automatic by hit rate.
 */
@DisplayName("Panel bands - scoring-cases 9.1 to 9.4")
class PanelBandTest {

    @ParameterizedTest(name = "{0} - hit rate {1} is {2}")
    @CsvSource(
        "9.1, 0.80, GOING_WELL",
        "9.2, 0.79, MIDDLING",
        "9.3, 0.60, MIDDLING",
        "9.4, 0.59, GOING_BADLY",
    )
    @DisplayName("9.1-9.4 - the 80% and 60% boundaries are inclusive at the bottom of each band")
    fun bands(case: String, hitRate: Double, expected: Panel) {
        assertEquals(expected, Panel.forHitRate(hitRate), "case $case")
    }

    @Test
    @DisplayName("a perfect and a zero hit rate land in the outer bands")
    fun extremes() {
        assertEquals(Panel.GOING_WELL, Panel.forHitRate(1.0))
        assertEquals(Panel.GOING_BADLY, Panel.forHitRate(0.0))
    }

    @Test
    @DisplayName("an item with no hit rate belongs to no panel at all")
    fun noHitRateMeansNoPanel() {
        // Spec 5.1: an empty panel is hidden rather than padded, and an item that was never scored
        // must not be dropped into going-badly, which it never earned (10.5, 11.7).
        assertNull(Panel.forHitRate(null))
    }
}
