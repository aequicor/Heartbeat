package io.aequicor.heartbeat.feature.harness.impl.di

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.ProfileWorkflowDrivers
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperGrant
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperJournal
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessWorkflowStartupTest {
    @Test
    fun `unreadable grant journal drains conservatively and keeps observing the toggle`() = runTest {
        val runs = StartupRuns()
        val toggles = StartupToggles()
        val profile = object : ScopeHandle {
            override val name = "test"
            override val coroutineScope = backgroundScope
            override val isClosed = false
            override val savedState: ScopeSavedState get() = error("Not used")
            override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
        }
        HarnessWorkflowStartup(
            lazy { runs },
            lazy { StartupLibrary() },
            toggles,
            lazy { CorruptGrants },
            lazy<ProfileWorkflowDrivers> { error("No running record is restored") },
            profile,
        ).start()
        runCurrent()
        assertEquals(
            listOf<HarnessRunsIntent>(HarnessRunsIntent.Internal.Suspended, HarnessRunsIntent.Internal.Start),
            runs.sent,
        )
        toggles.enabled.value = true
        runCurrent()
        assertEquals<HarnessRunsIntent>(HarnessRunsIntent.Internal.Resumed, runs.sent[2])
    }
}

private object CorruptGrants : WorkflowHelperJournal {
    override suspend fun granted(grant: WorkflowHelperGrant) = error("Not used")
    override suspend fun bind(reservation: ActionId, helper: HelperId): WorkflowHelperGrant = error("Not used")
    override suspend fun pending(): List<WorkflowHelperGrant> = throw HarnessStorageCorrupt()
    override suspend fun settled(grant: WorkflowHelperGrant) = error("Not used")
}

private class StartupToggles : FeatureToggles {
    val enabled = MutableStateFlow(false)

    @Suppress("UNCHECKED_CAST") // Only the boolean harness flag is read.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        check(toggle == HarnessEnabled)
        return enabled as Flow<T>
    }

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private class StartupRuns : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow<HarnessRunsState>(HarnessRunsState.Idle())
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()
    val sent = mutableListOf<HarnessRunsIntent>()

    override suspend fun send(intent: HarnessRunsIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}

private class StartupLibrary : HarnessMachine {
    override val name = "harness"
    override val state = MutableStateFlow<HarnessState>(HarnessState.Idle())
    override val outputs = MutableSharedFlow<HarnessOutput>()

    override suspend fun send(intent: HarnessIntent): SendResult = SendResult.Ignored
}
