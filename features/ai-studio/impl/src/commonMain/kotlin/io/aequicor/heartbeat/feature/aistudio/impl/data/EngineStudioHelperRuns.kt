package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind

/** Uses the repository's existing handle locks and fixed route; does not own another session registry. */
internal interface StudioHelperAccess {
    suspend fun helperRecord(helper: HelperId): StudioChatRecord
    suspend fun openHelper(record: StudioChatRecord): ActiveSession
    suspend fun liveHelper(helper: HelperId): ActiveSession?
}

@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioHelperRuns(
    private val access: Lazy<StudioHelperAccess>,
    private val runtime: Lazy<StudioRuntime>,
    private val host: Lazy<StudioRunHost>,
    private val coordinator: StudioRunCoordinator,
    private val native: StudioHelperNative,
) : StudioHelperRuns {
    private val log = Log.tag("StudioHelperRuns")

    override suspend fun runHelper(
        helper: HelperId,
        prompt: HelperPrompt,
        submission: StudioHelperSubmission,
        onAccepted: suspend () -> Unit,
    ) {
        val record = access.value.helperRecord(helper)
        val settings = hostRunSettings(
            record,
            runtime.value.state.value.configurations[record.id]?.applied,
            runtime.value.defaults(),
        )
        log.i { "Run a journaled helper request" }
        coordinator.run(
            host.value,
            StudioTurnRequest(
                record.id,
                prompt.text,
                settings,
                WorktreeRunKind.Coding,
                prompt.request,
                onAccepted = onAccepted,
                submission = submission,
            ),
            waitForIdle = true,
        )
    }

    override suspend fun helperTerminal(helper: HelperId, request: RequestId): StudioHelperTerminal? {
        val record = access.value.helperRecord(helper)
        log.v { "Read exact helper native result" }
        return if (record.ref == null || record.target == null) {
            null
        } else {
            native.result(record, request) { access.value.openHelper(record) }
        }
    }

    override suspend fun stopHelper(helper: HelperId, request: RequestId): StudioHelperTerminal? {
        val record = access.value.helperRecord(helper)
        log.v { "Stop exact helper request" }
        return native.stop(record, request, access.value.liveHelper(helper))
    }
}
