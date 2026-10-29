package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Рукопожатие handoff нативного лончера и приложения: приложение после первого кадра пишет `ready` и ждёт
 * `hidden`, которое лончер пишет только после DestroyWindow своего окна. Поэтому видимого второго окна и
 * второй иконки нет: окно приложения открывается уже после исчезновения окна лончера, в той же геометрии.
 * Таймаут не блокирует запуск: если лончер умер, приложение всё равно открывается.
 */
internal object DesktopHandoff {
    const val READY_FILE = "ready"
    const val HIDDEN_FILE = "hidden"
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
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(READY_FILE), "ready")
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!Files.exists(directory.resolve(HIDDEN_FILE)) && System.currentTimeMillis() < deadline) {
            delay(pollMillis)
        }
        visibility.openWindow()
    }
}

/** Запускает рукопожатие после первого кадра, когда лончер передал `HEARTBEAT_HANDOFF_DIR`; иначе ничего не делает. */
@Composable
internal fun HandoffEffect(visibility: DesktopWindowVisibility, ioDispatcher: CoroutineDispatcher) {
    val directory = DesktopHandoff.directory() ?: return
    LaunchedEffect(directory, visibility) {
        withContext(ioDispatcher) { DesktopHandoff.complete(directory, visibility) }
    }
}
