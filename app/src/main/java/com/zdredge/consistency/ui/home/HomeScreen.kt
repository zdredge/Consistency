package com.zdredge.consistency.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM")

/**
 * The landing screen, and since M10 the dashboard (spec §5.1): the outstanding banner, then the score
 * card and the trend carousel -- or, before there is enough history, how long until there is.
 *
 * The outstanding check-ins are listed here rather than blocking the way in. Spec §5.1 is explicit
 * that the banner must be persistent and *not* modal: trapping the user on open is what teaches them
 * not to open it. Backfilling is reached from here in one tap, and a check-in that will record as
 * a backfill says so before it is opened — the metric is not for sale, so the user should know.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    notificationsEnabled: Boolean,
    exportStatus: String?,
    onExport: () -> Unit,
    onOpenCheckIn: (LocalDate, Slot) -> Unit,
    onOpenItems: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Consistency", style = MaterialTheme.typography.headlineSmall)

        state.today?.let {
            Text(it.format(dayFormat), style = MaterialTheme.typography.bodyLarge)
        }

        if (state.loading) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        if (!notificationsEnabled) {
            // Muting cannot be engineered around and the app must not nag about it (spec §2,
            // architecture §4). So it is stated once, plainly, where the user will see it -- the
            // same posture as the stale-rollover line below. Without this, an accountability app
            // that has silently stopped asking looks identical to one with nothing to ask.
            Text(
                "Notifications are off. Check-ins won't prompt you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.rolloverOverdue) {
            // The one failure the product cannot otherwise report on itself. If the 04:00 job stops,
            // nothing crashes and nothing looks wrong -- check-ins simply stop being expected, and
            // every figure built on them drifts. Architecture §8 asks for it surfaced here rather
            // than only in logs, so this is stated plainly and not dressed up as an error.
            Text(
                "The daily update hasn't run recently. Figures may be out of date.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.outstanding.isEmpty()) {
            Text("Nothing outstanding.", style = MaterialTheme.typography.bodyMedium)
        } else {
            // Persistent, not modal, and it does not block anything below it (spec §5.1). Trapping
            // the user on open is what teaches them not to open it, so this states plainly what is
            // unanswered and then gets out of the way.
            Text(
                if (state.outstanding.size == 1) {
                    "1 check-in unanswered"
                } else {
                    "${state.outstanding.size} check-ins unanswered"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.outstanding.forEach { checkIn ->
                CheckInCard(checkIn, state.today, action = "Answer", onOpen = onOpenCheckIn)
            }
        }

        if (state.reviewable.isNotEmpty()) {
            // Answering one question marks a check-in answered and takes it off the banner above,
            // so this is the only way back to a question skipped inside a finished check-in while
            // it is still in grace (spec §3.2). The count is stated and nothing more: a skip is a
            // real answer, shown and never scolded.
            Text(
                "Answered, still open to changes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.reviewable.forEach { reviewable ->
                CheckInCard(
                    reviewable.checkIn,
                    state.today,
                    action = "Review",
                    detail = reviewable.unanswered.takeIf { it > 0 }?.let { "$it not answered" },
                    // Only what is filled in now is a backfill; answers already given keep the
                    // capture they were recorded with.
                    backfillNote = "Late — anything filled in now will be recorded as backfilled.",
                    onOpen = onOpenCheckIn,
                )
            }
        }

        state.dashboard?.let { dashboard ->
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                dashboard.firstRun?.let { FirstRunCard(it) }
                dashboard.score?.let { ScoreCard(it) }
                dashboard.trendsWaiting?.let { TrendsWaitingCard(it) }
                if (dashboard.trends.isNotEmpty()) TrendCarousel(dashboard.trends)
            }
        }

        // Every item's own screen, where the figures sit beside the chart and the full log.
        Button(onClick = onOpenItems, modifier = Modifier.padding(top = 20.dp)) {
            Text("Items and history")
        }

        Text(
            "Library: ${state.itemCount} items",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )

        // The only way data leaves this app. Plain and unglamorous on purpose -- it is not a feature
        // to be encouraged toward, it is the thing that means one lost phone is not the whole record.
        TextButton(onClick = onExport, modifier = Modifier.padding(top = 4.dp)) {
            Text("Export a copy to Downloads")
        }

        exportStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One check-in on Home: an outstanding one to answer, or an answered one still in grace to review.
 *
 * [detail] is a plain fact about the check-in -- how many questions were left unanswered -- in the
 * same quiet style as the backfill line, never an error colour.
 */
@Composable
private fun CheckInCard(
    checkIn: CheckIn,
    today: LocalDate?,
    action: String,
    onOpen: (LocalDate, Slot) -> Unit,
    detail: String? = null,
    backfillNote: String = "Late — this will be recorded as backfilled.",
) {
    // Yesterday's check-in is still answerable but will record as a backfill, and the record says so
    // permanently (spec §3.2). Saying it up front is not a warning, it is the honesty the product is
    // built on -- the metric is not for sale, and the user should know before they tap. It holds for
    // a reviewed check-in too: a skipped question filled in today is a backfill.
    val isBackfill = today != null && checkIn.day.isBefore(today)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (checkIn.slot) {
                    Slot.MORNING -> "Morning Check-in"
                    Slot.NIGHT -> "Nightly Check-in"
                    else -> "Check-in"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(checkIn.day.format(dayFormat), style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isBackfill) {
                Text(
                    backfillNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { onOpen(checkIn.day, checkIn.slot) }) { Text(action) }
        }
    }
}

