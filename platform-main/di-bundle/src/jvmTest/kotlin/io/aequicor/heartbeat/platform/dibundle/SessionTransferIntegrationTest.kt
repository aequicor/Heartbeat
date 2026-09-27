package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionEngineTransfer
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferIntent
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferMachineKey
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferOutput
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferState
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferFailure
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferRequest
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.TransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionTransferIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors
    private val request = TransferRequest(
        TransferId("transfer"),
        SessionRef(EngineId("codex"), SessionSourceId("codex-local"), "source-session"),
        EngineTarget(EngineId("claude"), EngineBindingId("binding"), ModelId("model")),
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `transfer toggle is registered and off by default`() = runTest {
        assertTrue(SessionEngineTransfer in toggles.toggleControl.registered)
        assertEquals(false, toggles.featureToggles.get(SessionEngineTransfer))
    }

    @Test
    fun `profile machine is created lazily and reachable through the registry`() = runTest {
        val session = app.profileSessions.open(ProfileId("transfer"))
        assertNull(app.machines.find(SessionTransferMachineKey))

        val machine = (session.graph as TestTransferAccessors).transferMachine

        assertSame(machine.state, app.machines.find(SessionTransferMachineKey)?.state)
        app.profileSessions.close()
        assertEquals(
            SendResult.NotRunning,
            app.machines.send(SessionTransferMachineKey, SessionTransferIntent.Public.Start(request)),
        )
    }

    @Test
    fun `disabled toggles finish the transfer without touching engines`() = runTest {
        val result = transfer()
        assertEquals(TransferResult.Failed(request.transfer, TransferFailure.Disabled), result)
    }

    @Test
    fun `without an installed facade an enabled transfer reports the engine as unavailable`() = runTest {
        toggles.toggleControl.setOverride(AiEngines, true)
        toggles.toggleControl.setOverride(SessionEngineTransfer, true)

        val result = transfer()

        val unavailable = TransferFailure.Engine(EngineFailure.Engine(EngineFailureReason.Unavailable))
        assertEquals(TransferResult.Failed(request.transfer, unavailable), result)
    }

    private suspend fun transfer(): TransferResult? {
        val session = app.profileSessions.open(ProfileId("transfer"))
        val machine = (session.graph as TestTransferAccessors).transferMachine
        assertEquals(
            SendResult.Accepted,
            app.machines.send(SessionTransferMachineKey, SessionTransferIntent.Public.Start(request)),
        )
        val finished = machine.state.first { it is SessionTransferState.Idle && it.last != null }
        return (finished as SessionTransferState.Idle).last
    }
}

/** Accessor instantiating the lazily created profile transfer machine. */
@ContributesTo(ProfileScope::class)
interface TestTransferAccessors {
    val transferMachine: Machine<SessionTransferState, SessionTransferIntent, SessionTransferOutput>
}

/** AiEngines is owned by the future facade implementation; tests register it to enable transfers. */
@ContributesTo(AppScope::class)
interface TestEngineToggleContribution {
    @Provides
    @IntoSet
    fun aiEngines(): FeatureToggle<*> = AiEngines
}
