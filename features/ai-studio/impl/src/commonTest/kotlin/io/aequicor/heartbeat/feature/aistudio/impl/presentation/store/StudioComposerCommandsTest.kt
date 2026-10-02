package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import kotlin.test.Test
import kotlin.test.assertEquals

class StudioComposerCommandsTest {
    @Test
    fun `remember action prefixes the draft once`() {
        assertEquals("/remember ", withRememberCommand(""))
        assertEquals("/remember use LF", withRememberCommand("use LF"))
        assertEquals("/remember use LF", withRememberCommand("/remember use LF"))
    }
}
