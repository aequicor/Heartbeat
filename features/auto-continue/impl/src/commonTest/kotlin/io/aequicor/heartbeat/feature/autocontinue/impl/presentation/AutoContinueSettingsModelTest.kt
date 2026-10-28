package io.aequicor.heartbeat.feature.autocontinue.impl.presentation

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoContinueSettingsModelTest {
    private val fallback = EngineTarget(EngineId("pi"), EngineBindingId("b"), ModelId("small"))
    private val other = fallback.copy(model = ModelId("other"))
    private val settings = AutoContinueSettings()

    @Test
    fun `changes keep the settings within their limits`() {
        assertEquals(
            AutoContinueMode.Suggest,
            settings.changed(AutoContinueSettingsIntent.SetDefaultMode(DefaultModeUi.Suggest), null).defaultMode,
        )
        assertEquals(20, settings.changed(AutoContinueSettingsIntent.SetAutoTurnLimit(20), null).autoTurnLimit)
        assertEquals(0, settings.changed(AutoContinueSettingsIntent.SetCountdown(0), null).countdownSeconds)
        assertEquals(1, settings.changed(AutoContinueSettingsIntent.SetOptionCount(1), null).optionCount)
        assertEquals(settings, settings.changed(AutoContinueSettingsIntent.SetOptionCount(9), null))
        assertEquals(settings, settings.changed(AutoContinueSettingsIntent.SetCountdown(-1), null))
    }

    @Test
    fun `the continuer is the chat model or the profile default`() {
        val toDefault = settings.changed(AutoContinueSettingsIntent.SetContinuer(ContinuerUi.ProfileDefault), fallback)
        assertEquals(fallback, toDefault.continuerTarget)
        assertNull(
            toDefault.changed(AutoContinueSettingsIntent.SetContinuer(ContinuerUi.Chat), fallback).continuerTarget,
        )
        assertEquals(
            settings,
            settings.changed(AutoContinueSettingsIntent.SetContinuer(ContinuerUi.ProfileDefault), null),
        )
        val custom = settings.copy(continuerTarget = other)
        assertEquals(custom, custom.changed(AutoContinueSettingsIntent.SetContinuer(ContinuerUi.Custom), fallback))
    }

    @Test
    fun `the screen shows the continuer by its model`() {
        val state = AutoContinueSettingsScreenState(settings.copy(continuerTarget = other), profileDefault = fallback)
        assertEquals(ContinuerUi.Custom, state.continuer)
        assertEquals("other", state.customModel)
        assertEquals("small", state.profileDefaultModel)
        assertEquals(
            ContinuerUi.ProfileDefault,
            state.copy(settings = settings.copy(continuerTarget = fallback)).continuer,
        )
        assertEquals(ContinuerUi.Chat, AutoContinueSettingsScreenState().continuer)
        assertEquals(DefaultModeUi.Off, AutoContinueSettingsScreenState().defaultMode)
    }
}
