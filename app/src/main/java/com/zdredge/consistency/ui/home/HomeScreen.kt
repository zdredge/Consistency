package com.zdredge.consistency.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM")

/**
 * The landing screen.
 *
 * **This is not the dashboard** — that is M10, with its three rings, panels and run. This screen
 * exists so a check-in can be reached, and it should not grow toward the dashboard in the meantime.
 *
 * The outstanding check-ins are listed here rather than blocking the way in. Spec §5.1 is explicit
 * that the banner must be persistent and *not* modal: trapping the user on open is what teaches them
 * not to open it. Backfilling is reached from here in one tap, and a check-in that will record as
 * a backfill says so before it is opened — the metric is not for sale, so the user should know.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenCheckIn: (LocalDate, Slot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
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
                OutstandingCheckInCard(checkIn, state.today, onOpenCheckIn)
            }
        }

        Text(
            "Library: ${state.itemCount} items",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun OutstandingCheckInCard(
    checkIn: CheckIn,
    today: LocalDate?,
    onOpen: (LocalDate, Slot) -> Unit,
) {
    // Yesterday's check-in is still answerable but will record as a backfill, and the record says so
    // permanently (spec §3.2). Saying it up front is not a warning, it is the honesty the product is
    // built on -- the metric is not for sale, and the user should know before they tap.
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
            if (isBackfill) {
                Text(
                    "Late — this will be recorded as backfilled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { onOpen(checkIn.day, checkIn.slot) }) { Text("Answer") }
        }
    }
}

