package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadataLimits
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Empty helper creation and identity lookup never open a native session. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioHelperChatRecords(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val writer: StudioHelperChatWriter,
    private val workspaces: LocalWorkspaces,
    private val clock: Clock,
    private val attempts: StudioHelperAttempts,
) : StudioHelperChatRecords {
    private val log = Log.tag("EngineStudioHelperChatRecords")
    private val store = stores.keyValue(ChatSpec)

    override suspend fun createHelper(request: HelperCreateRequest): HelperId {
        val parent = request.parent?.let { session ->
            checkNotNull(store.get(ChatsKey).orEmpty().singleOrNull { it.ref == session }) {
                "The helper parent must identify exactly one studio conversation"
            }
        }
        val record = newStudioHelperRecord(Uuid.random().toString(), clock.now(), request, parent)
        record.executionWorkspace?.let { workspace ->
            checkNotNull(workspaces.resolve(workspace)) { "The helper workspace is unavailable" }
        }
        // One write persists the empty chat, route, owner and parent before exposing its id. No native IO.
        writer.saveHelper(record)
        log.i { "Created empty helper conversation hasParent=${parent != null}" }
        return HelperId(record.id)
    }

    @HighFrequency
    override suspend fun helperMetadata(helper: HelperId): HelperMetadata? {
        log.v { "Read durable helper identity" }
        val matches = store.get(ChatsKey).orEmpty().filter { it.id == helper.value }
        check(matches.size <= 1) { "Helper identity is ambiguous" }
        return matches.singleOrNull()?.metadata()
    }

    @HighFrequency
    override suspend fun helperMetadata(session: SessionRef): HelperMetadata? {
        log.v { "Read durable helper session identity" }
        val matches = store.get(ChatsKey).orEmpty().filter { it.ref == session }
        check(matches.size <= 1) { "Helper session identity is ambiguous" }
        return matches.singleOrNull()?.metadata()
    }

    @HighFrequency
    override suspend fun ownedHelpers(owner: ActionId, after: HelperId?, limit: Int): List<HelperMetadata> {
        log.v { "Read bounded durable helper ownership" }
        require(limit in 1..HelperMetadataLimits.MAX_PAGE_SIZE) { "Invalid helper metadata page size" }
        val owned = store.get(ChatsKey).orEmpty().filter { it.helper?.owner == owner }
        check(owned.map { it.id }.distinct().size == owned.size) { "Helper identity is ambiguous" }
        return owned.asSequence().filter { after == null || it.id > after.value }.sortedBy { it.id }
            .take(limit).toList().map { checkNotNull(it.metadata()) }
    }

    @HighFrequency
    private suspend fun StudioChatRecord.metadata(): HelperMetadata? {
        log.v { "Project persisted helper metadata" }
        val identity = helper ?: return null
        val helperId = HelperId(id)
        return HelperMetadata(
            helperId,
            identity.owner,
            identity.parentSession,
            ref,
            attempts.unresolved(helperId) ?: lastRunRequest,
        )
    }

    override suspend fun isHelper(session: SessionRef): Boolean = helperMetadata(session) != null
}
