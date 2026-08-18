package com.snapaie.android.ui.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One transient message. [actionLabel] + [onAction] give the snackbar a single
 * tap target — the Android equivalent of the extension's inline "Undo" links.
 */
data class ToastEvent(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * App-wide transient feedback channel.
 *
 * The extension answered every action with a visible acknowledgement; snapaie
 * had no equivalent, so deletes, exports and copies happened silently. Screens
 * call `LocalSnapToast.current.show(...)` and the host in `SnapAieApp` renders
 * it, which keeps the snackbar above the navigation bar on every route.
 *
 * Backed by a buffered [Channel] so a burst of events queues instead of being
 * dropped, and each one is consumed exactly once.
 */
class SnapToastController {
    private val events = Channel<ToastEvent>(capacity = 8)

    val stream: Flow<ToastEvent> = events.receiveAsFlow()

    fun show(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        events.trySend(ToastEvent(message, actionLabel, onAction))
    }
}

val LocalSnapToast = staticCompositionLocalOf { SnapToastController() }

@Composable
fun rememberSnapToastController(): SnapToastController = remember { SnapToastController() }
