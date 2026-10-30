package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Рукопожатие handoff нативного лончера и приложения: приложение после первого кадра пишет `ready` и ждёт
 * `hidden`, которое лончер пишет только после DestroyWindow своего окна. Поэтому видимого второго окна и
 * второй иконки нет: окно приложения открывается уже после исчезновения окна лончера, в той же геометрии.
 * Таймаут не блокирует запуск: если лончер умер, приложение всё равно открывается.
 */
internal object DesktopHandoff {
    private val log = Log.tag("DesktopHandoff")
    const val READY_FILE = "ready"
    const val HIDDEN_FILE = "hidden"
    private const val PRESENTATION_FILE = "presentation.properties"
    private const val ACTIVATION_FILE = "user-activation"
    private const val MAX_PRESENTATION_BYTES = 1024
    private const val MAX_WINDOW_SIZE = 16384
    private const val MIN_WINDOW_SIZE = 64
    private const val MAX_COORDINATE = 100000
    private const val DEFAULT_TIMEOUT_MS = 3_000L
    private const val DEFAULT_POLL_MS = 50L

    /** Каталог рукопожатия от нативного лончера; null при обычном запуске (поведение не меняется). */
    fun directory(): Path? = System.getenv("HEARTBEAT_HANDOFF_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let(Path::of)

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

    private fun checkCancellation(directory: Path) {
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

/** Запускает рукопожатие после первого кадра, когда лончер передал `HEARTBEAT_HANDOFF_DIR`; иначе ничего не делает. */
@Composable
internal fun HandoffEffect(visibility: DesktopWindowVisibility, ioDispatcher: CoroutineDispatcher) {
    val directory = DesktopHandoff.directory() ?: return
    LaunchedEffect(directory, visibility) {
        val presentation = withContext(ioDispatcher) {
            DesktopHandoff.awaitHidden(directory)
            DesktopHandoff.presentation(directory)
        }
        visibility.showFromHandoff(presentation)
    }
}
