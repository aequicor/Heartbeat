package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CancellationSwallowedTest {

    private val rule = CancellationSwallowed(Config.empty)

    @Test
    fun `reports swallowed cancellation`() {
        val code = """
            suspend fun a() { try { load() } catch (e: CancellationException) { log.d { "cancelled" } } }
            suspend fun b() { try { load() } catch (e: Exception) { log.e(e) { "failed" } } }
            fun c(scope: CoroutineScope) = scope.launch { try { load() } catch (e: Throwable) { log.e(e) { "x" } } }
            suspend fun d() = runCatching { load() }.onFailure { log.e(it) { "failed" } }
        """.trimIndent()

        assertEquals(4, rule.lint(code).size)
    }

    @Test
    fun `accepts rethrow, ensureActive and non-coroutine code`() {
        val code = """
            suspend fun a() {
                try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.e(e) { "x" } }
            }
            suspend fun b() {
                try { load() } catch (e: Exception) { if (e is CancellationException) throw e; log.e(e) { "x" } }
            }
            suspend fun c() { try { load() } catch (e: Exception) { currentCoroutineContext().ensureActive() } }
            suspend fun d() { try { load() } catch (e: IOException) { log.e(e) { "x" } } }
            fun f() { try { load() } catch (e: Exception) { log.e(e) { "x" } } }
            fun g() = runCatching { parse() }.onFailure { log.e(it) { "x" } }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }
}
