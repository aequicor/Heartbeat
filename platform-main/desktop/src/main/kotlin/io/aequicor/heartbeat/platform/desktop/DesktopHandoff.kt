package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawWithContent
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/** Latest immutable presentation actually applied on the AWT event queue, captured before drawing a frame. */
internal class HandoffSession {
    var applied: HandoffPresentation? by mutableStateOf(null)
    var cover: WindowsHandoffCover? = null

    /** The painted snapshot owns the startup palette; afterwards the host resumes its forced/system preference. */
    fun darkTheme(initial: HandoffPresentation?, isFinished: Boolean, isForcedDark: Boolean?): Boolean? =
        if (isFinished) isForcedDark else applied?.isDark ?: initial?.isDark
}

/** Physical desktop coordinates, including negative monitor origins; never Compose dp or AWT logical pixels. */
internal data class HandoffBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** Snapshot refreshed by the launcher immediately before permitting presentation. */
internal data class HandoffPresentation(
    val bounds: HandoffBounds,
    val isDark: Boolean,
    val isMaximized: Boolean,
    val isMinimized: Boolean = false,
    val revision: Long = 0,
    val normalBounds: HandoffBounds = bounds,
    val nativeHandle: Long = 0,
    val nativePid: Int = 0,
    val isMoving: Boolean = false,
) {
    fun record(): String = "revision=$revision\nx=${bounds.x}\ny=${bounds.y}\nwidth=${bounds.width}\n" +
        "height=${bounds.height}\ndark=$isDark\nmaximized=$isMaximized\nminimized=$isMinimized\n" +
        "normalX=${normalBounds.x}\nnormalY=${normalBounds.y}\n" +
        "normalWidth=${normalBounds.width}\nnormalHeight=${normalBounds.height}\n" +
        "nativeHandle=$nativeHandle\nnativePid=$nativePid\nmoving=$isMoving\n"

    companion object {
        fun read(directory: Path): HandoffPresentation {
            val properties = Properties().apply {
                Files.newInputStream(directory.resolve("window.properties")).use(::load)
            }
            fun number(name: String) = requireNotNull(properties.getProperty(name)?.toIntOrNull()) { "Invalid $name" }
            val width = number("width")
            val height = number("height")
            require(width > 0 && height > 0) { "Invalid handoff window size" }
            val bounds = HandoffBounds(number("x"), number("y"), width, height)
            return HandoffPresentation(
                bounds,
                properties.getProperty("dark").toBooleanStrict(),
                properties.getProperty("maximized").toBooleanStrict(),
                properties.getProperty("minimized", "false").toBooleanStrict(),
                properties.getProperty("revision", "0").toLong(),
                HandoffBounds(
                    properties.getProperty("normalX", bounds.x.toString()).toInt(),
                    properties.getProperty("normalY", bounds.y.toString()).toInt(),
                    properties.getProperty("normalWidth", bounds.width.toString()).toInt(),
                    properties.getProperty("normalHeight", bounds.height.toString()).toInt(),
                ).also { require(it.width > 0 && it.height > 0) { "Invalid normal window size" } },
                properties.getProperty("nativeHandle", "0").toLong(),
                properties.getProperty("nativePid", "0").toInt(),
                properties.getProperty("moving", "false").toBooleanStrict(),
            )
        }
    }
}

/**
 * v2: prepared → show (latest native bounds) → presented → native window destruction.
 * The launcher covers the first visible Compose frame, without exposing the desktop during startup.
 * A dead launcher cannot leave the application permanently hidden.
 */
internal object DesktopHandoff {
    const val READY_FILE = "ready"
    const val SHOW_FILE = "show"
    const val PRESENTED_FILE = "presented"
    private const val DEFAULT_TIMEOUT_MS = 5_000L
    private const val DEFAULT_POLL_MS = 16L

    /** Каталог рукопожатия от нативного лончера; null при обычном запуске (поведение не меняется). */
    fun directory(): Path? = System.getenv("HEARTBEAT_HANDOFF_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let(Path::of)

    fun presentation(): HandoffPresentation? = directory()?.let { directory ->
        if (Files.exists(directory.resolve("window.properties"))) readPresentation(directory) else null
    }

    private fun readPresentation(directory: Path): HandoffPresentation? = try {
        HandoffPresentation.read(directory)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("DesktopHandoff").w(error) { "Cannot read native presentation; using desktop defaults" }
        null
    }

    /** Waits for the launcher's final snapshot; the callback runs even if the launcher is dead. */
    suspend fun complete(
        directory: Path,
        prepare: (HandoffPresentation?) -> Unit,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        pollMillis: Long = DEFAULT_POLL_MS,
    ) {
        checkCancellation(directory)
        Files.createDirectories(directory)
        val ready = directory.resolve("ready.tmp")
        Files.writeString(ready, "v2")
        Files.move(ready, directory.resolve(READY_FILE), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!Files.exists(directory.resolve(SHOW_FILE)) && System.nanoTime() < deadline) {
            checkCancellation(directory)
            delay(pollMillis)
        }
        checkCancellation(directory)
        val presentation = if (Files.exists(directory.resolve(SHOW_FILE))) readPresentation(directory) else null
        prepare(presentation)
    }

    /**
     * Native publishes the terminal marker before destroying its HWND. Read liveness before reading the marker:
     * a normal background exit must never take the unexpected-death path and activate the application.
     */
    fun completion(directory: Path, isNativeAlive: Boolean): Boolean? {
        checkCancellation(directory)
        return when {
            isNativeAlive -> null

            Files.exists(directory.resolve("failed")) -> false

            else -> {
                val hidden = directory.resolve("hidden")
                check(Files.exists(hidden)) { "Native launcher window exited without a handoff outcome" }
                Files.readString(hidden) == "activate"
            }
        }
    }

    private val log = Log.tag("DesktopHandoff")
    const val HIDDEN_FILE = "hidden"
    private const val PRESENTATION_FILE = "presentation.properties"
    private const val ACTIVATION_FILE = "user-activation"
    private const val MAX_PRESENTATION_BYTES = 1024
    private const val MAX_WINDOW_SIZE = 16384
    private const val MIN_WINDOW_SIZE = 64
    private const val MAX_COORDINATE = 100000

    /** Вызывается после первого кадра: пишет `ready`, ждёт `hidden` и открывает окно приложения. */
    suspend fun complete(
        directory: Path,
        visibility: DesktopWindowVisibility,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        pollMillis: Long = DEFAULT_POLL_MS,
    ) {
        awaitHidden(directory, timeoutMillis, pollMillis)
        visibility.showFromHandoff(presentation(directory))
    }

    /** The first JVM surface may be the Keychain introduction rather than the main application window. */
    suspend fun awaitHidden(
        directory: Path,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        pollMillis: Long = DEFAULT_POLL_MS,
    ) {
        checkCancellation(directory)
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(READY_FILE), "ready")
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!Files.exists(directory.resolve(HIDDEN_FILE)) && System.currentTimeMillis() < deadline) {
            checkCancellation(directory)
            delay(pollMillis)
        }
        checkCancellation(directory)
    }

    fun checkCancellation(directory: Path) {
        if (Files.exists(directory.resolve("cancel"))) throw CancellationException("Native startup cancelled")
    }

    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    data class Presentation(
        val isActivationRequested: Boolean = false,
        val isMinimized: Boolean = false,
        val isMaximized: Boolean = false,
        val bounds: Bounds? = null,
    )

    /**
     * Missing/invalid metadata never requests foreground activation.
     * Explicit user continuation overrides minimization.
     */
    fun presentation(directory: Path): Presentation {
        val isUserActivated = Files.exists(directory.resolve(ACTIVATION_FILE))
        val fallback = Presentation(isActivationRequested = isUserActivated)
        val file = directory.resolve(PRESENTATION_FILE)
        if (!Files.exists(directory.resolve(HIDDEN_FILE)) || !Files.exists(file)) return fallback
        return try {
            val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_PRESENTATION_BYTES + 1) }
            if (bytes.size > MAX_PRESENTATION_BYTES) {
                log.w { "Native window presentation exceeds its size limit" }
                fallback
            } else {
                val properties = Properties().apply { bytes.inputStream().use { load(it) } }
                Presentation(
                    isActivationRequested = isUserActivated || properties.getProperty("activate") == "true",
                    isMinimized = !isUserActivated && properties.getProperty("minimized") == "true",
                    isMaximized = properties.getProperty("maximized") == "true",
                    bounds = readBounds(properties),
                )
            }
        } catch (error: IOException) {
            log.w(error) { "Cannot read native window presentation" }
            fallback
        } catch (error: IllegalArgumentException) {
            log.w(error) { "Invalid native window presentation" }
            fallback
        }
    }

    private fun readBounds(properties: Properties): Bounds? {
        val values = listOf("x", "y", "width", "height").map { properties.getProperty(it)?.toIntOrNull() }
        if (values.any { it == null }) return null
        val numbers = values.filterNotNull().iterator()
        val bounds = Bounds(numbers.next(), numbers.next(), numbers.next(), numbers.next())
        val isPositionValid = bounds.x in -MAX_COORDINATE..MAX_COORDINATE && bounds.y in -MAX_COORDINATE..MAX_COORDINATE
        val isSizeValid = bounds.width in MIN_WINDOW_SIZE..MAX_WINDOW_SIZE &&
            bounds.height in MIN_WINDOW_SIZE..MAX_WINDOW_SIZE
        return if (isPositionValid && isSizeValid) bounds else null
    }

    /** Continue/Close is a new activation gesture, independent of the previous download window state. */
    fun requestActivation(directory: Path) {
        try {
            Files.writeString(directory.resolve(ACTIVATION_FILE), "true")
        } catch (error: IOException) {
            log.w(error) { "Cannot record the introduction continuation" }
        }
    }
}

/** Starts after the real ComposeWindow exists, rather than at application composition. */
@Composable
internal fun HandoffEffect(
    window: ComposeWindow,
    visibility: DesktopWindowVisibility,
    ioDispatcher: CoroutineDispatcher,
    session: HandoffSession,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
) {
    val directory = remember { DesktopHandoff.directory() } ?: return
    val currentFinish by rememberUpdatedState(onFinish)
    val currentCancel by rememberUpdatedState(onCancel)
    LaunchedEffect(window, visibility) {
        if (!Files.exists(
                directory.resolve("window.properties"),
            ) || Files.exists(directory.resolve(DesktopHandoff.HIDDEN_FILE))
        ) {
            val legacy = withContext(ioDispatcher) {
                DesktopHandoff.awaitHidden(directory)
                DesktopHandoff.presentation(directory)
            }
            visibility.showFromHandoff(legacy)
            currentFinish()
            return@LaunchedEffect
        }
        withContext(ioDispatcher) {
            performCoveredHandoff(window, visibility, directory, session) { currentCancel() }
        }
        currentFinish()
    }
}

private suspend fun performCoveredHandoff(
    window: ComposeWindow,
    visibility: DesktopWindowVisibility,
    directory: Path,
    session: HandoffSession,
    onCancel: () -> Unit,
) {
    var isActivationRequested = false
    try {
        EventQueue.invokeAndWait { session.cover = WindowsHandoffCover.create(window) }
        Files.writeString(
            directory.resolve("compose-window"),
            com.sun.jna.Pointer.nativeValue(com.sun.jna.Native.getWindowPointer(window)).toString(),
        )
        DesktopHandoff.complete(directory, prepare = { presentation ->
            EventQueue.invokeAndWait {
                visibility.showForHandoff()
                presentation?.let {
                    WindowsHandoffPresentation.apply(window, it)
                    session.applied = it
                }
            }
        })
        isActivationRequested = followHandoffPresentation(window, directory, session)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("DesktopHandoff").w(error) { "Handoff failed; opening the application without the cover" }
        EventQueue.invokeAndWait { visibility.openWindow() }
    } finally {
        val isStartupCancelled = Files.exists(directory.resolve("cancel"))
        EventQueue.invokeAndWait {
            if (isStartupCancelled) window.isVisible = false
            session.cover?.close()
            if (isStartupCancelled) {
                onCancel()
            } else if (isActivationRequested) {
                visibility.openWindow()
            }
        }
    }
}

/** Tracks user moves/resizes while Studio loads; each painted acknowledgement carries the matching revision. */
private suspend fun followHandoffPresentation(
    window: ComposeWindow,
    directory: Path,
    session: HandoffSession,
): Boolean {
    var previous: HandoffPresentation? = null
    var deadline = System.nanoTime() + FOLLOW_TIMEOUT_NANOS
    while (System.nanoTime() < deadline) {
        DesktopHandoff.checkCancellation(directory)
        val current = HandoffPresentation.read(directory)
        val isCompleteWithActivation = DesktopHandoff.completion(
            directory,
            WindowsHandoffPresentation.isNativeAlive(current),
        )
        if (isCompleteWithActivation != null) return isCompleteWithActivation
        if (current.isMinimized) deadline = System.nanoTime() + FOLLOW_TIMEOUT_NANOS
        if (current != previous) {
            EventQueue.invokeAndWait {
                session.cover?.hidePixels()
                WindowsHandoffPresentation.apply(window, current)
                session.applied = current
                window.contentPane.repaint()
            }
            previous = current
        }
        delay(FOLLOW_POLL_MILLIS)
    }
    return false
}

/** Acknowledges a painted frame after DWM composition, rather than acknowledging a layout or LaunchedEffect. */
internal fun Modifier.handoffFrame(
    window: ComposeWindow,
    session: HandoffSession,
    isContentReady: () -> Boolean,
): Modifier {
    val directory = DesktopHandoff.directory() ?: return this
    val frame = HandoffFrame(window, directory, session, isContentReady)
    return drawWithContent {
        val presentation = session.applied
        frame.redrawRevision
        drawContent()
        presentation?.let(frame::onDraw)
    }
}

private class HandoffFrame(
    private val window: ComposeWindow,
    private val directory: Path,
    private val session: HandoffSession,
    private val isContentReady: () -> Boolean,
) {
    private val pending = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    var redrawRevision by mutableIntStateOf(0)
        private set

    fun onDraw(presentation: HandoffPresentation) {
        if (!isContentReady() || !window.isShowing) return
        if (!completed.get() && pending.compareAndSet(false, true)) {
            EventQueue.invokeLater { acknowledge(presentation) }
        }
    }

    private fun acknowledge(painted: HandoffPresentation) {
        try {
            if (Files.exists(directory.resolve("hidden")) || Files.exists(directory.resolve("failed"))) {
                completed.set(true)
                return
            }
            val presentation = HandoffPresentation.read(directory)
            if (!isContentReady() || presentation != painted || presentation.isMoving) {
                session.cover?.hidePixels()
                return
            }
            val isAligned = WindowsHandoffPresentation.align(window, presentation)
            val isRevealed = isAligned && session.cover?.reveal(presentation.nativeHandle) != false
            if (isRevealed && session.cover?.unlayer() != true) {
                WindowsHandoffPresentation.flush()
                acknowledgeRevision(presentation)
            } else {
                // The unlayered surface is discarded by DWM, so the painted frame under the native cover is
                // reproduced once before the launcher may expose it.
                repaintForAcknowledgement()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            completed.set(true)
            Log.tag("DesktopHandoff").w(error) { "Cannot acknowledge the presented frame" }
            Files.writeString(directory.resolve("frame-error"), error.javaClass.simpleName)
        } finally {
            pending.set(false)
        }
    }

    /** The next painted frame is the one the launcher may expose; the pending acknowledgement waits for it. */
    private fun repaintForAcknowledgement() {
        redrawRevision++
        window.contentPane.repaint()
    }

    private fun acknowledgeRevision(presentation: HandoffPresentation) {
        val target = directory.resolve("presented.${presentation.revision}")
        if (Files.exists(target)) return
        val temporary = directory.resolve("presented.${presentation.revision}.tmp")
        Files.writeString(temporary, presentation.record())
        Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}

private const val FOLLOW_TIMEOUT_NANOS = 35_000_000_000L
private const val FOLLOW_POLL_MILLIS = 16L
