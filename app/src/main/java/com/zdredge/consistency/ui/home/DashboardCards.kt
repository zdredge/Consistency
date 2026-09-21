package com.zdredge.consistency.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.ui.items.chart.ChartPalette
import com.zdredge.consistency.ui.theme.Accent

/**
 * The ring colours, chosen in the M10 review (round 2) and validated against the card surface
 * `#191C21`: every pair clear of the colour-blind floor (8.4 at worst) and of the normal-vision floor
 * (19.8). Blue keeps the app's own hue on the primary figure; amber and teal stay clear of the one
 * red the app reserves for "scrolled on phone".
 */
private val RingColours = listOf(Color(0xFF3987E5), Color(0xFFC98500), Color(0xFF199E70))

/** How long each goal holds on the carousel before the next (M10 review, round 6). */
private const val GoalMillis = 6_000L

@Composable
private fun DashboardCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/**
 * The score: three concentric rings -- response rate outermost and thickest -- with each figure's
 * percentage and count in a legend beside them, never inside a ring, where a single number reads as
 * *the* score (constraint 8). The longest run sits beneath.
 */
@Composable
internal fun ScoreCard(score: ScoreUi) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Rings(score.rings.map { it.fraction }, Modifier.size(150.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                score.rings.forEachIndexed { i, ring -> Legend(ring, RingColours[i]) }
            }
        }
        HorizontalDivider(color = ChartPalette.Grid)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Longest run, every check-in answered",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(score.longestRun, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun Rings(fractions: List<Double?>, modifier: Modifier) {
    // Inset from the edge and stroke width per ring, outer first, as drawn in the review.
    val spec = listOf(9.dp to 14.dp, 28.dp to 11.dp, 44.dp to 11.dp)
    val description = "Three rings: response rate, daily goals and weekly goals"
    Canvas(modifier.semantics { contentDescription = description }) {
        spec.forEachIndexed { i, (inset, width) ->
            val stroke = width.toPx()
            val r = size.minDimension / 2 - inset.toPx()
            val topLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(r * 2, r * 2)
            drawArc(ChartPalette.Grid, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val f = fractions.getOrNull(i) ?: return@forEachIndexed
            if (f > 0) {
                drawArc(
                    RingColours[i], -90f, (360f * f.toFloat()).coerceAtMost(359.9f), false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun Legend(ring: RingUi, colour: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(colour))
            Text(ring.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Text in ink, never in the ring's colour: the colour says which ring, the words say how much.
        Text(ring.value, style = MaterialTheme.typography.titleLarge)
        Text(ring.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A plain bar filled to [fraction], in the app blue unless told otherwise. */
@Composable
private fun Bar(fraction: Float, colour: Color = Accent, height: Dp = 8.dp) {
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(ChartPalette.Grid),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(height / 2)).background(colour))
    }
}

/** Before day 14 (§5.5): how far along, and when the figures arrive. */
@Composable
internal fun FirstRunCard(first: FirstRunUi) {
    DashboardCard {
        Text(first.title, style = MaterialTheme.typography.titleLarge)
        Text(first.answered, style = MaterialTheme.typography.bodyLarge)
        Bar(first.progress)
        Text(first.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Days 14-27: a trend needs two fortnights, so the panels wait with a date. */
@Composable
internal fun TrendsWaitingCard(waiting: TrendsWaitingUi) {
    DashboardCard {
        Text("Trends", style = MaterialTheme.typography.titleLarge)
        Text(waiting.title, style = MaterialTheme.typography.bodyLarge)
        Bar(waiting.progress)
        Text(waiting.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The two-tier carousel (M10 review, round 6): one goal at a time, every goal in a trend, trend by
 * trend -- Slipping first, because this is an accountability product and the bad news must not be
 * the thing the user waits for (§5.1).
 *
 * Story segments along the top, one per goal and grouped by trend; the current one fills over six
 * seconds in the app blue. A tap on either half steps back or forward and a swipe does the same.
 * **Any touch stops the rotation for good**, so nothing ever moves under a finger.
 *
 * The timer counts frames rather than running an animation, so a phone with animations switched off
 * does not spin through every goal at once.
 */
@Composable
internal fun TrendCarousel(pages: List<TrendPageUi>) {
    val slides = remember(pages) {
        pages.flatMapIndexed { p, page -> page.goals.indices.map { g -> p to g } }
    }
    if (slides.isEmpty()) return

    var index by rememberSaveable { mutableIntStateOf(0) }
    var auto by rememberSaveable { mutableStateOf(true) }
    var fill by remember { mutableFloatStateOf(0f) }
    val current = index.coerceIn(0, slides.lastIndex)

    LaunchedEffect(current, auto, slides.size) {
        if (!auto) {
            fill = 1f
            return@LaunchedEffect
        }
        fill = 0f
        val start = withFrameMillis { it }
        while (fill < 1f) {
            val now = withFrameMillis { it }
            fill = ((now - start).toFloat() / GoalMillis).coerceAtMost(1f)
        }
        index = (current + 1) % slides.size
    }

    fun step(forward: Boolean) {
        auto = false
        index = if (forward) (current + 1) % slides.size else (current - 1 + slides.size) % slides.size
    }

    val (pageIndex, goalIndex) = slides[current]
    val page = pages[pageIndex]
    val goal = page.goals[goalIndex]

    var dragged by remember { mutableFloatStateOf(0f) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(slides.size) {
                detectTapGestures { offset -> step(forward = offset.x > size.width / 2) }
            }
            .pointerInput(slides.size) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -48f) step(true) else if (dragged > 48f) step(false) },
                    onHorizontalDrag = { _, dx -> dragged += dx },
                )
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StorySegments(pages, pageIndex, goalIndex, fill)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(page.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${goalIndex + 1} of ${page.goals.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                page.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 0.dp),
            )
            GoalCard(goal)
        }
    }
}

@Composable
private fun StorySegments(pages: List<TrendPageUi>, pageIndex: Int, goalIndex: Int, fill: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        pages.forEachIndexed { p, page ->
            if (p > 0) Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(page.goals.size.toFloat()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                page.goals.indices.forEach { g ->
                    val amount = when {
                        p < pageIndex || (p == pageIndex && g < goalIndex) -> 1f
                        p == pageIndex && g == goalIndex -> fill
                        else -> 0f
                    }
                    Box(Modifier.weight(1f)) { Bar(amount, height = 3.dp) }
                }
            }
        }
    }
}

@Composable
private fun GoalCard(goal: GoalCardUi) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(goal.question, style = MaterialTheme.typography.bodyLarge)
            Text(goal.goal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Comparison("Fortnight before", goal.before, goal.beforeFraction, ChartPalette.Quiet)
        Comparison("Last 14 days", goal.now, goal.nowFraction, Accent)
        Column {
            Text(goal.met, style = MaterialTheme.typography.bodyMedium)
            goal.extra?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Comparison(label: String, count: String, fraction: Float, colour: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(count, style = MaterialTheme.typography.titleSmall)
        }
        Bar(fraction, colour, height = 12.dp)
    }
}
