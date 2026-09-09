package com.zdredge.consistency

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
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

    // The process-wide graph, from ConsistencyApp. It used to be built here, which was fine while
    // every write started with a tap; the rollover job runs with no Activity alive and needs the
    // same repository, so the container moved up rather than being built twice.
    private val container by lazy { applicationContext.container }

    private val homeViewModel by lazy {
        ViewModelProvider(this, container.viewModelFactory)[HomeViewModel::class.java]
    }
    private val checkInViewModel by lazy {
        ViewModelProvider(this, container.viewModelFactory)[CheckInViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Forced dark, not system-following. The app has one scheme (M4.5), so letting the
        // system bars follow the device theme would put dark status icons on a dark ground the
        // moment the phone is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

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
                            // Collect, not observe. The exit is an event delivered once (see
                            // CheckInViewModel.exit); a Boolean here was defect 1, because a field
                            // set on the way out is still set on the way back in.
                            LaunchedEffect(Unit) {
                                checkInViewModel.exit.collect { screen = Screen.Home }
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
                                onSelectNone = checkInViewModel::selectNone,
                                onNext = checkInViewModel::next,
                                onBack = checkInViewModel::back,
                                onFinish = checkInViewModel::finish,
                                onEdit = checkInViewModel::editFromSummary,
                                onReturnToSummary = checkInViewModel::returnToSummary,
                                onConfirm = checkInViewModel::confirm,
                                onLeave = checkInViewModel::close,
                                modifier = Modifier.padding(padding),
                            )
                        }
                    }
                }
            }
        }
    }
}
