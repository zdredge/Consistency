package com.zdredge.consistency

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.ui.checkin.CheckInScreen
import com.zdredge.consistency.ui.checkin.CheckInViewModel
import com.zdredge.consistency.ui.home.HomeScreen
import com.zdredge.consistency.ui.home.HomeViewModel
import com.zdredge.consistency.ui.theme.ConsistencyTheme
import java.time.LocalDate

/**
 * Where the user lands, and the only place navigation is decided.
 *
 * **No navigation library.** Architecture T5 leaves the approach open, and at two screens a sealed
 * state switched with a `when` is the whole of it — the same posture as manual constructor
 * injection: adopt the framework once hand-rolling hurts. It has not yet. T5 gets revisited when the
 * item detail view and dashboard arrive and there are five screens with a real back stack.
 */
sealed interface Screen {
    data object Home : Screen
    data class CheckIn(val day: LocalDate, val slot: Slot) : Screen
}

class MainActivity : ComponentActivity() {

    // Lazy, not a field initialiser: field initialisers run before the base context is attached,
    // so applicationContext would be null and the database could not be built.
    private val container by lazy { AppContainer(applicationContext) }

    private val homeViewModel by lazy {
        ViewModelProvider(this, container.viewModelFactory)[HomeViewModel::class.java]
    }
    private val checkInViewModel by lazy {
        ViewModelProvider(this, container.viewModelFactory)[CheckInViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var screen by remember { mutableStateOf<Screen>(Screen.Home) }

            ConsistencyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    when (val current = screen) {
                        Screen.Home -> {
                            val state by homeViewModel.state.collectAsState()

                            // Refreshes on every return, so answering a check-in removes it from the
                            // outstanding list without needing the screens to talk to each other.
                            LaunchedEffect(Unit) { homeViewModel.refresh() }

                            HomeScreen(
                                state = state,
                                onOpenCheckIn = { day, slot -> screen = Screen.CheckIn(day, slot) },
                                modifier = Modifier.padding(padding),
                            )
                        }

                        is Screen.CheckIn -> {
                            val state by checkInViewModel.state.collectAsState()

                            LaunchedEffect(current) {
                                checkInViewModel.load(current.day, current.slot)
                            }
                            LaunchedEffect(state.submitted) {
                                if (state.submitted) screen = Screen.Home
                            }

                            CheckInScreen(
                                state = state,
                                onBool = checkInViewModel::setBool,
                                onNumber = checkInViewModel::setNumber,
                                onTime = checkInViewModel::setTime,
                                onScale = checkInViewModel::setScale,
                                onSelectOne = checkInViewModel::selectOne,
                                onToggle = checkInViewModel::toggleSelection,
                                onNote = checkInViewModel::setNote,
                                onDefer = checkInViewModel::toggleDeferred,
                                onSubmit = checkInViewModel::submit,
                                onBack = { screen = Screen.Home },
                                modifier = Modifier.padding(padding),
                            )
                        }
                    }
                }
            }
        }
    }
}
