package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Coalesces repeated close gestures while native callbacks still need the window's event loop. */
@Composable
internal fun rememberDesktopCloseHandler(
    participants: Set<AppShutdownParticipant>,
    destroy: () -> Unit,
    exit: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    var isClosing by remember { mutableStateOf(false) }
    val log = remember { Log.tag("DesktopShutdown") }
    return {
        if (!isClosing) {
            isClosing = true
            scope.launch {
                try {
                    log.i { "Close window after native resources finish" }
                    shutdownDesktop(participants, destroy, exit)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error) { "Native shutdown failed; keep the window open for retry" }
                    isClosing = false
                }
            }
        }
    }
}

/** Keeps the event loop alive for native callbacks until every participant has finished flushing. */
internal suspend fun shutdownDesktop(
    participants: Set<AppShutdownParticipant>,
    destroy: () -> Unit,
    exit: () -> Unit,
) {
    participants.forEach { it.shutdown() }
    destroy()
    exit()
}
