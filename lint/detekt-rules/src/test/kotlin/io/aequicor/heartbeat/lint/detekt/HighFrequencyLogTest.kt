package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HighFrequencyLogTest {

    private val rule = HighFrequencyLog(Config.empty)

    @Test
    fun `reports debug and info in frequent handlers including nested scopes`() {
        val code = """
            @HighFrequency
            fun publish() {
                log.d { "Codex history revision advanced" }
                this.log.i(message = { "revision" })
                mutex.withLock { historyLogger.debug { "delta" } }
                fun helper() { Log.tag("History").info { "delta" } }
            }
        """.trimIndent()

        assertEquals(4, rule.lint(code).size)
    }

    @Test
    fun `allows trace and real failures without changing their severity`() {
        val code = """
            @HighFrequency
            fun publish(error: Exception) {
                log.v { "revision" }
                log.w(error) { "journal overflow" }
                log.e(error) { "journal failed" }
            }
            fun finish() {
                log.d { "stream finished" }
                log.i { "request completed" }
            }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `checks accessors and fully qualified annotations`() {
        val code = """
            var progress = 0
                @io.aequicor.heartbeat.core.logging.HighFrequency
                get() { logger?.d { "read" }; return field }
                @HighFrequency
                set(value) { log.i { "write" }; field = value }
        """.trimIndent()

        assertEquals(2, rule.lint(code).size)
    }

    @Test
    fun `property use site annotations check only their matching accessor`() {
        val code = """
            @get:HighFrequency
            var revision = run { log.d { "initial value" }; 0 }
                get() { log.d { "read" }; return field }
                set(value) { log.i { "write" }; field = value }
            @set:io.aequicor.heartbeat.core.logging.HighFrequency
            var progress = run { log.i { "initial value" }; 0 }
                get() { log.d { "read" }; return field }
                set(value) { log.i { "write" }; field = value }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(2, findings.size)
        assertEquals(
            listOf("log.d { \"read\" }", "log.i { \"write\" }"),
            findings.map { it.entity.ktElement.parent.text },
        )
    }

    @Test
    fun `checks retryable transforms with trailing and named lambdas`() {
        val code = """
            fun update() {
                state.update { log.d { "retry" }; it + 1 }
                state.getAndUpdate(function = { log.i { "retry" }; it + 1 })
                state.updateAndGet { nested { log.d { "retry" } }; it + 1 }
                state.update { log.v { "trace" }; it + 1 }
                state.update { log.w(error) { "failed" }; it }
            }
        """.trimIndent()

        assertEquals(3, rule.lint(code).size)
    }

    @Test
    fun `does not classify every loop or flow as frequent business events`() {
        val code = """
            fun observe() {
                events.collect { log.i { "user action" } }
                batch.forEach { log.d { "operation" } }
                state.update { it + 1 }
                log.d { "finished" }
            }
            @HighFrequency
            fun render() { canvas.d(); service.info() }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `supports project specific handlers and logger names`() {
        val configured = HighFrequencyLog(
            TestConfig(
                "highFrequencyAnnotations" to listOf("PerFrame"),
                "repeatedCalls" to listOf("onDelta"),
                "loggerReceiverPattern" to "^diagnostics$",
            ),
        )
        val code = """
            @PerFrame
            fun render() { diagnostics.d { "frame" } }
            fun observe() {
                stream.onDelta { diagnostics.i { "delta" } }
                state.update { diagnostics.d { "not configured" } }
            }
        """.trimIndent()

        assertEquals(2, configured.lint(code).size)
    }
}
