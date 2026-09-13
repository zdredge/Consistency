package com.zdredge.consistency.fixture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.ui.theme.ConsistencyTheme

/**
 * The developer screen: every scenario, one tap each (build-order M9).
 *
 * A launcher entry of its own in the fixture build, and absent from every other build.
 */
class FixtureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConsistencyTheme {
                Surface(Modifier.fillMaxSize()) {
                    ScenarioList(Scenarios.all)
                }
            }
        }
    }
}

@Composable
private fun ScenarioList(scenarios: List<Scenario>) {
    Column(
        Modifier.safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Load scenario", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Replaces this install's whole history. The real Consistency app is a separate install " +
                "and is never touched.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (scenarios.isEmpty()) {
            Text("No scenarios yet.", style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scenarios, key = { it.id }) { scenario ->
                Column {
                    Text(scenario.id, style = MaterialTheme.typography.titleMedium)
                    Text(scenario.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
