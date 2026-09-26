package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GenericExceptionCaughtTest {

    private val rule = GenericExceptionCaught(Config.empty)

    @Test
    fun `reports generic catches without a cancellation rethrow`() {
        val code = """
            fun a() { try { load() } catch (e: Exception) { log.e(e) { "x" } } }
            fun b() { try { load() } catch (e: Throwable) { log.e(e) { "x" } } }
            fun c() {
                try { load() } catch (e: CancellationException) { throw e } catch (e: Throwable) { log.e(e) { "x" } }
            }
            fun d() {
                try { load() } catch (e: CancellationException) { log.d { "x" } } catch (e: Exception) { log.e(e) { "x" } }
            }
            fun f() { try { load() } catch (e: Exception) { if (e !is CancellationException) throw e } }
            fun g() { try { load() } catch (e: RuntimeException) { log.e(e) { "x" } } }
        """.trimIndent()

        assertEquals(6, rule.lint(code).size)
    }

    @Test
    fun `allows Exception after rethrowing cancellation and specific exceptions`() {
        val code = """
            suspend fun a() {
                try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.e(e) { "x" } }
            }
            suspend fun b() {
                try { load() } catch (e: Exception) { if (e is CancellationException) throw e; log.e(e) { "x" } }
            }
            fun c() { try { load() } catch (e: IOException) { log.e(e) { "x" } } }
            fun d() {
                try { load() } catch (e: CancellationException) { throw e } catch (e: IOException) { log.e(e) { "x" } }
            }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }
}
