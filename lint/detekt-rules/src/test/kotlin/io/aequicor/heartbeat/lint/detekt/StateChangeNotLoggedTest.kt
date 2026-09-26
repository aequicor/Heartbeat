package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StateChangeNotLoggedTest {

    private val rule = StateChangeNotLogged(Config.empty)

    @Test
    fun `reports unlogged mutations of state holders`() {
        val code = """
            class SessionHolder {
                private val _session = MutableStateFlow<Session?>(null)
                private val events = MutableSharedFlow<Event>()
                var mode by mutableStateOf(Mode.Idle)
                    private set

                fun signOut() { _session.value = null }
                fun rename(name: String) { this._session.update { it?.copy(name = name) } }
                suspend fun notify(event: Event) { events.emit(event) }
                fun busy() { mode = Mode.Busy }
            }
        """.trimIndent()

        assertEquals(4, rule.lint(code).size)
    }

    @Test
    fun `accepts logged mutations, composables and non-holders`() {
        val code = """
            class SessionHolder {
                private val _session = MutableStateFlow<Session?>(null)
                private val counter = AtomicInt(0)

                fun signOut() {
                    log.i { "session: signed out" }
                    _session.value = null
                }
                fun tick() { counter.update { it + 1 } }
                fun read() = _session.value
            }

            @Composable
            fun Screen() {
                var expanded by remember { mutableStateOf(false) }
                expanded = true
            }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `skips infrastructure packages that log automatically`() {
        val code = """
            package io.aequicor.heartbeat.core.mvi

            class StoreImpl {
                private val state = MutableStateFlow(0)
                fun set(v: Int) { state.value = v }
            }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }
}
