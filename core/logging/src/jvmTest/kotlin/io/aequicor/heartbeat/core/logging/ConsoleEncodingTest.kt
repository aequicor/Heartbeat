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
    fun console_preserves_unicode_with_a_windows_default_charset() {
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
            assertTrue(output.contains("$level Проверка - Привет, мир! Ёж 🦔"), output)
        }
        assertTrue(output.contains("IllegalStateException: Ошибка соединения"), output)
        assertFalse(output.contains('\uFFFD'), output)
    }
}

internal object ConsoleEncodingProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Charset.defaultCharset() == Charset.forName("windows-1251"))
        Log.init(isDebug = true)
        val log = Log.tag("Проверка")
        val message = "Привет, мир! Ёж 🦔"
        log.v { message }
        log.d { message }
        log.i { message }
        log.w { message }
        log.e(IllegalStateException("Ошибка соединения")) { message }
    }
}
