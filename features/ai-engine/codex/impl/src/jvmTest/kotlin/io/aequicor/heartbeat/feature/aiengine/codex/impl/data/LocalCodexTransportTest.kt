package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalCodexTransportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing local CLI reports an installation failure instead of a network failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val transport = LocalCodexTransport(
            CodexLocalConfiguration(executable = File(temporaryFolder.root, "missing-codex.exe").absolutePath),
            EngineLaunchConfig.Default,
            dispatchers,
            FakeScope(backgroundScope),
        )
        val expected = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)

        assertEquals(expected, assertFailsWith<EngineException> { transport.open() }.failure)
        assertEquals(EngineAvailability.Unavailable(expected), transport.available())
    }
}
