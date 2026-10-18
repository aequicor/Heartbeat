package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperProgress
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class EngineStudioHelperRunsTest {
    private val helper = HelperId("helper")

    @Test
    fun `progress reports only the live turn of the exact request with its pending permissions`() = runTest {
        val fixture = NativeHelperFixture()
        val runs = helperRuns(this, fixture, live = fixture)
        val request = fixture.turn.request!!
        val running = fixture.turn.copy(outcome = null)
        fixture.state.value = ActiveSessionState.Running(running)
        assertEquals(HelperProgress(request, fixture.ref, running.id), runs.helperProgress(helper, request))
        assertNull(runs.helperProgress(helper, RequestId("foreign")))
        val permission = PermissionRequest(
            PermissionRequestId("permission"),
            running.id,
            "Run the command?",
            listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
        )
        fixture.state.value = ActiveSessionState.AwaitingUserAction(running, listOf(permission))
        assertEquals(listOf(permission), runs.helperProgress(helper, request)?.permissions)
        assertNull(runs.helperProgress(helper, RequestId("foreign")))
        fixture.state.value = ActiveSessionState.Ready(fixture.turn.copy(request = RequestId("foreign")))
        assertNull(runs.helperProgress(helper, request))
    }

    @Test
    fun `progress without a live helper handle is no observation`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
        assertNull(helperRuns(this, fixture, live = null).helperProgress(helper, fixture.turn.request!!))
    }

    @Test
    fun `host directives of a helper prompt reach the engine-only turn directives`() = runTest {
        val fixture = NativeHelperFixture()
        val host = HelperRunsHost()
        val runs = helperRuns(this, fixture, live = null, host = host)
        val prompt = HelperPrompt(RequestId("action_a1"), "Visible task", directives = listOf("Return a summary"))
        fixture.attempts.prepare(helper, prompt)
        runs.runHelper(
            helper,
            prompt,
            StudioHelperSubmission(fixture.attempts, emptyHelperAdmissions(), helper, prompt.request),
        ) { _, _ -> }
        val executed = host.executed.single()
        assertEquals("Visible task", executed.prompt)
        assertEquals(listOf("Return a summary"), executed.directives)
    }

    private fun helperRuns(
        scope: CoroutineScope,
        fixture: NativeHelperFixture,
        live: ActiveSession?,
        host: StudioRunHost = HelperRunsHost(),
    ) = EngineStudioHelperRuns(
        lazyOf(
            object : StudioHelperAccess {
                override suspend fun helperRecord(helper: HelperId) = fixture.record
                override suspend fun openHelper(record: StudioChatRecord): ActiveSession = error("Must not open")
                override suspend fun liveHelper(helper: HelperId): ActiveSession? = live
            },
        ),
        lazyOf(HelperRunsRuntime()),
        lazyOf(host),
        StudioRunCoordinator(RunProfile(scope), RunClock),
        fixture.native,
        StudioHelperRecovery(RunClock),
    )
}

private class HelperRunsHost : StudioRunHost {
    val executed = mutableListOf<StudioTurnRequest>()
    override suspend fun startedRun(id: String, at: Instant) = Unit
    override suspend fun executeRun(request: StudioTurnRequest): RunOutcome {
        executed += request
        // Crossing the native boundary, as the repository's sender does.
        request.submission?.begin()
        return RunOutcome.Completed
    }
    override suspend fun finishedRun(id: String) = Unit
}

private class HelperRunsRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    override suspend fun defaults(): RunSettings = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome = error("Unused")
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) = Unit
}
