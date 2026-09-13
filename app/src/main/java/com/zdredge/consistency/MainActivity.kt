package com.zdredge.consistency

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
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
import com.zdredge.consistency.export.ExportToDownloads
import com.zdredge.consistency.data.health.StepPermissions
import com.zdredge.consistency.data.health.StepSourceStatus
import com.zdredge.consistency.notify.CheckInAlarmScheduler
import com.zdredge.consistency.notify.Notifications
import com.zdredge.consistency.ui.checkin.CheckInScreen
import com.zdredge.consistency.ui.checkin.CheckInViewModel
import com.zdredge.consistency.ui.home.HomeScreen
import com.zdredge.consistency.ui.home.HomeViewModel
import com.zdredge.consistency.ui.BackStack
import com.zdredge.consistency.ui.NavigationViewModel
import com.zdredge.consistency.ui.Screen
import com.zdredge.consistency.ui.items.ItemDetailScreen
import com.zdredge.consistency.ui.items.ItemDetailViewModel
import com.zdredge.consistency.ui.items.ItemsScreen
import com.zdredge.consistency.ui.items.ItemsViewModel
import com.zdredge.consistency.ui.theme.ConsistencyTheme
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    private val itemsViewModel by lazy {
        ViewModelProvider(this, container.viewModelFactory)[ItemsViewModel::class.java]
    }
    private val itemDetailViewModel by lazy {
        ViewModelProvider(this, container.viewModelFactory)[ItemDetailViewModel::class.java]
    }

    /**
     * Where the user is, and how they got there.
     *
     * Held on the Activity rather than in `remember`, because a notification tap has to be able to
     * change it from [onNewIntent] — which runs outside composition entirely.
     *
     * It became a stack in M8: with Home, the item list and one item's history, leaving a screen is
     * no longer a question with one answer. See `ui/Navigation.kt` for why there is still no
     * navigation library, and why the stack lives in a `ViewModel` rather than in a field here — a
     * field is rebuilt on every rotation, which would send the user Home mid-check-in.
     */
    private val navigation by lazy {
        ViewModelProvider(this, container.viewModelFactory)[NavigationViewModel::class.java]
    }
    private val backStack: BackStack get() = navigation.backStack

    /** Whether notifications can actually be delivered. Re-read on resume, since it changes in system settings. */
    private var notificationsEnabled by mutableStateOf(true)

    /**
     * What the last export did, or null before one has been asked for.
     *
     * Held here rather than in `HomeViewModel` because writing the file needs a `Context` and a
     * `ContentResolver`, and pushing those into the ViewModel would put Android in the one place the
     * architecture keeps it out of. The counts come back from the snapshot itself, so the line the
     * user reads is evidence of what was written rather than a hopeful "Done".
     */
    private var exportStatus by mutableStateOf<String?>(null)

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
            // Only now, with the first dialog gone. Asking for both at once is how the steps
            // request was lost on the first real install.
            askForStepsOnce()
        }

    /**
     * Health Connect's own permission flow, which is not the ordinary runtime one.
     *
     * Nothing is stored from the result. Whether steps can be read is asked of `StepSource` at the
     * moment it matters, because the user can revoke this in system settings without the app being
     * told — the same reason M6 re-reads the notification setting on every resume rather than
     * remembering an answer. A declined result simply means the night check-in has no steps row
     * (spec §3.3); it is never downgraded to manual entry, which is permanently out of scope.
     */
    private val requestHealth =
        registerForActivityResult(StepPermissions.requestContract()) {
            // Deliberately empty. See above: the answer is re-read, never cached.
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
        // Steps are asked for *after* notifications resolves, never alongside it -- see
        // askForNotificationsOnce for what happens otherwise.
        askForNotificationsOnce()
        // A notification tap arrives as the launch Intent on a cold start, and through onNewIntent
        // when the app is already alive. Either way it lands on the check-in with Home beneath it,
        // so backing out of a prompt goes home rather than closing the app.
        //
        // Only on a genuinely new Activity. The launch Intent is still the notification's after a
        // rotation, so handling it again would drag the user back to that check-in from wherever
        // they had since navigated.
        if (savedInstanceState == null) screenFor(intent)?.let(backStack::openFromNotification)

        setContent {
            ConsistencyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    when (val current = backStack.current) {
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
                                exportStatus = exportStatus,
                                onExport = {
                                    exportStatus = "Exporting…"
                                    lifecycleScope.launch {
                                        val summary = ExportToDownloads.run(this@MainActivity)
                                        exportStatus = summary?.let {
                                            "Saved to Downloads — ${it.checkIns} check-ins, " +
                                                "${it.answers} answers, ${it.measuredValues} step days"
                                        } ?: "Export failed. Nothing was saved."
                                    }
                                },
                                onOpenCheckIn = { day, slot -> backStack.push(Screen.CheckIn(day, slot)) },
                                onOpenItems = { backStack.push(Screen.Items) },
                                modifier = Modifier.padding(padding),
                            )
                        }

                        is Screen.CheckIn -> {
                            val state by checkInViewModel.state.collectAsState()

                            // The system back button does what the Close button does: commits the
                            // question on screen and leaves without marking the check-in answered.
                            // Popping the stack directly would discard whatever was just typed.
                            BackHandler { checkInViewModel.close() }

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
                                    backStack.pop()
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

                        Screen.Items -> {
                            val state by itemsViewModel.state.collectAsState()

                            BackHandler { backStack.pop() }
                            LaunchedEffect(Unit) { itemsViewModel.refresh() }

                            ItemsScreen(
                                state = state,
                                // Kept in the ViewModel, so coming back from an item returns to
                                // where the list was rather than to the top of it.
                                listState = itemsViewModel.listState,
                                onOpenItem = { backStack.push(Screen.ItemDetail(it)) },
                                onBack = { backStack.pop() },
                                modifier = Modifier.padding(padding),
                            )
                        }

                        is Screen.ItemDetail -> {
                            val state by itemDetailViewModel.state.collectAsState()

                            BackHandler { backStack.pop() }
                            // Keyed on the screen, so opening a second item reloads rather than
                            // showing the first one's figures under the second one's name.
                            LaunchedEffect(current) { itemDetailViewModel.load(current.itemId) }

                            ItemDetailScreen(
                                state = state,
                                onBack = { backStack.pop() },
                                modifier = Modifier.padding(padding),
                                onFilter = itemDetailViewModel::setFilter,
                                onShowTrend = itemDetailViewModel::setShowTrend,
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
        screenFor(intent)?.let(backStack::openFromNotification)
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

        // **One permission dialog at a time.** Firing both on first launch loses the second: the
        // steps request was launched behind the notifications dialog and never reached the user,
        // who was recorded as having made no choice at all -- no USER_SET flag, not a denial. On the
        // real day-0 install that meant steps silently never collected, which is the whole of M7.
        if (granted) askForStepsOnce() else requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Asks for step access on first launch, and only when it could actually be granted.
     *
     * Asked once rather than on every open, matching notifications: a permission dialog that returns
     * every time the app starts is the friction spec §1 says ends the product. Declining is a real
     * answer and the app takes it — steps disappears from the check-in and nothing nags.
     */
    private fun askForStepsOnce() {
        lifecycleScope.launch {
            if (container.stepSource.status() == StepSourceStatus.PermissionMissing) {
                requestHealth.launch(StepPermissions.required)
            }
        }
    }
}
