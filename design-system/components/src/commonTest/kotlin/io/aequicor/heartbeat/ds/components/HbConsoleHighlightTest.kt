package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbConsoleHighlightTest {
    @Test
    fun `anchored prompts status and log prefixes classify the complete line`() {
        val cases = mapOf(
            "$ ./gradlew check" to HbConsoleTone.Command,
            "PS C:\\workspace> .\\gradlew.bat check" to HbConsoleTone.Command,
            "> Task :app:compileKotlin" to HbConsoleTone.Info,
            "BUILD SUCCESSFUL in 2s" to HbConsoleTone.Success,
            "BUILD FAILED in 1s" to HbConsoleTone.Error,
            "FAILURE: Build failed with an exception." to HbConsoleTone.Error,
            "ERROR Missing file" to HbConsoleTone.Error,
            "error: cannot find symbol" to HbConsoleTone.Error,
            "WARN deprecated option" to HbConsoleTone.Warning,
            "warning: unused value" to HbConsoleTone.Warning,
            "INFO Ready" to HbConsoleTone.Info,
            "[INFO] Connected" to HbConsoleTone.Info,
            "\t  warn: indented log" to HbConsoleTone.Warning,
            "build successful" to HbConsoleTone.Success,
            "ps C:\\workspace> command" to HbConsoleTone.Command,
        )
        cases.forEach { (source, tone) ->
            assertEquals(listOf(HbConsoleSpan(0, source.length, tone)), highlightHbConsole(source), source)
        }
    }

    @Test
    fun `exit status parses signed and large numbers without matching embedded prose`() {
        listOf("exit code: 0", "EXIT CODE: +000 ", "exit code: -0").forEach { source ->
            assertEquals(HbConsoleTone.Success, highlightHbConsole(source).single().tone)
        }
        listOf("exit code: 1", "exit code: -1", "exit code: 999999999999999999999999999999").forEach { source ->
            assertEquals(HbConsoleTone.Error, highlightHbConsole(source).single().tone)
        }
        listOf("exit code:", "exit code: unknown", "exit code: 0 errors", "Process finished with exit code: 1")
            .forEach { assertTrue(highlightHbConsole(it).isEmpty(), it) }
    }

    @Test
    fun `ordinary prose and identifiers do not become error or status lines`() {
        val lines = listOf(
            "0 errors",
            "errorCount=0",
            "ERROR_COUNT=0",
            "WARNING_COUNT=0",
            "information only",
            "BUILD SUCCESSFULLY completed",
            "No ERROR was found",
            "The result was BUILD FAILED",
            "console > Task :check",
            "Price $ 4",
            "${'$'}variable",
            "PS C:\\work>no-space",
            "> TaskName",
            "\u001B[31mERROR: ANSI is not decoded",
            "",
            "\t  ",
        )
        lines.forEach { assertTrue(highlightHbConsole(it).isEmpty(), it) }
    }

    @Test
    fun `CRLF Unicode and the unterminated final line preserve source offsets and terminators`() {
        val first = "$ команда🙂"
        val second = "[INFO] готово e\u0301"
        val plain = "без подсветки"
        val last = "ERROR: ошибка"
        val source = "$first\r\n$second\r\n$plain\r\n$last"
        val secondStart = first.length + 2
        val lastStart = secondStart + second.length + 2 + plain.length + 2
        val spans = highlightHbConsole(source)
        assertEquals(
            listOf(
                HbConsoleSpan(0, first.length, HbConsoleTone.Command),
                HbConsoleSpan(secondStart, secondStart + second.length, HbConsoleTone.Info),
                HbConsoleSpan(lastStart, source.length, HbConsoleTone.Error),
            ),
            spans,
        )
        assertEquals(listOf(first, second, last), spans.map { source.substring(it.start, it.end) })
        assertTrue(spans.zipWithNext().all { (previous, next) -> previous.end <= next.start })
    }

    @Test
    fun `empty and mixed line endings produce no newline spans`() {
        val source = "\r\nINFO first\r\n\nWARN second\rBUILD FAILED\n"
        assertEquals(
            listOf("INFO first", "WARN second", "BUILD FAILED"),
            highlightHbConsole(source).map { source.substring(it.start, it.end) },
        )
        assertTrue(highlightHbConsole("").isEmpty())
        assertTrue(highlightHbConsole("\r\n\r\n").isEmpty())
    }
}
