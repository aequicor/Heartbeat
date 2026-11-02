package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind

/**
 * Scheduled runs in studio chats: the chat is found by its native session, and the run keeps the chat's confirmed
 * model and approval over the defaults, like a worktree action. Records are read from the studio's chat store; all
 * changes go through the studio runtime.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioScheduledChats(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val writer: StudioHelperChatWriter,
    private val clock: kotlin.time.Clock,
    private val workspaces: io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces,
    private val runtime: StudioRuntime,
    private val runs: StudioRunCoordinator,
    private val host: StudioRunHost,
    private val inbox: StudioWakeInbox,
    private val graphExecutions: StudioGraphExecutions,
) : StudioScheduledChats {
    private val log = Log.tag("EngineStudioScheduledChats")
    private val store by lazy { stores.keyValue(ChatSpec) }

    override suspend fun runGraphTask(
        chatId: String,
        request: SpawnRequest,
        previousExecution: String?,
        admission: kotlinx.coroutines.flow.Flow<Boolean>,
    ): GraphTaskResult = graphExecutions.run(chatId, request, previousExecution, admission)

    override suspend fun stopGraphTask(chatId: String): Boolean = graphExecutions.stop(chatId)

    override suspend fun chatOf(session: SessionRef): StudioScheduledChat? {
        log.v { "find the chat of a session" }
        return store.get(
            ChatsKey,
        ).orEmpty().firstOrNull { it.owns(session) }?.let { StudioScheduledChat(it.id, it.projectId) }
    }

    override suspend fun sessionOf(chatId: String): SessionRef? {
        log.v { "read the session of a chat" }
        return store.get(ChatsKey).orEmpty().firstOrNull { it.id == chatId }?.ref
    }

    override suspend fun createHelperChat(
        projectId: String?,
        title: String,
        workspace: WorkspaceRef?,
        parentId: String?,
    ): String {
        val parent = parentId?.let { id ->
            checkNotNull(resolvedStudioIdentities(store.get(ChatsKey).orEmpty()).singleOrNull { it.id == id }) {
                "Executor parent is unavailable"
            }
        }
        check(parent == null || parent.projectId == projectId) { "Executor project differs from its parent" }
        require(workspace == null || projectId != null) { "Executor workspace requires a project" }
        if (workspace != null) {
            requireNotNull(workspaces.executionDirectory(workspace)) { "Executor workspace is unavailable" }
        }
        if (projectId != null) {
            requireNotNull(workspaces.resolve(WorkspaceRef(projectId))) { "Executor project is unavailable" }
        }
        val id = kotlin.uuid.Uuid.random().toString()
        val record = StudioChatRecord(
            id,
            title,
            clock.now(),
            projectId = projectId,
            executionWorkspace = workspace,
            identity = helperConversationIdentity(
                id,
                parent,
                io.aequicor.heartbeat.feature.scheduler.api.HelperConversationRole.Executor,
            ),
        )
        writer.saveHelper(record)
        log.i { "Created explicit graph executor hasOwner=${record.logicalSession?.owner != null}" }
        return id
    }

    override suspend fun runScheduled(
        chatId: String,
        prompt: WakePrompt,
        route: ScheduledRunRoute,
        onAccepted: suspend () -> Unit,
    ): RunOutcome {
        when (if (prompt.isDeduplicationRequired) inbox.receipt(prompt.request) else null) {
            WakeReceipt.Accepted -> {
                onAccepted()
                return RunOutcome.Completed
            }

            WakeReceipt.Submitting -> error("Previous wake acceptance is unknown; explicit retry is required")

            null -> Unit
        }
        val records = store.get(ChatsKey).orEmpty()
        val record = checkNotNull(records.firstOrNull { it.id == chatId }) { "Unknown studio conversation" }
        val configurations = runtime.state.value.configurations
        // A helper inherits its parent's approval instead of the profile default.
        val parent = route.approvalFrom
        val parentSettings = parent?.let {
            configurations[it]?.applied
                ?: records.firstOrNull { r -> r.id == it }?.configuration
        }
        val base = runtime.defaults()
        val defaults = if (parentSettings == null) base else base.copy(approval = parentSettings.approval)
        val settings = hostRunSettings(record, configurations[chatId]?.applied, defaults, route.target)
        log.i { "run a scheduled prompt in a studio conversation" }
        return runs.run(
            host,
            StudioTurnRequest(
                chatId,
                prompt.visible,
                settings,
                WorktreeRunKind.Coding,
                prompt.request,
                onAccepted = {
                    if (prompt.isDeduplicationRequired) inbox.accepted(prompt.request)
                    onAccepted()
                },
                directives = listOf(prompt.directive),
                causes = prompt.causes,
                origin = StudioTurnOrigin.Conversation,
            ),
            waitForIdle = true,
            cancelBeforeSubmission = true,
            beforeExecute = { if (prompt.isDeduplicationRequired) inbox.submitting(prompt.request) },
            onCancelledBeforeSubmission = {
                if (prompt.isDeduplicationRequired) inbox.cancelledBeforeSubmission(prompt.request)
            },
            admission = prompt.admission,
        )
    }
}

/**
 * Settings of a run the host starts in chat [record] (a worktree action, a scheduled wake): the chat's confirmed model
 * and approval ([applied] first, then the stored ones) over [defaults]; [target] is the route of a chat without one.
 */
internal fun hostRunSettings(
    record: StudioChatRecord,
    applied: StudioSessionSettings?,
    defaults: RunSettings,
    target: EngineTarget? = null,
): RunSettings {
    val confirmed = applied ?: record.configuration
    return defaults.copy(
        modelId = confirmed?.modelId ?: (record.target ?: target)?.studioModelId().orEmpty(),
        approval = confirmed?.approval ?: defaults.approval,
    )
}
