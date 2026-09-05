package com.zdredge.consistency

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.ui.theme.ConsistencyTheme
import java.time.LocalDate

/**
 * Placeholder screen still, and still not decoration: it renders the day resolved by :domain and
 * the seeded item count read back through :data, which proves both wirings on a real device.
 *
 * Everything real arrives later — the check-in screen is M4, the dashboard M10.
 */
class MainActivity : ComponentActivity() {

    // Lazy, not a field initialiser: field initialisers run before the base context is attached,
    // so applicationContext would be null and the database could not be built.
    private val container by lazy { AppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var itemCount by remember { mutableStateOf<Int?>(null) }

            // First-run populate of the spec section 4 library (M3). A no-op on every later launch,
            // and guarded on the database being empty rather than on a stored flag, so a library the
            // user has edited or pruned is never quietly restored.
            LaunchedEffect(Unit) {
                container.repository.seedLibraryIfEmpty(container.dayResolver.today())
                itemCount = container.repository.items().size
            }

            ConsistencyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SkeletonScreen(
                        today = container.dayResolver.today(),
                        itemCount = itemCount,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun SkeletonScreen(today: LocalDate, itemCount: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Consistency", style = MaterialTheme.typography.titleLarge)
        Text("M3 skeleton", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Today, per DayResolver: $today",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "The day boundary is 04:00, so before 4am this still reads as yesterday.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            when (itemCount) {
                null -> "Library: loading..."
                else -> "Library: $itemCount items seeded from spec section 4"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
