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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.ui.theme.ConsistencyTheme
import java.time.LocalDate

/**
 * M1 placeholder. Not decoration: it renders the day resolved by :domain, which proves the
 * :app -> :domain wiring and shows the 04:00 boundary behaving on a real device.
 *
 * Everything real arrives later — the check-in screen is M4, the dashboard M10.
 */
class MainActivity : ComponentActivity() {

    private val container = AppContainer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConsistencyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SkeletonScreen(
                        today = container.dayResolver.today(),
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun SkeletonScreen(today: LocalDate, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Consistency", style = MaterialTheme.typography.titleLarge)
        Text("M1 skeleton", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Today, per DayResolver: $today",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "The day boundary is 04:00, so before 4am this still reads as yesterday.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
