package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.REMEMBER_COMMAND
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
import io.aequicor.heartbeat.feature.organicai.api.CellId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The screen model keeps literals of domain values so it builds without the domain; they must not drift. */
class ScreenModelLiteralsTest {
    @Test
    fun `screen literals equal the domain values they mirror`() {
        assertEquals(DefaultRunSettings.toUi(), DefaultSettingsUi)
        assertEquals(CellId.ZYGOTE.value, PRIMARY_SUB_SESSION)
        assertEquals(REMEMBER_COMMAND, COMPOSER_REMEMBER_COMMAND)
    }

    @Test
    fun `the demo models are marked scripted`() {
        assertTrue(StudioModelOptions.all { it.isScripted })
    }
}
