package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteOpen
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemotePage
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFeatureSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToggleBridgeFeaturesTest {
    @Test
    fun `diagram capability follows renderer availability and requires a connected studio`() = runTest {
        val toggles = Toggles()
        val studio = Studio()
        val diagrams = MutableStateFlow(false)
        val features = ToggleBridgeFeatures(toggles, studio, diagrams)
        val received = mutableListOf<BridgeFeatureSet>()
        backgroundScope.launch { features.observe().collect(received::add) }
        runCurrent()
        assertFalse(received.last().isDiagrams)

        diagrams.value = true
        runCurrent()
        assertTrue(received.last().isDiagrams)

        studio.availability.value = false
        runCurrent()
        assertFalse(received.last().isDiagrams)

        studio.availability.value = true
        toggles.enabled.value = false
        runCurrent()
        assertFalse(received.last().isDiagrams)

        toggles.enabled.value = true
        runCurrent()
        assertTrue(received.last().isDiagrams)

        diagrams.value = false
        runCurrent()
        assertFalse(received.last().isDiagrams)
        assertFalse(ToggleBridgeFeatures(toggles, studio).current().isDiagrams)
    }

    private class Toggles : FeatureToggles {
        val enabled = MutableStateFlow(true)

        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = enabled.map { isEnabled ->
            @Suppress("UNCHECKED_CAST") // The only overridden toggle is the Boolean bridge-enabled flag.
            if (toggle == ChromiumBridgeEnabled) isEnabled as T else toggle.default
        }

        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }

    private class Studio : StudioRemoteScreens {
        override val availability = MutableStateFlow(true)

        override suspend fun open(
            viewId: String,
            schema: String,
            environment: StudioChatEnvironment?,
            owner: String,
        ): StudioRemoteOpen = error("unused")

        override suspend fun send(
            viewId: String,
            generation: String,
            intents: List<StudioRemoteIntent>,
            owner: String,
        ): StudioRemoteReply = error("unused")

        override suspend fun attachPage(
            viewId: String,
            generation: String,
            seq: Long,
            paneId: Int,
            page: StudioRemotePage,
            owner: String,
        ): StudioRemoteReply = error("unused")
    }
}
