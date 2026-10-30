package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.platform.dibundle.desktopStorageRoot
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Метки времени запуска приложения: собственная реализация общего протокола лаунчера, потому что классы лаунчера
 * намеренно не видны приложению (изолированный загрузчик слота, на classpath образа — только selector).
 * Формат общий: строка `{"launch":"<pid>@<старт JVM>","stage":"<этап>","t":<мс от старта JVM>,"wall":<эпоха, мс>}`
 * в `<каталог данных>/logs/launch-timeline-<launch>.jsonl`; метки селектора, лаунчера и приложения одной JVM
 * собираются в один файл по `launch`. Отключение: `-Dheartbeat.launch.timeline=off`, каталог для проверок:
 * `-Dheartbeat.launch.timeline.dir=<каталог>`. Удержание файлов — пять суток. Сбой записи логируется один раз
 * и не влияет на запуск.
 */
internal object LaunchMarks {
    private const val TIMELINE_PROPERTY = "heartbeat.launch.timeline"
    private const val DIR_PROPERTY = "heartbeat.launch.timeline.dir"
    private const val ESCAPE_PADDING = 8
    private val retentionMillis: Long = Duration.ofDays(5).toMillis()
    private val broken = AtomicBoolean()

    /** Записывает метку этапа; при выключенном или сломанном протоколе ничего не делает. */
    fun mark(stage: String) {
        val isEnabled = !"off".equals(System.getProperty(TIMELINE_PROPERTY, "on"), ignoreCase = true)
        if (broken.get() || !isEnabled) return
        runCatching {
            val dir = resolveDir().resolve("logs")
            Files.createDirectories(dir)
            val launch = launchId()
            val file = dir.resolve("launch-timeline-$launch.jsonl")
            if (!Files.exists(file)) prune(dir)
            val elapsed = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime
            val wall = System.currentTimeMillis()
            val line = """{"launch":"$launch","stage":"${escape(stage)}","t":$elapsed,"wall":$wall}"""
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.onFailure { error ->
            if (broken.compareAndSet(false, true)) {
                Log.tag("Desktop").w(error) { "Launch timeline is off after a write failure" }
            }
        }
    }

    /** Каталог данных: свойство для проверок, иначе общий корень хранилища desktop. */
    internal fun resolveDir(): Path {
        System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        return Path.of(desktopStorageRoot())
    }

    internal fun launchId(): String =
        "${ProcessHandle.current().pid()}@${ManagementFactory.getRuntimeMXBean().startTime}"

    private fun prune(dir: Path) {
        runCatching {
            val now = System.currentTimeMillis()
            Files.list(dir).use { stream ->
                stream.filter {
                    it.fileName.toString().startsWith("launch-timeline-") && it.fileName.toString().endsWith(".jsonl")
                }.forEach { path ->
                    runCatching {
                        if (Files.getLastModifiedTime(path).toMillis() < now - retentionMillis) Files.delete(path)
                    }.onFailure { error ->
                        if (broken.compareAndSet(false, true)) {
                            Log.tag("Desktop").w(error) { "Launch timeline prune failed" }
                        }
                    }
                }
            }
        }.onFailure { error ->
            if (broken.compareAndSet(false, true)) {
                Log.tag("Desktop").w(error) { "Launch timeline prune failed" }
            }
        }
    }

    internal fun escape(value: String): String = buildString(value.length + ESCAPE_PADDING) {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(java.util.Locale.ROOT, c.code)) else append(c)
            }
        }
    }
}
