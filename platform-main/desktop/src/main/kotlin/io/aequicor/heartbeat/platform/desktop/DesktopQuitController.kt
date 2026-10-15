package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Starts one shutdown and permits an update restart to promote an ordinary quit while cleanup is pending.
 * Restart is irreversible: a later tray or system quit cannot enable the child sweep again. The cleanup checks
 * the current mode only when each stage begins; the final sweep follows the application scope teardown attempt.
 * Atomic state also makes a request visible when cleanup resumes on another dispatcher.
 */
internal class DesktopQuitController(
    private val scope: CoroutineScope,
    private val processes: DesktopProcessCleanup,
    private val shutdown: suspend (DesktopProcessCleanup) -> Unit,
) {
    private val log = Log.tag("DesktopShutdown")
    private val mode = AtomicReference(Mode.Idle)

    val requests = DesktopQuitRequests(quit = { request(Mode.Quit) }, restart = { request(Mode.Restart) })

    private fun request(requested: Mode) {
        val previous = mode.getAndUpdate { current -> if (current == Mode.Restart) current else requested }
        if (previous == Mode.Idle) {
            log.i { "Application quit requested restart=${requested == Mode.Restart}" }
            scope.launch { shutdown(Cleanup()) }
        } else if (requested == Mode.Restart && previous != Mode.Restart) {
            log.i { "Pending application quit promoted to update restart" }
        }
    }

    private inner class Cleanup : DesktopProcessCleanup {
        override suspend fun capture() {
            if (mode.get() != Mode.Restart) processes.capture()
        }

        override suspend fun stop() {
            if (mode.get() != Mode.Restart) processes.stop()
        }
    }

    private enum class Mode { Idle, Quit, Restart }
}
