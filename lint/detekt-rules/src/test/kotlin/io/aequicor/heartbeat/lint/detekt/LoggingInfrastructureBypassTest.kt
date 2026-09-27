package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LoggingInfrastructureBypassTest {

    private val rule = LoggingInfrastructureBypass(Config.empty)

    @Test
    fun `reports raw primitives outside their core module`() {
        val code = """
            package io.aequicor.heartbeat.feature.chat.impl

            import pro.respawn.flowmvi.dsl.store

            val client = HttpClient(OkHttp)
            val prefs = PreferenceDataStoreFactory.createWithPath { path }
            val db = Room.databaseBuilder<ChatDb>(name = "chat")
            val machine = createStateMachine(scope) { }
        """.trimIndent()

        // import + HttpClient + PreferenceDataStoreFactory + databaseBuilder + createStateMachine
        assertEquals(5, rule.lint(code).size)
    }

    @Test
    fun `allows primitives inside their core module`() {
        val network = """
            package io.aequicor.heartbeat.core.network

            fun createClient() = HttpClient(engine) { install(Logging) }
        """.trimIndent()
        val mvi = """
            package io.aequicor.heartbeat.core.mvi.dsl

            import pro.respawn.flowmvi.dsl.store
        """.trimIndent()
        val storage = """
            package io.aequicor.heartbeat.core.datastore.impl

            val prefs = PreferenceDataStoreFactory.createWithPath { path }
            val db = Room.databaseBuilder(name = path, factory = factory)
        """.trimIndent()

        assertEquals(0, rule.lint(network).size)
        assertEquals(0, rule.lint(mvi).size)
        assertEquals(0, rule.lint(storage).size)
    }
}
