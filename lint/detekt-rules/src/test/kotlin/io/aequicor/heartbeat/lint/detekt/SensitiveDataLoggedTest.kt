package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SensitiveDataLoggedTest {

    private val rule = SensitiveDataLogged(Config.empty)

    @Test
    fun `reports secrets in log calls`() {
        val code = """
            fun a(apiKey: String) = log.d { "key=${'$'}apiKey" }
            fun b(config: Config) = log.i { "auth=${'$'}{config.authorization}" }
            fun c(refreshToken: String, token: String) = log.w { "tokens ${'$'}refreshToken ${'$'}token" }
            fun d(password: String) = logger.info(password)
        """.trimIndent()

        assertEquals(5, rule.lint(code).size)
    }

    @Test
    fun `accepts redacted, reduced and non-secret values`() {
        val code = """
            fun a(apiKey: String) = log.d { "key=${'$'}{Log.redact(apiKey)} len=${'$'}{apiKey.length}" }
            fun b(config: Config) = log.i { "blank=${'$'}{config.apiKey.isBlank()}" }
            fun c(maxTokens: Int, tokenCount: Int) = log.i { "llm maxTokens=${'$'}maxTokens used=${'$'}tokenCount" }
            fun d(apiKey: String) = store.save(apiKey)
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }
}
