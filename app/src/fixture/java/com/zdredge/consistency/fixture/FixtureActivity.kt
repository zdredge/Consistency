package com.zdredge.consistency.fixture

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.zdredge.consistency.MainActivity
import com.zdredge.consistency.ui.theme.ConsistencyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The developer screen: every scenario, one tap each (build-order M9).
 *
 * A launcher entry of its own in the fixture build, and absent from every other build. A tap
 * replaces this install's whole history and reopens the app on it.
 */
class FixtureActivity : ComponentActivity() {

    /** The scenario being loaded, or null when idle. One at a time. */
    private var loading by mutableStateOf<String?>(null)

    private var status by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConsistencyTheme {
                Surface(Modifier.fillMaxSize()) {
                    ScenarioList(Scenarios.all, loading, status, onLoad = ::load)
                }
            }
        }
    }

    private fun load(scenario: Scenario) {
        if (loading != null) return
        loading = scenario.id
        status = null
        val store = (application as FixtureApp).store

        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { store.load(scenario) } }
            loading = null
            result
                .onSuccess { loaded ->
                    Log.i(TAG, "loaded ${scenario.id}: $loaded")
                    // A fresh task, so no screen still holds the history from before the load.
                    startActivity(
                        Intent(this@FixtureActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                }
                .onFailure {
                    Log.e(TAG, "loading ${scenario.id} failed", it)
                    status = "Loading ${scenario.id} failed: ${it.message}"
                }
        }
    }

    private companion object {
        const val TAG = "Fixture"
    }
}

@Composable
private fun ScenarioList(
    scenarios: List<Scenario>,
    loading: String?,
    status: String?,
    onLoad: (Scenario) -> Unit,
) {
    Column(
        Modifier.safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Load scenario", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Replaces this install's whole history, then reopens it. The real Consistency app is a " +
                "separate install and is never touched.",
            style = MaterialTheme.typography.bodyMedium,
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(scenarios, key = { it.id }) { scenario ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = loading == null) { onLoad(scenario) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        if (loading == scenario.id) "${scenario.id} - loading..." else scenario.id,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(scenario.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
