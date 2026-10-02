package io.aequicor.heartbeat.core.desktopdialogs

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop binding of [DesktopFileDialogs]: serializes modal dialogs, starts them on the AWT event
 * thread to capture their owner, and logs only cancellation or the selection count.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class NativeDesktopFileDialogs(
    platform: PlatformInfo,
    private val dispatchers: DispatcherProvider,
    private val dialogs: NativeDialogs = nativeDialogsFor(platform.host),
) : DesktopFileDialogs {
    private val log = Log.tag("DesktopFileDialogs")
    private val dialogMutex = Mutex()

    override suspend fun pickDirectory(title: String?): String? =
        onEventThread("folder") { dialogs.pickDirectory(title) }

    override suspend fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String> =
        onEventThread("file") { dialogs.pickFiles(title, extensions, allowMultiple) }

    override suspend fun pickSaveLocation(title: String?, suggestedName: String?, extensions: List<String>): String? =
        onEventThread("save") { dialogs.pickSaveLocation(title, suggestedName, extensions) }

    /** Waiting callers may cancel; an already visible dialog finishes its native cleanup before returning. */
    private suspend fun <T> onEventThread(kind: String, dialog: suspend () -> T): T = dialogMutex.withLock {
        withContext(dispatchers.main) {
            log.i { "Open native $kind dialog" }
            val selection = try {
                dialog().also { currentCoroutineContext().ensureActive() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkageError) {
                log.e(error) { "Native $kind backend is unavailable" }
                throw error
            } catch (error: Exception) {
                log.e(error) { "Native $kind dialog failed" }
                throw error
            }
            log.d { "Native $kind dialog closed selection=${selection.describeSelection()}" }
            selection
        }
    }

    private fun Any?.describeSelection(): String = when (this) {
        null -> "cancelled"
        is List<*> -> if (isEmpty()) "cancelled" else "count=$size"
        else -> "count=1"
    }
}
