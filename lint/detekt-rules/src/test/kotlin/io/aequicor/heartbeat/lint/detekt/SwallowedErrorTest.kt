package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SwallowedErrorTest {

    private val rule = SwallowedError(Config.empty)

    @Test
    fun `reports catch blocks that neither log nor propagate`() {
        val code = """
            fun a() { try { load() } catch (e: Exception) { } }
            fun b() { try { load() } catch (e: IOException) { showError() } }
            fun c() { try { load() } catch (_: IOException) { log.e { "failed" } } }
            fun d() { try { load() } catch (e: IOException) { log.d(e) { "failed" } } }
            fun f() { try { load() } catch (e: IOException) { log.e { "failed" } } }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(5, findings.size, findings.joinToString(" | ") { it.entity.signature + ": " + it.message })
    }

    @Test
    fun `accepts a log call without the throwable when the error reference is not required`() {
        val relaxed = SwallowedError(TestConfig("isErrorReferenceRequired" to false))
        val code = """
            fun a() { try { load() } catch (e: IOException) { log.e { "failed" } } }
            fun b() { try { load() } catch (_: IOException) { log.e { "failed" } } }
        """.trimIndent()

        assertEquals(0, relaxed.lint(code).size)
        assertEquals(2, rule.lint(code).size)
    }

    @Test
    fun `accepts logging with the throwable, rethrow, wrapping and propagation`() {
        val code = """
            fun a() { try { load() } catch (e: IOException) { log.e(e) { "failed" }; showError() } }
            fun b() { try { load() } catch (e: IOException) { Log.tag("X").w(e) { "fallback" } } }
            fun c() { try { load() } catch (e: IOException) { throw e } }
            fun d() { try { load() } catch (e: IOException) { throw LoadException("load", e) } }
            fun f(): Result<Unit> = try { Result.success(load()) } catch (e: IOException) { Result.failure(e) }
            suspend fun g() { try { load() } catch (e: CancellationException) { throw e } }
            fun h() { try { load() } catch (e: IOException) { chatLog.error("failed", e) } }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `reports lambda error handlers that ignore the error`() {
        val code = """
            fun a() = flow.catch { emit(Empty) }
            fun b() = result.onFailure { }
            fun c() = result.getOrElse { default }
            fun d() = result.fold({ it }, { null })
            val handler = CoroutineExceptionHandler { _, _ -> }
        """.trimIndent()

        assertEquals(5, rule.lint(code).size)
    }

    @Test
    fun `accepts lambda error handlers that log and ignores unrelated lambdas`() {
        val code = """
            fun a() = flow.catch { e -> log.w(e) { "fallback" }; emit(Empty) }
            fun b() = result.onFailure { log.e(it) { "failed" } }
            fun c() = result.fold(onSuccess = { it }, onFailure = { log.e(it) { "failed" }; null })
            val handler = CoroutineExceptionHandler { _, t -> log.e(t) { "uncaught" } }
            fun d() = list.getOrElse(0) { default }
            fun f() = list.fold(0) { acc, x -> acc + x }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }
}
