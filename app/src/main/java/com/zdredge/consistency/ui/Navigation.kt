package com.zdredge.consistency.ui

import androidx.compose.runtime.mutableStateListOf
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate

/**
 * Every screen the app has.
 *
 * **Still no navigation library.** Architecture T5 left the approach open and M4.5 settled it for two
 * screens: a sealed state switched with a `when`. M8 is where the promised revisit falls due, because
 * there is now a real back stack — Home to the item list to one item, and back out the way you came.
 *
 * The revisit's answer is that hand-rolling still wins. Navigation-Compose would buy route strings,
 * argument bundles and a graph builder, and what it would replace is [BackStack] below: a list, three
 * methods, and no serialisation of an [ItemId] into a URL and back. The same posture as manual
 * constructor injection — adopt the framework when hand-rolling hurts, and it does not yet. What
 * would change that is deep links to an item, or a screen that needs to survive process death with
 * its argument intact; neither exists.
 */
sealed interface Screen {
    data object Home : Screen
    data class CheckIn(val day: LocalDate, val slot: Slot) : Screen

    /** Every item, as a list. The way in to the detail views. */
    data object Items : Screen

    /** One item's history: its figures, its table, and from Phase 4 its chart. */
    data class ItemDetail(val itemId: ItemId) : Screen
}

/**
 * Where the user is, and how they got there.
 *
 * Backed by a `mutableStateListOf` so composition reads it directly; held on the Activity rather
 * than in `remember`, because a notification tap arrives through `onNewIntent`, which runs outside
 * composition entirely.
 *
 * **A back stack is not the same as a current screen**, which is what this replaces. With one
 * variable, leaving the item detail meant deciding where to go — and the answer is different
 * depending on how you arrived. The stack simply remembers.
 */
class BackStack(initial: Screen = Screen.Home) {

    private val entries = mutableStateListOf(initial)

    val current: Screen get() = entries.last()

    val canGoBack: Boolean get() = entries.size > 1

    fun push(screen: Screen) {
        entries.add(screen)
    }

    /** Returns false when there is nowhere to go, so the caller can let the system handle it. */
    fun pop(): Boolean {
        if (!canGoBack) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /**
     * Lands on [screen] with Home beneath it — what a notification tap should do.
     *
     * Not a bare replacement: a prompt that dropped the user into a check-in with an empty stack
     * would make the system back button close the app rather than return to Home, which is not what
     * backing out of a check-in means.
     */
    fun openFromNotification(screen: Screen) {
        entries.clear()
        entries.add(Screen.Home)
        if (screen != Screen.Home) entries.add(screen)
    }
}

/**
 * The back stack, held where a screen turn cannot destroy it.
 *
 * **A `ViewModel` for one field, and it earns its keep.** The Activity is recreated on every
 * configuration change — a rotation, the system font size, entering split screen — and a stack built
 * in `onCreate` would start again at Home each time. That is the failure `AppContainer` already names:
 * losing a half-finished check-in to a screen turn is exactly the friction spec §1 says ends the
 * product. The check-in's own answers already survive here; until now the *pointer at the screen* did
 * not, so a rotation mid-check-in left its half-typed session intact in memory and unreachable.
 *
 * It survives configuration changes and not process death, which is the right pair. Restoring a
 * screen whose ViewModel session had been destroyed with the process would put the user back on a
 * check-in with every answer gone — a worse lie than starting at Home. Making it survive that too
 * means serialising a [Screen] and everything it holds, which is the cost this file declines to pay
 * for a navigation library.
 */
class NavigationViewModel : androidx.lifecycle.ViewModel() {
    val backStack = BackStack()
}
