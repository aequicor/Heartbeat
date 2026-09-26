package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RawLoggingCallTest {

    private val rule = RawLoggingCall(Config.empty)

    @Test
    fun `reports println, printStackTrace, System out and raw logger imports`() {
        val code = """
            package io.aequicor.heartbeat.feature.chat.impl

            import android.util.Log
            import io.github.aakira.napier.Napier

            fun load(e: Exception) {
                println("loaded")
                System.out.println("loaded")
                e.printStackTrace()
            }
        """.trimIndent()

        // 2 imports + println + System.out + printStackTrace
        assertEquals(5, rule.lint(code).size)
    }

    @Test
    fun `allows the facade and the core logging package`() {
        val facadeUser = """
            package io.aequicor.heartbeat.feature.chat.impl

            private val log = Log.tag("Chat")
            fun load() { log.d { "loaded" } }
        """.trimIndent()
        val facade = """
            package io.aequicor.heartbeat.core.logging

            import io.github.aakira.napier.Napier
            fun d(message: String) = Napier.d(message)
        """.trimIndent()

        assertEquals(0, rule.lint(facadeUser).size)
        assertEquals(0, rule.lint(facade).size)
    }
}
