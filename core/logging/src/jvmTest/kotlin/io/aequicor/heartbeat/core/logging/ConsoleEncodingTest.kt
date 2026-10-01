package io.aequicor.heartbeat.core.logging

import io.github.aakira.napier.Napier
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleEncodingTest {

    @Test
    fun console_preserves_unicode_and_logs_once_after_reinitialization_with_a_windows_default_charset() {
        val classpath = listOf(
            ConsoleEncodingProbe::class.java,
            Log::class.java,
            Napier::class.java,
            Unit::class.java,
        ).map { Paths.get(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct()
            .joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=windows-1251",
            "-Duser.language=ru",
            "-Duser.country=RU",
            "-cp",
            classpath,
            ConsoleEncodingProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertEquals(0, process.waitFor(), output)
        listOf("[VERBOSE]", "[DEBUG]", "[INFO]", "[WARN]", "[ERROR]").forEach { level ->
            assertEquals(1, output.lineSequence().count { it.endsWith("$level Проверка - Привет, мир! Ёж 🦔") }, output)
        }
        assertTrue(output.contains("IllegalStateException: Ошибка соединения"), output)
        assertTrue(output.contains("Caused by: java.lang.IllegalArgumentException: Причина"), output)
        assertTrue(output.contains("Suppressed: java.lang.IllegalStateException: Дополнительная ошибка"), output)
        assertTrue(output.contains("[INFO] Проверка - Первая\\nВторая\\r\\nТретья"), output)
        val records = output.lineSequence().filter { " Проверка - " in it }.toList()
        assertEquals(6, records.size, output)
        assertTrue(records.all { it.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[\\w+\\] Проверка - .*")) }, output)
        assertFalse(output.contains('\uFFFD'), output)
        assertFalse(output.contains("performLog"), output)
    }
}

internal object ConsoleEncodingProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Charset.defaultCharset() == Charset.forName("windows-1251"))
        repeat(3) { Log.init(isDebug = true, isTrace = true) }
        val log = Log.tag("Проверка")
        val message = "Привет, мир! Ёж 🦔"
        log.v { message }
        log.d { message }
        log.i { message }
        log.w { message }
        val error = IllegalStateException("Ошибка соединения", IllegalArgumentException("Причина"))
        error.addSuppressed(IllegalStateException("Дополнительная ошибка"))
        log.e(error) { message }
        log.i { "Первая\nВторая\r\nТретья" }
    }
}
