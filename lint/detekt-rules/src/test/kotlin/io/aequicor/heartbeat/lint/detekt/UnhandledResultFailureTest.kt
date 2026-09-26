package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class UnhandledResultFailureTest {

    private val rule = UnhandledResultFailure(Config.empty)

    @Test
    fun `reports discarded and collapsed results`() {
        val code = """
            fun a() { runCatching { cache.clear() } }
            fun b() { val user = runCatching { parse(raw) }.getOrNull() }
            fun c() { runCatching { parse(raw) }.map { it.id }.getOrDefault(0) }
            fun d() { runCatching { cache.clear() }.onSuccess { log.d { "ok" } } }
        """.trimIndent()

        assertEquals(4, rule.lint(code).size)
    }

    @Test
    fun `accepts handled or returned results`() {
        val code = """
            fun a() { runCatching { cache.clear() }.onFailure { log.w(it) { "clear failed" } } }
            fun b() = runCatching { parse(raw) }.onFailure { log.e(it) { "bad" } }.getOrNull()
            fun c(): Result<User> = runCatching { parse(raw) }
            fun d() = runCatching { parse(raw) }.getOrElse { log.e(it) { "bad" }; null }
            fun f() = runCatching { a() }.mapCatching { b(it) }.onFailure { log.e(it) { "x" } }
            val g = items.map { runCatching { parse(it) } }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }
}
