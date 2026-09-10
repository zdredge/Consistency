package com.zdredge.consistency

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.notify.CheckInAlarmScheduler
import com.zdredge.consistency.notify.Notifications
import com.zdredge.consistency.ui.checkin.CheckInScreen
import com.zdredge.consistency.ui.checkin.CheckInViewModel
import com.zdredge.consistency.ui.home.HomeScreen
import com.zdredge.consistency.ui.home.HomeViewModel
import com.zdredge.consistency.ui.theme.ConsistencyTheme
import kotlinx.coroutines.launch
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

    /**
     * Held on the Activity rather than in `remember`, because a notification tap has to be able to
     * change it from [onNewIntent] — which runs outside composition entirely.
     */
    private var screen by mutableStateOf<Screen>(Screen.Home)

    /** Whether notifications can actually be delivered. Re-read on resume, since it changes in system settings. */
    private var notificationsEnabled by mutableStateOf(true)

    /**
     * Asked once, and never nagged about again.
     *
     * The result is deliberately ignored: if it is denied, Home says so (see [notificationsEnabled])
     * rather than the app asking a second time. Architecture §4 is explicit that muting cannot be
     * engineered around, so the honest response is to report it, not to push.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
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

        Notifications.ensureChannel(this)
        askForNotificationsOnce()
        // A notification tap arrives as the launch Intent on a cold start, and through onNewIntent
        // when the app is already alive.
        screen = screenFor(intent) ?: Screen.Home

        setContent {
            ConsistencyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    when (val current = screen) {
                        Screen.Home -> {
                            val state by homeViewModel.state.collectAsState()

                            // Refreshes on every return, so answering a check-in removes it from the
                            // outstanding list without needing the screens to talk to each other.
                            // The reschedule no longer has to follow it for correctness -- the
                            // scheduler generates the rows it needs -- but the order still keeps
                            // what is on screen and what is armed derived from the same read.
                            LaunchedEffect(Unit) {
                                homeViewModel.refresh()
                                CheckInAlarmScheduler.reschedule(this@MainActivity)
                            }

                            HomeScreen(
                                state = state,
                                notificationsEnabled = notificationsEnabled,
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
                                checkInViewModel.exit.collect {
                                    // Leaving a check-in is the moment its prompts may have become
                                    // pointless. Rescheduling recomputes the window, so an answered
                                    // check-in simply drops out -- no separate cancel path to get
                                    // wrong. The banner itself is dismissed here.
                                    Notifications.cancel(this@MainActivity, current.day, current.slot)
                                    CheckInAlarmScheduler.reschedule(this@MainActivity)
                                    screen = Screen.Home
                                }
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

    /**
     * A tap on a prompt lands on the check-in it was about, not on Home.
     *
     * `launchMode="singleTop"` means an already-running app gets this rather than a second instance,
     * so both entry points are handled: the launch Intent above, and this.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenFor(intent)?.let { screen = it }
    }

    override fun onResume() {
        super.onResume()
        // The user can turn notifications off in system settings at any time, and the app is told
        // nothing. Re-reading on every resume is what keeps Home honest about it.
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        lifecycleScope.launch { CheckInAlarmScheduler.reschedule(this@MainActivity) }
    }

    /** The check-in a notification names, or null for an ordinary launch. */
    private fun screenFor(intent: Intent?): Screen? {
        if (intent?.action != Notifications.ACTION_OPEN_CHECK_IN) return null
        val day = intent.getStringExtra(Notifications.EXTRA_DAY)?.let(LocalDate::parse) ?: return null
        val slot = intent.getStringExtra(Notifications.EXTRA_SLOT)?.let(Slot::valueOf) ?: return null
        return Screen.CheckIn(day, slot)
    }

    private fun askForNotificationsOnce() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
